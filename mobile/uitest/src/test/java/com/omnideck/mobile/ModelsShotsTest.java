package com.omnideck.mobile;

import android.app.AlertDialog;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static com.omnideck.mobile.ModelsFixesTest.LLAMA;
import static com.omnideck.mobile.ModelsFixesTest.LLAVA;
import static com.omnideck.mobile.ModelsFixesTest.QWEN;
import static com.omnideck.mobile.ModelsFixesTest.dialog;
import static com.omnideck.mobile.ModelsFixesTest.dialogText;
import static com.omnideck.mobile.ModelsFixesTest.pickRow;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The model bay's states in every theme, for visual review:
 * build/screens/models-{theme}-{pulling, pull-stopped, pull-failed, actions,
 * delete-confirm, details-loaded, empty, offline}.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ModelsShotsTest extends Harness {

    @Test
    public void cyberDownloadsAndDialogs() throws Exception {
        downloadsAndDialogs("cyber");
    }

    @Test
    public void lightDownloadsAndDialogs() throws Exception {
        downloadsAndDialogs("light");
    }

    @Test
    public void darkDownloadsAndDialogs() throws Exception {
        downloadsAndDialogs("dark");
    }

    @Test
    public void cyberEmpty() throws Exception {
        empty("cyber");
    }

    @Test
    public void lightEmpty() throws Exception {
        empty("light");
    }

    @Test
    public void darkEmpty() throws Exception {
        empty("dark");
    }

    @Test
    public void cyberOffline() throws Exception {
        offline("cyber");
    }

    @Test
    public void lightOffline() throws Exception {
        offline("light");
    }

    @Test
    public void darkOffline() throws Exception {
        offline("dark");
    }

    // ------------------------------------------------------------------

    private void downloadsAndDialogs(String theme) throws Exception {
        ModelsFixesTest.stockShelf(ollama);
        ollama.pullDelayMs = 1500;
        ollama.pullTotal = 637L * 1024 * 1024;
        launch(theme, MainActivity.TAB_MODELS);
        waitOnline();
        waitFor("details", () -> engine().details(QWEN) != null && engine().details(LLAVA) != null);
        engine().setModel(LLAMA);
        engine().setDeepModel(QWEN);
        idle();
        click("Load " + LLAMA);
        waitFor("llama loaded", () -> engine().isLoaded(LLAMA) && !shows("Loading into memory"));
        advance(300);

        // A download in flight, then stopped part-way.
        pullField().setText("tinyllama");
        click("Pull model");
        waitFor("progress", () -> {
            Engine.PullState ps = engine().pullState();
            return ps != null && !ps.done && ps.completed > 0 && ps.bytesPerSec > 0;
        });
        advance(100);
        scrollTop();
        shoot("models-" + theme + "-pulling");
        click("Cancel download");
        waitFor("stopped", () -> engine().pullState().done);
        advance(100);
        assertEquals("stopped", engine().pullState().error);
        assertNotNull(button("Resume download"));
        scrollTop();
        shoot("models-" + theme + "-pull-stopped");

        // A failed one: the plain reason, Ollama's words, and the way forward.
        pullField().setText("missing-model");
        click("Pull model");
        waitFor("failed", () -> engine().pullState().done && engine().pullState().name.startsWith("missing"));
        advance(100);
        assertTrue(shows("no model called missing-model"));
        scrollTop();
        shoot("models-" + theme + "-pull-failed");
        click("Dismiss download");

        // The card's menu and the delete confirmation.
        click("More actions for " + LLAVA);
        shootDialog("models-" + theme + "-actions");
        pickRow("Delete from PC");
        assertNotNull(dialogText("frees 4.4 GB"));
        shootDialog("models-" + theme + "-delete-confirm");
        ((AlertDialog) dialog()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();

        // The spec sheet of the active, loaded model.
        click(LLAMA);
        waitFor("sheet", () -> dialogText("131,072 tokens") != null);
        shootDialog("models-" + theme + "-details-loaded");
    }

    private void empty(String theme) throws Exception {
        ollama.clearModels();
        launch(theme, MainActivity.TAB_MODELS);
        waitFor("online", () -> engine().state() == Engine.State.ONLINE);
        waitFor("empty state", () -> shows("No models installed"));
        advance(200);
        assertNotNull(textView("Pull llama3.2"));
        shoot("models-" + theme + "-empty");
    }

    private void offline(String theme) throws Exception {
        prefs().edit().putString("server", "127.0.0.1:1").commit();
        launch(theme, MainActivity.TAB_MODELS);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        advance(300);
        assertNotNull(textView("Set address"));
        shoot("models-" + theme + "-offline");
    }

    private EditText pullField() {
        for (View v : views()) {
            if (v instanceof EditText && "Model to pull".contentEquals(String.valueOf(v.getContentDescription()))) {
                return (EditText) v;
            }
        }
        throw new AssertionError("no pull field");
    }

    private void scrollTop() {
        for (View v : views()) {
            if (v instanceof ScrollView && v.isShown()) {
                ((ScrollView) v).scrollTo(0, 0);
                break;
            }
        }
        idle();
    }
}
