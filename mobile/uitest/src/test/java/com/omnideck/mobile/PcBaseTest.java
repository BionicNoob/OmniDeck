package com.omnideck.mobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.ui.PcSlider;

import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Shared helpers for the PC tab's UI tests: a rich mock LaunchBridge, opening
 * the tab once the AI is linked, sheets (the themed dialogs), taps on the
 * volume slider and full-page screenshots. Abstract: Gradle runs only the
 * subclasses.
 */
public abstract class PcBaseTest extends Harness {

    /** A mock bridge that answers like a real PC (catalog, host/OS/battery, rendered screenshot). */
    protected void richBridge(boolean paired) throws Exception {
        withBridge(paired);
        bridge.rich = true;
    }

    /**
     * Launches on Models (a tab that doesn't read PC vitals, unlike Command),
     * waits for the AI link, then opens the PC tab.
     */
    protected void openPc(String theme) {
        launch(theme, MainActivity.TAB_MODELS);
        waitOnline();
        tab(MainActivity.TAB_PC);
    }

    protected void waitPaired() {
        waitFor("live vitals", () -> shows("ATLAS-PC") && shows("Live ·") && shows("8.1 / 16 GB"));
    }

    protected static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    protected <T extends View> T find(Class<T> type) {
        for (View v : views()) {
            if (type.isInstance(v) && v.isShown()) return type.cast(v);
        }
        return null;
    }

    protected PcSlider slider() {
        PcSlider s = find(PcSlider.class);
        assertNotNull("volume slider", s);
        return s;
    }

    protected ScrollView pcScroll() {
        ScrollView sv = find(ScrollView.class);
        assertNotNull("PC scroller", sv);
        return sv;
    }

    /** Scrolls the PC tab so the view showing {@code text} sits near the top. */
    protected void scrollTo(String text, int marginDp) {
        TextView v = textView(text);
        assertNotNull("no '" + text + "'", v);
        scrollToView(v, marginDp);
    }

    protected void scrollToView(View v, int marginDp) {
        ScrollView sv = pcScroll();
        int[] a = new int[2];
        int[] b = new int[2];
        v.getLocationInWindow(a);
        sv.getLocationInWindow(b);
        float d = act.getResources().getDisplayMetrics().density;
        sv.scrollBy(0, a[1] - b[1] - Math.round(marginDp * d));
        idle();
    }

    /** The whole PC page (beyond the viewport) on the theme's page color, for review. */
    protected void shootFull(String name) throws Exception {
        View content = pcScroll().getChildAt(0);
        Bitmap bmp = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(act.theme().bg);
        content.draw(c);
        File dir = new File("build/screens");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    protected void tap(View v, float x) {
        float y = v.getHeight() / 2f;
        long t0 = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_MOVE, x, y, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 80, MotionEvent.ACTION_UP, x, y, 0));
        idle();
    }

    protected int toolCalls(String tool) {
        int n = 0;
        synchronized (bridge.ranTools) {
            for (String s : bridge.ranTools) {
                if (tool.equals(s)) n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Sheets (the app's AlertDialog-based dialogs)
    // ------------------------------------------------------------------

    protected static AlertDialog latestAlert() {
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    /** The newest dialog, once it's a different one from {@code before} and showing. */
    protected static AlertDialog waitSheet(String what, AlertDialog before) {
        waitFor(what, () -> latestAlert() != null && latestAlert() != before && latestAlert().isShowing());
        return latestAlert();
    }

    protected static List<View> dialogViews(Dialog d) {
        List<View> out = new ArrayList<>();
        collect(d.getWindow().getDecorView(), out);
        return out;
    }

    protected static boolean dialogShows(Dialog d, String text) {
        String t = text.toLowerCase(Locale.US);
        for (View v : dialogViews(d)) {
            if (v instanceof TextView && v.isShown()
                    && ((TextView) v).getText().toString().toLowerCase(Locale.US).contains(t)) {
                return true;
            }
        }
        return false;
    }

    protected static View dialogView(Dialog d, String description) {
        for (View v : dialogViews(d)) {
            CharSequence cd = v.getContentDescription();
            if (v.isShown() && cd != null && description.contentEquals(cd)) return v;
        }
        return null;
    }

    protected static EditText dialogField(Dialog d, String description) {
        View v = dialogView(d, description);
        if (!(v instanceof EditText)) fail("no field '" + description + "' in the dialog");
        return (EditText) v;
    }

    protected static void dialogClick(Dialog d, String description) {
        View v = dialogView(d, description);
        if (v == null) fail("nothing described as '" + description + "' in the dialog");
        v.performClick();
        idle();
    }

    /** Taps the row of a pick list whose title is {@code title}. */
    protected static void pickRow(Dialog d, String title) {
        for (View v : dialogViews(d)) {
            if (v instanceof TextView && v.isShown() && title.contentEquals(((TextView) v).getText())) {
                View row = (View) v.getParent();
                row.performClick();
                idle();
                return;
            }
        }
        fail("no row '" + title + "' in the list");
    }

    protected static void positive(Dialog d) {
        ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
    }

    protected static void negative(Dialog d) {
        ((AlertDialog) d).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
    }

    protected static void neutral(Dialog d) {
        ((AlertDialog) d).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        idle();
    }
}
