package com.gathin.tvhome;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BakeRulesTest {
    private static final int W = 1920, H = 1080;

    @Test
    public void bakesOnlyASteadyBackdropOnASizedScreen() {
        assertTrue(BakeRules.bakeable(true, W, H));
        assertFalse("a picture fading in draws itself", BakeRules.bakeable(false, W, H));
        assertFalse("before layout", BakeRules.bakeable(true, 0, 0));
        assertFalse(BakeRules.bakeable(true, W, 0));
    }

    @Test
    public void aPageInTransitionDrawsItself() {
        assertTrue(BakeRules.pageSteady(true, true));
        assertFalse("sliding or fading in", BakeRules.pageSteady(true, false));
        assertFalse("no page yet", BakeRules.pageSteady(false, true));
    }

    @Test
    public void rootFillGoesUnderTheBakeAndUnderAnOpaquePicture() {
        assertFalse("the bake has the fill inside", BakeRules.rootFill(true, false));
        assertFalse(BakeRules.rootFill(true, true));
        assertFalse("an opaque picture covers the screen", BakeRules.rootFill(false, true));
        assertTrue("fading in, over the sky, or no picture", BakeRules.rootFill(false, false));
    }

    @Test
    public void dimAlphaFollowsTheViewsAlpha() {
        int dim = 0x99090E15;
        assertEquals(0, BakeRules.dimAlpha(dim, 0f));
        assertEquals(0x99, BakeRules.dimAlpha(dim, 1f));
        assertEquals(Math.round(0x99 * 0.5f), BakeRules.dimAlpha(dim, 0.5f));
        assertEquals("clamped", 0x99, BakeRules.dimAlpha(dim, 1.5f));
        assertEquals("clamped", 0, BakeRules.dimAlpha(dim, -0.2f));
        assertEquals(0, BakeRules.dimAlpha(0x00090E15, 1f));
    }

    @Test
    public void withAlphaKeepsTheColour() {
        assertEquals(0x4C090E15, BakeRules.withAlpha(0x99090E15, 0x4C));
        assertEquals(0x00090E15, BakeRules.withAlpha(0x99090E15, 0));
    }

    @Test
    public void onlyAPlainFullScreenViewCovers() {
        assertTrue(BakeRules.coversScreen(1f, true, 0, 0, W, H, W, H));
        assertFalse("faded", BakeRules.coversScreen(0.5f, true, 0, 0, W, H, W, H));
        assertFalse("translated or scaled", BakeRules.coversScreen(1f, false, 0, 0, W, H, W, H));
        assertFalse("offset", BakeRules.coversScreen(1f, true, 0, 10, W, H, W, H));
        assertFalse("shorter", BakeRules.coversScreen(1f, true, 0, 0, W, H - 1, W, H));
    }

    @Test
    public void bandsAreEqualByValue() {
        BakeRules.Band a = new BakeRules.Band(new int[]{1, 2}, null);
        BakeRules.Band b = new BakeRules.Band(new int[]{1, 2}, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new BakeRules.Band(new int[]{1, 3}, null));
        assertNotEquals(a, new BakeRules.Band(new int[]{1, 2}, new float[]{0f, 1f}));
    }

    @Test
    public void aBandKeepsItsOwnCopyOfTheColours() {
        int[] colors = {1, 2};
        BakeRules.Band a = new BakeRules.Band(colors, null);
        colors[0] = 9;
        assertEquals(new BakeRules.Band(new int[]{1, 2}, null), a);
    }

    @Test
    public void keysCompareValuesByValueAndPicturesByIdentity() {
        Object picture = new Object();
        BakeRules.Key a = key(picture, 0xFF000000, new float[]{1f, 0f, 0f});
        BakeRules.Key b = key(picture, 0xFF000000, new float[]{1f, 0f, 0f});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals("another picture object may draw other pixels", a, key(new Object(), 0xFF000000,
                new float[]{1f, 0f, 0f}));
        assertNotEquals("another fill", a, key(picture, 0xFF111111, new float[]{1f, 0f, 0f}));
        assertNotEquals("another placement", a, key(picture, 0xFF000000, new float[]{2f, 0f, 0f}));
    }

    @Test
    public void keysTellNoPictureFromAPicture() {
        assertEquals(key(null, 1, null), key(null, 1, null));
        assertNotEquals(key(null, 1, null), key(new Object(), 1, null));
    }

    @Test
    public void keysDifferByShade() {
        BakeRules.Band home = new BakeRules.Band(new int[]{0x40090E15, 0x8C090E15, 0xEB090E15}, null);
        BakeRules.Band page = new BakeRules.Band(new int[]{0xDB090E15, 0xE6090E15}, null);
        Object picture = new Object();
        BakeRules.Key withHome = new BakeRules.Key().add(picture).add(1).add(home);
        assertEquals(withHome, new BakeRules.Key().add(picture).add(1).add(
                new BakeRules.Band(new int[]{0x40090E15, 0x8C090E15, 0xEB090E15}, null)));
        assertNotEquals(withHome, new BakeRules.Key().add(picture).add(1).add(page));
        assertNotEquals("no shade", withHome, new BakeRules.Key().add(picture).add(1));
    }

    @Test
    public void keptHoldsTwoAndLetsTheOldestGo() {
        BakeRules.Kept<String, String> kept = new BakeRules.Kept<>(BakeRules.KEEP);
        assertTrue(kept.put("home", "H", null).isEmpty());
        assertTrue(kept.put("page", "P", "home").isEmpty());
        assertEquals(2, kept.size());
        List<String> gone = kept.put("other", "O", "page");
        assertEquals(Collections.singletonList("H"), gone);
        assertNull(kept.get("home"));
        assertEquals("P", kept.get("page"));
        assertEquals("O", kept.get("other"));
    }

    @Test
    public void keptNeverLetsTheShownOneGo() {
        BakeRules.Kept<String, String> kept = new BakeRules.Kept<>(BakeRules.KEEP);
        kept.put("home", "H", null);
        kept.put("page", "P", "home");
        // Home is shown and is also the oldest: the other one goes.
        assertEquals(Collections.singletonList("P"), kept.put("other", "O", "home"));
        assertEquals("H", kept.get("home"));
    }

    @Test
    public void keptCountsAGetAsShown() {
        BakeRules.Kept<String, String> kept = new BakeRules.Kept<>(BakeRules.KEEP);
        kept.put("home", "H", null);
        kept.put("page", "P", null);
        assertEquals("H", kept.get("home"));
        assertEquals(Collections.singletonList("P"), kept.put("other", "O", null));
    }

    @Test
    public void keptReturnsAReplacedPicture() {
        BakeRules.Kept<String, String> kept = new BakeRules.Kept<>(BakeRules.KEEP);
        kept.put("home", "H1", null);
        assertEquals(Collections.singletonList("H1"), kept.put("home", "H2", "home"));
        assertEquals("H2", kept.get("home"));
        String same = "S";
        kept.put("page", same, null);
        assertTrue("the same picture again is not let go", kept.put("page", same, null).isEmpty());
    }

    @Test
    public void keepOnlyLetsGoOfAllButTheShown() {
        BakeRules.Kept<String, String> kept = new BakeRules.Kept<>(BakeRules.KEEP);
        kept.put("home", "H", null);
        kept.put("page", "P", null);
        assertEquals(Collections.singletonList("P"), kept.keepOnly("home"));
        assertEquals(1, kept.size());
        assertSame("H", kept.get("home"));
        assertEquals(Collections.singletonList("H"), kept.keepOnly(null));
        assertEquals(0, kept.size());
        assertNull(kept.get(null));
    }

    @Test
    public void keepsOnePicturePerLook() {
        assertEquals("Home's and the other pages'", 2, BakeRules.KEEP);
        assertEquals(2, BakeRules.keep(false));
        assertEquals("a low-RAM TV keeps only the one on screen", 1, BakeRules.keep(true));
    }

    @Test
    public void keepingOneHoldsTheOldUntilTheNewIsShown() {
        BakeRules.Kept<String, String> kept = new BakeRules.Kept<>(1);
        kept.put("home", "H", null);
        assertTrue("home is still on screen", kept.put("page", "P", "home").isEmpty());
        assertEquals(2, kept.size());
        assertEquals(Collections.singletonList("H"), kept.shown("page"));
        assertEquals(1, kept.size());
        assertEquals("P", kept.get("page"));
        assertTrue(kept.shown("page").isEmpty());
    }

    @Test
    public void shownLetsGoOfNothingWithinMax() {
        BakeRules.Kept<String, String> kept = new BakeRules.Kept<>(2);
        kept.put("home", "H", null);
        kept.put("page", "P", "home");
        assertTrue(kept.shown("page").isEmpty());
        assertEquals(2, kept.size());
    }

    private static BakeRules.Key key(Object picture, int fill, float[] matrix) {
        return new BakeRules.Key().add(W).add(H).add(fill).add(picture).add(matrix);
    }
}
