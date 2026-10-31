package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Phone notes' waiting notes and last sends (BACKLOG.md, Phone notes). */
public class NoteBookTest {
    @Test
    public void theLatestNoteOfAListWins() {
        NoteBook b = new NoteBook();
        b.put("Rebecca", "Dune", 1);
        b.put("Partner", "Fargo", 2);
        b.put(" rebecca ", "Dune\nArrival", 3);
        List<NoteBook.Pending> waiting = b.pending();
        assertEquals(2, waiting.size());
        assertEquals("Partner", waiting.get(0).list);
        assertEquals("rebecca", waiting.get(1).list);
        assertEquals("Dune\nArrival", waiting.get(1).text);
    }

    @Test
    public void notesWaitOldestFirstAndCanBeSkipped() {
        NoteBook b = new NoteBook();
        b.put("Rebecca", "Dune", 1);
        b.put("Partner", "Fargo", 2);
        assertEquals("Rebecca", b.next(Collections.<String>emptySet()).list);
        assertEquals("Partner", b.next(new HashSet<>(Collections.singletonList("rebecca"))).list);
        assertNull(b.next(new HashSet<>(Arrays.asList("rebecca", "partner"))));
    }

    @Test
    public void aNoteDoneStopsWaitingUnlessANewerOneCameMeanwhile() {
        NoteBook b = new NoteBook();
        NoteBook.Pending first = b.put("Rebecca", "Dune", 1);
        NoteBook.Pending newer = b.put("Rebecca", "Dune\nArrival", 2);
        assertFalse("the newer send still waits", b.done(first));
        assertEquals(1, b.pending().size());
        assertTrue(b.done(newer));
        assertTrue(b.pending().isEmpty());
    }

    @Test
    public void waitingNotesSurviveARestart() {
        NoteBook b = new NoteBook();
        b.put("Rebecca", "☐ Dune\n☑ Arrival", 1000);
        b.put("Partner", "Fargo (tv)", 2000);
        NoteBook back = NoteBook.restore(b.pendingJson(), null);
        assertEquals(2, back.pending().size());
        assertEquals("Rebecca", back.pending().get(0).list);
        assertEquals("☐ Dune\n☑ Arrival", back.pending().get(0).text);
        assertEquals(2000, back.pending().get(1).at);
        // A note restored is replaced by the next send of its list, as before the restart.
        NoteBook.Pending again = back.put("REBECCA", "Dune", 3000);
        assertEquals(2, back.pending().size());
        assertTrue(back.done(again));
    }

    @Test
    public void eachListKeepsItsLastSendNewestFirst() {
        NoteBook b = new NoteBook();
        b.record(new NoteBook.Send("Partner", 1, 14, 14, null));
        b.record(new NoteBook.Send("Rebecca", 2, 12, 9, Arrays.asList("Zzyzx", "Qq", "Xy")));
        b.record(new NoteBook.Send("partner", 3, 15, 1, Collections.singletonList("Nope")));
        List<NoteBook.Send> sends = b.sends();
        assertEquals(2, sends.size());
        assertEquals("partner", sends.get(0).list);
        assertEquals(1, sends.get(0).added);
        assertEquals("Rebecca", sends.get(1).list);
        assertEquals("the section's \"4 not matched\"", 4, b.unmatched());
    }

    @Test
    public void lastSendsSurviveARestart() {
        NoteBook b = new NoteBook();
        b.record(new NoteBook.Send("Rebecca", 2, 12, 9, Arrays.asList("Zzyzx Qq", "Dune (1066)")));
        NoteBook back = NoteBook.restore(null, b.sendsJson());
        NoteBook.Send s = back.sends().get(0);
        assertEquals("Rebecca", s.list);
        assertEquals(2, s.at);
        assertEquals(12, s.lines);
        assertEquals(9, s.added);
        assertEquals(Arrays.asList("Zzyzx Qq", "Dune (1066)"), s.unmatched);
    }

    @Test
    public void onlyTheLatestSendsAreKept() {
        NoteBook b = new NoteBook();
        for (int i = 0; i < NoteBook.MAX_SENDS + 3; i++) b.record(new NoteBook.Send("List " + i, i, 1, 1, null));
        assertEquals(NoteBook.MAX_SENDS, b.sends().size());
        assertEquals("List " + (NoteBook.MAX_SENDS + 2), b.sends().get(0).list);
    }

    @Test
    public void somethingUnreadableIsLeftOut() {
        NoteBook b = NoteBook.restore("not json", "[{\"list\":\"\"},{\"list\":\"Rebecca\",\"lines\":3}]");
        assertTrue(b.pending().isEmpty());
        assertEquals(1, b.sends().size());
        assertEquals(0, b.unmatched());
        assertTrue(NoteBook.restore("[{\"list\":\"Rebecca\",\"text\":\"\"}]", null).pending().isEmpty());
    }
}
