package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NoteLinesTest {
    private static List<String> queries(String text) {
        List<String> out = new ArrayList<>();
        for (NoteLines.Line l : NoteLines.parse(text)) out.add(l.query);
        return out;
    }

    private static NoteLines.Line only(String text) {
        List<NoteLines.Line> l = NoteLines.parse(text);
        assertEquals(l.toString(), 1, l.size());
        return l.get(0);
    }

    @Test
    public void emptyTextHasNoLines() {
        assertTrue(NoteLines.parse(null).isEmpty());
        assertTrue(NoteLines.parse("  \n\n ").isEmpty());
    }

    @Test
    public void keepChecklistDropsTheTitleAndReadsTicks() {
        List<NoteLines.Line> l = NoteLines.parse("Movies to watch\n☐ Dune\n☑ Arrival\n☐ Past Lives\n");
        assertEquals(3, l.size());
        assertEquals("Dune", l.get(0).query);
        assertFalse(l.get(0).ticked);
        assertEquals("Arrival", l.get(1).query);
        assertTrue(l.get(1).ticked);
    }

    @Test
    public void markdownCheckboxesAndBullets() {
        List<NoteLines.Line> l = NoteLines.parse("Watch\n- [ ] Dune\n- [x] Arrival\n* [X] Moon\n• Coda\n1. Fargo\n2) Dark");
        assertEquals(List.of("Dune", "Arrival", "Moon", "Coda", "Fargo", "Dark"), queries(
                "Watch\n- [ ] Dune\n- [x] Arrival\n* [X] Moon\n• Coda\n1. Fargo\n2) Dark"));
        assertFalse(l.get(0).ticked);
        assertTrue(l.get(1).ticked);
        assertTrue(l.get(2).ticked);
        assertFalse(l.get(3).ticked);
    }

    @Test
    public void otherTickMarksAndStrikethrough() {
        assertTrue(only("✓ Dune").ticked);
        assertTrue(only("✅ Dune").ticked);
        assertTrue(only("✔️ Dune").ticked);
        assertTrue(only("~~Dune~~").ticked);
        assertTrue(only("- ~~Dune~~").ticked);
        assertEquals("Dune", only("- ~~Dune~~").query);
    }

    @Test
    public void plainNoteKeepsItsFirstLineUnlessItNamesTheList() {
        assertEquals(List.of("Dune", "Arrival"), queries("Dune\nArrival"));
        assertEquals(List.of("Dune", "Arrival"), queries("Movies to watch\nDune\nArrival"));
        assertEquals(List.of("Dune", "Arrival"), queries("Our watchlist\n\nDune\nArrival"));
    }

    @Test
    public void yearInBracketsNarrowsTheSearch() {
        NoteLines.Line l = only("☐ Dune (2021)");
        assertEquals("Dune", l.query);
        assertEquals(2021, l.year);
        assertEquals(1999, only("The Matrix [1999]").year);
    }

    @Test
    public void numbersInTitlesStay() {
        assertEquals("Blade Runner 2049", only("Blade Runner 2049").query);
        assertEquals(0, only("Blade Runner 2049").year);
        assertEquals("1917", only("1917").query);
        assertEquals("2001: A Space Odyssey", only("2001: A Space Odyssey").query);
        assertEquals("Mission: Impossible", only("Mission: Impossible").query);
        assertEquals("Se7en", only("Se7en").query);
    }

    @Test
    public void kindFromTheLineOrASeason() {
        assertEquals(Kind.SHOW, only("Fargo (tv)").kind);
        assertEquals(Kind.SHOW, only("Fargo (series)").kind);
        assertEquals(Kind.MOVIE, only("Fargo (movie)").kind);
        NoteLines.Line s = only("Andor S2");
        assertEquals("Andor", s.query);
        assertEquals(Kind.SHOW, s.kind);
        assertEquals("The Bear", only("The Bear - season 3").query);
        NoteLines.Line both = only("Fargo S2 (2014)");
        assertEquals("Fargo", both.query);
        assertEquals(2014, both.year);
        assertNull(only("Dune").kind);
    }

    @Test
    public void headingsSetTheKindAndAreNotTitles() {
        List<NoteLines.Line> l = NoteLines.parse("Watch list\nMovies:\n- Dune\n- Arrival\n\nShows\n- Dark\n# Later\n- Fargo");
        assertEquals(List.of("Dune", "Arrival", "Dark", "Fargo"),
                queries("Watch list\nMovies:\n- Dune\n- Arrival\n\nShows\n- Dark\n# Later\n- Fargo"));
        assertEquals(Kind.MOVIE, l.get(0).kind);
        assertEquals(Kind.SHOW, l.get(2).kind);
        assertEquals(Kind.SHOW, l.get(3).kind);
    }

    @Test
    public void themoviedbLinkIsTheTitle() {
        NoteLines.Line l = only("https://www.themoviedb.org/movie/603-the-matrix");
        assertEquals("movie:603", l.id);
        assertEquals("movie:603", l.key);
        assertEquals("tv:1399", only("☑ themoviedb.org/tv/1399").id);
        assertTrue(only("☑ themoviedb.org/tv/1399").ticked);
    }

    @Test
    public void aRepeatedTitleCountsOnceTheLaterLineWinning() {
        List<NoteLines.Line> l = NoteLines.parse("- Dune\n- Arrival\n- [x] dune");
        assertEquals(2, l.size());
        assertEquals("Arrival", l.get(0).query);
        assertEquals("dune", l.get(1).query);
        assertTrue(l.get(1).ticked);
    }

    @Test
    public void keyIgnoresCaseSpacesAndCurlyQuotes() {
        assertEquals(only("Ocean’s  Eleven").key, only("ocean's eleven").key);
        assertFalse(only("Dune (2021)").key.equals(only("Dune (1984)").key));
    }

    @Test
    public void linesOfOnlySymbolsAndObjectReplacementsAreSkipped() {
        assertEquals(List.of("Dune"), queries("- Dune\n- ---\n\uFFFC\n☐"));
    }

    @Test
    public void longNotesAreCut() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < NoteLines.MAX_LINES + 50; i++) b.append("Title ").append(i).append('\n');
        assertEquals(NoteLines.MAX_LINES, NoteLines.parse(b.toString()).size());
        assertTrue(only("x".repeat(400)).query.length() <= NoteLines.MAX_LENGTH);
    }
}
