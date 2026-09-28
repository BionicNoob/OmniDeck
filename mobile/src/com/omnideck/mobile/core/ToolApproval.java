package com.omnideck.mobile.core;

/**
 * The AI asks for the user's OK before it changes something on the PC. The
 * Engine creates one of these per call that needs approval; the UI shows it
 * and answers exactly once ({@link #allow}, {@link #allowForChat},
 * {@link #deny}); the Engine can also settle it itself (the user wasn't
 * there, the reply was stopped). Whatever comes first wins; later answers
 * are ignored. Main thread only. Plain Java.
 */
public final class ToolApproval {
    /** Hears the answer (the Engine). */
    public interface Decision {
        void decided(int answer);
    }

    public static final int PENDING = 0;
    public static final int ALLOW = 1;
    /** Allowed, and don't ask again for this tool in this chat. */
    public static final int ALLOW_CHAT = 2;
    public static final int DENY = 3;
    /** Nobody could answer (the app was in the background, or the sheet couldn't show). */
    public static final int UNAVAILABLE = 4;
    /** Taken back by the Engine (the reply was stopped): nothing more happens. */
    public static final int WITHDRAWN = 5;

    /** The tool's id ("set_volume"). */
    public final String tool;
    /** The action log's label ("Set volume → 40%"). */
    public final String label;
    /** "set volume to 40%": the sheet says "OMNI wants to {phrase} on {pc}". */
    public final String phrase;
    /** The PC's name (its hostname when known, else the bridge address). */
    public final String pc;
    /** Where LaunchBridge answers ("192.168.1.20:8765"); "" when unknown. */
    public final String where;
    /** The arguments (or, for open_app, the app's path) to show in mono; "" when none. */
    public final String detail;
    /** Shutdown, restart, delete…: asked every time, and never allowed "for this chat". */
    public final boolean destructive;

    private Decision decision;
    private Runnable onSettled;
    private int answer = PENDING;

    public ToolApproval(String tool, String label, String phrase, String pc, String where, String detail,
                        boolean destructive) {
        this.tool = tool == null ? "" : tool;
        this.label = label == null ? "" : label;
        this.phrase = phrase == null ? "" : phrase;
        this.pc = pc == null ? "" : pc;
        this.where = where == null ? "" : where;
        this.detail = detail == null ? "" : detail;
        this.destructive = destructive;
    }

    /** The Engine's side: who hears the answer. */
    public void setDecision(Decision d) {
        decision = d;
    }

    /** The UI's side: runs once the request is settled from anywhere (e.g. dismiss its sheet). */
    public void setOnSettled(Runnable r) {
        onSettled = r;
    }

    public boolean isPending() {
        return answer == PENDING;
    }

    /** {@link #PENDING} until answered, then how. */
    public int answer() {
        return answer;
    }

    /** "OMNI wants to set volume to 40% on ATLAS-PC". */
    public String sentence() {
        return "OMNI wants to " + phrase + (pc.length() > 0 ? " on " + pc : "");
    }

    public void allow() {
        settle(ALLOW);
    }

    /** Allows it and every later call of this tool in this chat (a destructive tool is only allowed once). */
    public void allowForChat() {
        settle(destructive ? ALLOW : ALLOW_CHAT);
    }

    public void deny() {
        settle(DENY);
    }

    public void unavailable() {
        settle(UNAVAILABLE);
    }

    public void withdraw() {
        settle(WITHDRAWN);
    }

    private void settle(int a) {
        if (answer != PENDING) return;
        answer = a;
        Runnable s = onSettled;
        onSettled = null;
        Decision d = decision;
        decision = null;
        if (s != null) s.run();
        if (d != null) d.decided(a);
    }
}
