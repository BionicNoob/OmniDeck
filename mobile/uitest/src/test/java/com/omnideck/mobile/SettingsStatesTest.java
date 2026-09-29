package com.omnideck.mobile;

import android.speech.tts.TextToSpeech;
import android.widget.EditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowTextToSpeech;

import java.net.DatagramSocket;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Screenshots of the Settings rows in their non-resting states, in every
 * theme (build/screens/settings-{theme}-state-*.png): an API key in use,
 * voice unavailable, notifications blocked, invalid bridge address and MAC,
 * the wake-up packet sent, PC tools not ready, incognito on with
 * hands-free and read-aloud engaged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsStatesTest extends SettingsBaseTest {

    private void states(String theme) throws Exception {
        withBridge(false);
        prefs().edit().putString("model", "llama3.2:3b").putString("api_key", "sk-omni-demo-4f2a")
                .putBoolean("incognito", true).putBoolean("hands_free", true).putBoolean("read_aloud", true)
                .commit();
        launch(theme, MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        String p = "settings-" + theme + "-state-";

        EditText key = field("API key");
        scrollTo(need("Auto-detect"));
        shoot(p + "apikey");

        jump("Voice");
        click("Test voice");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.ERROR);
        idle();
        advance(1600);
        assertTrue(shows("no working text-to-speech voice"));
        shoot(p + "voice-unavailable");

        jump("Notifications");
        assertTrue(shows("Allow notifications"));
        shoot(p + "notify-blocked");

        jump("PC bridge");
        typeDone(field("Bridge address"), "https://pc.lan");
        EditText mac = field("PC MAC address");
        typeDone(mac, "3c-7c-3f");
        scrollTo(need("Bridge address"));
        advance(400);
        shoot(p + "bridge-errors");

        typeDone(field("Bridge address"), "");
        typeDone(mac, "3c:7c:3f:12:ab:cd");
        try (DatagramSocket listener = new DatagramSocket(0)) {
            Engine.testWolPort = listener.getLocalPort();
            click("Wake PC");
            waitFor("sent", () -> shows("Wake-up packet sent"));
        } finally {
            Engine.testWolPort = 0;
        }
        scrollTo(need("Pair now"));
        advance(400);
        shoot(p + "wake-sent");

        jump("PC tools");
        waitFor("readiness", () -> shows("can call tools") && shows("Not paired"));
        shoot(p + "tools-not-ready");

        jump("Privacy");
        shoot(p + "incognito");
        assertTrue(key.length() > 0);
    }

    @Test
    public void statesCyber() throws Exception {
        states("cyber");
    }

    @Test
    public void statesLight() throws Exception {
        states("light");
    }

    @Test
    public void statesDark() throws Exception {
        states("dark");
    }
}
