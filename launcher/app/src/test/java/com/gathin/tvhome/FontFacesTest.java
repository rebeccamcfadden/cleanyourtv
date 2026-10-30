package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** What ThemeFonts asks Google Fonts for, and where it keeps each face on the TV. */
public class FontFacesTest {
    private static Theme themed(Theme.Font ui, Theme.Font display) {
        Theme.Builder b = new Theme.Builder("test", Theme.Group.CLASSIC, "Test");
        b.uiFont = ui;
        b.displayFont = display;
        return b.build();
    }

    @Test
    public void aQueryAsksForOneWeightOfOneFamily() {
        assertEquals("name=Outfit&weight=300&besteffort=true", new FontFaces.Face("Outfit", 300).query());
        assertEquals("name=Press Start 2P&weight=400&besteffort=true",
                new FontFaces.Face("Press Start 2P", 400).query());
    }

    @Test
    public void fileNamesAreTheFamilyInLowerCaseAndTheWeight() {
        assertEquals("outfit-300.ttf", new FontFaces.Face("Outfit", 300).fileName());
        assertEquals("press-start-2p-400.ttf", new FontFaces.Face("Press Start 2P", 400).fileName());
        assertEquals("dm-serif-display-400.ttf", new FontFaces.Face("DM Serif  Display!", 400).fileName());
        assertEquals("font-500.ttf", new FontFaces.Face("---", 500).fileName());
    }

    @Test
    public void midnightNeedsNoFiles() {
        FontFaces.Face[] faces = FontFaces.of(Theme.midnight(), false);
        assertEquals(4, faces.length);
        for (FontFaces.Face f : faces) assertNull(f);
    }

    @Test
    public void aThemeNeedsItsThreeUiWeightsAndItsDisplayWeight() {
        Theme t = themed(new Theme.Font("Inter", 400, 500, 600), new Theme.Font("Fraunces", 300, 300, 300));
        FontFaces.Face[] faces = FontFaces.of(t, false);
        assertEquals(new FontFaces.Face("Inter", 400), faces[FontFaces.REGULAR]);
        assertEquals(new FontFaces.Face("Inter", 500), faces[FontFaces.MEDIUM]);
        assertEquals(new FontFaces.Face("Inter", 600), faces[FontFaces.BOLD]);
        assertEquals(new FontFaces.Face("Fraunces", 300), faces[FontFaces.DISPLAY]);
    }

    @Test
    public void theSystemFontOptionNeedsNoFiles() {
        Theme t = themed(new Theme.Font("Inter", 400, 500, 600), new Theme.Font("Fraunces", 300, 300, 300));
        for (FontFaces.Face f : FontFaces.of(t, true)) assertNull(f);
    }

    @Test
    public void aDisplayFontThatIsAUiWeightIsFetchedOnce() {
        Theme t = themed(new Theme.Font("Outfit", 300, 500, 700), new Theme.Font("Outfit", 300, 300, 300));
        List<FontFaces.Face> faces = FontFaces.distinct(FontFaces.of(t, false));
        assertEquals(Arrays.asList(new FontFaces.Face("Outfit", 300), new FontFaces.Face("Outfit", 500),
                new FontFaces.Face("Outfit", 700)), faces);
    }

    @Test
    public void onlyTheThemedFontIsFetched() {
        Theme t = themed(Theme.Font.SYSTEM, new Theme.Font("Bungee", 400, 400, 400));
        FontFaces.Face[] faces = FontFaces.of(t, false);
        assertNull(faces[FontFaces.REGULAR]);
        assertEquals(new FontFaces.Face("Bungee", 400), faces[FontFaces.DISPLAY]);
        assertEquals(1, FontFaces.distinct(faces).size());
    }
}
