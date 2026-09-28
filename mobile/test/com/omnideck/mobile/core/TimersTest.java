package com.omnideck.mobile.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TimersTest {

    @Test
    public void addsListsAndRoundTripsThroughJson() {
        Timers t = new Timers();
        long now = 1_700_000_000_000L;
        Timers.Timer tea = t.add(now, 300, "tea");
        Timers.Timer eggs = t.add(now, 60, "  ");
        assertNotEquals("ids are unique even within one millisecond", tea.id, eggs.id);
        assertEquals("a blank message becomes a plain timer", "Timer", eggs.message);
        List<Timers.Timer> all = t.all();
        assertEquals("soonest first", eggs.id, all.get(0).id);
        assertEquals(60, all.get(0).secondsLeft(now));
        assertEquals(1, all.get(0).secondsLeft(now + 59_001));

        Timers back = Timers.parse(t.toJson());
        assertEquals(2, back.size());
        assertEquals("tea", back.find(tea.id).message);
        assertEquals(tea.endsAt, back.find(tea.id).endsAt);
        // New ids keep increasing after a restore.
        assertTrue(back.add(now, 10, "x").id > tea.id);
    }

    @Test
    public void dueTimersAndRemoval() {
        Timers t = new Timers();
        long now = 1_000_000L;
        Timers.Timer a = t.add(now, 5, "a");
        Timers.Timer b = t.add(now, 50, "b");
        assertTrue(t.due(now + 4_999).isEmpty());
        assertEquals(1, t.due(now + 5_000).size());
        assertEquals(2, t.due(now + 60_000).size());
        assertEquals(a.id, t.remove(a.id).id);
        assertNull("removing twice is harmless", t.remove(a.id));
        assertEquals(1, t.size());
        assertEquals(1, t.clear());
        assertTrue(t.isEmpty());
        assertNull(t.find(b.id));
    }

    @Test
    public void corruptOrPartialDataIsSkipped() {
        assertTrue(Timers.parse("not json").isEmpty());
        assertTrue(Timers.parse("").isEmpty());
        assertTrue(Timers.parse(null).isEmpty());
        Timers t = Timers.parse("[{\"id\":5,\"ends\":100,\"message\":\"ok\"},{\"id\":5,\"ends\":200},"
                + "{\"ends\":300},{\"id\":-1,\"ends\":5},\"junk\"]");
        assertEquals(1, t.size());
        assertEquals("ok", t.all().get(0).message);
    }
}
