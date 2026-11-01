package com.gathin.tvhome;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Glow's pre-drawn shadows: the crop that keeps every visible pixel, and the nine-patch chunk. */
public class ShadowPatchTest {

    @Test
    public void cropKeepsEveryPixelWithAlpha() {
        int w = 6, h = 5;
        int[] argb = new int[w * h];
        argb[1 * w + 2] = 0x01000000; // the faintest pixel still counts
        argb[3 * w + 4] = 0x80123456;
        argb[4 * w + 0] = 0x00FFFFFF; // colour without alpha draws nothing
        assertArrayEquals(new int[] {2, 1, 5, 4}, ShadowPatch.crop(argb, w, h, new int[] {3, 2, 4, 3}));
    }

    @Test
    public void cropGrowsToTheBoxItMustKeep() {
        int w = 8, h = 8;
        int[] argb = new int[w * h];
        argb[6 * w + 6] = 0xFF000000;
        assertArrayEquals(new int[] {2, 3, 7, 7}, ShadowPatch.crop(argb, w, h, new int[] {2, 3, 5, 6}));
        // Nothing visible at all: just the box.
        assertArrayEquals(new int[] {2, 3, 5, 6}, ShadowPatch.crop(new int[w * h], w, h, new int[] {2, 3, 5, 6}));
    }

    @Test
    public void chunkStretchesOneColumnAndRowAndSkipsTheMiddle() {
        byte[] chunk = ShadowPatch.chunk(17, 9);
        ByteBuffer b = ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder());
        assertEquals(32 + 4 * 13, chunk.length);
        assertEquals(1, b.get(0)); // wasDeserialized, never -1
        assertEquals(2, b.get(1));
        assertEquals(2, b.get(2));
        assertEquals(9, b.get(3));
        int xOff = b.getInt(4), yOff = b.getInt(8), colorsOff = b.getInt(28);
        assertEquals(17, b.getInt(xOff));
        assertEquals(18, b.getInt(xOff + 4));
        assertEquals(9, b.getInt(yOff));
        assertEquals(10, b.getInt(yOff + 4));
        for (int i = 12; i < 28; i += 4) assertEquals(0, b.getInt(i)); // no padding
        for (int i = 0; i < 9; i++) {
            assertEquals(i == 4 ? ShadowPatch.TRANSPARENT : ShadowPatch.NO_COLOR, b.getInt(colorsOff + 4 * i));
        }
    }
}
