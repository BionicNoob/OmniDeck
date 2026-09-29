package com.omnideck.mobile;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.ui.Widgets;

import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Shared helpers for the Settings tests: open / close the page, find its
 * controls by description, scroll to them, type into its fields, answer its
 * dialogs, read a card's cap summary and shoot a section. Abstract: the
 * concrete classes carry the Robolectric annotations.
 */
public abstract class SettingsBaseTest extends Harness {

    protected Settings settings() {
        return engine().settings;
    }

    protected void openSettings() {
        click("Settings");
        advance(300);
        assertTrue("settings open", act.settingsOpen());
    }

    protected void closeSettings() {
        click("Close settings");
        assertTrue("settings closed", !act.settingsOpen());
    }

    /** The settings page's vertical scroller (the only one showing while settings is open). */
    protected ScrollView scroller() {
        for (View v : views()) {
            if (v instanceof ScrollView && v.isShown()) return (ScrollView) v;
        }
        throw new AssertionError("no settings scroller");
    }

    /** Scrolls so {@code v} sits near the top of the settings scroller. */
    protected void scrollTo(View v) {
        ScrollView sv = scroller();
        int y = 0;
        View cur = v;
        while (cur != null && cur != sv) {
            y += cur.getTop();
            cur = (View) cur.getParent();
        }
        sv.scrollTo(0, Math.max(0, y - 40));
        advance(200);
    }

    /** Jumps to a section with the index strip ("Voice", "PC bridge"…). */
    protected void jump(String tab) {
        click("Jump to " + tab);
        advance(1200);
    }

    protected View need(String description) {
        View v = button(description);
        assertNotNull("no view described as " + description, v);
        return v;
    }

    protected EditText field(String description) {
        return (EditText) need(description);
    }

    protected Widgets.Toggle toggle(String title) {
        View v = need(title);
        if (v instanceof Widgets.Toggle) return (Widgets.Toggle) v;
        List<View> all = new ArrayList<>();
        collect(v, all);
        for (View c : all) {
            if (c instanceof Widgets.Toggle) return (Widgets.Toggle) c;
        }
        throw new AssertionError("no toggle for " + title);
    }

    /** Types into a settings field as the user would, then presses the keyboard's Done. */
    protected void typeDone(EditText f, String text) {
        f.requestFocus();
        f.setText(text);
        idle();
        f.onEditorAction(EditorInfo.IME_ACTION_DONE);
        idle();
    }

    protected static EditText firstEditText(View root) {
        List<View> all = new ArrayList<>();
        collect(root, all);
        for (View v : all) {
            if (v instanceof EditText) return (EditText) v;
        }
        throw new AssertionError("no EditText");
    }

    /** Types into the latest ui.prompt dialog and presses OK. */
    protected void answerPrompt(String text) {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("prompt dialog", d);
        firstEditText(d.getWindow().getDecorView()).setText(text);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
    }

    /** True when the latest dialog shows text containing {@code text} (case-insensitive). */
    protected boolean dialogShows(String text) {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d == null || !d.isShowing()) return false;
        List<View> all = new ArrayList<>();
        collect(d.getWindow().getDecorView(), all);
        String want = text.toLowerCase(Locale.US);
        for (View v : all) {
            if (v instanceof TextView
                    && ((TextView) v).getText().toString().toLowerCase(Locale.US).contains(want)) return true;
        }
        return false;
    }

    protected void confirmLatest() {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("confirm dialog", d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
    }

    protected void chatAndWait(String text) {
        int before = engine().conversation().messages.size();
        submit(text);
        waitFor("reply to " + text, () -> !engine().isBusy()
                && engine().conversation().messages.size() >= before + 2);
    }

    protected List<ConversationStore.Entry> savedChats() {
        AtomicReference<List<ConversationStore.Entry>> out = new AtomicReference<>();
        engine().listChats((v, err) -> out.set(v));
        waitFor("chat list", () -> out.get() != null);
        return out.get();
    }

    /** The cap summary (right side of the header band) of the card titled {@code title}. */
    protected TextView capStatus(String title) {
        String want = title.toUpperCase(Locale.US);
        for (View v : views()) {
            if (v instanceof TextView && want.contentEquals(((TextView) v).getText())
                    && v.getParent() instanceof ViewGroup) {
                ViewGroup head = (ViewGroup) v.getParent();
                View last = head.getChildAt(head.getChildCount() - 1);
                if (last instanceof TextView && last != v) return (TextView) last;
            }
        }
        fail("no card titled " + title);
        return null;
    }

    /** The first shown TextView whose text is exactly {@code text}. */
    protected TextView exactText(String text) {
        for (View v : views()) {
            if (v instanceof TextView && v.isShown() && text.contentEquals(((TextView) v).getText())) {
                return (TextView) v;
            }
        }
        return null;
    }
}
