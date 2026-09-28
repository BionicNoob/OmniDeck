package com.omnideck.mobile;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.omnideck.mobile.core.LanScanner;
import com.omnideck.mobile.mock.MockOllama;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

/** Renders real screenshots (Robolectric native graphics) into build/screens for a visual check. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ScreenshotTest {
    static final String REPLY = "Here's the plan:\n\n"
            + "## Steps\n"
            + "1. **Warm** the model with `/warm`\n"
            + "2. Ask anything — replies stream in live\n"
            + "- works over *your* Wi-Fi\n\n"
            + "```python\nprint(\"hello from the PC\")\n```\n"
            + "Docs: https://ollama.com";

    private MockOllama ollama;
    private ActivityController<MainActivity> ctl;
    private MainActivity act;

    @Before
    public void setUp() throws Exception {
        Engine.reset();
        Engine.testSubnets = Collections.singletonList(
                new LanScanner.Subnet("wlan0", LanScanner.parseIp("127.0.3.1"), 28));
        ollama = MockOllama.start("127.0.0.1", 0);
        ollama.tokenDelayMs = 1;
        ollama.replier = (req, text) -> text.startsWith("Summarize") || text.startsWith("/")
                ? MockOllama.defaultReply(text) : MockOllama.words(REPLY);
    }

    @After
    public void tearDown() {
        if (ctl != null) {
            try {
                ctl.pause().stop().destroy();
            } catch (RuntimeException ignored) {
            }
        }
        Engine.reset();
        ollama.stop();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    }

    private static void waitFor(String what, BooleanSupplier c) {
        long end = System.currentTimeMillis() + 20000;
        while (!c.getAsBoolean()) {
            idle();
            if (System.currentTimeMillis() > end) fail("timed out: " + what);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        idle();
    }

    private void start(String theme, String server) {
        RuntimeEnvironment.getApplication().getSharedPreferences("omnideck", Context.MODE_PRIVATE).edit()
                .putString("theme", theme).putString("server", server).commit();
        ctl = Robolectric.buildActivity(MainActivity.class).setup();
        act = ctl.get();
    }

    private Engine engine() {
        return Engine.get(act);
    }

    private EditText input() {
        return find(act.getWindow().getDecorView(), EditText.class);
    }

    private static <T> T find(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                T t = find(g.getChildAt(i), type);
                if (t != null) return t;
            }
        }
        return null;
    }

    private void send(String text) {
        input().setText(text);
        idle();
        View b = act.getWindow().getDecorView().findViewWithTag("x");
        List<View> all = new java.util.ArrayList<>();
        act.getWindow().getDecorView().findViewsWithText((java.util.ArrayList<View>) all, "Send",
                View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        all.get(0).performClick();
        idle();
    }

    private static void shoot(View root, String name) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        Bitmap bmp = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));
        File dir = new File("build/screens");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private void conversation() {
        waitFor("online", () -> engine().state() == Engine.State.ONLINE && !engine().models().isEmpty());
        send("How do I use you from my phone?");
        waitFor("reply", () -> !engine().isBusy() && engine().conversation().messages.size() >= 2);
        send("/models");
        waitFor("models", () -> engine().conversation().messages.size() >= 3);
        input().setText("/m");
        idle();
    }

    @Test
    public void cyberChat() throws Exception {
        start("cyber", "127.0.0.1:" + ollama.port());
        conversation();
        shoot(act.getWindow().getDecorView(), "cyber-chat");
    }

    @Test
    public void lightChat() throws Exception {
        start("light", "127.0.0.1:" + ollama.port());
        conversation();
        shoot(act.getWindow().getDecorView(), "light-chat");
    }

    @Test
    public void cyberEmptyAndMenu() throws Exception {
        start("cyber", "127.0.0.1:" + ollama.port());
        waitFor("online", () -> engine().state() == Engine.State.ONLINE && !engine().models().isEmpty());
        shoot(act.getWindow().getDecorView(), "cyber-empty");
        act.findViewById(android.R.id.content).getRootView();
        List<View> menu = new java.util.ArrayList<>();
        act.getWindow().getDecorView().findViewsWithText((java.util.ArrayList<View>) menu, "Menu",
                View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        menu.get(0).performClick();
        idle();
        Dialog d = ShadowDialog.getLatestDialog();
        shoot(d.getWindow().getDecorView(), "cyber-menu");
    }

    @Test
    public void cyberStreamingWithThinking() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("omnideck", Context.MODE_PRIVATE).edit()
                .putString("model", "qwen3:8b").putString("mode", "deep").commit();
        ollama.replier = (req, text) -> MockOllama.words("Sunlight scatters off air molecules. **Blue** light has a "
                + "shorter wavelength, so it scatters much more than red — that's *Rayleigh scattering*.");
        start("cyber", "127.0.0.1:" + ollama.port());
        waitFor("online", () -> engine().state() == Engine.State.ONLINE
                && Boolean.TRUE.equals(engine().supportsThinking("qwen3:8b")));
        ollama.tokenDelayMs = 120;
        send("Why is the sky blue?");
        waitFor("thinking", () -> engine().streamingMessage() != null
                && engine().streamingMessage().thinking.split(" ").length >= 5
                && engine().streamingMessage().content.isEmpty());
        shoot(act.getWindow().getDecorView(), "cyber-thinking");
        waitFor("answer streaming", () -> engine().streamingMessage() != null
                && engine().streamingMessage().content.split(" ").length >= 9);
        shoot(act.getWindow().getDecorView(), "cyber-streaming");
        waitFor("done", () -> !engine().isBusy());
    }

    @Test
    public void lightShortExchange() throws Exception {
        ollama.replier = (req, text) -> MockOllama.words("Yes — I'm running on your PC and streaming to your phone "
                + "over Wi-Fi. Type `/help` to see everything you can control from here.");
        start("light", "127.0.0.1:" + ollama.port());
        waitFor("online", () -> engine().state() == Engine.State.ONLINE && !engine().models().isEmpty());
        send("Are you there?");
        waitFor("reply", () -> !engine().isBusy() && engine().conversation().messages.size() >= 2);
        shoot(act.getWindow().getDecorView(), "light-short");
    }

    @Test
    public void offline() throws Exception {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        start("cyber", "127.0.0.1:" + port);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        shoot(act.getWindow().getDecorView(), "cyber-offline");
    }

    @Test
    public void lightSettings() throws Exception {
        start("light", "127.0.0.1:" + ollama.port());
        waitFor("online", () -> engine().state() == Engine.State.ONLINE && !engine().models().isEmpty());
        send("/settings");
        Dialog d = ShadowDialog.getLatestDialog();
        shoot(d.getWindow().getDecorView(), "light-settings");
    }
}
