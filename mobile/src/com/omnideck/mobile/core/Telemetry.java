package com.omnideck.mobile.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Live numbers for the command center: link latency, generation speed,
 * time-to-first-token, context fill, plus a timestamped event log. Plain
 * Java; the Engine feeds it and the dashboard reads snapshots.
 * Not thread-safe — used from the main thread only.
 */
public final class Telemetry {

    /** Fixed-size ring of samples, oldest first when read. */
    public static final class Series {
        private final double[] data;
        private int start;
        private int size;

        public Series(int capacity) {
            data = new double[Math.max(1, capacity)];
        }

        public void add(double v) {
            if (size < data.length) {
                data[(start + size) % data.length] = v;
                size++;
            } else {
                data[start] = v;
                start = (start + 1) % data.length;
            }
        }

        public int size() {
            return size;
        }

        public int capacity() {
            return data.length;
        }

        public double get(int i) {
            if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i + " of " + size);
            return data[(start + i) % data.length];
        }

        public double last() {
            return size == 0 ? Double.NaN : get(size - 1);
        }

        public double[] toArray() {
            double[] out = new double[size];
            for (int i = 0; i < size; i++) out[i] = get(i);
            return out;
        }

        public double max() {
            double m = Double.NaN;
            for (int i = 0; i < size; i++) {
                double v = get(i);
                if (Double.isNaN(m) || v > m) m = v;
            }
            return m;
        }

        public double average() {
            if (size == 0) return Double.NaN;
            double s = 0;
            for (int i = 0; i < size; i++) s += get(i);
            return s / size;
        }

        public void clear() {
            start = 0;
            size = 0;
        }
    }

    public static final class Event {
        public final long time;
        /** "info" | "ok" | "warn" | "error" */
        public final String level;
        public final String text;

        public Event(long time, String level, String text) {
            this.time = time;
            this.level = level;
            this.text = text;
        }
    }

    public static final int LOG_MAX = 200;

    public final Series latencyMs = new Series(60);
    public final Series tokensPerSec = new Series(30);
    public final Series ttftMs = new Series(30);
    public final Series contextFill = new Series(30);
    public final long sessionStart;
    public int replies;
    public long tokensOut;
    public long tokensIn;
    public int errors;
    public long lastLoadMs;
    private final List<Event> log = new ArrayList<Event>();

    public Telemetry(long now) {
        sessionStart = now;
    }

    /** Records a finished reply. {@code numCtx} ≤ 0 skips the context-fill sample. */
    public void reply(ChatStats s, long ttft, int numCtx) {
        replies++;
        tokensOut += s.evalTokens;
        tokensIn += s.promptTokens;
        if (s.evalMs > 0) tokensPerSec.add(s.tokensPerSecond());
        if (ttft >= 0) ttftMs.add(ttft);
        if (numCtx > 0) contextFill.add(Math.min(1.0, (s.promptTokens + s.evalTokens) / (double) numCtx));
        lastLoadMs = s.loadMs;
    }

    public void log(long time, String level, String text) {
        log.add(new Event(time, level == null ? "info" : level, text));
        if (log.size() > LOG_MAX) log.remove(0);
    }

    /** Newest last. */
    public List<Event> events() {
        return new ArrayList<Event>(log);
    }

    public Event lastEvent() {
        return log.isEmpty() ? null : log.get(log.size() - 1);
    }

    public long uptimeMs(long now) {
        return Math.max(0, now - sessionStart);
    }
}
