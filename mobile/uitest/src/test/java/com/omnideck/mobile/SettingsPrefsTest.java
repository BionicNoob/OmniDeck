package com.omnideck.mobile;

import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;

import com.omnideck.mobile.mock.MockOllama;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowTextToSpeech;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * The preference rows added in review round 2, end to end against the mock
 * Ollama / LaunchBridge: the API key, the bridge address and port, a typed
 * token bound to its PC, the PC's MAC with Wake PC, what OMNI may do on the
 * PC (and whether it's ready), hands-free conversation, background
 * notifications (permission and blocked advice) and the voice check.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsPrefsTest extends SettingsBaseTest {

    // ------------------------------------------------------------------
    // Connection · API key
    // ------------------------------------------------------------------

    @Test
    public void apiKeyIsMaskedSavedAndSentOnlyToTheTypedAddress() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        EditText key = field("API key");
        scrollTo(key);
        assertTrue(shows("https:// addresses work too"));
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, key.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);

        ollama.requiredKey = "sk-omni";
        typeDone(key, "  sk-omni  ");
        assertEquals("sk-omni", settings().apiKey());
        waitOnline(); // it reconnects with the key
        waitFor("the key reaches Ollama", () -> {
            synchronized (ollama.authSeen) {
                for (String s : ollama.authSeen) if (s.endsWith("Bearer sk-omni")) return true;
            }
            return false;
        });
        click("Show API key");
        assertSame(act.theme().mono, key.getTypeface());
        click("Hide API key");

        // Auto-detect: the key only ever goes to a typed-in address, and the page says so.
        click("Auto-detect");
        assertEquals("", settings().server());
        assertTrue(shows("Not in use"));

        // Cleared, it's gone.
        typeDone(key, "");
        assertEquals("", settings().apiKey());
        assertFalse(shows("Not in use"));
    }

    // ------------------------------------------------------------------
    // PC bridge · address, token, Wake-on-LAN
    // ------------------------------------------------------------------

    @Test
    public void bridgeAddressTakesHostAndPortAndSaysWhatsWrong() throws Exception {
        withBridge(true);
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("PC bridge");
        EditText host = field("Bridge address");
        assertEquals("empty: the PC running the AI", "", host.getText().toString());
        assertEquals("127.0.0.1", String.valueOf(host.getHint()));
        assertTrue(shows("Leave empty to use the PC running your AI (127.0.0.1)"));
        waitFor("bridge seen", () -> shows("Paired · online"));

        // host:port sets both; the token stays with the PC that issued it.
        typeDone(host, "localhost:" + bridge.port());
        assertEquals("localhost", settings().bridgeHost());
        assertEquals(bridge.port(), settings().bridgePort());
        assertEquals(String.valueOf(bridge.port()), field("Bridge port").getText().toString());
        assertEquals("localhost", engine().bridgeHost());
        assertFalse(engine().bridgePaired());
        assertTrue(shows("pair again for this PC"));

        typeDone(host, "https://pc.lan");
        assertTrue(shows("leave out https://"));
        assertEquals("not saved", "localhost", settings().bridgeHost());
        assertEquals("kept in the field to fix", "https://pc.lan", host.getText().toString());
        typeDone(host, "bad host name");
        assertTrue(shows("isn't an address"));

        // Empty again: back to the AI's PC, paired as before.
        typeDone(host, "");
        assertEquals("", settings().bridgeHost());
        assertFalse(shows("isn't an address"));
        assertTrue(engine().bridgePaired());
        waitFor("paired again", () -> shows("Paired · online"));
    }

    @Test
    public void aTypedTokenIsBoundToTheBridgeInUse() throws Exception {
        withBridge(false);
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("PC bridge");
        assertTrue(need("Pair now").isShown());
        EditText token = field("Bridge token");
        typeDone(token, bridge.token);
        assertEquals(bridge.token, settings().bridgeToken());
        assertEquals("bound to the bridge in use", "127.0.0.1", settings().bridgeTokenHost());
        assertTrue(engine().bridgePaired());
        assertTrue(need("Pair again").isShown());
        assertTrue(shows("PAIRED"));

        typeDone(token, "");
        assertEquals("", settings().bridgeToken());
        assertEquals("", settings().bridgeTokenHost());
        assertFalse(engine().bridgePaired());
        assertTrue(need("Pair now").isShown());
    }

    @Test
    public void macIsNormalizedValidatedAndWakePcSendsThePacket() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("PC bridge");
        EditText mac = field("PC MAC address");
        scrollTo(mac);
        click("Wake PC");
        waitFor("advice", () -> shows("needs the PC's MAC address"));

        typeDone(mac, "3c-7c-3f-12-ab-cd");
        assertEquals("3C:7C:3F:12:AB:CD", settings().pcMac());
        assertEquals("shown normalized", "3C:7C:3F:12:AB:CD", mac.getText().toString());
        typeDone(mac, "3c-7c-3f-12-ab");
        assertTrue(shows("isn't a MAC address"));
        assertEquals("kept in the field to fix", "3c-7c-3f-12-ab", mac.getText().toString());
        assertEquals("not saved", "3C:7C:3F:12:AB:CD", settings().pcMac());
        typeDone(mac, "3C:7C:3F:12:AB:CD");
        assertFalse(shows("isn't a MAC address"));

        try (DatagramSocket listener = new DatagramSocket(0)) {
            listener.setSoTimeout(5000);
            Engine.testWolPort = listener.getLocalPort();
            click("Wake PC");
            waitFor("sent", () -> shows("Wake-up packet sent to 3C:7C:3F:12:AB:CD"));
            byte[] buf = new byte[256];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            listener.receive(p);
            assertEquals(102, p.getLength());
            assertEquals((byte) 0x3C, buf[6]);
        } finally {
            Engine.testWolPort = 0;
        }

        // A MAC the phone learns from the PC shows up here (never over one the user typed).
        settings().setPcMac("");
        closeSettings();
        settings().setPcMac("AA:BB:CC:00:11:22");
        openSettings();
        assertEquals("AA:BB:CC:00:11:22", field("PC MAC address").getText().toString());
    }

    // ------------------------------------------------------------------
    // PC tools · what OMNI may do on the PC
    // ------------------------------------------------------------------

    @Test
    public void pcToolsSwitchesAndReadiness() throws Exception {
        withBridge(true);
        ollama.addModel(new MockOllama.Model("gemma2:2b", 1629518495L, "2.6B", "Q4_0", false));
        prefs().edit().putString("model", "llama3.2:3b").commit();
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("PC tools");
        assertTrue(toggle("Let OMNI use PC tools").isChecked());
        assertTrue(toggle("Ask before PC actions").isChecked());
        waitFor("model checked", () -> shows("llama3.2:3b can call tools"));
        waitFor("bridge checked", () -> shows("Paired · online."));
        assertEquals("On · asks first", capStatus("PC tools").getText().toString());
        assertEquals(act.theme().ok, capStatus("PC tools").getCurrentTextColor());

        click("Ask before PC actions");
        assertFalse(settings().confirmPcActions());
        assertEquals("On · acts directly", capStatus("PC tools").getText().toString());
        click("Let OMNI use PC tools");
        assertFalse(settings().aiTools());
        assertEquals("Off", capStatus("PC tools").getText().toString());
        click("Let OMNI use PC tools");
        click("Ask before PC actions");
        assertTrue(settings().aiTools());
        assertTrue(settings().confirmPcActions());

        // A model without tool calling, and no pairing: the list says what's missing.
        engine().setModel("gemma2:2b");
        waitFor("no tools", () -> shows("can't call tools"));
        engine().setBridgeToken("");
        waitFor("unpaired", () -> shows("Not paired — pair it in PC bridge above."));
        assertEquals(act.theme().warn, capStatus("PC tools").getCurrentTextColor());
    }

    // ------------------------------------------------------------------
    // Voice · hands-free, availability
    // ------------------------------------------------------------------

    @Test
    public void handsFreeIsTheSameSwitchAsTheCommsHeadset() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        openSettings();
        jump("Voice");
        click("Hands-free conversation");
        assertTrue(settings().handsFree());
        assertTrue(shows("hands-free"));
        closeSettings();
        View headset = need("Hands-free conversation"); // the Comms header's button now
        assertTrue("engaged in Comms too", headset.isSelected());
        headset.performClick();
        idle();
        assertFalse(settings().handsFree());
        openSettings();
        assertFalse(toggle("Hands-free conversation").isChecked());
    }

    @Test
    public void testVoiceSaysWhatsWrongWhenThePhoneCantSpeak() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("Voice");
        assertTrue(shows("Plays a line at the current rate."));
        click("Test voice");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        assertNotNull(tts);
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.ERROR);
        idle();
        advance(1600);
        assertFalse(engine().speechAvailable());
        assertTrue(shows("no working text-to-speech voice"));
        assertTrue(capStatus("Voice").getText().toString(), capStatus("Voice").getText().toString()
                .startsWith("No voice"));
        click("Open voice settings");
        Intent i = shadowOf(act).getNextStartedActivity();
        assertNotNull(i);
        assertEquals("com.android.settings.TTS_SETTINGS", i.getAction());

        // With a voice installed, testing again clears the warning.
        ShadowTextToSpeech.addLanguageAvailability(Locale.US);
        click("Test voice");
        TextToSpeech second = ShadowTextToSpeech.getLastTextToSpeechInstance();
        shadowOf(second).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        idle();
        advance(1600);
        assertTrue(engine().speechAvailable());
        assertFalse(shows("no working text-to-speech voice"));

        // While the phone speaks, the hint says so.
        act.speakingOverride = true;
        advance(800);
        assertTrue(shows("Speaking…"));
        act.speakingOverride = false;
        advance(800);
        assertTrue(shows("Plays a line at the current rate."));
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    @Test
    public void notificationsAskForPermissionAndSayWhenTheyreBlocked() throws Exception {
        launch("light", MainActivity.TAB_COMMAND); // Android 14, permission not granted yet
        waitOnline();
        openSettings();
        jump("Notifications");
        assertTrue(toggle("Background notifications").isChecked());
        assertTrue(shows("Allow notifications for OmniDeck in Android settings"));
        assertEquals("Blocked", capStatus("Notifications").getText().toString());
        assertTrue(need("Open notification settings").isShown());

        click("Background notifications");
        assertFalse(settings().notifications());
        assertFalse(shows("Allow notifications"));
        assertEquals("Off", capStatus("Notifications").getText().toString());
        assertEquals("nothing asked while off", null, shadowOf(act).getLastRequestedPermission());

        click("Background notifications");
        assertTrue(settings().notifications());
        ShadowActivity.PermissionsRequest p = shadowOf(act).getLastRequestedPermission();
        assertNotNull("switching on asks Android for permission", p);
        assertEquals("android.permission.POST_NOTIFICATIONS", p.requestedPermissions[0]);

        click("Open notification settings");
        Intent i = shadowOf(act).getNextStartedActivity();
        assertNotNull(i);
        assertEquals("android.settings.APP_NOTIFICATION_SETTINGS", i.getAction());
        assertEquals(act.getPackageName(), i.getStringExtra("android.provider.extra.APP_PACKAGE"));

        // Allowed in the system dialog: back in the window, the page reads it again.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Notifier.PERMISSION);
        windowFocus(true);
        assertFalse(shows("Allow notifications"));
        assertEquals("On", capStatus("Notifications").getText().toString());
        assertEquals(act.theme().ok, capStatus("Notifications").getCurrentTextColor());
    }

    /** What the system does when the window gets focus back (after a permission dialog). */
    private void windowFocus(boolean has) throws Exception {
        ViewTreeObserver vto = act.getWindow().getDecorView().getViewTreeObserver();
        Method m = ViewTreeObserver.class.getDeclaredMethod("dispatchOnWindowFocusChange", boolean.class);
        m.setAccessible(true);
        m.invoke(vto, has);
        idle();
    }
}
