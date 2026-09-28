package com.omnideck.mobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.mock.MockBridge;
import com.omnideck.mobile.ui.PcKit;
import com.omnideck.mobile.ui.PcSlider;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The PC tab against a mock LaunchBridge: unreachable / unpaired / paired
 * states, vitals, volume, screen capture, clipboard, the app launcher and the
 * tool runner. Screenshots → build/screens/pc-{theme}-{state}.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PcScreenTest extends Harness {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A mock bridge that answers like a real PC (catalog, host/OS/battery, rendered screenshot). */
    private void richBridge(boolean paired) throws Exception {
        withBridge(paired);
        bridge.rich = true;
    }

    /**
     * Launches on Models (a tab that doesn't read PC vitals, unlike Command),
     * waits for the AI link, then opens the PC tab.
     */
    private void openPc(String theme) {
        launch(theme, MainActivity.TAB_MODELS);
        waitOnline();
        tab(MainActivity.TAB_PC);
    }

    private void waitPaired() {
        waitFor("live vitals", () -> shows("ATLAS-PC") && shows("Live ·") && shows("8.1 / 16 GB"));
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private <T extends View> T find(Class<T> type) {
        for (View v : views()) {
            if (type.isInstance(v) && v.isShown()) return type.cast(v);
        }
        return null;
    }

    private ScrollView pcScroll() {
        ScrollView sv = find(ScrollView.class);
        assertNotNull("PC scroller", sv);
        return sv;
    }

    /** Scrolls the PC tab so the view showing {@code text} sits near the top. */
    private void scrollTo(String text, int marginDp) {
        TextView v = textView(text);
        assertNotNull("no '" + text + "'", v);
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
    private void shootFull(String name) throws Exception {
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

    private void tap(View v, float x) {
        float y = v.getHeight() / 2f;
        long t0 = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_MOVE, x, y, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 80, MotionEvent.ACTION_UP, x, y, 0));
        idle();
    }

    /** The view itself if it's a TextView, else the first TextView inside it (icon + label buttons). */
    private static TextView labelOf(View v) {
        List<View> all = new ArrayList<>();
        collect(v, all);
        for (View x : all) {
            if (x instanceof TextView) return (TextView) x;
        }
        throw new AssertionError("no label in " + v);
    }

    private static List<View> dialogViews(Dialog d) {
        List<View> out = new ArrayList<>();
        collect(d.getWindow().getDecorView(), out);
        return out;
    }

    private static boolean dialogShows(Dialog d, String text) {
        String t = text.toLowerCase(Locale.US);
        for (View v : dialogViews(d)) {
            if (v instanceof TextView && ((TextView) v).getText().toString().toLowerCase(Locale.US).contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static EditText dialogField(Dialog d) {
        for (View v : dialogViews(d)) {
            if (v instanceof EditText) return (EditText) v;
        }
        throw new AssertionError("no field in dialog");
    }

    private static AlertDialog latestAlert() {
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    private int systemInfoCalls() {
        int n = 0;
        synchronized (bridge.ranTools) {
            for (String s : bridge.ranTools) {
                if ("get_system_info".equals(s)) n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Link states
    // ------------------------------------------------------------------

    @Test
    public void unreachableBridgeShowsGuidance() throws Exception {
        int port = closedPort();
        prefs().edit().putInt("bridge_port", port).commit();
        openPc("cyber");
        waitFor("guidance", () -> shows("Bridge unreachable"));
        assertTrue(shows("same Wi-Fi"));
        assertTrue(shows("127.0.0.1:" + port));
        assertTrue(shows("Offline"));
        assertTrue(shows("Auto-retry in"));
        advance(3200); // let the shell's "link established" banner fade
        shoot("pc-cyber-unreachable");

        click("Retry");
        waitFor("still unreachable", () -> shows("Bridge unreachable") && shows("Auto-retry in"));
        click("Bridge settings");
        assertTrue(act.settingsOpen());
    }

    @Test
    public void noAiLinkExplainsWhatToDoFirst() throws Exception {
        prefs().edit().putString("server", "").putString("last_host", "").commit();
        launch("cyber", MainActivity.TAB_PC);
        // No AI link and no bridge address: the card offers both ways forward.
        waitFor("no-address card", () -> shows("No PC address") && shows("same PC as your AI"));
        assertNotNull(button("Enter PC address"));
        advance(3200);
        shoot("pc-cyber-nolink");
        click("Find AI");
        assertNotNull(latestAlert());
        assertTrue(latestAlert().isShowing());
    }

    @Test
    public void slowBridgeShowsTheLinkingState() throws Exception {
        richBridge(true);
        bridge.delayMs = 2500;
        openPc("cyber");
        advance(300);
        assertTrue(shows("Linking"));
        assertTrue(shows("Checking"));
        assertFalse(shows("Vitals"));
        shoot("pc-cyber-linking");
        bridge.delayMs = 0;
        waitPaired();
        assertFalse(shows("Linking"));
    }

    @Test
    public void vitalsErrorsAreExplainedAndTheRestKeepsWorking() throws Exception {
        richBridge(true);
        bridge.failing.add("get_system_info");
        openPc("dark");
        waitFor("vitals error", () -> shows("Couldn't read vitals") && shows("access denied by the PC"));
        // No vitals yet: the cap says Error, a remedy and Retry replace the empty instrument grid.
        assertTrue(shows("Error"));
        assertTrue(shows("Allow system info in LaunchBridge"));
        assertNotNull(button("Retry vitals"));
        assertFalse(shows("Processor load"));
        waitFor("launcher still works", () -> button("Open Spotify") != null);
        advance(3200);
        shoot("pc-dark-vitals-error");
        bridge.failing.clear();
        waitFor("recovers on the next poll", () -> {
            advance(1000);
            return shows("8.1 / 16 GB") && !shows("Couldn't read vitals");
        });
    }

    @Test
    public void pairingFromTheUnpairedStateStoresTheToken() throws Exception {
        richBridge(false);
        openPc("cyber");
        waitFor("pair card", () -> shows("Pair this phone") && shows("Not paired"));
        assertTrue(shows("v2.1"));
        assertTrue(shows("42"));
        advance(400);
        shoot("pc-cyber-unpaired");
        assertEquals("", engine().settings.bridgeToken());

        click("Pair with PC");
        waitFor("token stored", () -> bridge.token.equals(engine().settings.bridgeToken()));
        waitPaired();
        assertTrue(shows("Paired"));
        assertFalse(shows("Pair this phone"));
    }

    @Test
    public void rejectedTokenAsksToPairAgain() throws Exception {
        richBridge(true);
        bridge.token = "rotated-on-the-pc";
        openPc("dark");
        waitFor("re-pair card", () -> shows("no longer accepts") && shows("Re-pair"));
        click("Pair again");
        waitFor("paired again", () -> "rotated-on-the-pc".equals(engine().settings.bridgeToken()));
        waitPaired();
    }

    // ------------------------------------------------------------------
    // Paired: vitals in every theme
    // ------------------------------------------------------------------

    private void paired(String theme) throws Exception {
        richBridge(true);
        openPc(theme);
        waitPaired();
        assertTrue(shows("Windows 11 Pro 23H2"));
        assertTrue(shows("210 of 476 GB free"));
        assertTrue(shows("Battery"));
        assertTrue(shows("78%"));
        assertTrue(shows("Charging"));
        assertTrue(shows("3d 4h"));
        assertTrue(shows("23%"));
        // A second poll 5 s later extends the CPU trace.
        waitFor("second sample", () -> {
            advance(1000);
            return shows("31%");
        });
        advance(800);
        pcScroll().scrollTo(0, 0);
        idle();
        shoot("pc-" + theme + "-paired");
        shootFull("pc-" + theme + "-full");
    }

    @Test
    public void pairedCyber() throws Exception {
        paired("cyber");
    }

    @Test
    public void pairedLight() throws Exception {
        paired("light");
    }

    @Test
    public void pairedDark() throws Exception {
        paired("dark");
    }

    @Test
    public void vitalsPollOnlyWhileTheTabIsShown() throws Exception {
        richBridge(true);
        openPc("cyber");
        waitPaired();
        // Models doesn't read vitals (Command does, while it's shown).
        tab(MainActivity.TAB_MODELS);
        advance(200);
        int before = systemInfoCalls();
        for (int i = 0; i < 20; i++) advance(1000);
        assertEquals("no polling while hidden", before, systemInfoCalls());
        tab(MainActivity.TAB_PC);
        waitFor("polling resumes", () -> systemInfoCalls() > before);
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    public void fitsA360dpPhone() throws Exception {
        richBridge(true);
        openPc("cyber");
        waitPaired();
        click("Capture screen");
        click("Get PC clipboard");
        waitFor("capture + clipboard", () -> button("Ask AI") != null && button("Send to chat") != null);
        advance(600);
        String[] actions = {"Ask AI", "Save", "Share", "Copy", "Send to chat", "Capture screen", "Get PC clipboard"};
        for (String name : actions) {
            View v = button(name);
            assertNotNull(name, v);
            TextView b = labelOf(v);
            assertTrue(name + " fits", b.getLayout() != null && b.getLayout().getEllipsisCount(0) == 0);
        }
        String[] labels = {"Bridge", "Latency", "Version", "Apps", "Memory", "Disk", "Battery", "Uptime"};
        for (String l : labels) {
            TextView v = textView(l);
            assertNotNull(l, v);
            assertTrue(l + " fits", v.getLayout() != null && v.getLayout().getEllipsisCount(0) == 0);
        }
        shootFull("pc-cyber-360-full");
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    @Test
    public void volumeSliderAndPresetsSetThePcVolume() throws Exception {
        richBridge(true);
        openPc("dark");
        waitPaired();
        waitFor("volume read", () -> find(PcSlider.class) != null && find(PcSlider.class).value() == 35);
        PcSlider s = find(PcSlider.class);
        assertTrue(s.isEnabled());

        tap(s, s.positionOf(70));
        waitFor("set_volume 70", () -> bridge.volume == 70);
        assertEquals(70, s.value());

        // Mute right away, while set_volume 70 may still be in flight: Unmute must bring back 70.
        click("Mute");
        waitFor("muted", () -> bridge.volume == 0 && button("Unmute") != null);
        click("Unmute");
        waitFor("restored", () -> bridge.volume == 70);
        click("Max");
        waitFor("max", () -> bridge.volume == 100);
        assertTrue(shows("100%"));
    }

    @Test
    public void screenCapturePreviewsAndExpands() throws Exception {
        richBridge(true);
        openPc("cyber");
        waitPaired();
        assertTrue(shows("No capture yet"));
        click("Capture screen");
        waitFor("preview", () -> {
            ImageView iv = (ImageView) button("PC screenshot — tap to expand");
            return iv != null && iv.getDrawable() instanceof BitmapDrawable;
        });
        assertTrue(shows("480 × 270"));
        assertTrue(bridge.ranTools.contains("screenshot"));
        assertNotNull(button("Ask AI"));
        assertNotNull(button("Save"));
        advance(300);
        scrollTo("Controls", 14);
        shoot("pc-cyber-screenshot");

        click("PC screenshot — tap to expand");
        Dialog full = ShadowDialog.getLatestDialog();
        assertNotNull(full);
        assertTrue(full.isShowing());
        View img = null;
        for (View v : dialogViews(full)) {
            if ("PC screenshot, tap to close".contentEquals(v.getContentDescription() == null ? ""
                    : v.getContentDescription())) img = v;
        }
        assertNotNull(img);
        img.performClick();
        idle();
        assertFalse(full.isShowing());

        // The mock's models can't see images: Ask AI says so instead of sending a blind request.
        engine().fetchDetails(engine().currentModel(), (d, err) -> { });
        waitFor("model details", () -> engine().supportsVision(engine().currentModel()) != null);
        click("Ask AI");
        assertTrue(ShadowToast.getTextOfLatestToast().contains("can't see images"));
        assertEquals(MainActivity.TAB_PC, act.currentTab());
        assertTrue(engine().conversation().messages.isEmpty());
    }

    @Test
    public void clipboardIsShownAndCanGoToChat() throws Exception {
        richBridge(true);
        openPc("light");
        waitPaired();
        click("Get PC clipboard");
        waitFor("clipboard text", () -> shows("ssh omni@atlas-pc -p 2222"));
        assertTrue(shows("2 lines"));
        advance(300);
        scrollTo("PC clipboard", 60);
        shoot("pc-light-clipboard");
        click("Send to chat");
        advance(300);
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        assertTrue(composer().getText().toString().contains("tail -f launchbridge.log"));
    }

    @Test
    public void phoneClipboardCanBePushedWhenTheBridgeOffersIt() throws Exception {
        richBridge(true);
        openPc("cyber");
        waitPaired();
        waitFor("push offered", () -> pushButton() != null);
        act.copy("test", "from the phone");
        pushButton().performClick();
        waitFor("pushed", () -> "from the phone".equals(bridge.clipboard));
    }

    /** The "Send phone clipboard" button (its description names where it goes). */
    private TextView pushButton() {
        return (TextView) button("Send phone clipboard to PC");
    }

    // ------------------------------------------------------------------
    // Launcher
    // ------------------------------------------------------------------

    @Test
    public void launcherSearchesAndOpensAfterConfirming() throws Exception {
        richBridge(true);
        openPc("light");
        waitPaired();
        waitFor("first apps", () -> button("Open Spotify") != null);
        // 9 listed, 6 shown: the footer offers the other 3 (never "Show all" under "42 indexed").
        assertTrue(shows("Show 3 more"));
        EditText field = (EditText) button("Search apps");
        assertNotNull(field);
        field.setText("code");
        advance(400);
        waitFor("filtered", () -> button("Open Visual Studio Code") != null && button("Open Spotify") == null);
        assertTrue(shows("1 match"));
        advance(300);
        scrollTo("Launcher", 14);
        shoot("pc-light-launcher");

        click("Open Visual Studio Code");
        AlertDialog confirm = latestAlert();
        assertNotNull(confirm);
        assertTrue(dialogShows(confirm, "Open Visual Studio Code on the PC?"));
        assertTrue(bridge.launched.isEmpty());
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("launched", () -> bridge.launched.contains("Visual Studio Code"));
        waitFor("recent chip", () -> button("Open Visual Studio Code again") != null);

        field.setText("zzz-nothing");
        advance(400);
        waitFor("no matches", () -> shows("No app on the PC matches"));
    }

    // ------------------------------------------------------------------
    // Tool runner
    // ------------------------------------------------------------------

    @Test
    public void toolRunnerListsToolsValidatesJsonAndShowsResults() throws Exception {
        richBridge(true);
        openPc("dark");
        waitPaired();
        assertNull(button("Run get_system_info"));
        click("Tool runner");
        waitFor("tools", () -> button("Run list_processes") != null);
        String[] expected = {"get_system_info", "get_volume", "set_volume", "screenshot", "get_clipboard",
                "set_clipboard", "lock_screen", "list_processes"};
        for (String t : expected) assertNotNull(t, button("Run " + t));
        advance(400);
        scrollTo("Tool runner", 14);
        shoot("pc-dark-tools");

        click("Run list_processes");
        AlertDialog args = latestAlert();
        args.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("result", () -> latestAlert() != args && latestAlert().isShowing()
                && dialogShows(latestAlert(), "ollama.exe"));
        assertTrue(dialogShows(latestAlert(), "OK"));
        shootDialog("pc-dark-tool-result");
        latestAlert().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();

        // The runner builds a form; raw JSON is one tap away ("Edit as JSON") and still validated.
        click("Run set_volume");
        AlertDialog vol = latestAlert();
        dialogClick(vol, "Edit as JSON");
        EditText json = dialogFieldDescribed(vol, "Tool arguments");
        json.setText("{level: ");
        vol.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue("invalid JSON keeps the dialog open", vol.isShowing());
        assertTrue(dialogShows(vol, "isn't a JSON object"));
        json.setText("{\"level\": 20}");
        vol.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("volume 20", () -> bridge.volume == 20);
    }

    private static EditText dialogFieldDescribed(Dialog d, String description) {
        for (View v : dialogViews(d)) {
            if (v instanceof EditText && description.contentEquals(v.getContentDescription() == null ? ""
                    : v.getContentDescription())) {
                return (EditText) v;
            }
        }
        throw new AssertionError("no field '" + description + "' in dialog");
    }

    private static void dialogClick(Dialog d, String description) {
        for (View v : dialogViews(d)) {
            if (v.isShown() && description.contentEquals(v.getContentDescription() == null ? ""
                    : v.getContentDescription())) {
                v.performClick();
                idle();
                return;
            }
        }
        throw new AssertionError("nothing described as '" + description + "' in dialog");
    }

    // ------------------------------------------------------------------
    // Result parsing (LaunchBridge answers come in many shapes)
    // ------------------------------------------------------------------

    @Test
    public void parsesBridgeResultShapes() throws Exception {
        assertEquals(35, PcKit.parseLevel("Volume is 35%"));
        assertEquals(40, PcKit.parseLevel("Device 2: volume 40%"));
        assertEquals(72, PcKit.parseLevel(new JSONObject().put("level", 72)));
        assertEquals(40, PcKit.parseLevel(new JSONObject().put("volume", 0.4)));
        assertEquals(15, PcKit.parseLevel(new JSONObject().put("master", "15 %")));
        assertEquals(-1, PcKit.parseLevel("no level here"));
        assertEquals(-1, PcKit.parseLevel("Endpoint 1234"));
        assertTrue(PcKit.parseMuted(new JSONObject().put("muted", true)));
        assertTrue(PcKit.parseMuted("Volume 30% (muted)"));
        assertFalse(PcKit.parseMuted("Volume 30% (unmuted)"));

        assertEquals("3d 4h", PcKit.compactUptime("3 days, 4:12:05"));
        assertEquals("3d 4h", PcKit.compactUptime("273600"));
        assertEquals("5h 12m", PcKit.compactUptime("5h 12m"));
        assertEquals("42m", PcKit.compactUptime("0:42:10"));
        assertEquals("since Tuesday", PcKit.compactUptime("since Tuesday"));

        assertEquals("hi", PcKit.clipText(new JSONObject().put("text", "hi")));
        assertEquals("plain", PcKit.clipText("plain"));
        assertEquals(MockBridge.PNG_1PX, PcKit.extractImage("data:image/png;base64," + MockBridge.PNG_1PX));
        assertEquals(MockBridge.PNG_1PX, PcKit.extractImage(new JSONObject().put("png", MockBridge.PNG_1PX)));
        assertNull(PcKit.extractImage(new JSONObject().put("path", "C:\\shot.png")));
        assertEquals("path: C:\\shot.png", PcKit.imageMeta(new JSONObject().put("path", "C:\\shot.png")
                .put("image", "data:image/png;base64," + MockBridge.PNG_1PX)));

        assertEquals("8.1 GB", PcKit.gb(8.1));
        assertEquals("476 GB", PcKit.gb(476));
        assertEquals("1.9 TB", PcKit.gb(1946));
        assertEquals("just now", PcKit.age(1200));
        assertEquals("3m ago", PcKit.age(200000));
    }

    // ------------------------------------------------------------------
    // API 23
    // ------------------------------------------------------------------

    @Test
    @Config(sdk = 23)
    public void worksOnApi23WithAPlainBridge() throws Exception {
        withBridge(true); // the original fixtures: text answers, no battery, two apps
        openPc("cyber");
        waitFor("vitals", () -> shows("12%") && shows("8.1 / 16 GB") && shows("210 GB free"));
        assertTrue(shows("AC"));
        waitFor("volume from text", () -> find(PcSlider.class) != null && find(PcSlider.class).value() == 35);
        waitFor("apps", () -> button("Open Notepad") != null);
        click("Open Notepad");
        latestAlert().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("launched", () -> bridge.launched.contains("Notepad"));
        click("Get PC clipboard");
        waitFor("clipboard", () -> shows("text from the PC clipboard"));
    }
}
