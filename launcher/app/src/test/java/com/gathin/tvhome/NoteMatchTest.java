package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NoteMatchTest {
    /** Canned TMDB search answers by query and type; offline when {@link #offline} is set. */
    private final Map<String, JSONArray> answers = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private boolean offline;

    private final NoteMatch match = new NoteMatch(path -> {
        asked.add(path);
        if (offline) throw new IOException("offline");
        String type = path.startsWith("/search/movie") ? "movie" : "tv";
        String q = java.net.URLDecoder.decode(path.replaceAll(".*query=([^&]*).*", "$1"), "UTF-8");
        JSONArray a = answers.get(type + ":" + q);
        try {
            return new JSONObject().put("results", a == null ? new JSONArray() : a);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    });

    private static JSONObject movie(long id, String title, String date, double pop) throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("original_title", title).put("release_date", date)
                .put("popularity", pop);
    }

    private static JSONObject show(long id, String name, String date, double pop) throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("original_name", name).put("first_air_date", date)
                .put("popularity", pop);
    }

    private void answer(String key, JSONObject... results) {
        answers.put(key, new JSONArray(Arrays.asList(results)));
    }

    private static NoteLines.Line line(String text) {
        return NoteLines.parse(text).get(0);
    }

    @Test
    public void exactTitleWinsOverASequelAndTheMostPopularExactOneWins() throws Exception {
        answer("movie:Dune", movie(438631, "Dune", "2021-09-15", 90), movie(693134, "Dune: Part Two", "2024-02-27", 300),
                movie(841, "Dune", "1984-12-14", 20));
        assertEquals("movie:438631", match.match(line("Dune")));
    }

    @Test
    public void theYearPicksTheOlderOne() throws Exception {
        answer("movie:Dune", movie(438631, "Dune", "2021-09-15", 90), movie(841, "Dune", "1984-12-14", 20));
        assertEquals("movie:841", match.match(line("Dune (1984)")));
        assertTrue(asked.get(0), asked.get(0).contains("&year=1984"));
    }

    @Test
    public void accentsCaseAndPunctuationDontMatter() throws Exception {
        answer("movie:amelie", movie(194, "Amélie", "2001-04-25", 30));
        assertEquals("movie:194", match.match(line("amelie")));
        answer("movie:Knives Out!", movie(546554, "Knives Out", "2019-11-27", 40));
        assertEquals("movie:546554", match.match(line("Knives Out!")));
    }

    @Test
    public void aShowLineOnlyAsksForShows() throws Exception {
        answer("tv:Fargo", show(60622, "Fargo", "2014-04-15", 50));
        answer("movie:Fargo", movie(275, "Fargo", "1996-03-08", 30));
        assertEquals("tv:60622", match.match(line("Fargo S2")));
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).startsWith("/search/tv"));
    }

    @Test
    public void withoutAKindTheMorePopularExactTitleWins() throws Exception {
        answer("tv:Fargo", show(60622, "Fargo", "2014-04-15", 50));
        answer("movie:Fargo", movie(275, "Fargo", "1996-03-08", 30));
        assertEquals("tv:60622", match.match(line("Fargo")));
    }

    @Test
    public void onlyALeadingTheIsForgiven() throws Exception {
        answer("tv:Bear", show(136315, "The Bear", "2022-06-23", 80));
        assertEquals("tv:136315", match.match(line("Bear (tv)")));
        answer("movie:Lord of the Rings", movie(120, "The Lord of the Rings: The Fellowship of the Ring", "2001", 99));
        assertNull(match.match(line("Lord of the Rings")));
        answer("movie:Dun", movie(438631, "Dune", "2021-09-15", 90));
        assertNull(match.match(line("Dun")));
    }

    @Test
    public void aLinkNeedsNoSearch() throws Exception {
        assertEquals("movie:603", match.match(line("https://www.themoviedb.org/movie/603-the-matrix")));
        assertTrue(asked.isEmpty());
    }

    @Test
    public void planAddsNewLinesOnceAndListsWhatDidntMatch() throws Exception {
        answer("movie:Dune", movie(438631, "Dune", "2021-09-15", 90));
        answer("movie:Arrival", movie(329865, "Arrival", "2016-11-10", 60));
        NoteMatch.Memory memory = new NoteMatch.Memory();
        List<int[]> steps = new ArrayList<>();
        NoteMatch.Plan p = match.plan("Rebecca", NoteLines.parse("☐ Dune\n☐ Arrival\n☐ Zzyzx Qq"), memory,
                Collections.<String>emptySet(), (done, total) -> steps.add(new int[] {done, total}));
        assertEquals(Arrays.asList("movie:438631", "movie:329865"), p.add);
        assertEquals(Collections.singletonList("Zzyzx Qq"), p.unmatched);
        assertEquals(3, p.looked);
        assertEquals(4, steps.size());
        assertEquals(3, steps.get(3)[0]);
        assertEquals(3, steps.get(3)[1]);

        // The same note again: nothing looked up, nothing added (the household may have taken Dune off on the TV).
        asked.clear();
        NoteMatch.Plan again = match.plan("rebecca", NoteLines.parse("☐ Dune\n☐ Arrival\n☐ Zzyzx Qq"), memory,
                Collections.<String>emptySet(), null);
        assertTrue(asked.isEmpty());
        assertTrue(again.add.isEmpty());
        assertEquals(0, again.looked);
        assertEquals(Collections.singletonList("Zzyzx Qq"), again.unmatched);
    }

    @Test
    public void aTickedLineGoesToSeenEvenWhenItWasKnown() throws Exception {
        answer("movie:Dune", movie(438631, "Dune", "2021-09-15", 90));
        NoteMatch.Memory memory = new NoteMatch.Memory();
        match.plan("A", NoteLines.parse("- Dune"), memory, Collections.<String>emptySet(), null);
        NoteMatch.Plan p = match.plan("A", NoteLines.parse("- [x] Dune"), memory, Collections.<String>emptySet(), null);
        assertEquals(Collections.singletonList("movie:438631"), p.seen);
        assertTrue(p.add.isEmpty());
    }

    @Test
    public void aTitleAlreadyOnTheListIsNotAddedAgain() throws Exception {
        answer("movie:Dune", movie(438631, "Dune", "2021-09-15", 90));
        Set<String> on = new HashSet<>(Collections.singletonList("movie:438631"));
        assertTrue(match.plan("A", NoteLines.parse("Dune"), new NoteMatch.Memory(), on, null).add.isEmpty());
    }

    @Test
    public void offlineLinesAreTriedAgainNextTime() throws Exception {
        NoteMatch.Memory memory = new NoteMatch.Memory();
        offline = true;
        NoteMatch.Plan p = match.plan("A", NoteLines.parse("Dune"), memory, Collections.<String>emptySet(), null);
        assertEquals(1, p.failed);
        assertTrue(p.add.isEmpty());
        assertTrue(p.unmatched.isEmpty());
        offline = false;
        answer("movie:Dune", movie(438631, "Dune", "2021-09-15", 90));
        assertEquals(Collections.singletonList("movie:438631"),
                match.plan("A", NoteLines.parse("Dune"), memory, Collections.<String>emptySet(), null).add);
    }

    @Test
    public void memoryRoundTripsThroughJson() throws Exception {
        NoteMatch.Memory m = new NoteMatch.Memory();
        m.put("Rebecca", "dune", "movie:438631");
        m.put("Rebecca", "zzyzx", "");
        m.put("Partner", "fargo [tv]", "tv:60622");
        NoteMatch.Memory back = NoteMatch.Memory.fromJson(new JSONObject(m.toJson().toString()));
        assertEquals("movie:438631", back.get("rebecca", "dune"));
        assertEquals("", back.get("REBECCA", "zzyzx"));
        assertEquals("tv:60622", back.get("Partner", "fargo [tv]"));
        assertNull(back.get("Partner", "dune"));
    }
}
