package com.omnideck.mobile;

import android.app.AlertDialog;

import com.omnideck.mobile.mock.MockBridge;
import com.omnideck.mobile.ui.PcSlider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the PC tab's verified bugs: a volume change queued
 * behind a failing read (pc#0), Mute with an unknown level (pc#1), stale
 * app lists and tools after an index rebuild or a PC switch (pc#2), a token
 * that stays "rejected" after the bridge accepts it again (pc#3), plus the
 * launcher's "Show N more" counts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PcFixesTest extends PcBaseTest {

    // ------------------------------------------------------------------
    // pc#0 — a level picked while get_volume is out
    // ------------------------------------------------------------------

    @Test
    public void aLevelPickedWhileTheReadFailsIsSentAndNeverOverridesALaterOne() throws Exception {
        richBridge(true);
        openPc("dark");
        waitPaired();
        waitFor("volume read", () -> slider().value() == 35);

        // The next read (the tab is shown again) is slow and then fails.
        bridge.toolDelayMs.put("get_volume", 1500);
        bridge.failing.add("get_volume");
        int reads = toolCalls("get_volume");
        tab(MainActivity.TAB_MODELS);
        tab(MainActivity.TAB_PC);
        waitFor("read out", () -> toolCalls("get_volume") > reads);
        click("25");
        assertEquals("queued behind the read", 35, bridge.volume);
        waitFor("read failed, the picked level still goes out", () -> bridge.volume == 25);
        assertTrue(shows("Couldn't read the volume"));

        // A later choice sticks: no stale follow-up puts 25 back.
        bridge.failing.clear();
        bridge.toolDelayMs.clear();
        PcSlider s = slider();
        tap(s, s.positionOf(80));
        waitFor("80 set", () -> bridge.volume == 80);
        for (int i = 0; i < 6; i++) {
            advance(250);
            Thread.sleep(50);
        }
        assertEquals(80, bridge.volume);
        assertEquals(80, s.value());
    }

    @Test
    public void aLevelQueuedWhenTheLinkDropsIsForgotten() throws Exception {
        richBridge(true);
        openPc("cyber");
        waitPaired();
        waitFor("volume read", () -> slider().value() == 35);
        String token = bridge.token;
        int port = bridge.port();

        // The read is out when the PC goes away.
        bridge.toolDelayMs.put("get_volume", 1200);
        int reads = toolCalls("get_volume");
        tab(MainActivity.TAB_MODELS);
        tab(MainActivity.TAB_PC);
        waitFor("read out", () -> toolCalls("get_volume") > reads);
        click("75");
        bridge.stop();
        waitFor("unreachable", () -> shows("Bridge unreachable"));

        // The PC comes back: the old session's 75 must not be sent.
        bridge = MockBridge.start("127.0.0.1", port);
        bridge.rich = true;
        bridge.token = token;
        click("Retry");
        waitFor("paired again", () -> shows("8.1 / 16 GB") && slider().value() == 35);
        for (int i = 0; i < 6; i++) {
            advance(250);
            Thread.sleep(50);
        }
        assertEquals(35, bridge.volume);
        assertFalse(bridge.ranTools.contains("set_volume"));
    }

    // ------------------------------------------------------------------
    // pc#1 — Mute with an unknown level
    // ------------------------------------------------------------------

    @Test
    public void muteMutesWhenTheLevelCantBeRead() throws Exception {
        richBridge(true);
        bridge.failing.add("get_volume");
        bridge.volume = 60;
        openPc("light");
        waitPaired();
        waitFor("read failed", () -> shows("Couldn't read the volume"));
        click("Mute");
        waitFor("muted", () -> bridge.ranTools.contains("set_volume"));
        assertEquals("Mute silences the PC (never 30%)", 0, bridge.volume);
        waitFor("unmute offered", () -> button("Unmute") != null);
    }

    @Test
    public void presetsWaitForTheFirstRead() throws Exception {
        richBridge(true);
        bridge.toolDelayMs.put("get_volume", 1500);
        openPc("cyber");
        waitFor("read out", () -> toolCalls("get_volume") > 0);
        click("Mute");
        click("75");
        advance(200);
        assertFalse("nothing is sent before the level is known", bridge.ranTools.contains("set_volume"));
        waitFor("read", () -> slider().value() == 35);
        for (int i = 0; i < 4; i++) {
            advance(250);
            Thread.sleep(50);
        }
        assertEquals(35, bridge.volume);
        assertFalse(bridge.ranTools.contains("set_volume"));
        // Now the presets work.
        click("Max");
        waitFor("max", () -> bridge.volume == 100);
    }

    // ------------------------------------------------------------------
    // pc#2 — stale apps and tools
    // ------------------------------------------------------------------

    @Test
    public void refreshReloadsTheAppListAfterTheIndexIsRebuilt() throws Exception {
        richBridge(true);
        bridge.emptyIndex = true;
        bridge.appsIndexed = 0;
        openPc("dark");
        waitPaired();
        waitFor("empty index", () -> shows("hasn't indexed any apps"));

        // Rebuilt on the PC, then Refresh.
        bridge.emptyIndex = false;
        bridge.appsIndexed = 9;
        click("Refresh PC link");
        waitFor("apps", () -> button("Open Spotify") != null);
        assertFalse(shows("hasn't indexed any apps"));
    }

    @Test
    public void showingTheTabAgainPicksUpARebuiltIndex() throws Exception {
        richBridge(true);
        bridge.emptyIndex = true;
        bridge.appsIndexed = 0;
        openPc("light");
        waitPaired();
        waitFor("empty index", () -> shows("hasn't indexed any apps"));
        bridge.emptyIndex = false;
        bridge.appsIndexed = 9;
        tab(MainActivity.TAB_MODELS);
        tab(MainActivity.TAB_PC);
        waitFor("apps after the index changed", () -> button("Open Spotify") != null);
    }

    @Test
    public void anotherPcNeverShowsOrLaunchesTheOldPcsApps() throws Exception {
        richBridge(true); // PC A: the rich catalog at 127.0.0.1
        MockBridge other = MockBridge.start("127.0.0.2", bridge.port()); // PC B: Notepad and Spotify
        try {
            openPc("cyber");
            waitPaired();
            waitFor("A's apps", () -> button("Open Discord") != null);
            click("Tool runner");
            waitFor("A's tools", () -> button("Run list_processes") != null);
            // Something opened on A becomes one of A's recent apps.
            click("Open Discord");
            positive(latestAlert());
            waitFor("recent on A", () -> button("Open Discord again") != null);

            // A slow vitals answer from A is still on its way when the tab moves to B.
            bridge.toolDelayMs.put("get_system_info", 2500);
            int polls = toolCalls("get_system_info");
            click("Refresh PC link");
            waitFor("A's slow poll out", () -> toolCalls("get_system_info") > polls);

            // Point the tab at PC B.
            click("PC link options");
            pickRow(latestAlert(), "PC address");
            AlertDialog addr = latestAlert();
            dialogField(addr, "PC address").setText("127.0.0.2");
            positive(addr);
            assertEquals("127.0.0.2", engine().settings.bridgeHost());
            waitFor("B asks to pair", () -> shows("Other PC") && shows("paired with the PC at"));

            click("Pair with this PC");
            waitFor("paired with B", () -> other.token.equals(engine().settings.bridgeToken()));
            waitFor("B's apps", () -> button("Open Notepad") != null);
            waitFor("B's vitals", () -> shows("210 GB free"));
            // Let A's late answer land: it must not show on B's screen.
            for (int i = 0; i < 12; i++) {
                advance(250);
                Thread.sleep(250);
            }
            assertNull("A's apps are gone", button("Open Discord"));
            assertNull("A's recent apps stay with A", button("Open Discord again"));
            assertNull("A's tools are gone", button("Run list_processes"));
            assertFalse("A's name never shows for B", shows("ATLAS-PC"));
            assertTrue(shows("210 GB free"));

            click("Open Notepad");
            positive(latestAlert());
            waitFor("launched on B", () -> other.launched.contains("Notepad"));
            assertEquals("nothing else went to A", 1, bridge.launched.size());
        } finally {
            other.stop();
        }
    }

    // ------------------------------------------------------------------
    // pc#3 — a token refused once
    // ------------------------------------------------------------------

    @Test
    public void aTokenRefusedOnceIsAcceptedAgainOnRefresh() throws Exception {
        richBridge(true);
        String good = bridge.token;
        openPc("light");
        waitPaired();
        // LaunchBridge restarts and briefly refuses the phone's token.
        bridge.token = "restarting";
        waitFor("rejected", () -> {
            advance(1000);
            return shows("no longer accepts");
        });
        bridge.token = good;
        click("Refresh PC link");
        waitFor("paired again without re-pairing", () -> shows("8.1 / 16 GB") && !shows("no longer accepts"));
        assertEquals(good, engine().settings.bridgeToken());
        assertTrue(shows("Paired"));
    }

    @Test
    public void aRefusedTokenIsRetriedOnItsOwn() throws Exception {
        richBridge(true);
        String good = bridge.token;
        openPc("dark");
        waitPaired();
        bridge.token = "restarting";
        waitFor("rejected", () -> {
            advance(1000);
            return shows("no longer accepts");
        });
        bridge.token = good;
        waitFor("back without a tap", () -> {
            advance(1000);
            return shows("8.1 / 16 GB") && !shows("no longer accepts");
        });
    }

    // ------------------------------------------------------------------
    // Launcher counts (V7 / V30)
    // ------------------------------------------------------------------

    @Test
    public void theLauncherSaysHowManyMoreAndHowToReachTheRest() throws Exception {
        richBridge(true);
        openPc("cyber");
        waitPaired();
        waitFor("apps", () -> button("Open Spotify") != null);
        assertTrue(shows("9 of 42"));
        assertTrue(shows("Show 3 more"));
        assertFalse(shows("Show all"));
        click("Show 3 more");
        assertNotNull(button("Open Blender"));
        assertTrue(shows("listed 9 of the 42 indexed apps"));
        assertTrue(shows("Show fewer"));
        click("Show fewer");
        assertNull(button("Open Blender"));
    }
}
