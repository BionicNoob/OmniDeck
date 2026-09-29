package com.omnideck.mobile;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.omnideck.mobile.ui.Theme;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

/**
 * The Settings overlay end to end: opened from the top-bar gear, every
 * control persists to prefs and reaches Ollama in the next chat request,
 * theme cards recreate the shell, the PC bridge pairs against a mock
 * LaunchBridge, chat history clears, Back closes. Screenshots of each theme
 * and key states go to build/screens/settings-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsScreenTest extends SettingsBaseTest {

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    @Test
    public void gearOpensSettingsTogglesPersistAndBackCloses() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        assertTrue(shows("System configuration"));
        assertTrue(shows("Appearance"));
        assertNotNull(button("Close settings"));
        assertNotNull(button("Jump to Generation"));

        click("Reduce motion");
        assertTrue(settings().reduceMotion());
        assertTrue(prefs().getBoolean("reduce_motion", false));
        click("Haptics");
        assertFalse(settings().haptics());
        assertFalse("the live Ui stops ticking too", act.ui().haptics);
        click("HUD effects");
        assertFalse(settings().hudEffects());
        click("Incognito");
        assertTrue(settings().incognito());
        click("Keep model loaded");
        assertFalse(settings().keepLoaded());
        click("Read replies aloud");
        assertTrue(settings().readAloud());
        click("Mode: Deep");
        assertEquals(Settings.MODE_DEEP, settings().mode());
        click("Mode: Fast");
        assertEquals(Settings.MODE_FAST, settings().mode());
        assertTrue("change is acknowledged in the header", shows("Saved"));

        // The connection card reflects the live link.
        assertTrue(shows("LINK // ONLINE"));
        assertTrue(shows("127.0.0.1:" + ollama.port()));
        assertTrue(shows("llama3.2:3b"));

        // Back closes settings and returns to the tab underneath.
        act.onBackPressed();
        idle();
        assertFalse(act.settingsOpen());
        assertEquals(MainActivity.TAB_COMMAND, act.currentTab());

        // Reopened, every control reads the saved values back.
        settings().setReduceMotion(false);
        openSettings();
        assertFalse(toggle("Reduce motion").isChecked());
        assertFalse(toggle("Haptics").isChecked());
        assertTrue(toggle("Incognito").isChecked());
        assertTrue(toggle("Read replies aloud").isChecked());
        assertTrue(need("Mode: Fast").isSelected());
        click("Close settings");
        assertFalse(act.settingsOpen());

        // Keep-loaded off reaches Ollama as keep_alive 5m.
        tab(MainActivity.TAB_COMMS);
        settings().setReadAloud(false);
        chatAndWait("status?");
        assertEquals("5m", ollama.lastChatRequest().getString("keep_alive"));
    }

    @Test
    public void performanceSamplingAndPersonaReachTheChatRequest() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        openSettings();

        click("Context window: 16K");
        assertEquals(16384, settings().numCtx());
        assertTrue(need("Context window: 16K").isSelected());
        click("Increase CPU threads");
        click("Increase CPU threads");
        assertEquals(2, settings().numThread());
        click("Decrease CPU threads");
        assertEquals(1, settings().numThread());

        SeekBar temp = (SeekBar) need("Temperature");
        temp.setProgress(14);
        idle();
        assertEquals(0.70f, settings().temperature(), 0.001f);
        assertTrue(shows("0.70"));
        SeekBar topP = (SeekBar) need("Top-p");
        topP.setProgress(15);
        idle();
        assertEquals(0.80f, settings().topP(), 0.001f);
        click("Increase max reply tokens");
        click("Increase max reply tokens");
        click("Increase max reply tokens");
        assertEquals(512, settings().maxTokens());

        EditText prompt = (EditText) need("System prompt");
        scrollTo(prompt);
        prompt.setText("You are OMNI. Be terse.");
        idle();
        assertTrue("unsaved edits are flagged", shows("unsaved"));
        click("Save system prompt");
        assertEquals("You are OMNI. Be terse.", settings().systemPrompt());

        click("Add fact");
        answerPrompt("My name is Tony");
        click("Add fact");
        answerPrompt("I build suits in the garage");
        click("Add fact");
        answerPrompt("Temporary fact");
        assertEquals(3, settings().facts().size());
        assertTrue(shows("My name is Tony"));
        click("Forget fact 3");
        assertEquals(2, settings().facts().size());
        assertFalse(shows("Temporary fact"));
        advance(500);
        shoot("settings-light-persona-facts");

        click("Close settings");
        chatAndWait("status report");
        JSONObject req = ollama.lastChatRequest();
        JSONObject o = req.getJSONObject("options");
        assertEquals(16384, o.getInt("num_ctx"));
        assertEquals(1, o.getInt("num_thread"));
        assertEquals(0.7, o.getDouble("temperature"), 1e-9);
        assertEquals(0.8, o.getDouble("top_p"), 1e-9);
        assertEquals(512, o.getInt("num_predict"));
        JSONArray msgs = req.getJSONArray("messages");
        JSONObject sys = msgs.getJSONObject(0);
        assertEquals("system", sys.getString("role"));
        assertTrue(sys.getString("content"), sys.getString("content").startsWith("You are OMNI. Be terse."));
        assertTrue(sys.getString("content").contains("My name is Tony"));
        assertTrue(sys.getString("content").contains("I build suits in the garage"));
        assertFalse(sys.getString("content").contains("Temporary fact"));

        // "Reset" puts sampling back to the model's defaults; nothing extra is sent.
        openSettings();
        click("Reset generation");
        assertTrue(settings().temperature() < 0);
        assertTrue(settings().topP() < 0);
        assertEquals(0, settings().maxTokens());
        click("Context window: Auto");
        assertEquals(0, settings().numCtx());
        click("Close settings");
        chatAndWait("again");
        JSONObject o2 = ollama.lastChatRequest().getJSONObject("options");
        assertFalse(o2.has("temperature"));
        assertFalse(o2.has("top_p"));
        assertFalse(o2.has("num_predict"));
        assertEquals("auto context matches the loaded model", 8192, o2.getInt("num_ctx"));
    }

    @Test
    public void modelPickersAndModeRouteTheReply() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        openSettings();
        click("Default model");
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("model picker", d);
        clickRowInDialog(d, "qwen3:8b");
        assertEquals("qwen3:8b", settings().model());
        assertEquals("qwen3:8b", engine().currentModel());
        click("Deep-mode model");
        clickRowInDialog(ShadowAlertDialog.getLatestAlertDialog(), "llama3.2:3b");
        assertEquals("llama3.2:3b", settings().deepModel());
        click("Mode: Deep");
        click("Close settings");
        chatAndWait("think hard");
        assertEquals("deep mode uses the deep model", "llama3.2:3b", ollama.lastChatRequest().getString("model"));
        openSettings();
        click("Deep-mode model");
        clickRowInDialog(ShadowAlertDialog.getLatestAlertDialog(), "Same as default");
        assertEquals("", settings().deepModel());
        assertTrue(shows("Same as default"));
    }

    private void clickRowInDialog(AlertDialog d, String title) {
        List<View> all = new ArrayList<>();
        collect(d.getWindow().getDecorView(), all);
        for (View v : all) {
            if (v instanceof TextView && title.contentEquals(((TextView) v).getText())) {
                ((View) v.getParent()).performClick();
                idle();
                return;
            }
        }
        fail("no row " + title);
    }

    @Test
    public void themeCardsSwitchTheLook() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        assertTrue(need("Theme: Cyber").isSelected());
        click("Theme: Light");
        assertEquals("light", settings().theme());
        ctl.recreate();
        act = ctl.get();
        idle();
        assertEquals(Theme.LIGHT, act.theme().id);
        assertTrue("settings stays open across the recreate", act.settingsOpen());
        assertTrue(need("Theme: Light").isSelected());
        assertFalse(need("Theme: Cyber").isSelected());

        // System resolves to Light here (the phone is in light mode): no recreate, the choice is saved.
        click("Theme: System");
        assertEquals("system", settings().theme());
        assertTrue(need("Theme: System").isSelected());
        assertTrue(shows("Follows the phone"));

        click("Theme: Dark");
        assertEquals("dark", settings().theme());
        ctl.recreate();
        act = ctl.get();
        idle();
        assertEquals(Theme.DARK, act.theme().id);
        assertTrue(need("Theme: Dark").isSelected());
    }

    @Test
    public void bridgeTokenIsMaskedPairsAndForgets() throws Exception {
        withBridge(false);
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        EditText token = (EditText) need("Bridge token");
        scrollTo(token);
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, token.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertTrue(token.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertTrue(shows("Not paired"));

        click("Pair now");
        waitFor("paired", () -> bridge.token.equals(settings().bridgeToken()));
        idle();
        assertEquals(bridge.token, token.getText().toString());
        assertTrue("still masked after pairing", token.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertTrue(shows("Paired"));
        click("Show token");
        assertNull(token.getTransformationMethod());
        assertNotNull(button("Hide token"));
        advance(400);
        shoot("settings-dark-bridge-paired");
        click("Hide token");
        assertTrue(token.getTransformationMethod() instanceof PasswordTransformationMethod);

        // Port: validated, saved on Done.
        EditText port = (EditText) need("Bridge port");
        port.setText("70000");
        port.onEditorAction(EditorInfo.IME_ACTION_DONE);
        idle();
        assertEquals(bridge.port(), settings().bridgePort());
        port.setText("9123");
        port.onEditorAction(EditorInfo.IME_ACTION_DONE);
        idle();
        assertEquals(9123, settings().bridgePort());
        port.setText(String.valueOf(bridge.port()));
        port.onEditorAction(EditorInfo.IME_ACTION_DONE);
        idle();
        assertEquals(bridge.port(), settings().bridgePort());

        // Paste replaces the token.
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("t", "  pasted-token  "));
        click("Paste token");
        assertEquals("pasted-token", settings().bridgeToken());

        click("Forget pairing");
        confirmLatest();
        assertEquals("", settings().bridgeToken());
        assertEquals("", token.getText().toString());
        assertTrue(shows("Not paired"));
    }

    @Test
    public void clearHistoryDeletesEverySavedChat() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("first chat");
        engine().newChat();
        idle();
        chatAndWait("second chat");
        assertEquals(2, savedChats().size());
        openSettings();
        waitFor("chat count", () -> shows("2 chats"));
        click("Clear all chat history");
        confirmLatest();
        waitFor("history cleared", () -> savedChats().isEmpty());
        assertTrue(engine().conversation().messages.isEmpty());
        waitFor("count updated", () -> shows("None on this phone yet"));
    }

    @Test
    public void offlineStateAndAutoDetect() throws Exception {
        // Point the app at a port nobody answers on.
        prefs().edit().putString("server", "127.0.0.1:1").commit();
        launch("cyber", MainActivity.TAB_COMMAND);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        openSettings();
        assertTrue(shows("LINK // OFFLINE"));
        assertTrue(shows("Manual"));
        advance(600);
        shoot("settings-cyber-offline");
        scrollTo(need("Default model"));
        shoot("settings-cyber-offline-model");
        click("Default model");
        assertEquals("no picker while offline", null, ShadowAlertDialog.getLatestAlertDialog());
        scrollTo(need("Auto-detect"));
        click("Auto-detect");
        assertEquals("manual address cleared", "", settings().server());
        assertTrue(shows("Auto-detect · finds Ollama"));
    }

    @Test
    public void commandReferenceRunsHelpInComms() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        View row = need("Command reference");
        scrollTo(row);
        row.performClick();
        idle();
        assertFalse(act.settingsOpen());
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        waitFor("help notice", () -> shows("/model <name>"));
    }

    @Test
    public void sectionIndexJumpsAndTracksScroll() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        ScrollView sv = scroller();
        assertEquals(0, sv.getScrollY());
        click("Jump to Voice");
        advance(1200);
        assertTrue("scrolled down to Voice", sv.getScrollY() > 0);
        View voice = need("Read replies aloud");
        int[] loc = new int[2];
        voice.getLocationOnScreen(loc);
        int[] sl = new int[2];
        sv.getLocationOnScreen(sl);
        assertTrue("Voice card near the top", loc[1] - sl[1] < sv.getHeight() / 2);
    }

    // ------------------------------------------------------------------
    // Screenshots
    // ------------------------------------------------------------------

    /** Every section of the page, at rest (notifications allowed, bridge paired). */
    static final String[] SECTIONS = {"Connection", "AI model", "Performance", "Generation", "Persona", "Voice",
            "Notifications", "PC bridge", "PC tools", "Privacy", "About"};

    private void shots(String theme) throws Exception {
        withBridge(true);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Notifier.PERMISSION);
        launch(theme, MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        advance(800);
        shoot("settings-" + theme + "-top");
        for (String s : SECTIONS) {
            jump(s);
            shoot("settings-" + theme + "-" + s.toLowerCase(java.util.Locale.US).replace(' ', '-'));
        }
    }

    @Test
    public void shotsCyber() throws Exception {
        // A configured deck: custom runner + sampling settings, a persona and memory.
        prefs().edit().putInt("num_ctx", 16384).putInt("num_thread", 8).putFloat("temperature", 0.7f)
                .putString("system_prompt", "You are OMNI, a calm, precise assistant. Answer briefly.")
                .putString("facts", "[\"My name is Tony\",\"I work on the suit in the garage lab\"]").commit();
        shots("cyber");
        ScrollView sv = scroller();
        sv.scrollTo(0, ((ViewGroup) sv).getChildAt(0).getHeight());
        advance(800);
        shoot("settings-cyber-bottom");
    }

    @Test
    public void shotsLight() throws Exception {
        shots("light");
        // Generation with a custom temperature next to a default top-p.
        settings().setTemperature(0.65f);
        settings().setMaxTokens(1024);
        click("Close settings");
        openSettings();
        click("Jump to Generation");
        advance(1200);
        shoot("settings-light-generation");
    }

    @Test
    public void shotsDark() throws Exception {
        shots("dark");
    }

    // ------------------------------------------------------------------
    // minSdk
    // ------------------------------------------------------------------

    @Test
    @Config(sdk = 23)
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void worksOnApi23() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        click("Context window: 8K");
        assertEquals(8192, settings().numCtx());
        click("Reduce motion");
        assertTrue(settings().reduceMotion());
        ((SeekBar) need("Speech rate")).setProgress(20);
        idle();
        assertEquals(1.5f, settings().speechRate(), 0.001f);
        click("Mode: Deep");
        assertEquals(Settings.MODE_DEEP, settings().mode());
        EditText token = (EditText) need("Bridge token");
        assertTrue(token.getTransformationMethod() instanceof PasswordTransformationMethod);
        click("Jump to About");
        advance(1200);
        assertTrue(shows("OMNI-DECK Mobile · companion for OMNI-DECK"));
        act.onBackPressed();
        idle();
        assertFalse(act.settingsOpen());
    }
}
