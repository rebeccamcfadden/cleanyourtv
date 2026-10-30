package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The font files a theme needs (its UI font's regular, medium and bold weights and its display font) and how
 * ThemeFonts asks Google Fonts for each and names it on the TV. Pure Java, so it is unit-tested.
 */
final class FontFaces {
    private FontFaces() {}

    static final int REGULAR = 0, MEDIUM = 1, BOLD = 2, DISPLAY = 3;

    /** One weight of one family: one request to the provider and one file in filesDir/fonts/. */
    static final class Face {
        final String family;
        final int weight;

        Face(String family, int weight) {
            this.family = family;
            this.weight = weight;
        }

        /** The provider's query, e.g. {@code name=Outfit&weight=300}; the nearest weight when that one is missing. */
        String query() {
            return "name=" + family + "&weight=" + weight + "&besteffort=true";
        }

        /** The cached file's name, e.g. {@code press-start-2p-400.ttf}: the family in lower case, words joined by -. */
        String fileName() {
            StringBuilder b = new StringBuilder();
            for (char c : family.toLowerCase(Locale.ROOT).toCharArray()) {
                boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
                if (keep) b.append(c);
                else if (b.length() > 0 && b.charAt(b.length() - 1) != '-') b.append('-');
            }
            while (b.length() > 0 && b.charAt(b.length() - 1) == '-') b.setLength(b.length() - 1);
            if (b.length() == 0) b.append("font");
            return b + "-" + weight + ".ttf";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Face && ((Face) o).family.equals(family) && ((Face) o).weight == weight;
        }

        @Override
        public int hashCode() {
            return family.hashCode() * 31 + weight;
        }
    }

    /**
     * The faces a theme shows, indexed by REGULAR, MEDIUM, BOLD and DISPLAY; null where it is Roboto, and all null
     * when the System font option is on. The display font has one weight, its regular.
     */
    static Face[] of(Theme t, boolean systemFont) {
        Face[] faces = new Face[4];
        if (systemFont) return faces;
        Theme.Font ui = t.uiFont, display = t.displayFont;
        if (ui != null && ui.family != null) {
            faces[REGULAR] = new Face(ui.family, ui.regular);
            faces[MEDIUM] = new Face(ui.family, ui.medium);
            faces[BOLD] = new Face(ui.family, ui.bold);
        }
        if (display != null && display.family != null) faces[DISPLAY] = new Face(display.family, display.regular);
        return faces;
    }

    /** Each face once, in order: a display font that is also a UI weight is fetched only once. */
    static List<Face> distinct(Face[] faces) {
        List<Face> out = new ArrayList<>();
        for (Face f : faces) {
            if (f != null && !out.contains(f)) out.add(f);
        }
        return out;
    }
}
