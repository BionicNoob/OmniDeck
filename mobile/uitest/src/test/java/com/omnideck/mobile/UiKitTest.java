package com.omnideck.mobile;

import android.text.Spanned;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;

import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The UI kit's small rules: unit symbols, the clock, identifiers, chips, buttons, toggles, live tags. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class UiKitTest extends Harness {

    @Test
    public void cyberCapsWordsButNotUnitSymbols() {
        launch("cyber", MainActivity.TAB_COMMAND);
        Theme t = act.theme();
        assertEquals("10 tok · 40.0 tok/s · 2.7s · LOAD 2.4s · FIRST TOKEN 0.0s",
                t.labelUnits("10 tok · 40.0 tok/s · 2.7s · load 2.4s · first token 0.0s"));
        assertEquals("ONLINE · 44 ms", t.labelUnits("Online · 44 ms"));
        assertEquals("AUTO-RETRY IN 13s", t.labelUnits("Auto-retry in 13s"));
        assertEquals("4 MODELS · 8.1 / 16 GB", t.labelUnits("4 models · 8.1 / 16 GB"));
        assertEquals("TIMER 5m 0s", t.labelUnits("Timer 5m 0s"));
    }

    @Test
    public void theClockFollowsThePhone() {
        launch("light", MainActivity.TAB_COMMAND);
        Ui ui = act.ui();
        long at = 1_700_000_000_000L;
        android.provider.Settings.System.putString(act.getContentResolver(),
                android.provider.Settings.System.TIME_12_24, "24");
        assertTrue(ui.clock(at, false), ui.clock(at, false).matches("\\d{2}:\\d{2}"));
        assertTrue(ui.clock(at, true), ui.clock(at, true).matches("\\d{2}:\\d{2}:\\d{2}"));
        android.provider.Settings.System.putString(act.getContentResolver(),
                android.provider.Settings.System.TIME_12_24, "12");
        String twelve = ui.clock(at, false);
        assertTrue(twelve, twelve.matches("\\d{1,2}:\\d{2}\\s?[AaPp]\\.?\\s?[Mm]\\.?"));
    }

    @Test
    public void identifiersKeepTheirCaseInMono() {
        launch("cyber", MainActivity.TAB_COMMAND);
        Ui ui = act.ui();
        assertTrue(ui.identOrText("llava:7b") instanceof Spanned);
        assertTrue(ui.identOrText("list_processes") instanceof Spanned);
        assertTrue(ui.identOrText("192.168.1.20") instanceof Spanned);
        String words = "Switch model";
        assertSame(words, ui.identOrText(words));
        assertSame("Spotify", ui.identOrText("Spotify"));
        assertEquals("llama3.2:3b", ui.chip("llama3.2:3b", act.theme().accent, true).getText().toString());
        assertEquals("LOADED", ui.chip("Loaded", act.theme().accent).getText().toString());
        assertEquals("/help", ui.actionChip("/help", true, null).getText().toString());
        assertEquals("OMNI // llama3.2:3b", ui.labelIdent("Omni // ", "llama3.2:3b").toString());
    }

    @Test
    public void buttonsAreThemedButtonsNotMaterialCaps() {
        launch("light", MainActivity.TAB_COMMAND);
        Ui ui = act.ui();
        TextView b = ui.button("Clear all chat history", com.omnideck.mobile.ui.IconDrawable.TRASH, Ui.DANGER, null);
        assertTrue("a real Button for TalkBack", b instanceof Button);
        assertFalse(b.isAllCaps());
        assertEquals("Clear all chat history", b.getText().toString());
        assertEquals("symmetric padding keeps icon + label centred", b.getPaddingLeft(), b.getPaddingRight());
        TextView ghost = ui.button("Reset", 0, Ui.GHOST, null);
        assertEquals(ui.dp(4), ghost.getPaddingRight());
        assertTrue("48dp touch target", ghost.getMinWidth() >= ui.dp(48));
        TextView mono = ui.button("Pull llama3.2", 0, Ui.PRIMARY, true, null);
        assertEquals(act.theme().mono, mono.getTypeface());
    }

    @Test
    public void cyberButtonLabelsAreHudCapsButMonoOnesKeepCase() {
        launch("cyber", MainActivity.TAB_COMMAND);
        Ui ui = act.ui();
        assertEquals("SCAN AGAIN", ui.button("Scan again", 0, Ui.SECONDARY, null).getText().toString());
        assertEquals("Pull llama3.2", ui.button("Pull llama3.2", 0, Ui.PRIMARY, true, null).getText().toString());
    }

    @Test
    public void togglesAnnounceAsSwitches() {
        launch("dark", MainActivity.TAB_COMMAND);
        Widgets.Toggle tg = act.ui().toggle(true, null);
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        tg.onInitializeAccessibilityNodeInfo(info);
        assertEquals("android.widget.Switch", info.getClassName().toString());
        assertTrue(info.isCheckable());
        assertTrue(info.isChecked());
        tg.performClick();
        assertFalse(tg.isChecked());
    }

    @Test
    public void liveTagsSayHowFreshTheFeedIs() {
        launch("cyber", MainActivity.TAB_COMMAND);
        Ui.LiveTag tag = act.ui().liveTag();
        tag.update(true, 2000);
        assertEquals("LIVE · 2s", ((TextView) tag.getChildAt(1)).getText().toString());
        assertTrue(tag.isPulsing());
        tag.update(false, 125_000);
        assertEquals("PAUSED · 2m", ((TextView) tag.getChildAt(1)).getText().toString());
        assertFalse(tag.isPulsing());
        assertEquals("now", Ui.LiveTag.age(300));
        assertEquals("3h", Ui.LiveTag.age(3 * 3600_000L + 5));
    }

    @Test
    public void emptyInstrumentsSayNoSamples() {
        launch("light", MainActivity.TAB_COMMAND);
        Widgets.Sparkline s = new Widgets.Sparkline(act, act.theme().data, act.theme().hair);
        s.setData(new double[0]);
        assertEquals("No samples", s.getContentDescription());
        s.setData(new double[]{1, 2, 3});
        assertNull(s.getContentDescription());
        Widgets.Gauge g = new Widgets.Gauge(act, act.theme().edge, act.theme().data);
        g.setFraction(-1, false);
        assertEquals("No samples", g.getContentDescription());
        g.setFraction(0.5f, false);
        assertNull(g.getContentDescription());
    }
}
