package com.omnideck.mobile;

import android.app.AlertDialog;
import android.content.Intent;

import com.omnideck.mobile.core.ChatMessage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.net.DatagramSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Slash commands wired to the newer Engine APIs, and notification taps opening their tab. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 34})
@LooperMode(LooperMode.Mode.PAUSED)
public class CommandWiringTest extends Harness {

    private String lastNotice() {
        ChatMessage n = engine().conversation().lastOfRole(ChatMessage.NOTICE);
        return n == null ? "" : n.content;
    }

    @Test
    public void wolExplainsAMissingMacThenSendsThePacket() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        submit("/wol");
        waitFor("MAC advice", () -> lastNotice().contains("MAC address"));

        engine().settings.setPcMac("3c:7c:3f:12:ab:cd");
        try (DatagramSocket listener = new DatagramSocket(0)) {
            Engine.testWolPort = listener.getLocalPort();
            submit("/wakepc");
            waitFor("sent", () -> lastNotice().startsWith("Wake-up packet sent to 3C:7C:3F:12:AB:CD"));
        } finally {
            Engine.testWolPort = 0;
        }
    }

    @Test
    public void lockAsksFirstThenLocksThroughTheBridge() throws Exception {
        withBridge(true);
        bridge.rich = true;
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        submit("/lock");
        waitFor("confirm", () -> ShadowAlertDialog.getLatestAlertDialog() != null
                && ShadowAlertDialog.getLatestAlertDialog().isShowing());
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitFor("locked", () -> lastNotice().contains("Workstation locked"));

        // Without confirmations it locks straight away.
        engine().settings.setConfirmPcActions(false);
        int before = engine().conversation().messages.size();
        submit("/lock");
        waitFor("locked again", () -> engine().conversation().messages.size() > before
                && lastNotice().contains("Workstation locked"));
    }

    @Test
    public void stopCancelsACompaction() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        submit("first question");
        waitFor("reply", () -> !engine().isBusy() && engine().conversation().messages.size() >= 2);
        submit("second question");
        waitFor("reply", () -> !engine().isBusy() && engine().conversation().messages.size() >= 4);
        ollama.tokenDelayMs = 400; // keep the summary running long enough to stop it
        submit("/compact");
        waitFor("compacting", () -> engine().isWorking());
        submit("/stop");
        waitFor("stopped", () -> !engine().isWorking());
        assertTrue(lastNotice(), lastNotice().contains("stopped"));
    }

    @Test
    public void aNotificationTapOpensItsTab() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        assertEquals(MainActivity.TAB_COMMAND, act.currentTab());
        act.openSettings();
        idle();
        Intent tap = new Intent(act, MainActivity.class).putExtra(Notifier.EXTRA_TAB, MainActivity.TAB_MODELS);
        act.onNewIntent(tap);
        idle();
        assertFalse(act.settingsOpen());
        assertEquals(MainActivity.TAB_MODELS, act.currentTab());
        assertFalse("consumed", tap.hasExtra(Notifier.EXTRA_TAB));
    }
}
