package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a phone note's lines into titles on a list (BACKLOG.md, Phone notes). Each line is looked up once: its answer,
 * a title id or "not found", is kept per list ({@link Memory}), so sending the same note again only looks up the new
 * lines, and a title taken off the list on the TV is not put back. Adding only: a line gone from the note takes
 * nothing off. A ticked line's title goes to Seen and comes off the list.
 *
 * <p>Matching: a TMDB link is the title. Otherwise TMDB's search (movies or shows when the line says which, with its
 * year), and the result whose title, or original title, is the line's (ignoring case, accents and punctuation) and,
 * when the line has a year, from that year or the next; the most popular of those. Without one, TMDB's first answer
 * counts only when its title and the line differ by a leading "The"; "Dune" never becomes "Dune: Part Two". Nothing
 * else is guessed: an unmatched line is listed for the household to fix in the note (a changed line is looked up
 * again; an unchanged one is not).
 * Blocking and pure Java over {@link Fetch}; unit-tested against canned TMDB answers (NoteMatchTest).
 */
final class NoteMatch {
    /** GET a TMDB v3 path ("/search/movie?…"); throws when TMDB can't be reached. */
    interface Fetch {
        JSONObject get(String path) throws IOException;
    }

    /** How a run is going: lines looked up so far of the new ones. Called on the run's thread. */
    interface Progress {
        void step(int done, int total);
    }

    /** What a note asks for, worked out. */
    static final class Plan {
        /** Titles to add to the list, in the note's order. */
        final List<String> add;
        /** Titles ticked in the note: onto Seen, off the list. */
        final List<String> seen;
        /** Lines that matched nothing, as written (without markers). */
        final List<String> unmatched;
        /** Lines in the note, and those looked up this time (the rest were known). */
        final int lines, looked;
        /** Lines that couldn't be looked up (offline): they are tried again next time. */
        final int failed;

        Plan(List<String> add, List<String> seen, List<String> unmatched, int lines, int looked, int failed) {
            this.add = add;
            this.seen = seen;
            this.unmatched = unmatched;
            this.lines = lines;
            this.looked = looked;
            this.failed = failed;
        }
    }

    /**
     * Each list's lines and their answers, saved with the household's state: line key → title id, or "" for not
     * found. Not thread-safe; the run's thread owns it while a note is matched.
     */
    static final class Memory {
        private final Map<String, Map<String, String>> lists = new LinkedHashMap<>();

        String get(String list, String line) {
            Map<String, String> m = lists.get(NoteLines.key(list));
            return m == null ? null : m.get(line);
        }

        void put(String list, String line, String id) {
            String k = NoteLines.key(list);
            Map<String, String> m = lists.get(k);
            if (m == null) lists.put(k, m = new LinkedHashMap<>());
            m.put(line, id);
        }

        /** Drops everything remembered for the list. */
        void forget(String list) {
            lists.remove(NoteLines.key(list));
        }

        /** Lines remembered for the list, for the pane's "not matched" list. */
        Map<String, String> of(String list) {
            Map<String, String> m = lists.get(NoteLines.key(list));
            return m == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(m);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                for (Map.Entry<String, Map<String, String>> e : lists.entrySet()) o.put(e.getKey(), new JSONObject(e.getValue()));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            return o;
        }

        static Memory fromJson(JSONObject o) {
            Memory m = new Memory();
            if (o == null) return m;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String list = it.next();
                JSONObject lines = o.optJSONObject(list);
                if (lines == null) continue;
                for (java.util.Iterator<String> l = lines.keys(); l.hasNext(); ) {
                    String line = l.next();
                    m.put(list, line, lines.optString(line, ""));
                }
            }
            return m;
        }
    }

    private final Fetch fetch;

    NoteMatch(Fetch fetch) {
        this.fetch = fetch;
    }

    /**
     * Works out what the note asks of the list. {@code onList} is what the list holds now, so a title already there
     * isn't added again.
     */
    Plan plan(String list, List<NoteLines.Line> lines, Memory memory, Set<String> onList, Progress progress) {
        int total = 0;
        for (NoteLines.Line l : lines) if (l.id == null && memory.get(list, l.key) == null) total++;
        Set<String> add = new LinkedHashSet<>(), seen = new LinkedHashSet<>();
        List<String> unmatched = new ArrayList<>();
        int done = 0, failed = 0;
        if (progress != null) progress.step(0, total);
        for (NoteLines.Line l : lines) {
            String known = l.id != null ? l.id : memory.get(list, l.key);
            boolean fresh = known == null;
            if (fresh) {
                try {
                    known = match(l);
                } catch (IOException e) {
                    failed++;
                    done++;
                    if (progress != null) progress.step(done, total);
                    continue;
                }
                memory.put(list, l.key, known == null ? "" : known);
                done++;
                if (progress != null) progress.step(done, total);
            } else if (l.id != null) {
                memory.put(list, l.key, l.id);
            }
            if (known == null || known.isEmpty()) {
                unmatched.add(l.query);
            } else if (l.ticked) {
                seen.add(known);
            } else if (fresh && !onList.contains(known)) {
                // Only a line seen for the first time adds: one the household took off on the TV stays off.
                add.add(known);
            }
        }
        add.removeAll(seen);
        return new Plan(new ArrayList<>(add), new ArrayList<>(seen), unmatched, lines.size(), done, failed);
    }

    /** The line's title id, or null when nothing matches. */
    String match(NoteLines.Line l) throws IOException {
        if (l.id != null) return l.id;
        List<Candidate> found = new ArrayList<>();
        if (l.kind == null || l.kind == Kind.MOVIE) found.addAll(search("movie", l));
        if (l.kind == null || l.kind == Kind.SHOW) found.addAll(search("tv", l));
        return best(l, found);
    }

    private List<Candidate> search(String type, NoteLines.Line l) throws IOException {
        String path = "/search/" + type + "?query=" + URLEncoder.encode(l.query, "UTF-8") + "&include_adult=false&page=1"
                + (l.year > 0 ? (type.equals("movie") ? "&year=" : "&first_air_date_year=") + l.year : "");
        JSONObject o = fetch.get(path);
        List<Candidate> out = new ArrayList<>();
        JSONArray results = o == null ? null : o.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || r.optBoolean("adult")) continue;
            String id = TmdbAccountApi.id(type, r.optLong("id"));
            if (id == null) continue;
            String date = r.optString(type.equals("movie") ? "release_date" : "first_air_date", "");
            int year = date.length() >= 4 && date.substring(0, 4).matches("\\d{4}") ? Integer.parseInt(date.substring(0, 4)) : 0;
            out.add(new Candidate(id, r.optString(type.equals("movie") ? "title" : "name", ""),
                    r.optString(type.equals("movie") ? "original_title" : "original_name", ""), year,
                    r.optDouble("popularity", 0), i));
        }
        return out;
    }

    static final class Candidate {
        final String id, title, original;
        final int year, rank;
        final double popularity;

        Candidate(String id, String title, String original, int year, double popularity, int rank) {
            this.id = id;
            this.title = title;
            this.original = original;
            this.year = year;
            this.popularity = popularity;
            this.rank = rank;
        }
    }

    static String best(NoteLines.Line l, List<Candidate> found) {
        String want = fold(l.query);
        if (want.isEmpty()) return null;
        Candidate exact = null;
        for (Candidate c : found) {
            if (!want.equals(fold(c.title)) && !want.equals(fold(c.original))) continue;
            if (l.year > 0 && c.year != 0 && c.year != l.year && c.year != l.year + 1) continue;
            if (exact == null || c.popularity > exact.popularity) exact = c;
        }
        if (exact != null) return exact.id;
        // TMDB's first answer, when only a leading "The" differs: "Bear" for "The Bear", "The Arrival" for "Arrival".
        Candidate first = null;
        for (Candidate c : found) if (c.rank == 0 && (first == null || c.popularity > first.popularity)) first = c;
        if (first == null) return null;
        if (l.year > 0 && first.year != 0 && Math.abs(first.year - l.year) > 1) return null;
        String t = fold(first.title);
        return t.equals("the" + want) || want.equals("the" + t) ? first.id : null;
    }

    /** Lower case, no accents, only letters and digits, "&" as "and": "Amélie", "amelie" and "AMÉLIE!" fold alike. */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace("&", "and"), Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }
}
