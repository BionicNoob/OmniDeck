package com.omnideck.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import android.text.Spanned;

import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.ui.SettingsKit;
import com.omnideck.mobile.ui.SettingsWidgets;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the Settings review bugs and visual fixes:
 * <ul>
 * <li>a hidden Settings page never writes stale field copies when the app
 * stops (the system prompt set by /system, a pairing forgotten on the PC
 * tab), while a visible one still saves what the user typed;</li>
 * <li>turning Incognito off never writes the private chat (and chatting in
 * incognito writes nothing);</li>
 * <li>the CPU-threads stepper never steps against the button pressed;</li>
 * <li>the pairing result line doesn't outlive the state it describes;</li>
 * <li>Clear all chat history also clears the open chat, saved or not;</li>
 * <li>unit symbols stay lower-case in Cyber cap summaries, selects use a
 * chevron with mono chips, the sliders share the data ink and ring thumb,
 * the masked token uses the body face, "Pair again" is quiet.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsFixesTest extends SettingsBaseTest {

    // ------------------------------------------------------------------
    // settings#0 — stale fields written by a hidden page
    // ------------------------------------------------------------------

    @Test
    public void aHiddenPageNeverUndoesSystemFromTheChat() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        openSettings();
        closeSettings(); // the page stays built, its prompt field holding "" from then

        act.commander().run("/system Be terse");
        idle();
        assertEquals("Be terse", settings().systemPrompt());
        ctl.pause().stop(); // Home: onStop reaches every built screen
        idle();
        assertEquals("the new prompt survives the app going to the background", "Be terse",
                settings().systemPrompt());
        ctl.restart().resume();
        idle();

        act.commander().run("/system clear");
        idle();
        assertEquals("", settings().systemPrompt());
        ctl.pause().stop();
        idle();
        assertEquals("/system clear isn't undone either", "", settings().systemPrompt());
        ctl.restart().resume();
        idle();

        // Reopened, the page shows the live prompt; typing and leaving the app still saves the edit.
        act.commander().run("/system Be terse");
        idle();
        openSettings();
        EditText prompt = field("System prompt");
        assertEquals("Be terse", prompt.getText().toString());
        prompt.setText("Be terse and exact.");
        idle();
        ctl.pause().stop();
        idle();
        assertEquals("a visible page saves what was typed", "Be terse and exact.", settings().systemPrompt());
        ctl.restart().resume();
        idle();
    }

    @Test
    public void aHiddenPageNeverRestoresAForgottenPairing() throws Exception {
        withBridge(true);
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        assertEquals(bridge.token, field("Bridge token").getText().toString());
        closeSettings();

        // The PC tab's Forget pairing writes the setting directly (no state event).
        settings().setBridgeToken("");
        ctl.pause().stop();
        idle();
        assertEquals("the revoked token stays revoked", "", settings().bridgeToken());
        ctl.restart().resume();
        idle();

        // Same through the theme switch, which stops every built screen before recreating.
        settings().setBridgeToken("");
        act.applyTheme(Settings.THEME_LIGHT);
        idle();
        assertEquals("", settings().bridgeToken());
    }

    @Test
    public void closingThePageWritesOnlyWhatWasTyped() throws Exception {
        withBridge(true);
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        settings().setSystemPrompt("Stay calm.");
        openSettings();
        // Revealing and hiding the token re-sets its text: not an edit.
        jump("PC bridge");
        click("Show token");
        click("Hide token");
        // Changed elsewhere while the page is open (the fields still show the old values).
        settings().setSystemPrompt("Changed elsewhere.");
        settings().setBridgeToken("changed-elsewhere");
        closeSettings(); // onHide commits: nothing was typed, so nothing is written back
        assertEquals("Changed elsewhere.", settings().systemPrompt());
        assertEquals("changed-elsewhere", settings().bridgeToken());
    }

    // ------------------------------------------------------------------
    // settings#1 / platform#2 — incognito
    // ------------------------------------------------------------------

    @Test
    public void incognitoChatsAreNeverWrittenNotEvenWhenItsTurnedOff() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        openSettings();
        click("Incognito");
        assertTrue(settings().incognito());
        assertTrue("the row says what turning it off does", shows("closes the open chat without saving"));
        closeSettings();

        chatAndWait("my secret question");
        assertTrue("nothing is written while incognito", savedChats().isEmpty());

        openSettings();
        click("Incognito");
        assertFalse(settings().incognito());
        assertFalse("the switch stays off while the chat is replaced", toggle("Incognito").isChecked());
        idle();
        assertTrue("the private chat is still not on disk", savedChats().isEmpty());
        assertTrue("…and it's closed", engine().conversation().messages.isEmpty());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("without saving"));
        closeSettings();

        // Saving is back for new chats.
        chatAndWait("a normal question");
        List<ConversationStore.Entry> saved = savedChats();
        assertEquals(1, saved.size());
        assertEquals(2, saved.get(0).count);
        assertFalse(saved.get(0).title, saved.get(0).title.contains("secret"));
    }

    @Test
    public void incognitoOffKeepsAnEmptyChat() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        String id = engine().conversation().id;
        openSettings();
        click("Incognito");
        click("Incognito");
        assertFalse(settings().incognito());
        assertEquals("an empty chat has nothing to hide: it stays open", id, engine().conversation().id);
    }

    // ------------------------------------------------------------------
    // settings#2 — CPU threads stepper
    // ------------------------------------------------------------------

    @Test
    public void threadsStepperNeverStepsAgainstTheButton() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        View edit = need("Edit CPU threads");
        scrollTo(edit);
        edit.performClick();
        idle();
        answerPrompt("96");
        assertEquals(96, settings().numThread());
        click("Increase CPU threads");
        assertEquals("the stepper covers the typed range", 97, settings().numThread());
        click("Decrease CPU threads");
        click("Decrease CPU threads");
        assertEquals(95, settings().numThread());

        need("Edit CPU threads").performClick();
        idle();
        answerPrompt("300");
        assertEquals(256, settings().numThread());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("Capped"));
        click("Increase CPU threads");
        assertEquals("+ at the top does nothing", 256, settings().numThread());
        assertFalse("and is shown disabled", need("Increase CPU threads").isEnabled());
        click("Decrease CPU threads");
        assertEquals(255, settings().numThread());

        // The widget itself, with a value typed in above its range: + holds, − steps down by one.
        SettingsWidgets.Stepper s = new SettingsWidgets.Stepper(act.ui(), "things", null).range(0, 64, 1);
        final int[] got = {-1};
        s.setOnStep(v -> got[0] = v);
        s.bind(96);
        View minus = s.getChildAt(0), plus = s.getChildAt(4);
        plus.performClick();
        assertEquals(96, s.value());
        assertEquals(-1, got[0]);
        minus.performClick();
        assertEquals(95, s.value());
        assertEquals(95, got[0]);
    }

    // ------------------------------------------------------------------
    // settings#3 — pairing status line
    // ------------------------------------------------------------------

    @Test
    public void thePairingLineDoesNotOutliveTheStateItDescribes() throws Exception {
        withBridge(false);
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("PC bridge");
        click("Pair now");
        waitFor("paired", () -> bridge.token.equals(settings().bridgeToken()));
        idle();
        assertTrue(shows("Paired. The PC tab"));
        closeSettings();

        // Forgotten on the PC tab while Settings is closed (it writes the setting, no state event).
        settings().setBridgeToken("");
        idle();
        openSettings();
        assertFalse("the green line is gone once not paired", shows("Paired. The PC tab"));
        assertTrue(shows("Online · not paired") || shows("Not paired"));

        // Paired again, then forgotten while the page is on screen.
        jump("PC bridge");
        click("Pair now");
        waitFor("paired again", () -> bridge.token.equals(settings().bridgeToken()));
        idle();
        assertTrue(shows("Paired. The PC tab"));
        engine().setBridgeToken("");
        idle();
        assertFalse(shows("Paired. The PC tab"));
    }

    // ------------------------------------------------------------------
    // G16 — clear history takes the open chat too
    // ------------------------------------------------------------------

    @Test
    public void clearHistoryAlsoClearsAnUnsavedOpenChat() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("saved one");
        engine().settings.setIncognito(true);
        engine().newChat(); // not saved: incognito
        idle();
        chatAndWait("private one");
        assertEquals(1, savedChats().size());
        String open = engine().conversation().id;
        openSettings();
        jump("Privacy");
        waitFor("chat count", () -> shows("1 chat"));
        click("Clear all chat history");
        assertTrue(dialogShows("Deletes the saved chat on this phone and clears the open chat"));
        confirmLatest();
        waitFor("cleared", () -> savedChats().isEmpty());
        assertNotEquals(open, engine().conversation().id);
        assertTrue(engine().conversation().messages.isEmpty());

        // With nothing saved at all, the button still clears an open chat.
        closeSettings();
        chatAndWait("another private one");
        openSettings();
        jump("Privacy");
        waitFor("count", () -> shows("the open chat is incognito"));
        View clear = need("Clear all chat history");
        assertTrue("enabled for the open chat", clear.isEnabled());
        clear.performClick();
        idle();
        assertTrue(dialogShows("Clears the open chat"));
        confirmLatest();
        waitFor("open chat cleared", () -> engine().conversation().messages.isEmpty());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("open chat"));
        assertTrue(savedChats().isEmpty());
        waitFor("disabled now", () -> !need("Clear all chat history").isEnabled());
    }

    // ------------------------------------------------------------------
    // Visual fixes
    // ------------------------------------------------------------------

    @Test
    public void cyberCapSummariesKeepUnitSymbolsLowerCase() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        String s = capStatus("Connection").getText().toString();
        assertTrue(s, s.startsWith("ONLINE · "));
        assertTrue("'ms', never 'MS': " + s, s.endsWith(" ms"));
        // The loaded-model note never upper-cases the model tag.
        jump("Performance");
        waitFor("loaded note", () -> shows("llama3.2:3b") || shows("NO MODEL LOADED"));
        for (View v : views()) {
            if (v instanceof TextView && v.isShown()) {
                String txt = ((TextView) v).getText().toString();
                assertFalse(txt, txt.contains("LLAMA3.2:3B"));
            }
        }
    }

    @Test
    public void selectsUseAChevronMonoChipsAndBodyPlaceholders() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("AI model");
        Theme t = act.theme();
        ViewGroup model = (ViewGroup) need("Default model");
        TextView value = (TextView) model.getChildAt(0);
        assertEquals("llama3.2:3b", value.getText().toString());
        assertSame(t.mono, value.getTypeface());
        ViewGroup tags = (ViewGroup) model.getChildAt(1);
        TextView size = (TextView) tags.getChildAt(tags.getChildCount() - 1);
        assertEquals("the size keeps its case, in a mono chip", "3.2B", size.getText().toString());
        assertSame(t.mono, size.getTypeface());
        assertTrue(model.getChildAt(2) instanceof ImageView);

        ViewGroup deep = (ViewGroup) need("Deep-mode model");
        TextView unset = (TextView) deep.getChildAt(0);
        assertEquals("Same as default", unset.getText().toString());
        assertSame("a placeholder isn't set like a model id", t.body, unset.getTypeface());
        assertEquals(t.dim, unset.getCurrentTextColor());
    }

    @Test
    public void slidersUseTheDataInkAndARingThumbInDark() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("Voice");
        View rate = need("Speech rate");
        Theme t = act.theme();
        Bitmap bmp = Bitmap.createBitmap(rate.getWidth(), rate.getHeight(), Bitmap.Config.ARGB_8888);
        rate.draw(new Canvas(bmp));
        int cy = rate.getHeight() / 2;
        int pad = rate.getPaddingLeft();
        int fill = bmp.getPixel(pad + 3, cy);
        assertClose("the fill is the data ink, not Dark's white accent", t.data, fill);
        int rest = bmp.getPixel(rate.getWidth() - pad - 3, cy);
        assertTrue("the rest of the track is a faint data tint",
                Color.alpha(rest) > 0 && Color.alpha(rest) < 0x60);
        // 1.00× sits one third along: the thumb there is a surface disc with a data-colored center dot.
        int thumbX = pad + (rate.getWidth() - 2 * pad) / 3;
        assertClose("thumb center dot", t.data, bmp.getPixel(thumbX, cy));
        int disc = bmp.getPixel(thumbX + Math.round(3.5f * act.getResources().getDisplayMetrics().density), cy);
        assertClose("thumb disc", t.surface, disc);
    }

    @Test
    public void maskedTokenUsesTheBodyFaceAndPairAgainIsQuiet() throws Exception {
        withBridge(true);
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        openSettings();
        jump("PC bridge");
        Theme t = act.theme();
        EditText token = field("Bridge token");
        assertSame("masked dots in Inter", t.body, token.getTypeface());
        click("Show token");
        assertSame("revealed in mono", t.mono, token.getTypeface());
        click("Hide token");
        assertSame(t.body, token.getTypeface());

        assertTrue(need("Pair again").isShown());
        View now = button("Pair now");
        assertTrue("the primary Pair now is swapped out once paired", now == null || !now.isShown());
        // The ghost "Reset" link ends on the content edge.
        jump("Generation");
        assertTrue(need("Reset generation").getPaddingRight() <= Math.round(4 * act.getResources()
                .getDisplayMetrics().density));
    }

    @Test
    public void addressesAndMacsInSentencesAreSetInMono() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        SettingsKit kit = new SettingsKit(act.ui(), null);
        String text = "Wake-up packet sent to 3C:7C:3F:12:AB:CD. Bridge at 192.168.1.20:8765, proxy "
                + "https://ai.example.com. Host pc.lan:8765 since 19:36, Ollama 0.12.6.";
        CharSequence s = kit.idents(text);
        assertEquals("the words are unchanged", text, s.toString());
        Spanned sp = (Spanned) s;
        List<String> mono = new ArrayList<>();
        for (Ui.IdentSpan span : sp.getSpans(0, sp.length(), Ui.IdentSpan.class)) {
            mono.add(s.subSequence(sp.getSpanStart(span), sp.getSpanEnd(span)).toString());
        }
        assertEquals(Arrays.asList("3C:7C:3F:12:AB:CD", "192.168.1.20:8765", "https://ai.example.com", "pc.lan:8765"),
                mono);
        assertSame("plain text stays plain", "No address here.", kit.idents("No address here."));
    }

    private static void assertClose(String what, int want, int got) {
        int d = Math.abs(Color.red(want) - Color.red(got)) + Math.abs(Color.green(want) - Color.green(got))
                + Math.abs(Color.blue(want) - Color.blue(got));
        assertTrue(what + String.format(": want #%06X, got #%08X", want & 0xFFFFFF, got), d < 40);
    }
}
