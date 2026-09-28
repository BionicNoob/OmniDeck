package com.omnideck.mobile.ui;

import com.omnideck.mobile.Engine;

/**
 * What the model bay has already done with the latest download result,
 * kept for the life of the process — like the Engine's own
 * {@link Engine.PullState} — rather than per screen. A theme change (or any
 * other activity recreate) builds a new model bay, and without this a result
 * card the user had closed would come back, its "seen" clock would restart,
 * and a finished download would be flagged as new a second time.
 * Main thread only. Entries are compared by identity: every download has its
 * own PullState, so an old entry never matches a new download.
 */
public final class ModelsPullMemo {
    private ModelsPullMemo() {}

    private static Engine.PullState dismissed;
    private static Engine.PullState claimed;
    private static Engine.PullState seen;
    private static long seenAt;

    /** True once the user closed this result (or it retired on its own). */
    public static boolean isDismissed(Engine.PullState ps) {
        return ps != null && ps == dismissed;
    }

    public static void dismiss(Engine.PullState ps) {
        dismissed = ps;
    }

    /**
     * True the first time it's asked about a finished download, false after:
     * the fresh model is flagged in the list exactly once.
     */
    public static boolean claim(Engine.PullState ps) {
        if (ps == null || ps == claimed) return false;
        claimed = ps;
        return true;
    }

    /** When this result's card was first on screen, marking it seen at {@code now} the first time. */
    public static long seenAt(Engine.PullState ps, long now) {
        if (ps != seen) {
            seen = ps;
            seenAt = now;
        }
        return seenAt;
    }
}
