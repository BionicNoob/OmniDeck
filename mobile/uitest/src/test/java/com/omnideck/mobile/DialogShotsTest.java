package com.omnideck.mobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.Sheet;
import com.omnideck.mobile.ui.Ui;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The themed dialog shell (Ui.sheet and the pick / confirm / prompt built on
 * it) in each theme → build/screens/dialog-{theme}-{kind}.png, plus how its
 * buttons behave for code written against the stock AlertDialog.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class DialogShotsTest extends Harness {
    private final List<String> ran = new ArrayList<>();

    private Runnable mark(final String what) {
        return () -> ran.add(what);
    }

    private static List<View> tree(Dialog d) {
        List<View> out = new ArrayList<>();
        collect(d.getWindow().getDecorView(), out);
        return out;
    }

    private static TextView dialogText(Dialog d, String exact) {
        for (View v : tree(d)) {
            if (v instanceof TextView && exact.contentEquals(((TextView) v).getText())) return (TextView) v;
        }
        return null;
    }

    private void all(String theme) throws Exception {
        launch(theme, MainActivity.TAB_COMMS);
        advance(300);
        Ui ui = act.ui();
        boolean hud = act.theme().hud;

        // A pick list: highlighted choice, details, icons, a destructive row, a side action.
        List<Ui.Row> rows = new ArrayList<>();
        rows.add(new Ui.Row("llama3.2:3b", "loaded · 3.2B · Q4_K_M · 2.0 GB", true, mark("llama"), null));
        rows.add(new Ui.Row("qwen3:8b", "thinking · 8.2B · Q4_K_M · 5.2 GB", false, mark("qwen"), null));
        rows.add(new Ui.Row("Copy text", null, false, mark("copy"), null).icon(IconDrawable.COPY));
        rows.add(new Ui.Row("Delete", null, false, mark("delete"), null).icon(IconDrawable.TRASH).danger());
        AlertDialog pick = ui.pick("Switch model", rows, "Mode: auto", mark("mode"));
        idle();
        assertTrue(pick.isShowing());
        assertSame(pick, ShadowAlertDialog.getLatestAlertDialog());
        assertNotNull("Close is the negative button", pick.getButton(AlertDialog.BUTTON_NEGATIVE));
        assertEquals(hud ? "CLOSE" : "Close", pick.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        shootDialog("dialog-" + theme + "-pick");
        // Rows are tappable through their title's parent (like the stock list rows).
        ((View) dialogText(pick, "qwen3:8b").getParent()).performClick();
        idle();
        assertFalse(pick.isShowing());
        assertEquals("qwen", ran.get(ran.size() - 1));

        // A plain confirmation: the primary button, sentence case outside Cyber.
        AlertDialog confirm = ui.confirm("Open Spotify on the PC?", "C:\\Users\\omni\\AppData\\Roaming\\Spotify\\"
                + "Spotify.exe", "Open", mark("open"));
        idle();
        Button yes = confirm.getButton(AlertDialog.BUTTON_POSITIVE);
        assertEquals(hud ? "OPEN" : "Open", yes.getText().toString());
        assertFalse("never the platform's ALL-CAPS transform", yes.isAllCaps());
        shootDialog("dialog-" + theme + "-confirm");
        yes.performClick();
        idle();
        assertFalse(confirm.isShowing());
        assertEquals("open", ran.get(ran.size() - 1));

        // A destructive confirmation gets the danger button; Cancel runs nothing.
        AlertDialog del = ui.confirm("Delete llava:7b?", "This removes llava:7b from your PC and frees 4.4 GB. "
                + "You can pull it again any time.", "Delete", mark("deleted"));
        idle();
        shootDialog("dialog-" + theme + "-danger");
        int before = ran.size();
        del.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertFalse(del.isShowing());
        assertEquals(before, ran.size());

        // A prompt: the field has focus, OK hands over the text.
        final String[] typed = new String[1];
        AlertDialog prompt = ui.prompt("AI address", "e.g. 192.168.1.20 or 192.168.1.20:11434", "192.168.1.",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> typed[0] = text);
        idle();
        EditText field = null;
        for (View v : tree(prompt)) {
            if (v instanceof EditText) field = (EditText) v;
        }
        assertNotNull(field);
        shootDialog("dialog-" + theme + "-prompt");
        field.setText("192.168.1.20");
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("192.168.1.20", typed[0]);
        assertFalse(prompt.isShowing());

        // A custom sheet: identifier in mono, a close ×, a body that stays open on a failed check.
        Sheet s = ui.sheet("Tool runner", ui.mono("list_processes"));
        s.message("OK · 65 ms · 3 processes");
        TextView code = ui.text("ollama.exe        18.5%   5120 MB\nchrome.exe         6.2%   1480 MB\n"
                + "launchbridge.exe   0.4%     96 MB", 12, act.theme().codeText, act.theme().mono);
        code.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        code.setBackground(ui.rounded(act.theme().codeBg, act.theme().edge, 8));
        s.body.addView(code, Ui.fillW());
        s.closeButton("Close result");
        s.negative("Copy", mark("copy-result"));
        final Sheet sheet = s;
        s.positive("Run again", Ui.PRIMARY, false, () -> ran.add("again"));
        s.show();
        idle();
        shootDialog("dialog-" + theme + "-sheet");
        sheet.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue("autoDismiss=false keeps it open", sheet.isShowing());
        View close = null;
        for (View v : tree(sheet.dialog)) {
            if ("Close result".contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) {
                close = v;
            }
        }
        assertNotNull(close);
        close.performClick();
        idle();
        assertFalse(sheet.isShowing());
    }

    @Test
    public void cyber() throws Exception {
        all("cyber");
    }

    @Test
    public void light() throws Exception {
        all("light");
    }

    @Test
    public void dark() throws Exception {
        all("dark");
    }

    /** Callbacks that outlive their activity (a recreate mid-request) must not show a dialog on it. */
    @Test
    public void noDialogOnAFinishedActivity() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        Ui ui = act.ui();
        act.finish();
        idle();
        AlertDialog d = ui.confirm("Open Spotify on the PC?", "", "Open", mark("x"));
        assertFalse(d.isShowing());
        assertNull(ShadowDialog.getLatestDialog());
        assertNotNull("buttons still exist for callers", d.getButton(AlertDialog.BUTTON_POSITIVE));
        assertEquals("open", d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().toLowerCase(Locale.US));
    }
}
