package com.omnideck.mobile;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import com.omnideck.mobile.core.LanScanner;
import com.omnideck.mobile.mock.MockBridge;
import com.omnideck.mobile.mock.MockOllama;

import org.junit.After;
import org.junit.Before;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

/**
 * Shared harness for UI tests: runs the real MainActivity + Engine inside
 * Robolectric against a mock Ollama (and optionally a mock LaunchBridge)
 * over real HTTP on loopback. Subclasses add @RunWith/@Config/@LooperMode(PAUSED)
 * and, for screenshots, @GraphicsMode(NATIVE).
 */
public abstract class Harness {
    protected MockOllama ollama;
    protected MockBridge bridge;
    protected ActivityController<MainActivity> ctl;
    protected MainActivity act;

    @Before
    public void harnessSetUp() throws Exception {
        Engine.reset();
        // Sweep a small loopback "LAN" instead of the build machine's network.
        Engine.testSubnets = Collections.singletonList(
                new LanScanner.Subnet("wlan0", LanScanner.parseIp("127.0.3.1"), 28));
        ollama = MockOllama.start("127.0.0.1", 0);
        ollama.tokenDelayMs = 2;
        prefs().edit().clear().putString("server", "127.0.0.1:" + ollama.port()).commit();
    }

    @After
    public void harnessTearDown() {
        if (ctl != null) {
            try {
                ctl.pause().stop().destroy();
            } catch (RuntimeException ignored) {
            }
        }
        Engine.reset();
        if (ollama != null) ollama.stop();
        if (bridge != null) bridge.stop();
    }

    // ------------------------------------------------------------------
    // Setup helpers
    // ------------------------------------------------------------------

    protected static SharedPreferences prefs() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("omnideck", Context.MODE_PRIVATE);
    }

    /** Starts a mock LaunchBridge and points the app at it (call before launch()). */
    protected void withBridge(boolean paired) throws Exception {
        bridge = MockBridge.start("127.0.0.1", 0);
        SharedPreferences.Editor ed = prefs().edit().putInt("bridge_port", bridge.port());
        if (paired) ed.putString("bridge_token", bridge.token);
        ed.commit();
    }

    /** theme: "cyber" | "light" | "dark" | "system". tab: MainActivity.TAB_*. */
    protected void launch(String theme, int tab) {
        prefs().edit().putString("theme", theme).putInt("last_tab", tab).commit();
        ctl = Robolectric.buildActivity(MainActivity.class).setup();
        act = ctl.get();
        idle();
    }

    protected Engine engine() {
        return Engine.get(act);
    }

    protected void waitOnline() {
        waitFor("ONLINE", () -> engine().state() == Engine.State.ONLINE && !engine().models().isEmpty());
    }

    // ------------------------------------------------------------------
    // Looper / waiting
    // ------------------------------------------------------------------

    protected static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    }

    /** Advances the main looper by {@code ms} of fake time (animations, polling). */
    protected static void advance(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    protected static void waitFor(String what, BooleanSupplier cond) {
        long end = System.currentTimeMillis() + 20000;
        while (!cond.getAsBoolean()) {
            idle();
            if (System.currentTimeMillis() > end) fail("timed out waiting for " + what);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        idle();
    }

    // ------------------------------------------------------------------
    // View queries
    // ------------------------------------------------------------------

    protected static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    protected List<View> views() {
        List<View> out = new ArrayList<>();
        collect(act.getWindow().getDecorView(), out);
        return out;
    }

    /** Visible text containing {@code text}, case-insensitive (Cyber labels are upper-case). */
    protected boolean shows(String text) {
        String t = text.toLowerCase(Locale.US);
        for (View v : views()) {
            if (v instanceof TextView && v.isShown()
                    && ((TextView) v).getText().toString().toLowerCase(Locale.US).contains(t)) return true;
        }
        return false;
    }

    protected TextView textView(String contains) {
        String t = contains.toLowerCase(Locale.US);
        for (View v : views()) {
            if (v instanceof TextView && v.isShown()
                    && ((TextView) v).getText().toString().toLowerCase(Locale.US).contains(t)) return (TextView) v;
        }
        return null;
    }

    /** First shown view with this exact content description. */
    protected View button(String description) {
        for (View v : views()) {
            CharSequence d = v.getContentDescription();
            if (v.isShown() && d != null && description.contentEquals(d)) return v;
        }
        return null;
    }

    protected void click(String description) {
        View b = button(description);
        if (b == null) fail("no shown view described as '" + description + "'");
        b.performClick();
        idle();
    }

    protected void tab(int index) {
        act.select(index, false);
        idle();
    }

    /** The chat composer (Comms tab). */
    protected EditText composer() {
        for (View v : views()) {
            if (v instanceof EditText && v.isShown() && "Message".contentEquals(
                    v.getContentDescription() == null ? "" : v.getContentDescription())) return (EditText) v;
        }
        for (View v : views()) {
            if (v instanceof EditText && v.isShown()) return (EditText) v;
        }
        throw new AssertionError("no composer");
    }

    /** Types into the Comms composer and taps Send. */
    protected void submit(String text) {
        composer().setText(text);
        idle();
        click("Send");
    }

    // ------------------------------------------------------------------
    // Screenshots
    // ------------------------------------------------------------------

    /** Draws the whole window to build/screens/{name}.png. */
    protected void shoot(String name) throws Exception {
        shoot(act.getWindow().getDecorView(), name);
    }

    protected void shootDialog(String name) throws Exception {
        Dialog d = ShadowDialog.getLatestDialog();
        if (d == null) fail("no dialog");
        shoot(d.getWindow().getDecorView(), name);
    }

    protected static void shoot(View root, String name) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()),
                Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));
        File dir = new File("build/screens");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
