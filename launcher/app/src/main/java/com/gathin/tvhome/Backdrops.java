package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The picture behind every page, from the theme's backdrop key ({@link Theme#backdrop}, read by
 * {@link BackdropKeys}): a bundled drawable, a bundled 8-bit scene drawn without smoothing, the theme's sky gradient,
 * nothing, or a photo or drawing from launcher/backdrops/ in the repo. Those are too big for the APK, so they are
 * downloaded once from {@code theme_assets_url} (BuildConfig.THEME_ASSETS_URL) into the app's files folder and kept;
 * the sky is drawn until one arrives, and for as long as the TV is offline without it. Home never waits: decoding
 * and downloads run off the main thread, and a picture fades in when it is ready.
 *
 * <p>A theme with an hourly list (Landscapes) shows the next picture when the hour changes while Home is on screen,
 * checked once an hour. A Seasonal theme's picture follows the part of the day ({@link DayPhase}, the F22 daylight
 * cycle): the key names it with {@link BackdropKeys#PHASE}, and the picture crossfades to the next one when the sun
 * moves on, checked once at that moment. Once a theme is shown, the rest of its list, the next part of the day and a
 * Seasonal theme's next period are fetched in the background, on a low-priority thread, so later changes need no
 * download. Nothing is drawn between those changes: one still picture, as before.
 */
final class Backdrops {
    private static final int CONNECT_MS = 10_000;
    private static final int READ_MS = 20_000;
    /** The pictures are about 250 KB; anything far bigger is not one of them. */
    private static final long MAX_BYTES = 4L << 20;
    /** A failed download is tried again when Home next shows, but not more often than this. */
    private static final long RETRY_MS = 60_000;

    private static final ThreadPoolExecutor SHOW = pool("backdrop", Process.THREAD_PRIORITY_DEFAULT);
    private static final ThreadPoolExecutor PREFETCH = pool("backdrop-prefetch", Process.THREAD_PRIORITY_LOWEST);
    /** When each path last failed, by path; a path is not asked for again until RETRY_MS later. */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    /** Paths waiting on the prefetch thread, so a second theme apply does not queue them twice. */
    private static final Set<String> QUEUED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static ThreadPoolExecutor pool(String name, int priority) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(priority);
                r.run();
            }, name);
            t.setDaemon(true);
            return t;
        });
        // Idle threads go away instead of sitting on a 1 GB TV's memory.
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    private final Activity activity;
    private final ImageView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File filesDir;
    private final Runnable hourly = this::refresh;

    private BackdropKeys.Key key = BackdropKeys.Key.NONE;
    /** The part of the day a phased key shows; DAY for any other key. */
    private DayPhase phase = DayPhase.DAY;
    /** Bumped by every new load and by release(): a result from an older one is dropped. */
    private int generation;
    /** The URL path on screen or on its way; null for other kinds. */
    private String wanted;
    private boolean loading;
    private boolean imageShown;
    private boolean visible;
    private ValueAnimator fade;

    Backdrops(Activity activity, ImageView view) {
        this.activity = activity;
        this.view = view;
        this.filesDir = activity.getFilesDir();
    }

    /** Shows a theme's backdrop, or its plain sky when Performance's backdrop picture is Plain colour. Main thread. */
    void show(Theme t) {
        key = BackdropKeys.parse(t.backdrop);
        stop();
        if (PerfChoices.current().plainBackdrop && key.kind != BackdropKeys.Kind.NONE) {
            view.setBackground(sky(t));
            // Nothing to fetch, refresh or rotate hourly.
            key = BackdropKeys.Key.NONE;
            return;
        }
        phase = phaseNow();
        switch (key.kind) {
            case NONE:
                view.setBackground(null);
                break;
            case SKY:
                view.setBackground(sky(t));
                break;
            case RES:
                // The bundled photo fades in over plain bg, as Home always did.
                view.setBackground(null);
                String res = key.name;
                load(() -> decodeRes(res));
                break;
            case PIXEL:
                view.setBackground(sky(t));
                showPixel();
                break;
            case URL:
                view.setBackground(sky(t));
                showUrl(BackdropKeys.pick(key, LocalDateTime.now().getHour(), phase));
                prefetch();
                break;
        }
        schedule();
    }

    /**
     * Lets go of the picture while Home is in the background (MainActivity.onTrimMemory); show() decodes it again.
     */
    void release() {
        stop();
    }

    /**
     * Home came on screen or left it. On screen, an hourly list catches up with the hour and a picture that could not
     * be fetched is tried again; off screen, the hourly check stops.
     */
    void setVisible(boolean on) {
        visible = on;
        if (on) refresh();
        else main.removeCallbacks(hourly);
    }

    private void stop() {
        generation++;
        loading = false;
        imageShown = false;
        wanted = null;
        cancelFade();
        main.removeCallbacks(hourly);
        view.setImageDrawable(null);
        view.setImageAlpha(255);
    }

    /** Stops a fade-in where it is; cleared first, so its end leaves the sky under the picture alone. */
    private void cancelFade() {
        ValueAnimator f = fade;
        fade = null;
        if (f != null) f.cancel();
    }

    private void refresh() {
        DayPhase now = phaseNow();
        boolean turned = now != phase;
        phase = now;
        if (key.kind == BackdropKeys.Kind.URL && !loading) {
            String path = BackdropKeys.pick(key, LocalDateTime.now().getHour(), phase);
            if (!imageShown || !path.equals(wanted)) showUrl(path);
            if (turned) prefetch();
        } else if (key.kind == BackdropKeys.Kind.PIXEL && key.phased && turned) {
            showPixel();
        }
        schedule();
    }

    /** The part of the day for a phased key, else DAY; reading it costs a preferences read, never a frame. */
    private DayPhase phaseNow() {
        return key.phased ? ThemeStore.phase(activity, ZonedDateTime.now()) : DayPhase.DAY;
    }

    /**
     * The next check while Home is on screen: on the hour for an hourly list, and when the part of the day changes for
     * a phased key. One post, whichever comes first; nothing runs in between.
     */
    private void schedule() {
        main.removeCallbacks(hourly);
        if (!visible) return;
        long ms = Long.MAX_VALUE;
        if (key.kind == BackdropKeys.Kind.URL && key.paths.size() >= 2) {
            LocalDateTime now = LocalDateTime.now();
            ms = Duration.between(now, now.truncatedTo(ChronoUnit.HOURS).plusHours(1)).toMillis();
        }
        if (key.phased) {
            ZonedDateTime now = ZonedDateTime.now();
            ZonedDateTime next = ThemeStore.nextPhaseChange(activity, now);
            if (next != null) ms = Math.min(ms, Math.max(0, Duration.between(now, next).toMillis()));
        }
        if (ms != Long.MAX_VALUE) main.postDelayed(hourly, ms + 1000);
    }

    private void showPixel() {
        String scene = key.name;
        DayPhase p = key.phased ? phase : DayPhase.DAY;
        load(() -> decodePixel(scene, p));
    }

    private void showUrl(String path) {
        wanted = path;
        load(() -> decodeUrl(path));
    }

    private void load(Supplier<Drawable> decode) {
        int gen = ++generation;
        loading = true;
        SHOW.execute(() -> {
            Drawable d = decode.get();
            main.post(() -> {
                if (gen != generation || activity.isDestroyed()) return;
                loading = false;
                if (d != null) reveal(d);
            });
        });
    }

    /** Fades a picture in: over the sky the first time, crossfading from the one before when the hour changes it. */
    private void reveal(Drawable d) {
        long ms = Ui.ms(Ui.SLOW_MS);
        Drawable old = view.getDrawable();
        cancelFade();
        if (imageShown && old != null && ms > 0) {
            TransitionDrawable t = new TransitionDrawable(new Drawable[] {old, d});
            t.setCrossFadeEnabled(true);
            view.setImageDrawable(t);
            t.startTransition((int) ms);
            // The old picture goes once the crossfade is over, so only one stays in memory.
            view.postDelayed(() -> {
                if (view.getDrawable() == t) view.setImageDrawable(d);
            }, ms + 50);
            return;
        }
        imageShown = true;
        view.setImageDrawable(d);
        if (ms <= 0) {
            view.setImageAlpha(255);
            view.setBackground(null);
            return;
        }
        view.setImageAlpha(0);
        ValueAnimator a = ValueAnimator.ofInt(0, 255);
        a.setDuration(ms);
        a.addUpdateListener(v -> view.setImageAlpha((int) v.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setImageAlpha(255);
                // The picture covers the screen: the sky under it would only be a second full-screen fill per frame.
                if (fade == a) view.setBackground(null);
            }
        });
        fade = a;
        a.start();
    }

    private void prefetch() {
        DayPhase next = key.phased && ThemeStore.followSun(activity) ? phase.following() : phase;
        for (String path : BackdropKeys.prefetch(key, LocalDate.now(), phase, next)) {
            if (path.equals(wanted) || file(path).isFile() || !QUEUED.add(path)) continue;
            File f = file(path);
            PREFETCH.execute(() -> {
                try {
                    if (!f.isFile()) download(path, f);
                } finally {
                    QUEUED.remove(path);
                }
            });
        }
    }

    static Drawable sky(Theme t) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {t.skyTop, t.skyMid, t.horizon});
    }

    // ---- Off the main thread -----------------------------------------------------------------------------------

    private Drawable decodeRes(String name) {
        int id = "bg_evening".equals(name) ? R.drawable.bg_evening : 0;
        if (id == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeResource(activity.getResources(), id, o);
        return b == null ? null : new BitmapDrawable(activity.getResources(), b);
    }

    /** 160×90 pixels blown up to the screen, each one a sharp square: no filtering, no smoothing. */
    private Drawable decodePixel(String slug, DayPhase phase) {
        int id = pixelDrawable(BackdropKeys.period(slug), phase);
        if (id == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        Bitmap b = BitmapFactory.decodeResource(activity.getResources(), id, o);
        if (b == null) return null;
        BitmapDrawable d = new BitmapDrawable(activity.getResources(), b);
        d.setFilterBitmap(false);
        d.setAntiAlias(false);
        d.setDither(false);
        return d;
    }

    /** The bundled scenes by period (SeasonCalendar.Period's order) and part of the day (DayPhase's order). */
    private static final int[][] PIXEL = {
            {R.drawable.pixel_snow_dawn, R.drawable.pixel_snow, R.drawable.pixel_snow_sunset,
                    R.drawable.pixel_snow_night},
            {R.drawable.pixel_thaw_dawn, R.drawable.pixel_thaw, R.drawable.pixel_thaw_sunset,
                    R.drawable.pixel_thaw_night},
            {R.drawable.pixel_spring_rain_dawn, R.drawable.pixel_spring_rain, R.drawable.pixel_spring_rain_sunset,
                    R.drawable.pixel_spring_rain_night},
            {R.drawable.pixel_bloom_dawn, R.drawable.pixel_bloom, R.drawable.pixel_bloom_sunset,
                    R.drawable.pixel_bloom_night},
            {R.drawable.pixel_pride_dawn, R.drawable.pixel_pride, R.drawable.pixel_pride_sunset,
                    R.drawable.pixel_pride_night},
            {R.drawable.pixel_midsummer_dawn, R.drawable.pixel_midsummer, R.drawable.pixel_midsummer_sunset,
                    R.drawable.pixel_midsummer_night},
            {R.drawable.pixel_late_summer_dawn, R.drawable.pixel_late_summer, R.drawable.pixel_late_summer_sunset,
                    R.drawable.pixel_late_summer_night},
            {R.drawable.pixel_autumn_dawn, R.drawable.pixel_autumn, R.drawable.pixel_autumn_sunset,
                    R.drawable.pixel_autumn_night},
            {R.drawable.pixel_halloween_dawn, R.drawable.pixel_halloween, R.drawable.pixel_halloween_sunset,
                    R.drawable.pixel_halloween_night},
            {R.drawable.pixel_christmas_dawn, R.drawable.pixel_christmas, R.drawable.pixel_christmas_sunset,
                    R.drawable.pixel_christmas_night},
    };

    private static int pixelDrawable(SeasonCalendar.Period p, DayPhase phase) {
        return p == null ? 0 : PIXEL[p.ordinal()][phase.ordinal()];
    }

    private Drawable decodeUrl(String path) {
        File f = file(path);
        if (!f.isFile() && !download(path, f)) return null;
        Resources res = activity.getResources();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        Bitmap b = null;
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            DisplayMetrics dm = res.getDisplayMetrics();
            int width = BackdropKeys.decodeWidth(bounds.outWidth, bounds.outHeight,
                    Math.max(dm.widthPixels, dm.heightPixels), Math.min(dm.widthPixels, dm.heightPixels));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            if (width < bounds.outWidth) {
                // Scaled down while decoding, never up: the view stretches a smaller picture for free.
                o.inScaled = true;
                o.inDensity = bounds.outWidth;
                o.inTargetDensity = width;
            }
            b = BitmapFactory.decodeFile(f.getPath(), o);
        }
        if (b == null) {
            // Not a picture after all (a captive portal's page, a cut-off file): fetch it again next time.
            if (!f.delete()) f.deleteOnExit();
            return null;
        }
        return new BitmapDrawable(res, b);
    }

    private File file(String path) {
        return new File(filesDir, BackdropKeys.cachePath(path));
    }

    /** Fetches one picture into its file, through a temporary file so a cut-off download is never kept. */
    private static boolean download(String path, File f) {
        Long failedAt = FAILED.get(path);
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return false;
        File dir = f.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
        File part = null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BackdropKeys.join(BuildConfig.THEME_ASSETS_URL, path)).openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setUseCaches(false);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("not there");
            part = File.createTempFile("download", ".part", dir);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("too big");
                    out.write(buf, 0, n);
                }
            }
            if (!part.renameTo(f)) throw new IOException("not kept");
            part = null;
            FAILED.remove(path);
            return true;
        } catch (IOException | RuntimeException e) {
            FAILED.put(path, SystemClock.elapsedRealtime());
            return false;
        } finally {
            if (part != null && !part.delete()) part.deleteOnExit();
            if (c != null) c.disconnect();
        }
    }
}
