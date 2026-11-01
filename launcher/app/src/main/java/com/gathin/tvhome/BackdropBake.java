package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Everything under the pages, baked into one screen-size picture and drawn in one pass: the root's plain fill, the
 * backdrop (photo, drawing or sky), the theme's overlays and the shade of the page at the bottom of the stage, with
 * Home's dim folded in as a colour over it. Before, each of those was a full-screen fill of its own on every frame,
 * three to four of them, and a TV GPU without partial updates (the TCL's Mali-470, no buffer_age) redraws the whole
 * screen every frame.
 *
 * <p>Nothing looks different: the picture is drawn from the same colours, picture and placement as the views it
 * stands in for, and they are hidden only while it shows. It is drawn on a background thread, at 16 bits per pixel
 * with dithering, and only when what goes into it changes ({@link BakeRules.Key}): the backdrop, the theme's colours,
 * the screen size or the page's shade. Until the new one is ready the views draw themselves, as they always did, and
 * while the backdrop fades in or crossfades they draw themselves too. At most {@link BakeRules#keep} pictures are kept
 * (Home's and the other pages', or on a low-RAM TV only the one on screen); older ones are recycled.
 *
 * <p>A page marks its shades with {@link #shade} (the first children of its root, full-screen) and Home its dim with
 * {@link #dim} (the child after them). Anything that moves or covers the page's content (Library's bottom fade, a
 * popup's scrim) stays a view.
 */
@SuppressLint("ViewConstructor") // built in code only, never inflated
final class BackdropBake extends View {
    /** One thread, below the UI's priority: a bake is never in a hurry, the views draw meanwhile. */
    private static final ThreadPoolExecutor BAKE = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "backdrop-bake");
                t.setDaemon(true);
                return t;
            });

    static {
        BAKE.allowCoreThreadTimeOut(true);
    }

    /** A shade or a dim: a full-screen view over the backdrop that draws nothing while the bake draws it. */
    private static final class Still extends View {
        private final Drawable look;
        private boolean folded;

        Still(Context ctx, Drawable look) {
            super(ctx);
            this.look = look;
            setBackground(look);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setTag(R.id.ui_still, Boolean.TRUE);
        }

        void fold(boolean on) {
            if (folded == on) return;
            folded = on;
            setBackground(on ? null : look);
        }
    }

    /** A page's full-screen shade over the backdrop, top to bottom: the bake draws it while it can. */
    static View shade(Context ctx, int[] colors) {
        Still v = new Still(ctx, new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors));
        v.setTag(R.id.ui_backdrop_shade, new BakeRules.Band(colors, null));
        return v;
    }

    /** Home's dim over its shade, faded by its alpha: the bake draws it over the picture at that alpha. */
    static View dim(Context ctx, int color) {
        Still v = new Still(ctx, new ColorDrawable(color));
        v.setTag(R.id.ui_backdrop_dim, color);
        return v;
    }

    private final ViewGroup root;
    private final ImageView backdrop;
    private final Backdrops backdrops;
    private final List<View> overlays;
    private final boolean vignette, scanlines;
    private final ViewGroup stage;
    private final Drawable rootFill;
    private final int fill;
    private final Bitmap.Config config;
    private final Paint paint = new Paint();
    private final BakeRules.Kept<BakeRules.Key, Bitmap> kept;
    private final Set<BakeRules.Key> baking = new HashSet<>();
    private final List<Still> shades = new ArrayList<>();
    private final List<Still> folded = new ArrayList<>();
    private final float[] values = new float[9];
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        update();
        return true;
    };

    private Still dimView;
    private int dimColor;
    private int dimDrawn = -1;
    private BakeRules.Key shownKey;
    private Bitmap shown;
    /** Bumped by release(): a bake from before it is dropped. */
    private int generation;
    private boolean dirty = true;
    private View lastPage;
    private boolean lastPlain;

    BackdropBake(Context ctx, ViewGroup root, ImageView backdrop, Backdrops backdrops, List<View> overlays,
            Theme theme, ViewGroup stage) {
        super(ctx);
        this.root = root;
        this.backdrop = backdrop;
        this.backdrops = backdrops;
        this.overlays = overlays;
        this.vignette = theme.vignette;
        this.scanlines = theme.scanlines;
        this.stage = stage;
        rootFill = root.getBackground();
        fill = rootFill instanceof ColorDrawable ? ((ColorDrawable) rootFill).getColor() : Ui.BG;
        // Half the memory of 32 bits; dithering keeps the gradients from banding. Low-RAM or not, the same look.
        config = Bitmap.Config.RGB_565;
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        kept = new BakeRules.Kept<>(BakeRules.keep(am != null && am.isLowRamDevice()));
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(INVISIBLE);
        backdrops.setOnChange(this::changed);
    }

    /** What goes into the picture may have changed: looked at again before the next frame. */
    void changed() {
        dirty = true;
        root.invalidate();
    }

    /**
     * Lets go of the pictures (MainActivity.onTrimMemory, with the backdrop's): the views draw themselves until the
     * backdrop is back and baked again.
     */
    void release() {
        generation++;
        baking.clear();
        show(null, null);
        recycleLater(kept.keepOnly(null));
        changed();
    }

    /** Home went to the background: keeps only the picture on screen, so coming back needs no bake. */
    void trim() {
        recycleLater(kept.keepOnly(shownKey));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dirty = true;
    }

    /** Before every frame: cheap unless the bottom page, its place or the backdrop changed. */
    private void update() {
        View page = stage.getChildCount() > 0 ? stage.getChildAt(0) : null;
        boolean plain = page != null && covers(page);
        if (page != lastPage || plain != lastPlain) dirty = true;
        if (dirty) {
            dirty = false;
            lastPage = page;
            lastPlain = plain;
            look(page, plain);
        }
        updateDim();
    }

    /** Finds the page's shades and dim, then shows the picture for them, or asks for it. */
    private void look(View page, boolean plain) {
        shades.clear();
        dimView = null;
        // A page sliding or fading in (a transition with Motion on) draws its own shade for those few frames: baking
        // a look without it would make a third picture and could push Home's out.
        if (!BakeRules.pageSteady(page != null, plain)) {
            show(null, null);
            return;
        }
        if (page instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) page;
            int i = 0;
            while (i < g.getChildCount() && g.getChildAt(i) instanceof Still
                    && g.getChildAt(i).getTag(R.id.ui_backdrop_shade) instanceof BakeRules.Band
                    && covers(g.getChildAt(i)) && g.getChildAt(i).getVisibility() == VISIBLE) {
                shades.add((Still) g.getChildAt(i));
                i++;
            }
            if (i < g.getChildCount() && g.getChildAt(i) instanceof Still
                    && g.getChildAt(i).getTag(R.id.ui_backdrop_dim) instanceof Integer
                    && g.getChildAt(i).getVisibility() == VISIBLE) {
                dimView = (Still) g.getChildAt(i);
                dimColor = (Integer) dimView.getTag(R.id.ui_backdrop_dim);
            }
        }
        Recipe r = recipe();
        BakeRules.Key key = r == null ? null : r.key();
        Bitmap b = kept.get(key);
        show(b == null ? null : key, b);
        if (key != null && b == null && baking.add(key)) bake(key, r);
    }

    /** Shows a baked picture and hides what it stands in for; with null, puts the views back. */
    private void show(BakeRules.Key key, Bitmap b) {
        boolean on = b != null;
        int under = on ? INVISIBLE : VISIBLE;
        if (backdrop.getVisibility() != under) backdrop.setVisibility(under);
        for (View v : overlays) if (v.getVisibility() != under) v.setVisibility(under);
        List<Still> fold = new ArrayList<>();
        if (on) {
            fold.addAll(shades);
            if (dimView != null) fold.add(dimView);
        }
        for (Still v : folded) if (!fold.contains(v)) v.fold(false);
        for (Still v : fold) v.fold(true);
        folded.clear();
        folded.addAll(fold);
        Drawable fillNow = BakeRules.rootFill(on, opaquePicture()) ? rootFill : null;
        if (root.getBackground() != fillNow) root.setBackground(fillNow);
        if (getVisibility() != (on ? VISIBLE : INVISIBLE)) setVisibility(on ? VISIBLE : INVISIBLE);
        if (b != shown) {
            shown = b;
            shownKey = key;
            dimDrawn = -1;
            invalidate();
            if (key != null) recycleLater(kept.shown(key));
        }
    }

    /** Home's dim, folded: the same colour over the picture at the dim's alpha, in the same pass. */
    private void updateDim() {
        int a = shown != null && dimView != null && folded.contains(dimView)
                ? BakeRules.dimAlpha(dimColor, dimView.getAlpha()) : 0;
        if (a == dimDrawn) return;
        dimDrawn = a;
        paint.setColorFilter(a == 0 ? null
                : new PorterDuffColorFilter(BakeRules.withAlpha(dimColor, a), PorterDuff.Mode.SRC_ATOP));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        Bitmap b = shown;
        if (b != null && !b.isRecycled()) c.drawBitmap(b, 0, 0, paint);
    }

    private boolean covers(View v) {
        return BakeRules.coversScreen(v.getAlpha(), v.getMatrix().isIdentity(), v.getLeft(), v.getTop(), v.getWidth(),
                v.getHeight(), getWidth(), getHeight());
    }

    private boolean opaquePicture() {
        Drawable d = backdrop.getDrawable();
        if (!(d instanceof BitmapDrawable) || backdrop.getImageAlpha() != 255) return false;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        return b != null && !b.hasAlpha();
    }

    // ---- The picture -------------------------------------------------------------------------------------------

    /** What the views under the pages draw now, as plain values; null while they can't be baked. */
    private Recipe recipe() {
        int w = getWidth(), h = getHeight();
        if (!BakeRules.bakeable(backdrops.steady(), w, h) || !covers(backdrop)) return null;
        Recipe r = new Recipe();
        r.width = w;
        r.height = h;
        r.fill = fill;
        int[] sky = backdrops.skyColors();
        r.sky = sky == null ? null : new BakeRules.Band(sky, null);
        Drawable d = backdrop.getDrawable();
        if (d != null) {
            if (!(d instanceof BitmapDrawable) || ((BitmapDrawable) d).getBitmap() == null) return null;
            BitmapDrawable bd = (BitmapDrawable) d;
            r.picture = bd.getBitmap();
            r.pictureWidth = bd.getIntrinsicWidth();
            r.pictureHeight = bd.getIntrinsicHeight();
            r.filter = bd.getPaint().isFilterBitmap();
            r.alpha = backdrop.getImageAlpha();
            backdrop.getImageMatrix().getValues(values);
            r.matrix = values.clone();
        }
        r.vignette = vignette;
        r.scanlines = scanlines;
        for (Still v : shades) r.bands.add((BakeRules.Band) v.getTag(R.id.ui_backdrop_shade));
        return r;
    }

    private void bake(BakeRules.Key key, Recipe r) {
        int gen = generation;
        Context ctx = getContext();
        BAKE.execute(() -> {
            Bitmap b;
            try {
                b = r.draw(ctx, config);
            } catch (OutOfMemoryError | RuntimeException e) {
                b = null;
            }
            Bitmap done = b;
            post(() -> arrived(gen, key, done));
        });
    }

    private void arrived(int gen, BakeRules.Key key, Bitmap b) {
        if (gen != generation || !isAttachedToWindow()) {
            if (b != null) b.recycle();
            return;
        }
        baking.remove(key);
        if (b == null) return;
        recycleLater(kept.put(key, b, shownKey));
        changed();
    }

    /** After the next frame, so a display list that still holds one never draws a recycled bitmap. */
    private void recycleLater(List<Bitmap> gone) {
        if (gone.isEmpty()) return;
        postOnAnimation(() -> post(() -> {
            for (Bitmap g : gone) if (g != shown) g.recycle();
        }));
    }

    /** The values a bake draws from, read on the main thread; nothing in it is shared with a view. */
    private static final class Recipe {
        int width, height, fill;
        BakeRules.Band sky;
        Bitmap picture;
        int pictureWidth, pictureHeight, alpha;
        boolean filter;
        float[] matrix;
        boolean vignette, scanlines;
        final List<BakeRules.Band> bands = new ArrayList<>();

        BakeRules.Key key() {
            BakeRules.Key k = new BakeRules.Key().add(width).add(height).add(fill).add(sky).add(picture);
            if (picture != null) k.add(pictureWidth).add(pictureHeight).add(alpha).add(filter).add(matrix);
            k.add(vignette).add(scanlines).add(bands.size());
            for (BakeRules.Band b : bands) k.add(b);
            return k;
        }

        /** In the views' order: the root's fill, the sky, the picture, the overlays, the shades. */
        Bitmap draw(Context ctx, Bitmap.Config config) {
            Bitmap out = Bitmap.createBitmap(width, height, config);
            Canvas c = new Canvas(out);
            c.drawColor(fill);
            if (sky != null) band(c, sky);
            if (picture != null) {
                int save = c.save();
                Matrix m = new Matrix();
                m.setValues(matrix);
                c.concat(m);
                Paint p = new Paint(Paint.DITHER_FLAG);
                p.setFilterBitmap(filter);
                p.setAlpha(alpha);
                c.drawBitmap(picture, null, new RectF(0, 0, pictureWidth, pictureHeight), p);
                c.restoreToCount(save);
            }
            for (Drawable d : Overlays.drawables(ctx, vignette, scanlines)) {
                d.setBounds(0, 0, width, height);
                d.draw(c);
            }
            for (BakeRules.Band b : bands) band(c, b);
            return out;
        }

        private void band(Canvas c, BakeRules.Band b) {
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, height, b.colors, b.stops, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, width, height, p);
        }
    }
}
