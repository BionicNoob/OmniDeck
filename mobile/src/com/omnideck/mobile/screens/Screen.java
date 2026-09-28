package com.omnideck.mobile.screens;

import android.content.Intent;
import android.view.View;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.MainActivity;
import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.Telemetry;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;

/**
 * One page of the app (a bottom-bar tab or Settings). The view is built
 * lazily the first time it's shown and kept for the life of the activity;
 * the shell forwards Engine events to every built screen.
 */
public abstract class Screen {
    protected final MainActivity a;
    protected final Engine e;
    protected final Ui ui;
    protected final Theme t;
    private View root;
    private boolean shown;
    /** False between the activity's onStop and onStart (app in the background). */
    private boolean started = true;

    protected Screen(MainActivity a) {
        this.a = a;
        this.e = a.engine();
        this.ui = a.ui();
        this.t = a.theme();
    }

    public final View view() {
        if (root == null) root = build();
        return root;
    }

    public final boolean isBuilt() {
        return root != null;
    }

    /**
     * True while this screen is actually on screen: it is the selected page
     * AND the app is in the foreground. Gate animations, polling and
     * "seen" effects on this — Engine events keep arriving while the app is
     * in the background.
     */
    public final boolean isShown() {
        return shown && started;
    }

    /** The selected page in the shell, even while the app is in the background. */
    public final boolean isSelected() {
        return shown;
    }

    /** Called by the shell from onStart: marks the app visible, then runs {@link #onActivityStart()}. */
    public final void dispatchActivityStart() {
        started = true;
        onActivityStart();
    }

    /**
     * Called by the shell from onStop: runs {@link #onActivityStop()} while
     * {@link #isShown()} still reports the visible state (so a visible page
     * can save its drafts), then marks the app as in the background.
     */
    public final void dispatchActivityStop() {
        onActivityStop();
        started = false;
    }

    /** Builds the screen's view tree (called once). */
    protected abstract View build();

    /** Called by the shell. */
    public final void show() {
        shown = true;
        onShow();
    }

    public final void hide() {
        shown = false;
        onHide();
    }

    protected void onShow() {}

    protected void onHide() {}

    // --- Engine events (forwarded by the shell to built screens) ---

    public void onStateChanged() {}

    public void onTelemetry() {}

    public void onLog(Telemetry.Event ev) {}

    public void onPull() {}

    public void onConversationReplaced() {}

    public void onMessageAdded(ChatMessage m) {}

    public void onMessageChanged(ChatMessage m) {}

    public void onMessageRemoved(ChatMessage m) {}

    public void onBusyChanged() {}

    /** Text to put into this screen's composer (only the chat screen has one). */
    public void onInsertText(String text) {}

    /** Back pressed while this screen is showing; return true if handled. */
    public boolean onBack() {
        return false;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {}

    /** The activity became visible again (onStart). */
    public void onActivityStart() {}

    /** The activity is going to the background (onStop): save drafts, stop polling. */
    public void onActivityStop() {}

    /** Activity is being destroyed. */
    public void onDestroy() {}
}
