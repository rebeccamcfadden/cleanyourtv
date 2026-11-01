package com.gathin.tvhome;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The pure-Java half of Glow's pre-drawn shadows: where a shadow picture can be cropped, and the nine-patch chunk that
 * lets it be drawn in one call. Tested on the JVM (ShadowPatchTest).
 */
final class ShadowPatch {
    private ShadowPatch() {}

    /**
     * The smallest box {left, top, right, bottom} (right and bottom exclusive) holding every pixel of a w × h picture
     * whose alpha isn't 0, grown to keep the box {@code keep} too. Rows and columns that would draw nothing are cut,
     * so they cost no fill.
     */
    static int[] crop(int[] argb, int w, int h, int[] keep) {
        int left = w, top = h, right = 0, bottom = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((argb[y * w + x] >>> 24) == 0) continue;
                if (x < left) left = x;
                if (x >= right) right = x + 1;
                if (y < top) top = y;
                if (y >= bottom) bottom = y + 1;
            }
        }
        return new int[] {Math.min(left, keep[0]), Math.min(top, keep[1]), Math.max(right, keep[2]),
                Math.max(bottom, keep[3])};
    }

    /** Res_png_9patch's colour hints: a patch the renderer skips, and one it draws from the picture. */
    static final int TRANSPARENT = 0x00000000, NO_COLOR = 0x00000001;

    /**
     * A nine-patch chunk (the serialized Res_png_9patch that android.graphics.NinePatch takes) that stretches only
     * column {@code xDiv} and row {@code yDiv}, and marks the middle patch transparent, so the renderer doesn't draw
     * under the tile at all. Both must leave at least one pixel either side.
     */
    static byte[] chunk(int xDiv, int yDiv) {
        int[] colors = {NO_COLOR, NO_COLOR, NO_COLOR, NO_COLOR, TRANSPARENT, NO_COLOR, NO_COLOR, NO_COLOR, NO_COLOR};
        int header = 32;
        ByteBuffer b = ByteBuffer.allocate(header + 4 * (2 + 2 + colors.length)).order(ByteOrder.nativeOrder());
        b.put((byte) 1); // wasDeserialized: anything but -1
        b.put((byte) 2); // numXDivs
        b.put((byte) 2); // numYDivs
        b.put((byte) colors.length);
        b.putInt(header); // xDivsOffset
        b.putInt(header + 8); // yDivsOffset
        b.putInt(0).putInt(0).putInt(0).putInt(0); // padding left, right, top, bottom
        b.putInt(header + 16); // colorsOffset
        b.putInt(xDiv).putInt(xDiv + 1);
        b.putInt(yDiv).putInt(yDiv + 1);
        for (int c : colors) b.putInt(c);
        return b.array();
    }
}
