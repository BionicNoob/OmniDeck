package com.omnideck.mobile;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.screens.CommandScreen;
import com.omnideck.mobile.screens.Screen;
import com.omnideck.mobile.ui.CommandKit;
import com.omnideck.mobile.ui.CoreView;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Widgets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The COMMAND tab's review-round fixes: the AI core with the phone's
 * animations off, the Active chip, nothing animating in the background,
 * TalkBack wording, HUD effects, one tile label size, honest readouts, the
 * offline focal point, speech and the PC power strip.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
// NATIVE graphics: under LEGACY, Robolectric reports the window as GONE, so no loop
// (the core's or the kit's Widgets.Animated) would ever start.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CommandFixesTest extends Harness {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private CommandScreen command() {
        Screen[] tabs = ReflectionHelpers.getField(act, "tabs");
        return (CommandScreen) tabs[MainActivity.TAB_COMMAND];
    }

    private CoreView core() {
        List<View> all = new ArrayList<>();
        collect(command().view(), all);
        for (View v : all) {
            if (v instanceof CoreView) return (CoreView) v;
        }
        throw new AssertionError("no AI core");
    }

    /** Views inside the Command page only (not the shell's top bar or nav). */
    private List<View> commandViews() {
        List<View> all = new ArrayList<>();
        collect(command().view(), all);
        return all;
    }

    private View inCommand(String description) {
        for (View v : commandViews()) {
            CharSequence d = v.getContentDescription();
            if (v.isShown() && d != null && description.contentEquals(d)) return v;
        }
        return null;
    }

    private TextView commandText(String contains) {
        for (View v : commandViews()) {
            if (v instanceof TextView && v.isShown() && ((TextView) v).getText().toString().contains(contains)) {
                return (TextView) v;
            }
        }
        return null;
    }

    private static String spoken(View v) {
        AccessibilityNodeInfo info = v.createAccessibilityNodeInfo();
        CharSequence d = info.getContentDescription();
        return d == null ? "" : d.toString();
    }

    private static boolean hasActionLabel(View v, String label) {
        for (AccessibilityNodeInfo.AccessibilityAction act : v.createAccessibilityNodeInfo().getActionList()) {
            if (act.getLabel() != null && label.contentEquals(act.getLabel())) return true;
        }
        return false;
    }

    /** Every looping instrument on the Command page that is running a frame loop right now. */
    private List<String> runningAnimators() {
        List<String> out = new ArrayList<>();
        for (View v : commandViews()) {
            if (v instanceof Widgets.Animated && ((Widgets.Animated) v).isAnimating()) {
                out.add(v.getClass().getSimpleName() + "@" + describe(v));
            }
            if (v instanceof CoreView && ((CoreView) v).isAnimating()) out.add("CoreView");
        }
        return out;
    }

    private static String describe(View v) {
        View p = v;
        for (int i = 0; i < 6 && p != null; i++) {
            if (p.getContentDescription() != null) return p.getContentDescription().toString();
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
        return "?";
    }

    /** Points the app at a closed port and launches: the AI can't be found. */
    private int launchOffline(String theme) throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        prefs().edit().putString("server", "127.0.0.1:" + port).commit();
        launch(theme, MainActivity.TAB_COMMAND);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        advance(1500);
        return port;
    }

    // ------------------------------------------------------------------
    // command#0 — the core with the phone's animations off
    // ------------------------------------------------------------------

    /** A reply, then a dropped link: the drawing shows each state the moment it happens. */
    private void replyThenDropTheLink(CoreView c) throws Exception {
        ollama.firstTokenDelayMs = 1200;
        ollama.tokenDelayMs = 60;
        engine().send("Status report, please.");
        waitFor("thinking", () -> engine().isBusy());
        advance(200);
        assertEquals(CoreView.THINKING, c.mode());
        assertEquals(CoreView.THINKING, c.shownMode());
        waitFor("streaming", () -> {
            ChatMessage m = engine().streamingMessage();
            return m != null && m.content.length() > 8;
        });
        advance(120);
        assertEquals(CoreView.STREAMING, c.mode());
        assertEquals("pulses never stick without frames to decay them", CoreView.STREAMING, c.shownMode());
        waitFor("reply done", () -> !engine().isBusy());
        advance(200);
        assertEquals(CoreView.IDLE, c.shownMode());

        ollama.stop();
        waitFor("link lost", () -> engine().state() != Engine.State.ONLINE);
        idle();
        assertEquals(c.mode(), c.shownMode());
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        idle();
        assertEquals(CoreView.OFFLINE, c.mode());
        assertEquals("the dark offline look at once", CoreView.OFFLINE, c.shownMode());
        assertFalse(c.isAnimating());
    }

    /** The phone's animations are off from the start (Accessibility › Remove animations): never a loop. */
    @Test
    public void coreStaysStaticWhenThePhonesAnimationsAreOff() throws Exception {
        ValueAnimator.setDurationScale(0f);
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(600);
        CoreView c = core();
        assertFalse("no frame loop while the phone's animations are off", c.isAnimating());
        assertEquals(CoreView.IDLE, c.shownMode());
        replyThenDropTheLink(c);
    }

    /**
     * The reported case: animations go off while the loop runs, so the loop
     * ends on a frame (and a restart ends at once). From then on the core must
     * take the static path: every later state shows immediately and pulses
     * don't pile up.
     */
    @Test
    public void coreFallsBackToStaticWhenAnimationsGoOffMidRun() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(600);
        CoreView c = core();
        assertTrue(c.isAnimating());
        ValueAnimator.setDurationScale(0f);
        advance(800);
        assertFalse("the loop ended and stays off", c.isAnimating());
        assertEquals(CoreView.IDLE, c.shownMode());
        replyThenDropTheLink(c);
    }

    /** Before API 26 there's no areAnimatorsEnabled(): the setting itself decides. */
    @Test
    @Config(sdk = 23)
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void olderPhonesReadTheAnimatorSetting() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        android.content.ContentResolver cr = act.getContentResolver();
        assertTrue(CoreView.systemAnimationsOn(act));
        android.provider.Settings.Global.putFloat(cr, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        assertFalse(CoreView.systemAnimationsOn(act));
        android.provider.Settings.Global.putFloat(cr, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        assertTrue(CoreView.systemAnimationsOn(act));
    }

    /** Newer phones ask ValueAnimator (which also covers battery saver). */
    @Test
    public void newerPhonesAskValueAnimator() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        assertTrue(CoreView.systemAnimationsOn(act));
        ValueAnimator.setDurationScale(0f);
        assertFalse(CoreView.systemAnimationsOn(act));
    }

    /** With animations on, the core runs its loop and eases into each look. */
    @Test
    public void coreAnimatesAndSettlesWhenAnimationsAreOn() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(2500);
        CoreView c = core();
        assertTrue(c.isAnimating());
        assertEquals(CoreView.IDLE, c.shownMode());
    }

    // ------------------------------------------------------------------
    // command#1 — the Active chip follows the model switch
    // ------------------------------------------------------------------

    /** The row that carries the "Active" chip in the Loaded models card, by model name. */
    private String activeRow() {
        for (View v : commandViews()) {
            if (v instanceof TextView && v.isShown() && "active".equalsIgnoreCase(((TextView) v).getText().toString())) {
                ViewGroup row = (ViewGroup) v.getParent();
                return ((TextView) row.getChildAt(0)).getText().toString();
            }
        }
        return null;
    }

    @Test
    public void activeChipFollowsTheModelSwitch() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        final boolean[] done = new boolean[2];
        engine().setLoaded("llama3.2:3b", true, (v, err) -> done[0] = true);
        engine().setLoaded("qwen3:8b", true, (v, err) -> done[1] = true);
        waitFor("both loaded", () -> done[0] && done[1] && engine().isLoaded("llama3.2:3b")
                && engine().isLoaded("qwen3:8b"));
        advance(300);
        String first = engine().currentModel();
        assertEquals(first, activeRow());

        String other = first.equals("llama3.2:3b") ? "qwen3:8b" : "llama3.2:3b";
        // Switch through the chip's picker, as a user would.
        click("Switch model");
        AlertDialog pick = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(pick);
        List<View> rows = new ArrayList<>();
        collect(pick.getWindow().getDecorView(), rows);
        View target = null;
        for (View v : rows) {
            if (v instanceof TextView && other.equals(((TextView) v).getText().toString())) {
                target = (View) v.getParent();
            }
        }
        assertNotNull("picker row for " + other, target);
        target.performClick();
        idle();
        assertEquals(other, engine().currentModel());
        assertEquals("the Active chip moved with the switch", other, activeRow());

        // Back again, and after a tab round trip it still holds.
        engine().setModel(first);
        idle();
        assertEquals(first, activeRow());
        tab(MainActivity.TAB_MODELS);
        tab(MainActivity.TAB_COMMAND);
        assertEquals(first, activeRow());
    }

    // ------------------------------------------------------------------
    // command#2 — nothing animates in the background
    // ------------------------------------------------------------------

    @Test
    public void nothingAnimatesWhileTheAppIsInTheBackground() throws Exception {
        withBridge(true);
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        waitFor("PC vitals", () -> engine().lastVitals() != null);
        advance(1500);
        CommandScreen cs = command();
        assertTrue(cs.isLive());
        assertFalse("live cards breathe while shown", runningAnimators().isEmpty());

        ctl.pause().stop();
        idle();
        assertFalse(cs.isLive());
        assertEquals("parked on stop", new ArrayList<String>(), runningAnimators());

        // Engine events keep arriving in the background: a reply streams and finishes,
        // the phone reads it aloud, a rescan runs. None of them may start a loop.
        ollama.tokenDelayMs = 40;
        engine().send("A question asked just before the phone locked.");
        waitFor("busy", () -> engine().isBusy());
        idle();
        assertEquals(new ArrayList<String>(), runningAnimators());
        waitFor("reply", () -> !engine().isBusy());
        cs.onSpeechChanged(true);
        cs.onBusyChanged();
        idle();
        assertEquals(new ArrayList<String>(), runningAnimators());
        engine().discover(true);
        idle();
        assertEquals(Engine.State.SEARCHING, engine().state());
        assertEquals(new ArrayList<String>(), runningAnimators());
        waitFor("rescan done", () -> engine().state() == Engine.State.ONLINE);
        advance(3000);
        assertEquals(new ArrayList<String>(), runningAnimators());

        // Back in the foreground everything resumes.
        ctl.start().resume();
        idle();
        assertTrue(cs.isLive());
        assertTrue(core().isAnimating());
    }

    @Test
    public void leavingTheTabParksEverything() throws Exception {
        withBridge(true);
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        waitFor("PC vitals", () -> engine().lastVitals() != null);
        advance(1500);
        tab(MainActivity.TAB_PC);
        assertFalse(command().isLive());
        assertEquals(new ArrayList<String>(), runningAnimators());
        tab(MainActivity.TAB_COMMAND);
        assertTrue(command().isLive());
        assertTrue(core().isAnimating());
    }

    // ------------------------------------------------------------------
    // command#3 — TalkBack hears the state
    // ------------------------------------------------------------------

    @Test
    public void talkBackHearsChipsTilesAndVitals() throws Exception {
        withBridge(true);
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        waitFor("PC vitals", () -> engine().lastVitals() != null);
        advance(1500);

        View model = inCommand("Switch model");
        assertEquals("Model llama3.2:3b, not loaded", spoken(model));
        assertTrue(hasActionLabel(model, "Switch model"));
        View mode = inCommand("Change mode");
        assertEquals("Mode Auto", spoken(mode));
        assertTrue(hasActionLabel(mode, "Change mode"));
        click("Change mode");
        assertEquals("Mode Fast", spoken(inCommand("Change mode")));

        View ra = inCommand("Read aloud");
        AccessibilityNodeInfo n = ra.createAccessibilityNodeInfo();
        assertTrue("a toggle for TalkBack", n.isCheckable());
        assertFalse(n.isChecked());
        assertEquals("android.widget.ToggleButton", n.getClassName().toString());
        click("Read aloud");
        assertTrue(inCommand("Read aloud").createAccessibilityNodeInfo().isChecked());
        click("Read aloud");
        assertFalse(inCommand("Read aloud").createAccessibilityNodeInfo().isChecked());

        View pc = inCommand("PC vitals");
        assertEquals("PC vitals, CPU 12%, RAM 8.1 of 16 GB, disk 210 GB free", spoken(pc));
        assertTrue(hasActionLabel(pc, "Open PC"));

        View coreV = inCommand("AI core");
        assertEquals("AI core, idle", spoken(coreV));
        assertTrue(hasActionLabel(coreV, "Open Comms"));
        assertTrue(hasActionLabel(coreV, "Talk to OMNI"));
    }

    @Test
    public void unavailableTilesSayWhy() throws Exception {
        launchOffline("dark");
        assertEquals("Warm model, needs your AI online", spoken(inCommand("Warm model")));
        assertEquals("tiles that always work just say their name", "Talk", spoken(inCommand("Talk")));
    }

    // ------------------------------------------------------------------
    // command#4 — HUD effects reach the core without a restart
    // ------------------------------------------------------------------

    @Test
    public void hudEffectsToggleReachesTheCore() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        assertTrue(core().hudGlow());
        act.openSettings();
        idle();
        click("HUD effects");
        assertFalse(engine().settings.hudEffects());
        act.closeSettings();
        idle();
        assertFalse("no bloom once HUD effects are off", core().hudGlow());
        act.openSettings();
        idle();
        click("HUD effects");
        act.closeSettings();
        idle();
        assertTrue(core().hudGlow());
    }

    // ------------------------------------------------------------------
    // V3 — one label size for the whole grid
    // ------------------------------------------------------------------

    private static final String[] TILES = {"Talk", "New chat", "Summarize chat", "Read aloud", "Warm model",
            "Unload model", "Benchmark", "Open Models", "Scan network", "Open PC", "Chat history", "Diagnostics"};

    private void assertOneLabelSize() {
        float size = -1;
        for (String d : TILES) {
            LinearLayout tile = (LinearLayout) inCommand(d);
            assertNotNull(d, tile);
            TextView label = (TextView) tile.getChildAt(1);
            if (size < 0) size = label.getTextSize();
            assertEquals(d + " shares the grid's label size", size, label.getTextSize(), 0.01f);
            assertTrue(d + " is never cut", label.getLayout() == null || label.getLayout().getEllipsisCount(0) == 0);
            float text = label.getPaint().measureText(label.getText().toString());
            assertTrue(d + " fits its tile", text <= label.getWidth() + 0.5f);
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h760dp-xhdpi")
    public void quickActionLabelsShareOneSizeOnANarrowPhone() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        assertOneLabelSize();
    }

    @Test
    public void quickActionLabelsShareOneSize() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        assertOneLabelSize();
    }

    // ------------------------------------------------------------------
    // Honest readouts (V6/V49, V9, V27)
    // ------------------------------------------------------------------

    /** Free space without the disk's size has no scale: a dashed track, never an empty bar. */
    @Test
    public void diskWithoutATotalShowsADashedTrack() throws Exception {
        withBridge(true);
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        waitFor("PC vitals", () -> engine().lastVitals() != null);
        advance(600);
        assertTrue(engine().lastVitals().diskPercent < 0);
        int dashes = 0;
        for (View v : commandViews()) {
            if (v instanceof CommandKit.DashLine && v.isShown()) {
                dashes++;
                ViewGroup track = (ViewGroup) v.getParent();
                assertEquals("the meter steps aside", View.INVISIBLE, track.getChildAt(0).getVisibility());
            }
        }
        assertEquals("only the disk row", 1, dashes);
        assertNotNull(commandText("210 GB free"));
    }

    @Test
    public void diskWithATotalShowsItsMeter() throws Exception {
        withBridge(true);
        bridge.rich = true;
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        waitFor("PC vitals", () -> engine().lastVitals() != null);
        advance(600);
        assertEquals(56, Math.round(engine().lastVitals().diskPercent));
        for (View v : commandViews()) {
            assertFalse("no dashed track once the scale is known",
                    v instanceof CommandKit.DashLine && v.isShown());
        }
        // Rich vitals name the machine and its power in the foot.
        assertNotNull(commandText("ATLAS-PC · Charging"));
    }

    @Test
    public void linkReadoutIsAmberWhileProbingAndDimWhenDown() throws Exception {
        int port = launchOffline("light");
        Theme t = act.theme();
        TextView link = commandText("down");
        assertNotNull(link);
        assertEquals("no alarm red in the corner readout", t.dim, link.getCurrentTextColor());
        TextView uptime = null;
        for (View v : commandViews()) {
            if (v instanceof TextView && v.isShown() && "Uptime".equalsIgnoreCase(((TextView) v).getText().toString())) {
                ViewGroup box = (ViewGroup) v.getParent();
                uptime = (TextView) box.getChildAt(1);
            }
        }
        assertNotNull(uptime);
        assertEquals("link uptime reads — while the link is down", "—", uptime.getText().toString());

        try (ServerSocket blackHole = new ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            click("Scan again");
            idle();
            assertEquals(Engine.State.SEARCHING, engine().state());
            TextView probing = commandText("probing");
            assertNotNull(probing);
            assertEquals(t.warn, probing.getCurrentTextColor());
            waitFor("scan gives up", () -> engine().state() == Engine.State.OFFLINE);
        }
    }

    @Test
    public void uptimeIsTheLinksAndSessionKeepsItsOwnClock() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(2200);
        TextView uptime = null;
        for (View v : commandViews()) {
            if (v instanceof TextView && v.isShown() && "Uptime".equalsIgnoreCase(((TextView) v).getText().toString())) {
                uptime = (TextView) ((ViewGroup) v.getParent()).getChildAt(1);
            }
        }
        assertNotNull(uptime);
        long link = engine().linkUptimeMs() / 1000;
        assertTrue(link >= 0);
        String[] hms = uptime.getText().toString().split(":");
        assertEquals(uptime.getText().toString(), 3, hms.length);
        long shown = Long.parseLong(hms[0]) * 3600 + Long.parseLong(hms[1]) * 60 + Long.parseLong(hms[2]);
        assertTrue("the core's UPTIME is the link's (" + shown + " vs " + link + ")", Math.abs(shown - link) <= 1);
        TextView elapsed = null;
        for (View v : commandViews()) {
            if (v instanceof TextView && v.isShown() && "Elapsed".equalsIgnoreCase(((TextView) v).getText().toString())) {
                elapsed = (TextView) ((ViewGroup) v.getParent()).getChildAt(0);
            }
        }
        assertNotNull(elapsed);
        assertTrue("session time in the same 00:00:00 format",
                elapsed.getText().toString().matches("\\d{2}:\\d{2}:\\d{2}"));
    }

    // ------------------------------------------------------------------
    // Offline: one focal point (V10, V26, V27, V28)
    // ------------------------------------------------------------------

    @Test
    public void offlineSaysEachThingOnce() throws Exception {
        int port = launchOffline("light");
        // The diagnosis isn't printed twice: the core says what was searched and when.
        int copies = 0;
        for (View v : commandViews()) {
            if (v instanceof TextView && v.isShown()
                    && ((TextView) v).getText().toString().contains("No AI answered")) copies++;
        }
        assertEquals(0, copies);
        assertNotNull(commandText("Port " + port + " · 127.0.3.1/28 · probed"));
        // The typed address refused: the card says exactly that, once, instead of the generic checklist.
        assertNotNull(commandText("refused port " + port));
        assertNull(commandText("Check these three things"));
        // An address that accepts but never answers: the checklist, naming the port that was scanned.
        try (ServerSocket blackHole = new ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            engine().discover(true);
            waitFor("scan gives up", () -> engine().state() == Engine.State.OFFLINE);
        }
        advance(600);
        assertNotNull(commandText("Then check these three things and scan again:"));
        assertNotNull("step 03 names the scanned port", commandText("Allow port " + port + " through"));
        assertNull("11434 only when that's the port", commandText("Allow port 11434"));
        // The core goes dark: its annunciator is dim, not a second red alarm.
        assertEquals(act.theme().dim, commandText("OFFLINE").getCurrentTextColor());
        assertEquals(CoreView.OFFLINE, core().shownMode());
    }

    // ------------------------------------------------------------------
    // Speech (onSpeechChanged)
    // ------------------------------------------------------------------

    @Test
    public void speakingShowsOnTheCoreWithAStop() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        assertNull(inCommand("Stop speaking"));
        act.speakingOverride = true; // stands in for the TTS engine
        advance(800);
        assertEquals(CoreView.SPEAKING, core().mode());
        assertNotNull(commandText("Speaking the reply aloud"));
        View stop = inCommand("Stop speaking");
        assertNotNull(stop);
        stop.performClick();
        idle();
        assertFalse(act.isSpeaking());
        assertEquals(CoreView.IDLE, core().mode());
        assertNull(inCommand("Stop speaking"));
    }

    // ------------------------------------------------------------------
    // Headline (V51)
    // ------------------------------------------------------------------

    @Test
    public void headlineSaysWhatTheAiIsDoing() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        assertNotNull(commandText("Standing by"));
        assertEquals("the server, not the address again", "Ollama 0.12.6", commandText("Ollama 0.12").getText().toString());

        // Deep mode routes to the deep model: the headline says so while it works.
        engine().setDeepModel("qwen3:8b");
        engine().setMode(Settings.MODE_DEEP);
        ollama.firstTokenDelayMs = 1500;
        engine().send("Prove that there are infinitely many primes.");
        waitFor("busy", () -> engine().isBusy());
        advance(200);
        assertEquals("qwen3:8b", engine().streamingMessage().model);
        assertNotNull("before any text: preparing, on the deep model", commandText("Preparing a reply · deep model"));
        engine().stop();
        waitFor("stopped", () -> !engine().isBusy());
    }

    /** A benchmark (or compaction) is real work on the model: the core shows it too. */
    @Test
    public void aBenchmarkShowsOnTheCore() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        ollama.tokenDelayMs = 150; // keep the benchmark running long enough to look at
        click("Benchmark");
        waitFor("working", () -> engine().isWorking());
        idle();
        assertFalse(engine().isBusy());
        assertEquals(CoreView.THINKING, core().mode());
        assertNotNull(commandText("RUNNING A MODEL TASK"));
        assertNotNull(commandText("WORKING"));
        waitFor("done", () -> !engine().isWorking());
        advance(300);
        assertEquals(CoreView.IDLE, core().mode());
    }

    // ------------------------------------------------------------------
    // Quick actions: the PC power strip
    // ------------------------------------------------------------------

    @Test
    public void pcPowerStripOnlyWhenAPcIsSetUp() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        assertNull("no PC set up: no power strip", inCommand("Wake PC"));
        assertNull(inCommand("Lock PC"));
        engine().settings.setPcMac("3c:7c:3f:12:ab:cd");
        advance(1100);
        assertNotNull(inCommand("Wake PC"));
        View lock = inCommand("Lock PC");
        assertNotNull("both tiles keep the row even", lock);
        assertEquals("Lock PC, needs the PC bridge paired", spoken(lock));
        lock.performClick();
        idle();
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Pair the PC bridge first"));
    }

    @Test
    public void lockAsksThenLocksThroughTheBridge() throws Exception {
        withBridge(true);
        bridge.rich = true;
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        click("Lock PC");
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(d);
        assertTrue(d.isShowing());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("locked", () -> bridge.ranTools.contains("lock_screen"));
        waitFor("result toast", () -> "Workstation locked".equals(ShadowToast.getTextOfLatestToast()));
        assertTrue(shows("PC locked"));
    }

    @Test
    public void wakeSendsTheMagicPacket() throws Exception {
        withBridge(true);
        prefs().edit().putString("pc_mac", "3c:7c:3f:12:ab:cd").commit();
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(300);
        try (java.net.DatagramSocket listener = new java.net.DatagramSocket(0)) {
            Engine.testWolPort = listener.getLocalPort();
            click("Wake PC");
            waitFor("sent", () -> String.valueOf(ShadowToast.getTextOfLatestToast())
                    .startsWith("Wake-up packet sent to 3C:7C:3F:12:AB:CD."));
        } finally {
            Engine.testWolPort = 0;
        }
        assertTrue("logged on the dashboard", shows("Wake-on-LAN sent"));
    }

    // ------------------------------------------------------------------
    // Kit: the phone's clock
    // ------------------------------------------------------------------

    @Test
    public void logAndSessionFollowThePhonesClock() throws Exception {
        android.content.ContentResolver cr = org.robolectric.RuntimeEnvironment.getApplication().getContentResolver();
        android.provider.Settings.System.putString(cr, android.provider.Settings.System.TIME_12_24, "24");
        try {
            launch("dark", MainActivity.TAB_COMMAND);
            waitOnline();
            advance(300);
            boolean found = false;
            for (View v : commandViews()) {
                // Log times are 11sp mono; the bigger 00:00:00 readouts are session and link time.
                if (v instanceof TextView && v.isShown()
                        && ((TextView) v).getText().toString().matches("\\d{2}:\\d{2}:\\d{2}")
                        && ((TextView) v).getTextSize() < 25) found = true;
            }
            assertTrue("24-hour log times", found);
            assertNotNull(commandText("since "));
            assertTrue(commandText("since ").getText().toString().matches("since \\d{2}:\\d{2}"));
        } finally {
            android.provider.Settings.System.putString(cr, android.provider.Settings.System.TIME_12_24, null);
        }
    }
}
