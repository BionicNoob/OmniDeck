package com.omnideck.mobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.mock.MockOllama;
import com.omnideck.mobile.ui.ModelsFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The model bay (Models tab) against the mock Ollama: listing with capability
 * chips, switching, loading/unloading, the deep model, deletion behind a
 * confirmation, pulling with live progress, the spec sheet, and the offline /
 * empty / loading states. Screenshots → build/screens/models-{theme}-{state}.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ModelsScreenTest extends Harness {
    private static final String LLAMA = "llama3.2:3b";
    private static final String QWEN = "qwen3:8b";
    private static final String LLAVA = "llava:7b";
    private static final String EMBED = "nomic-embed-text:latest";

    /** A realistic shelf: chat, thinking, vision and embedding models of different ages. */
    private void stockShelf() {
        ollama.addModel(new MockOllama.Model(LLAMA, 2019393189L, "3.2B", "Q4_K_M", false)
                .modified("2026-09-06T08:12:00Z"));
        ollama.addModel(new MockOllama.Model(QWEN, 5225388164L, "8.2B", "Q4_K_M", true)
                .modified("2026-09-26T21:40:00Z").context(40960));
        ollama.addModel(new MockOllama.Model(LLAVA, 4733363377L, "7B", "Q4_0", false)
                .modified("2026-07-30T16:05:00Z").context(32768));
        ollama.addModel(new MockOllama.Model(EMBED, 274302450L, "137M", "F16", false)
                .modified("2026-05-18T10:00:00Z").context(2048));
    }

    private void open(String theme) {
        launch(theme, MainActivity.TAB_MODELS);
        waitOnline();
        waitDetails();
    }

    private void waitDetails() {
        waitFor("model details", () -> {
            for (ModelInfo m : engine().models()) {
                if (engine().details(m.name) == null) return false;
            }
            return true;
        });
        advance(200);
    }

    private ScrollView bayScroll() {
        for (View v : views()) {
            if (v instanceof ScrollView && v.isShown()) return (ScrollView) v;
        }
        throw new AssertionError("no scroll view");
    }

    private void scrollTop() {
        bayScroll().scrollTo(0, 0);
        idle();
    }

    private void scrollBottom() {
        ScrollView s = bayScroll();
        s.scrollTo(0, s.getChildAt(0).getHeight());
        idle();
    }

    private static List<View> tree(View root) {
        List<View> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static Dialog dialog() {
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("a dialog is open", d);
        return d;
    }

    /** Visible text inside the latest dialog containing {@code text} (case-insensitive). */
    private static TextView dialogText(String text) {
        String q = text.toLowerCase(Locale.US);
        for (View v : tree(dialog().getWindow().getDecorView())) {
            if (v instanceof TextView && v.isShown()
                    && ((TextView) v).getText().toString().toLowerCase(Locale.US).contains(q)) return (TextView) v;
        }
        return null;
    }

    /** Taps a row of a Ui.pick dialog by its title. */
    private static void pickRow(String title) {
        TextView tv = dialogText(title);
        if (tv == null) fail("no dialog row '" + title + "'");
        View row = (View) tv.getParent();
        assertTrue(row.performClick());
        idle();
    }

    /** Sets up the "control room" state: llama active + loaded, qwen as the deep model. */
    private void activeLoadedDeep() {
        engine().setModel(LLAMA);
        engine().setDeepModel(QWEN);
        idle();
        click("Load " + LLAMA);
        waitFor("llama loaded", () -> engine().isLoaded(LLAMA) && !shows("Loading into memory"));
        assertTrue(ollama.isLoaded(LLAMA));
        advance(300);
    }

    private void listShots(String theme) throws Exception {
        stockShelf();
        open(theme);
        activeLoadedDeep();
        scrollTop();
        shoot("models-" + theme + "-list");
        scrollBottom();
        shoot("models-" + theme + "-list-bottom");
    }

    // ------------------------------------------------------------------
    // Functionality
    // ------------------------------------------------------------------

    @Test
    public void listsModelsWithCapabilityChips() throws Exception {
        stockShelf();
        open("cyber");
        // Every installed model has a card (its content description is the name).
        for (String n : new String[]{LLAMA, QWEN, LLAVA, EMBED}) assertNotNull("card for " + n, button(n));
        // Capability chips from /api/show (Cyber upper-cases chips).
        assertTrue(shows("VISION"));
        assertTrue(shows("THINKING"));
        assertTrue(shows("TOOLS"));
        assertTrue(shows("EMBEDDING"));
        assertTrue(shows("ACTIVE"));
        // Summary: counts and disk use.
        assertTrue(shows("Installed · 4"));
        assertTrue(shows("11.4 GB"));
        // The spec line has family, parameters, quantization and a relative age.
        assertTrue(shows("qwen3 · 8.2B · Q4_K_M"));
        assertTrue(shows("ago"));
        // Sorted: active first.
        String first = null;
        for (View v : views()) {
            CharSequence d = v.getContentDescription();
            if (d != null && v.isShown() && (d.toString().equals(LLAMA) || d.toString().equals(QWEN)
                    || d.toString().equals(LLAVA) || d.toString().equals(EMBED))) {
                first = d.toString();
                break;
            }
        }
        assertEquals(engine().currentModel(), first);
    }

    @Test
    public void useSwitchesTheActiveModel() throws Exception {
        stockShelf();
        open("cyber");
        String before = engine().currentModel();
        assertEquals(LLAMA, before);
        click("Use " + QWEN);
        assertEquals(QWEN, engine().currentModel());
        assertEquals(QWEN, engine().settings.model());
        // The active card now offers "Open chat" instead of "Use".
        assertNotNull(button("Open chat with " + QWEN));
        assertNull(button("Use " + QWEN));
        // Embedding models can't become the chat model: their card offers the spec sheet instead.
        assertNull(button("Use " + EMBED));
        assertNull(button("Load " + EMBED));
        assertNotNull(button("Details for " + EMBED));
        click("More actions for " + EMBED);
        assertNull(dialogText("Use for chat"));
        assertNotNull(dialogText("Delete from PC"));
        dialog().dismiss();
        idle();
        assertEquals(QWEN, engine().currentModel());
        // The Active slot in the summary switches too.
        click("Choose the active model");
        pickRow(LLAVA);
        assertEquals(LLAVA, engine().currentModel());
    }

    @Test
    public void warmThenUnload() throws Exception {
        stockShelf();
        open("cyber");
        // Loading a model that isn't active must not switch the active model.
        click("Load " + LLAVA);
        waitFor("llava loaded", () -> ollama.isLoaded(LLAVA) && engine().isLoaded(LLAVA));
        waitFor("busy cleared", () -> button("Unload " + LLAVA) != null && !shows("Loading into memory"));
        assertEquals(LLAMA, engine().currentModel());
        assertTrue(shows("In memory"));
        assertTrue(shows("50% GPU"));
        assertTrue(shows("stays loaded"));
        click("Unload " + LLAVA);
        waitFor("llava unloaded", () -> !ollama.isLoaded(LLAVA) && !engine().isLoaded(LLAVA));
        waitFor("load offered again", () -> button("Load " + LLAVA) != null && !shows("Releasing memory"));
        assertFalse(shows("In memory"));
    }

    /** An embedding model another app left resident can be released (via /api/embed, not /api/chat). */
    @Test
    public void residentEmbeddingModelCanBeUnloaded() throws Exception {
        stockShelf();
        open("dark");
        // Something else on the PC (e.g. a RAG app) loads the embedding model.
        new com.omnideck.mobile.core.OllamaClient("127.0.0.1", ollama.port())
                .loadModel(EMBED, -1, null);
        click("Refresh models");
        waitFor("embed resident", () -> engine().isLoaded(EMBED) && button("Unload " + EMBED) != null);
        advance(200);
        assertNotNull(button("Details for " + EMBED));
        click("Unload " + EMBED);
        waitFor("embed released", () -> !ollama.isLoaded(EMBED) && !engine().isLoaded(EMBED));
        waitFor("card settled", () -> !shows("Releasing memory"));
        assertTrue(shows("not a chat model"));
    }

    @Test
    public void deepModelFromTheOverflowMenu() throws Exception {
        stockShelf();
        open("cyber");
        click("More actions for " + QWEN);
        pickRow("Set as deep model");
        assertEquals(QWEN, engine().settings.deepModel());
        assertTrue(shows("DEEP"));
        click("More actions for " + QWEN);
        pickRow("Remove as deep model");
        assertEquals("", engine().settings.deepModel());
    }

    @Test
    public void deleteNeedsConfirmationThenRemovesTheModel() throws Exception {
        stockShelf();
        open("cyber");
        click("More actions for " + LLAVA);
        pickRow("Delete from PC");
        // Nothing is deleted until the confirmation is accepted.
        AlertDialog confirm = (AlertDialog) dialog();
        assertTrue(confirm.isShowing());
        assertTrue(ollama.hasModel(LLAVA));
        assertNotNull(dialogText("frees 4.4 GB"));
        shootDialog("models-cyber-delete-confirm");
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        waitFor("deleted", () -> !ollama.hasModel(LLAVA) && engine().resolveInstalled(LLAVA) == null);
        advance(200);
        assertNull(button(LLAVA));
        assertTrue(shows("Installed · 3"));
    }

    @Test
    public void pullShowsProgressThenTheModelAppears() throws Exception {
        stockShelf();
        ollama.pullDelayMs = 1500;
        ollama.pullTotal = 637L * 1024 * 1024;
        open("cyber");
        EditText field = null;
        for (View v : views()) {
            if (v instanceof EditText && "Model to pull".contentEquals(String.valueOf(v.getContentDescription()))) {
                field = (EditText) v;
            }
        }
        assertNotNull(field);
        // Suggestion chips fill the field.
        click("Suggest gemma3");
        assertEquals("gemma3", field.getText().toString());
        field.setText("tinyllama");
        click("Pull model");
        waitFor("progress", () -> {
            Engine.PullState ps = engine().pullState();
            return ps != null && !ps.done && ps.completed > 0 && ps.bytesPerSec > 0;
        });
        advance(100);
        assertTrue(shows("Downloading tinyllama"));
        assertTrue(shows("%"));
        assertNotNull(button("Cancel download"));
        scrollTop();
        shoot("models-cyber-pulling");
        waitFor("pull done", () -> engine().pullState().done);
        waitFor("tinyllama listed", () -> engine().resolveInstalled("tinyllama") != null && button("tinyllama:latest") != null);
        advance(200);
        assertNull(engine().pullState().error);
        assertTrue(shows("Installed tinyllama:latest"));
        assertTrue(shows("NEW"));   // the fresh card is flagged and highlighted
        assertEquals("", field.getText().toString());
        shoot("models-cyber-pulled");
        // "Use now" switches to the new model.
        TextView useNow = textView("Use now");
        assertNotNull(useNow);
        useNow.performClick();
        idle();
        assertEquals("tinyllama:latest", engine().currentModel());
    }

    @Test
    public void pullErrorsAreShownClearly() throws Exception {
        open("light");
        EditText field = null;
        for (View v : views()) {
            if (v instanceof EditText && "Model to pull".contentEquals(String.valueOf(v.getContentDescription()))) {
                field = (EditText) v;
            }
        }
        assertNotNull(field);
        field.setText("missing-model");
        click("Pull model");
        waitFor("pull failed", () -> engine().pullState() != null && engine().pullState().done);
        advance(100);
        assertTrue(shows("Download failed"));
        // The plain-language reason leads; Ollama's own words follow, for diagnosis.
        assertTrue(shows("no model called missing-model"));
        assertTrue(shows("file does not exist"));
        // Nothing by that name in the library: retrying can't help, fixing the name can.
        assertNull(textView("Retry"));
        field.setText("");
        click("Edit the model name");
        assertEquals("missing-model", field.getText().toString());
        scrollTop();
        shoot("models-light-pull-error");
        click("Dismiss download");
        assertFalse(shows("Download failed"));
    }

    @Test
    public void detailsSheetShowsContextLength() throws Exception {
        stockShelf();
        open("cyber");
        activeLoadedDeep();
        click(QWEN);   // tapping a card opens its spec sheet
        waitFor("sheet", () -> dialogText("40,960 tokens") != null);
        assertNotNull(dialogText("Q4_K_M"));
        assertNotNull(dialogText("Apache License"));
        assertNotNull(dialogText("temperature 0.6"));
        shootDialog("models-cyber-details");
        dialog().dismiss();
        idle();
        click(LLAMA);
        waitFor("llama sheet", () -> dialogText("131,072 tokens") != null);
        assertNotNull(dialogText("8,192"));   // loaded context
        shootDialog("models-cyber-details-loaded");
    }

    @Test
    public void filterAppearsForBigShelves() throws Exception {
        stockShelf();
        ollama.addModel(new MockOllama.Model("mistral:7b", 4113301824L, "7.2B", "Q4_0", false));
        ollama.addModel(new MockOllama.Model("phi4:14b", 9053116391L, "14.7B", "Q4_K_M", false));
        ollama.addModel(new MockOllama.Model("gemma3:4b", 3338801804L, "4.3B", "Q4_K_M", false));
        open("dark");
        View filter = button("Filter models");
        assertNotNull(filter);
        ((EditText) filter).setText("qwen");
        idle();
        assertTrue(shows("Installed · 1 of 7"));
        assertNotNull(button(QWEN));
        assertNull(button(LLAMA));
        ((EditText) filter).setText("vision");
        idle();
        assertNotNull(button(LLAVA));
        assertNotNull(button("gemma3:4b"));
        ((EditText) filter).setText("zzz");
        idle();
        assertTrue(shows("No matches"));
        shoot("models-dark-nomatch");
        assertTrue(act.getWindow().getDecorView() != null);
        act.onBackPressed();   // back clears the filter first
        idle();
        assertEquals("", ((EditText) filter).getText().toString());
        assertEquals(MainActivity.TAB_MODELS, act.currentTab());
    }

    // ------------------------------------------------------------------
    // States
    // ------------------------------------------------------------------

    @Test
    public void offlineStatePointsToScanAndCommand() throws Exception {
        prefs().edit().putString("server", "127.0.0.1:1").commit();
        launch("cyber", MainActivity.TAB_MODELS);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        advance(300);
        assertTrue(shows("AI link offline"));
        assertNotNull(textView("Scan network"));
        assertNotNull(textView("Open Command"));
        shoot("models-cyber-offline");
        textView("Open Command").performClick();
        idle();
        assertEquals(MainActivity.TAB_COMMAND, act.currentTab());
    }

    @Test
    public void offlineLight() throws Exception {
        prefs().edit().putString("server", "127.0.0.1:1").commit();
        launch("light", MainActivity.TAB_MODELS);
        waitFor("offline", () -> engine().state() == Engine.State.OFFLINE);
        advance(300);
        assertTrue(shows("Can't reach your AI"));
        shoot("models-light-offline");
    }

    @Test
    public void emptyShelfGuidesToAPull() throws Exception {
        ollama.clearModels();
        launch("cyber", MainActivity.TAB_MODELS);
        waitFor("online", () -> engine().state() == Engine.State.ONLINE);
        waitFor("empty state", () -> shows("No models installed"));
        advance(200);
        assertNotNull(textView("Pull llama3.2"));
        assertTrue(shows("Installed · 0"));
        shoot("models-cyber-empty");
    }

    @Test
    public void loadingSkeletonWhileTheListIsOnItsWay() throws Exception {
        stockShelf();
        ollama.tagsDelayMs = 2500;
        launch("cyber", MainActivity.TAB_MODELS);
        waitFor("online", () -> engine().state() == Engine.State.ONLINE);
        advance(150);
        assertTrue(shows("reading"));
        assertNotNull(button("Loading models"));
        shoot("models-cyber-loading");
        waitFor("list", () -> !engine().models().isEmpty());
        advance(200);
        assertNull(button("Loading models"));
    }

    // ------------------------------------------------------------------
    // Screenshots per theme
    // ------------------------------------------------------------------

    @Test
    public void cyberList() throws Exception {
        listShots("cyber");
    }

    @Test
    public void lightList() throws Exception {
        listShots("light");
        click(QWEN);
        waitFor("sheet", () -> dialogText("40,960 tokens") != null);
        shootDialog("models-light-details");
    }

    @Test
    public void darkList() throws Exception {
        listShots("dark");
        click(LLAMA);
        waitFor("sheet", () -> dialogText("131,072 tokens") != null);
        shootDialog("models-dark-details");
        dialog().dismiss();
        idle();
        click("More actions for " + LLAVA);
        shootDialog("models-dark-actions");
    }

    /** The narrow end of the range (360dp) with motion reduced: no clipping, no animators. */
    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    public void narrowPhoneWithReducedMotion() throws Exception {
        prefs().edit().putBoolean("reduce_motion", true).commit();
        stockShelf();
        ollama.tagsDelayMs = 1200;
        launch("cyber", MainActivity.TAB_MODELS);
        waitFor("online", () -> engine().state() == Engine.State.ONLINE);
        assertNotNull(button("Loading models"));
        waitOnline();
        waitDetails();
        activeLoadedDeep();
        scrollTop();
        shoot("models-cyber-narrow");
        scrollBottom();
        shoot("models-cyber-narrow-bottom");
        // Busy state without motion: a static bar, then back to normal.
        click("Load " + QWEN);
        assertTrue(shows("Loading into memory"));
        waitFor("qwen loaded", () -> engine().isLoaded(QWEN) && !shows("Loading into memory"));
        assertEquals(LLAMA, engine().currentModel());
    }

    // ------------------------------------------------------------------
    // API 23 (minSdk)
    // ------------------------------------------------------------------

    @Test
    @Config(sdk = 23)
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void worksOnApi23() {
        stockShelf();
        open("light");
        assertTrue(shows("Installed · 4"));
        assertTrue(shows("Vision"));
        click("Use " + QWEN);
        assertEquals(QWEN, engine().currentModel());
        click("Load " + LLAVA);
        waitFor("llava loaded", () -> ollama.isLoaded(LLAVA) && engine().isLoaded(LLAVA));
        click(LLAVA);
        waitFor("sheet", () -> dialogText("32,768 tokens") != null);
        dialog().dismiss();
        idle();
        click("More actions for " + EMBED);
        pickRow("Delete from PC");
        ((AlertDialog) dialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        waitFor("deleted", () -> !ollama.hasModel(EMBED) && engine().resolveInstalled(EMBED) == null);
    }

    // ------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------

    @Test
    public void formatHelpers() {
        long t = ModelsFormat.parseTime("2026-09-01T10:00:00Z");
        assertEquals(t, ModelsFormat.parseTime("2026-09-01T03:00:00.123456789-07:00") - 123);
        assertEquals(-1, ModelsFormat.parseTime("yesterday"));
        assertEquals("3d ago", ModelsFormat.ago(t, t + 3 * 86400000L + 5000));
        assertEquals("2w ago", ModelsFormat.ago(t, t + 15 * 86400000L));
        assertEquals("just now", ModelsFormat.ago(t, t + 20000));
        assertEquals("128K", ModelsFormat.compactTokens(131072));
        assertEquals("131,072", ModelsFormat.grouped(131072));
        assertNull(ModelsFormat.capability("completion"));
        assertEquals("Vision", ModelsFormat.capability("vision"));
        assertEquals(":3b", ModelsFormat.splitTag("llama3.2:3b")[1]);
        assertEquals("hf.co/org/model", ModelsFormat.splitTag("hf.co/org/model:Q4_K_M")[0]);
        assertTrue(ModelsFormat.validName("hf.co/bartowski/Llama-3.2-3B-Instruct-GGUF:Q4_K_M"));
        assertFalse(ModelsFormat.validName("rm -rf"));
        assertEquals("Pulling layer 6a0746a1ec1a", ModelsFormat.pullStatus("pulling 6a0746a1ec1a"));
    }

    @SuppressWarnings("unused")
    private static int childCount(View v) {
        return v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0;
    }
}
