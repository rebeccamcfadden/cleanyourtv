package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BackdropBake's rules, kept apart from the views so they are tested on the JVM: when the baked picture stands in
 * for the views under the pages, what goes into it (and so when it is drawn again), how Home's dim folds into it, and
 * which pictures are kept.
 */
final class BakeRules {
    private BakeRules() {}

    /** Baked pictures kept, one per look: Home's and the other pages', so a tab switch finds its picture ready. */
    static final int KEEP = 2;

    /**
     * How many to keep on this TV: a low-RAM TV (the TCL: 1 GB, 4 MB per 1080p picture) keeps only the one on screen
     * and draws the other again, off the main thread, when the bottom page changes between Home and another page.
     */
    static int keep(boolean lowRam) {
        return lowRam ? 1 : KEEP;
    }

    /**
     * Whether the views under the pages can be baked now: only while nothing in them moves (no picture fading in or
     * crossfading) and once the screen has a size. Otherwise they draw themselves, as they always did.
     */
    static boolean bakeable(boolean backdropSteady, int width, int height) {
        return backdropSteady && width > 0 && height > 0;
    }

    /**
     * Whether the page at the bottom of the stage can have its shades baked: there is one, and it covers the screen
     * with no alpha or transform of its own. While it slides or fades in, everything draws itself.
     */
    static boolean pageSteady(boolean hasPage, boolean pageCoversScreen) {
        return hasPage && pageCoversScreen;
    }

    /**
     * Whether the root keeps its plain fill under everything: the baked picture has it inside, and an opaque picture
     * at full alpha covers the screen on its own (a centre-cropped picture always does). While a picture fades in or
     * crossfades, over the sky, or with no picture, it stays.
     */
    static boolean rootFill(boolean baked, boolean opaquePicture) {
        return !baked && !opaquePicture;
    }

    /**
     * Home's dim folded into the baked picture: the alpha of the colour drawn over it in the same pass, as the dim
     * view would have drawn it at its own alpha. 0 when there is nothing to dim.
     */
    static int dimAlpha(int color, float viewAlpha) {
        float a = Math.max(0f, Math.min(1f, viewAlpha));
        return Math.round((color >>> 24) * a);
    }

    /** A dim colour at another alpha. */
    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * Whether a view can be folded into the baked picture: drawn straight onto the screen, covering it exactly, with
     * no alpha or transform of its own.
     */
    static boolean coversScreen(float alpha, boolean identity, int left, int top, int width, int height,
            int screenWidth, int screenHeight) {
        return alpha == 1f && identity && left == 0 && top == 0 && width == screenWidth && height == screenHeight;
    }

    /**
     * A full-screen vertical gradient over the backdrop: a page's shade, the theme's sky. The same colours at the same
     * stops draw the same pixels, so two pages with the same shade share one baked picture.
     */
    static final class Band {
        final int[] colors;
        /** Where each colour sits, from 0 at the top to 1 at the bottom; null spreads them evenly. */
        final float[] stops;

        Band(int[] colors, float[] stops) {
            this.colors = colors.clone();
            this.stops = stops == null ? null : stops.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Band)) return false;
            Band b = (Band) o;
            return Arrays.equals(colors, b.colors) && Arrays.equals(stops, b.stops);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(colors) * 31 + Arrays.hashCode(stops);
        }
    }

    /**
     * What went into a baked picture: numbers, colours and bands by value, a picture by identity (the same bitmap
     * object draws the same pixels; a new one may not). Two keys equal draw the same picture.
     */
    static final class Key {
        private final List<Object> parts = new ArrayList<>();

        Key add(Object part) {
            parts.add(part == null ? Nothing.NULL : part);
            return this;
        }

        Key add(int part) {
            parts.add(part);
            return this;
        }

        Key add(boolean part) {
            parts.add(part);
            return this;
        }

        Key add(float[] values) {
            parts.add(new Floats(values));
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            List<Object> other = ((Key) o).parts;
            if (other.size() != parts.size()) return false;
            for (int i = 0; i < parts.size(); i++) if (!same(parts.get(i), other.get(i))) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int h = parts.size();
            for (Object p : parts) h = h * 31 + (byValue(p) ? p.hashCode() : System.identityHashCode(p));
            return h;
        }

        private static boolean same(Object a, Object b) {
            if (a == b) return true;
            return byValue(a) && a.equals(b);
        }

        private static boolean byValue(Object p) {
            return p instanceof Number || p instanceof Boolean || p instanceof Band || p instanceof Floats
                    || p instanceof Nothing;
        }
    }

    private enum Nothing { NULL }

    private static final class Floats {
        private final float[] values;

        Floats(float[] values) {
            this.values = values == null ? new float[0] : values.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Floats && Arrays.equals(values, ((Floats) o).values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    /**
     * The baked pictures kept, least recently shown first. Holding more than {@link #KEEP} lets the oldest go, never
     * the one on screen; what goes is returned so its memory can be given back.
     */
    static final class Kept<K, V> {
        private final LinkedHashMap<K, V> map = new LinkedHashMap<>(4, 0.75f, true);
        private final int max;

        Kept(int max) {
            this.max = max;
        }

        /** The picture for key, which counts as shown now; null when there is none. */
        V get(K key) {
            return key == null ? null : map.get(key);
        }

        /** Adds a picture; returns those to let go: one it replaced, and the oldest beyond max other than shown. */
        List<V> put(K key, V value, K shown) {
            List<V> gone = new ArrayList<>();
            V old = map.put(key, value);
            if (old != null && old != value) gone.add(old);
            gone.addAll(trim(key, shown));
            return gone;
        }

        /**
         * A picture is on screen now: lets go of the oldest beyond max other than it, which put had to keep while
         * the one before was still on screen. Returns them.
         */
        List<V> shown(K shown) {
            return trim(shown, shown);
        }

        private List<V> trim(K keep, K shown) {
            List<V> gone = new ArrayList<>();
            Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
            while (map.size() > max && it.hasNext()) {
                Map.Entry<K, V> e = it.next();
                if (e.getKey().equals(keep) || e.getKey().equals(shown)) continue;
                gone.add(e.getValue());
                it.remove();
            }
            return gone;
        }

        /** Lets go of every picture but the one shown; returns them. */
        List<V> keepOnly(K shown) {
            List<V> gone = new ArrayList<>();
            Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<K, V> e = it.next();
                if (e.getKey().equals(shown)) continue;
                gone.add(e.getValue());
                it.remove();
            }
            return gone;
        }

        int size() {
            return map.size();
        }
    }
}
