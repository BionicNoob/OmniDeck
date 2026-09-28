package com.omnideck.mobile;

import android.app.AlertDialog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Every state of the PC tab, and each of its sheets, in Cyber, Light and
 * Dark → build/screens/pcshot-{theme}-{state}.png, for visual review.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PcShotsTest extends PcBaseTest {
    private final String theme;

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static List<Object[]> themes() {
        return Arrays.asList(new Object[]{"cyber"}, new Object[]{"light"}, new Object[]{"dark"});
    }

    public PcShotsTest(String theme) {
        this.theme = theme;
    }

    private String name(String state) {
        return "pcshot-" + theme + "-" + state;
    }

    /** Lets the shell's "link established" banner fade before a shot. */
    private void settle() {
        advance(3200);
    }

    @Test
    public void noAddress() throws Exception {
        prefs().edit().putString("server", "").putString("last_host", "").commit();
        launch(theme, MainActivity.TAB_PC);
        waitFor("no address", () -> shows("No PC address"));
        settle();
        shoot(name("nolink"));
        click("Enter PC address");
        advance(300);
        shootDialog(name("address-sheet"));
    }

    @Test
    public void linking() throws Exception {
        richBridge(true);
        bridge.delayMs = 4000;
        openPc(theme);
        advance(700);
        assertTrue(shows("Contacting LaunchBridge"));
        shoot(name("linking"));
        bridge.delayMs = 0;
    }

    @Test
    public void unreachable() throws Exception {
        prefs().edit().putInt("bridge_port", closedPort()).commit();
        openPc(theme);
        waitFor("unreachable", () -> shows("Bridge unreachable") && shows("Auto-retry in"));
        settle();
        shoot(name("unreachable"));
        click("Wake PC");
        advance(300);
        shootDialog(name("mac-sheet"));
    }

    @Test
    public void unpaired() throws Exception {
        richBridge(false);
        openPc(theme);
        waitFor("pair card", () -> shows("Pair this phone"));
        settle();
        shoot(name("unpaired"));
    }

    @Test
    public void pairedWithAnotherPc() throws Exception {
        richBridge(true);
        prefs().edit().putString("bridge_token_host", "192.168.1.42").commit();
        openPc(theme);
        waitFor("other PC", () -> shows("Other PC"));
        settle();
        shoot(name("other-pc"));
    }

    @Test
    public void rejected() throws Exception {
        richBridge(true);
        bridge.token = "rotated-on-the-pc";
        openPc(theme);
        waitFor("re-pair", () -> shows("no longer accepts"));
        settle();
        shoot(name("rejected"));
    }

    @Test
    public void paired() throws Exception {
        richBridge(true);
        bridge.power = true;
        bridge.mac = "3c:7c:3f:12:ab:cd";
        openPc(theme);
        waitPaired();
        waitFor("second sample", () -> {
            advance(1000);
            return shows("31%");
        });
        waitFor("power keys", () -> button("Shut down PC") != null && shows("WoL ·"));
        settle();
        pcScroll().scrollTo(0, 0);
        idle();
        shoot(name("paired"));
        shootFull(name("full"));
        click("PC link options");
        advance(300);
        shootDialog(name("menu"));
    }

    @Test
    public void vitalsError() throws Exception {
        richBridge(true);
        bridge.failing.add("get_system_info");
        openPc(theme);
        waitFor("vitals error", () -> shows("Couldn't read vitals"));
        settle();
        shoot(name("vitals-error"));
    }

    @Test
    public void vitalsStale() throws Exception {
        richBridge(true);
        openPc(theme);
        waitPaired();
        bridge.failing.add("get_system_info");
        waitFor("stale", () -> {
            advance(1000);
            return shows("Couldn't read vitals") && shows("Paused");
        });
        advance(4000);
        pcScroll().scrollTo(0, 0);
        idle();
        scrollTo("Vitals", 14);
        shoot(name("vitals-stale"));
    }

    @Test
    public void controlsAndLauncher() throws Exception {
        richBridge(true);
        PcToolsTest.addSchemaTools(bridge); // media keys
        openPc(theme);
        waitPaired();
        click("Capture screen");
        click("Get PC clipboard");
        waitFor("capture + clipboard", () -> button("Ask AI") != null && button("Send to chat") != null);
        settle();
        scrollTo("Controls", 14);
        shoot(name("controls"));
        click("Show 3 more");
        idle();
        pcScroll().scrollTo(0, 0);
        idle();
        scrollTo("Launcher", 14);
        shoot(name("launcher"));
    }

    @Test
    public void toolRunner() throws Exception {
        richBridge(true);
        bridge.power = true;
        PcToolsTest.addSchemaTools(bridge);
        openPc(theme);
        waitPaired();
        click("Tool runner");
        waitFor("tools", () -> button("Run list_processes") != null);
        settle();
        scrollTo("Tool runner", 14);
        shoot(name("tools"));

        click("Run set_brightness");
        advance(300);
        shootDialog(name("tool-form"));
        AlertDialog f = latestAlert();
        dialogField(f, "Level").setText("150");
        positive(f);
        shootDialog(name("tool-form-error"));
        dialogClick(f, "Edit as JSON");
        shootDialog(name("tool-json"));
        negative(f);

        click("Run media_key");
        dialogClick(latestAlert(), "Key: next");
        advance(300);
        shootDialog(name("tool-choice"));
        negative(latestAlert());

        click("Run list_processes");
        AlertDialog run = latestAlert();
        positive(run);
        waitSheet("result", run);
        advance(300);
        shootDialog(name("tool-result"));
        negative(latestAlert());

        click("Run shutdown_pc");
        advance(300);
        shootDialog(name("tool-danger"));
    }

    @Test
    public void powerConfirm() throws Exception {
        richBridge(true);
        bridge.power = true;
        openPc(theme);
        waitPaired();
        waitFor("keys", () -> button("Shut down PC") != null);
        click("Shut down PC");
        advance(300);
        shootDialog(name("power-confirm"));
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    public void narrowPhone() throws Exception {
        richBridge(true);
        bridge.power = true;
        openPc(theme);
        waitPaired();
        waitFor("keys", () -> button("Shut down PC") != null);
        settle();
        shootFull(name("360-full"));
    }
}
