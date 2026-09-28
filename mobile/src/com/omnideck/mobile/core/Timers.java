package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The /timer list: when each timer ends and what it's for. Saved as JSON in
 * the app's preferences so timers survive the app being closed (an alarm
 * wakes the app to ring them). Plain Java; not thread-safe (main thread).
 */
public final class Timers {
    /** One running timer. */
    public static final class Timer {
        /** Unique and stable: names the timer's alarm. */
        public final long id;
        /** Wall-clock time it ends, in ms. */
        public final long endsAt;
        public final String message;

        public Timer(long id, long endsAt, String message) {
            this.id = id;
            this.endsAt = endsAt;
            this.message = message == null || message.trim().length() == 0 ? "Timer" : message.trim();
        }

        /** Seconds left at {@code now} (0 once it's due). */
        public long secondsLeft(long now) {
            return Math.max(0, (endsAt - now + 999) / 1000);
        }
    }

    private final List<Timer> list = new ArrayList<Timer>();
    private long lastId;

    /** Reads saved timers; anything unreadable is skipped. */
    public static Timers parse(String json) {
        Timers t = new Timers();
        if (json == null || json.trim().length() == 0) return t;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !o.has("id") || !o.has("ends")) continue;
                Timer x = new Timer(o.optLong("id"), o.optLong("ends"), OllamaClient.str(o, "message"));
                if (x.id > 0 && x.endsAt > 0 && t.find(x.id) == null) t.put(x);
            }
        } catch (JSONException ignored) {
            // Corrupt: start over with no timers.
        }
        return t;
    }

    public String toJson() {
        JSONArray a = new JSONArray();
        try {
            for (Timer x : list) a.put(new JSONObject().put("id", x.id).put("ends", x.endsAt).put("message", x.message));
        } catch (JSONException ignored) {
            // Plain values; can't happen.
        }
        return a.toString();
    }

    /** Starts a timer of {@code seconds} from {@code now}. */
    public Timer add(long now, long seconds, String message) {
        long id = Math.max(now, lastId + 1);
        Timer x = new Timer(id, now + seconds * 1000L, message);
        put(x);
        return x;
    }

    private void put(Timer x) {
        list.add(x);
        lastId = Math.max(lastId, x.id);
        Collections.sort(list, new Comparator<Timer>() {
            @Override
            public int compare(Timer a, Timer b) {
                return a.endsAt < b.endsAt ? -1 : a.endsAt > b.endsAt ? 1 : (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
            }
        });
    }

    /** All timers, soonest first. */
    public List<Timer> all() {
        return new ArrayList<Timer>(list);
    }

    /** Timers that have ended at {@code now}, soonest first. */
    public List<Timer> due(long now) {
        List<Timer> out = new ArrayList<Timer>();
        for (Timer x : list) {
            if (x.endsAt <= now) out.add(x);
        }
        return out;
    }

    public Timer find(long id) {
        for (Timer x : list) {
            if (x.id == id) return x;
        }
        return null;
    }

    /** Removes and returns the timer, or null when it's gone already. */
    public Timer remove(long id) {
        Timer x = find(id);
        if (x != null) list.remove(x);
        return x;
    }

    /** Removes every timer; returns how many there were. */
    public int clear() {
        int n = list.size();
        list.clear();
        return n;
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public int size() {
        return list.size();
    }
}
