package com.omnideck.mobile;

import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.mock.MockOllama;
import com.omnideck.mobile.ui.ModelsList;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Widgets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for the model bay's review fixes: work that happens in
 * the background, download results across activity recreates, the deep
 * model picker, embedding models Ollama doesn't describe, re-sorting cards,
 * deleting the active model, and busy models. An {@link OllamaProxy} in front
 * of the mock plays an older / slower / failing Ollama where needed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ModelsFixesTest extends Harness {
    static final String LLAMA = "llama3.2:3b";
    static final String QWEN = "qwen3:8b";
    static final String LLAVA = "llava:7b";
    static final String EMBED = "nomic-embed-text:latest";
    /** ModelsScreen.SUCCESS_CARD_MS: how long a finished download's card stays once seen. */
    static final long SUCCESS_CARD_MS = 90_000;

    private OllamaProxy proxy;

    @After
    public void stopProxy() {
        if (proxy != null) proxy.stop();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A realistic shelf: chat, thinking, vision and embedding models. */
    static void stockShelf(MockOllama ollama) {
        ollama.addModel(new MockOllama.Model(LLAMA, 2019393189L, "3.2B", "Q4_K_M", false)
                .modified("2026-09-06T08:12:00Z"));
        ollama.addModel(new MockOllama.Model(QWEN, 5225388164L, "8.2B", "Q4_K_M", true)
                .modified("2026-09-26T21:40:00Z").context(40960));
        ollama.addModel(new MockOllama.Model(LLAVA, 4733363377L, "7B", "Q4_0", false)
                .modified("2026-07-30T16:05:00Z").context(32768));
        ollama.addModel(new MockOllama.Model(EMBED, 274302450L, "137M", "F16", false)
                .modified("2026-05-18T10:00:00Z").context(2048));
    }

    /** Points the app at a proxy in front of the mock (call before launch()). */
    private OllamaProxy viaProxy() throws IOException {
        proxy = new OllamaProxy(ollama.port());
        prefs().edit().putString("server", "127.0.0.1:" + proxy.port()).commit();
        return proxy;
    }

    private void open(String theme) {
        launch(theme, MainActivity.TAB_MODELS);
        waitOnline();
        waitFor("model details", () -> {
            for (com.omnideck.mobile.core.ModelInfo m : engine().models()) {
                if (engine().details(m.name) == null) return false;
            }
            return true;
        });
        advance(200);
    }

    /** llama active + loaded, qwen as the deep model. */
    private void activeLoadedDeep() {
        engine().setModel(LLAMA);
        engine().setDeepModel(QWEN);
        idle();
        click("Load " + LLAMA);
        waitFor("llama loaded", () -> engine().isLoaded(LLAMA) && !shows("Loading into memory"));
        advance(300);
    }

    private EditText pullField() {
        for (View v : views()) {
            if (v instanceof EditText && "Model to pull".contentEquals(String.valueOf(v.getContentDescription()))) {
                return (EditText) v;
            }
        }
        throw new AssertionError("no pull field");
    }

    static Dialog dialog() {
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("a dialog is open", d);
        return d;
    }

    static List<View> tree(View root) {
        List<View> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    /** Visible text in the latest dialog containing {@code text} (case-insensitive). */
    static TextView dialogText(String text) {
        Dialog d = ShadowDialog.getLatestDialog();
        if (d == null || !d.isShowing()) return null;
        String q = text.toLowerCase(Locale.US);
        for (View v : tree(d.getWindow().getDecorView())) {
            if (v instanceof TextView && v.isShown()
                    && ((TextView) v).getText().toString().toLowerCase(Locale.US).contains(q)) return (TextView) v;
        }
        return null;
    }

    static void pickRow(String title) {
        TextView tv = dialogText(title);
        if (tv == null) fail("no dialog row '" + title + "'");
        assertTrue(((View) tv.getParent()).performClick());
        idle();
    }

    /** The card for a model (its content description is the model name). */
    private View card(String name) {
        View v = button(name);
        assertNotNull("card for " + name, v);
        return v;
    }

    /** The card's memory-state dot. */
    private Widgets.StatusDot cardDot(String name) {
        for (View v : tree(card(name))) {
            if (v instanceof Widgets.StatusDot) return (Widgets.StatusDot) v;
        }
        throw new AssertionError("no dot on " + name);
    }

    /** The download card's status dot (next to "Downloading …" / "Installed …"). */
    private Widgets.StatusDot transferDot() {
        for (View v : views()) {
            if (!(v instanceof TextView)) continue;
            String s = ((TextView) v).getText().toString();
            if (s.startsWith("Downloading ") || s.startsWith("Installed ") || s.startsWith("Download ")) {
                return (Widgets.StatusDot) ((ViewGroup) v.getParent()).getChildAt(0);
            }
        }
        throw new AssertionError("no download card");
    }

    /** A chip (or any text) with exactly this text inside a model's card. */
    private TextView textIn(String name, String exact) {
        for (View v : tree(card(name))) {
            if (v instanceof TextView && exact.contentEquals(((TextView) v).getText())) return (TextView) v;
        }
        return null;
    }

    private boolean newFlagShown() {
        for (View v : views()) {
            if (v instanceof TextView && v.isShown() && "new".equalsIgnoreCase(((TextView) v).getText().toString())) {
                return true;
            }
        }
        return false;
    }

    /** Capability placeholders still on cards (they go once a model's details arrive or fail). */
    private int placeholderChips() {
        int n = 0;
        for (String name : new String[]{LLAMA, QWEN, LLAVA, EMBED}) {
            View c = button(name);
            if (c == null) return -1;
            for (View v : tree(c)) {
                if (v instanceof com.omnideck.mobile.ui.ModelsFlow) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) if (!(g.getChildAt(i) instanceof TextView)) n++;
                }
            }
        }
        return n;
    }

    /** Model names in the order their cards are shown. */
    private List<String> cardOrder() {
        for (View v : views()) {
            if (v instanceof ModelsList) {
                ModelsList l = (ModelsList) v;
                List<String> out = new ArrayList<>();
                for (int i = 0; i < l.getChildCount(); i++) out.add(String.valueOf(l.getChildAt(i).getContentDescription()));
                return out;
            }
        }
        throw new AssertionError("no model list");
    }

    private static String toast() {
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    // ------------------------------------------------------------------
    // models#0 — the app in the background
    // ------------------------------------------------------------------

    /**
     * A download that finishes while the app is in the background starts no
     * animations, keeps its "New" flag for when the user is back, and its
     * result card waits to be seen before it retires.
     */
    @Test
    public void aDownloadFinishedInTheBackgroundWaitsToBeSeen() throws Exception {
        stockShelf(ollama);
        ollama.pullDelayMs = 400;
        ollama.pullTotal = 637L * 1024 * 1024;
        open("cyber");
        activeLoadedDeep();
        assertTrue("a loaded model pulses on screen", cardDot(LLAMA).isPulsing());
        pullField().setText("tinyllama");
        click("Pull model");
        waitFor("progress", () -> engine().pullState() != null && engine().pullState().completed > 0);
        advance(100);
        assertTrue("the download pulses on screen", transferDot().isPulsing());

        ctl.pause().stop();   // the user leaves for another app mid-download
        final long before = engine().pullState().completed;
        waitFor("progress while away", () -> engine().pullState().completed > before || engine().pullState().done);
        assertFalse("progress in the background restarts no pulse", transferDot().isPulsing());
        waitFor("pull done", () -> engine().pullState().done);
        waitFor("listed", () -> engine().resolveExact("tinyllama") != null && button("tinyllama:latest") != null);
        advance(300);
        assertFalse(transferDot().isPulsing());
        assertFalse("the list refresh restarts no card pulse", cardDot(LLAMA).isPulsing());
        assertFalse("the New flag waits for the user", newFlagShown());
        advance(SUCCESS_CARD_MS + 10_000);   // away for a long while: an unseen result must not retire

        ctl.start().resume();
        // Checked before idling: the bay re-renders synchronously on return, and the harness's
        // idle can jump the fake clock past the 5 s flash.
        assertTrue("the result is still up", shows("Installed tinyllama:latest"));
        assertTrue("and the new model is flagged now", newFlagShown());
        assertTrue("back on screen, the loaded model pulses again", cardDot(LLAMA).isPulsing());
        idle();
        advance(SUCCESS_CARD_MS + 1000);
        assertFalse("seen, it retires on its own", shows("Installed tinyllama:latest"));
    }

    // ------------------------------------------------------------------
    // models#1 — download results across recreates
    // ------------------------------------------------------------------

    @Test
    public void aDismissedDownloadResultStaysDismissedAfterARecreate() throws Exception {
        open("light");
        pullField().setText("missing-model");
        click("Pull model");
        waitFor("failed", () -> engine().pullState() != null && engine().pullState().done);
        advance(100);
        assertTrue(shows("Download failed"));
        click("Dismiss download");
        assertFalse(shows("Download failed"));

        ctl.recreate();   // a theme change, or the phone's dark-mode schedule
        act = ctl.get();
        idle();
        advance(200);
        assertEquals(MainActivity.TAB_MODELS, act.currentTab());
        assertFalse("a closed result doesn't come back", shows("Download failed"));

        // One that wasn't dismissed survives the recreate, so it isn't lost either.
        pullField().setText("missing-model-2");
        click("Pull model");
        waitFor("failed again", () -> engine().pullState().done && engine().pullState().name.endsWith("-2"));
        ctl.recreate();
        act = ctl.get();
        idle();
        advance(200);
        assertTrue(shows("Download failed"));
    }

    @Test
    public void aFinishedDownloadCardOutlivesARecreateThenRetires() throws Exception {
        stockShelf(ollama);
        ollama.pullDelayMs = 5;
        open("dark");
        pullField().setText("tinyllama");
        click("Pull model");
        waitFor("done", () -> engine().pullState().done && button("tinyllama:latest") != null);
        advance(200);
        assertTrue(shows("Installed tinyllama:latest"));
        advance(30_000);

        ctl.recreate();
        act = ctl.get();
        idle();
        advance(200);
        assertTrue("still up after a recreate", shows("Installed tinyllama:latest"));
        assertFalse("the New flag isn't replayed", newFlagShown());
        advance(SUCCESS_CARD_MS - 30_000 + 1000);   // 90 s after it was first seen
        assertFalse("retired on its own after the recreate too", shows("Installed tinyllama"));

        ctl.recreate();
        act = ctl.get();
        idle();
        advance(200);
        assertFalse("and it stays retired", shows("Installed tinyllama"));
    }

    // ------------------------------------------------------------------
    // models#2 — the deep model picker
    // ------------------------------------------------------------------

    @Test
    public void clearWorksAfterTheDeepModelLeftTheList() throws Exception {
        stockShelf(ollama);
        open("cyber");
        engine().setDeepModel(QWEN);
        idle();
        click("Choose the deep model");
        AlertDialog picker = (AlertDialog) dialog();
        Button clear = picker.getButton(AlertDialog.BUTTON_NEUTRAL);
        assertNotNull("the picker offers Clear", clear);
        // While it's open, the model is removed on the PC and the list refreshes.
        new OllamaClient("127.0.0.1", ollama.port()).deleteModel(QWEN);
        engine().refreshModels(null);
        waitFor("qwen gone", () -> engine().resolveInstalled(QWEN) == null);
        clear.performClick();   // used to crash with a NullPointerException
        idle();
        assertEquals("", engine().settings.deepModel());
        assertEquals("Deep model cleared — deep questions use the active model.", toast());
    }

    // ------------------------------------------------------------------
    // models#3 — embedding models Ollama doesn't describe
    // ------------------------------------------------------------------

    @Test
    public void embeddingModelsAreRecognisedByNameWhenOllamaSaysNothing() throws Exception {
        stockShelf(ollama);
        viaProxy().stripCapabilities = true;   // an Ollama without "capabilities" in /api/show
        open("dark");
        assertTrue(engine().details(EMBED).capabilities.isEmpty());
        // It can't become the chat model: no Use / Load on its card, just the spec sheet.
        assertNull(button("Use " + EMBED));
        assertNull(button("Load " + EMBED));
        assertNotNull(button("Details for " + EMBED));
        assertNotNull("its chip comes from the Engine's name check", textIn(EMBED, "Embedding"));
        assertNotNull(button("Use " + LLAVA));   // chat models are unaffected

        click("More actions for " + EMBED);
        assertNull(dialogText("Use for chat"));
        assertNull(dialogText("Set as deep model"));
        assertNull(dialogText("Load into memory"));
        dialog().dismiss();
        idle();
        click("Choose the active model");
        assertNotNull(dialogText(LLAVA));
        assertNull("the active picker leaves it out", dialogText(EMBED));
        dialog().dismiss();
        idle();
        click(EMBED);
        waitFor("sheet", () -> dialogText("Context window") != null);
        assertNull("no Use model in its sheet", ((AlertDialog) dialog()).getButton(AlertDialog.BUTTON_POSITIVE));
        assertNotNull(dialogText("It can't chat"));
    }

    @Test
    public void refreshRetriesCapabilityReadsThatFailed() throws Exception {
        stockShelf(ollama);
        OllamaProxy p = viaProxy();
        p.failShow = true;
        launch("cyber", MainActivity.TAB_MODELS);
        waitOnline();
        waitFor("every model's read failed", () -> p.shown.containsAll(Arrays.asList(LLAMA, QWEN, LLAVA, EMBED))
                && placeholderChips() == 0);
        advance(300);
        assertNull(engine().details(QWEN));
        assertFalse(shows("THINKING"));

        p.failShow = false;
        click("Refresh models");
        waitFor("capabilities after a manual refresh", () -> engine().details(QWEN) != null
                && engine().details(LLAVA) != null);
        advance(300);
        assertTrue(shows("THINKING"));
        assertTrue(shows("VISION"));
    }

    // ------------------------------------------------------------------
    // models#4 — re-sorting keeps cards (and their animations) attached
    // ------------------------------------------------------------------

    @Test
    public void reorderingMovesCardsWithoutDetachingThem() throws Exception {
        stockShelf(ollama);
        open("cyber");
        activeLoadedDeep();
        assertEquals(Arrays.asList(LLAMA, LLAVA, EMBED, QWEN), cardOrder());
        final Map<String, View> roots = new LinkedHashMap<>();
        final int[] detaches = {0};
        for (String n : new String[]{LLAMA, QWEN, LLAVA, EMBED}) {
            View r = card(n);
            roots.put(n, r);
            r.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    detaches[0]++;
                }
            });
        }
        click("Load " + QWEN);
        waitFor("qwen loaded", () -> engine().isLoaded(QWEN) && !shows("Loading into memory"));
        advance(300);
        assertEquals("the loaded model moves up", Arrays.asList(LLAMA, QWEN, LLAVA, EMBED), cardOrder());
        assertEquals("cards move without leaving the window", 0, detaches[0]);
        for (Map.Entry<String, View> en : roots.entrySet()) assertSame(en.getValue(), card(en.getKey()));
        assertTrue(cardDot(LLAMA).isPulsing());
        assertTrue(cardDot(QWEN).isPulsing());
    }

    // ------------------------------------------------------------------
    // models#5 — deleting the active model
    // ------------------------------------------------------------------

    @Test
    public void deletingTheActiveModelHandsOverToAnotherChatModel() throws Exception {
        stockShelf(ollama);
        open("cyber");
        activeLoadedDeep();
        click("More actions for " + LLAMA);
        pickRow("Delete from PC");
        assertNotNull("the confirmation names the successor", dialogText("replies will switch to llava:7b"));
        ((AlertDialog) dialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        waitFor("deleted", () -> !ollama.hasModel(LLAMA) && engine().resolveInstalled(LLAMA) == null);
        advance(300);
        // The successor is saved — never the deleted model — so the active badge stays put.
        assertEquals(LLAVA, engine().settings.model());
        assertEquals(LLAVA, engine().currentModel());
        assertTrue(toast(), toast().contains("now using llava:7b"));
        assertEquals("the deep model is untouched", QWEN, engine().settings.deepModel());
        click("Load " + QWEN);
        waitFor("qwen loaded", () -> engine().isLoaded(QWEN) && !shows("Loading into memory"));
        assertEquals("loading another model leaves the active one alone", LLAVA, engine().currentModel());
        // Pulling the deleted model again doesn't make it active behind the user's back.
        ollama.pullDelayMs = 5;
        pullField().setText(LLAMA);
        click("Pull model");
        waitFor("re-pulled", () -> engine().pullState().done && engine().resolveExact(LLAMA) != null);
        assertEquals(LLAVA, engine().currentModel());
    }

    @Test
    public void deletingTheDeepModelClearsIt() throws Exception {
        stockShelf(ollama);
        open("light");
        activeLoadedDeep();
        click("More actions for " + QWEN);
        pickRow("Delete from PC");
        ((AlertDialog) dialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        waitFor("deleted", () -> !ollama.hasModel(QWEN) && engine().resolveInstalled(QWEN) == null);
        advance(200);
        assertEquals("", engine().settings.deepModel());
        assertEquals("the active model is untouched", LLAMA, engine().settings.model());
    }

    // ------------------------------------------------------------------
    // models#6 — busy models
    // ------------------------------------------------------------------

    @Test
    public void aBusyModelSaysWhyLoadAndDeleteWait() throws Exception {
        stockShelf(ollama);
        OllamaProxy p = viaProxy();
        open("light");
        p.loadDelayMs = 8000;   // a big model takes its time to load (released below)
        click("Load " + QWEN);
        assertTrue(shows("Loading into memory"));
        click("More actions for " + QWEN);
        assertNotNull("the menu says what's running", dialogText("Loading into memory…"));
        assertNull("no Delete while it loads", dialogText("Delete from PC"));
        assertNull(dialogText("Unload from memory"));
        assertNotNull(dialogText("Details"));
        shootDialog("models-light-busy-menu");
        dialog().dismiss();
        idle();
        scrollToCard(QWEN);
        shoot("models-light-busy");

        // A delete confirmed just as a load starts gets an answer instead of silence.
        click("More actions for " + LLAVA);
        pickRow("Delete from PC");
        AlertDialog confirm = (AlertDialog) dialog();
        button("Load " + LLAVA).performClick();   // (behind the dialog) a load starts
        idle();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("llava:7b is still loading into memory — try again when it's done.", toast());
        assertTrue(ollama.hasModel(LLAVA));

        // The spec sheet shows the operation, and its Load button waits for it.
        click(QWEN);
        waitFor("sheet", () -> dialogText("Context window") != null);
        Button load = ((AlertDialog) dialog()).getButton(AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Loading…", load.getText().toString());
        assertFalse(load.isEnabled());
        dialog().dismiss();
        idle();
        p.loadDelayMs = 0;
        waitFor("both loaded", () -> engine().isLoaded(QWEN) && engine().isLoaded(LLAVA)
                && !shows("Loading into memory"));
    }

    private void scrollToCard(String name) {
        View c = card(name);
        for (View v = c; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v instanceof android.widget.ScrollView) {
                int y = 0;
                for (View w = c; w != null && w != v; w = (View) w.getParent()) y += w.getTop();
                ((android.widget.ScrollView) v).scrollTo(0, Math.max(0, y - 40));
                break;
            }
        }
        idle();
    }

    // ------------------------------------------------------------------
    // Sheet, failures and the kit's rules
    // ------------------------------------------------------------------

    /** Load / Unload in the spec sheet runs in place: the sheet stays up and follows the model live. */
    @Test
    public void theSpecSheetFollowsALoadLive() throws Exception {
        stockShelf(ollama);
        open("dark");
        click(LLAVA);
        waitFor("sheet", () -> dialogText("32,768 tokens") != null);
        AlertDialog sheet = (AlertDialog) dialog();
        assertNotNull(dialogText("Not loaded"));
        Button load = sheet.getButton(AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Load", load.getText().toString());
        load.performClick();
        idle();
        assertTrue("the sheet stays open", sheet.isShowing());
        waitFor("loaded, live in the sheet", () -> engine().isLoaded(LLAVA) && dialogText("50% GPU") != null);
        advance(200);
        assertEquals("Unload", load.getText().toString());
        assertTrue(load.isEnabled());
        sheet.getButton(AlertDialog.BUTTON_POSITIVE).performClick();   // Use model
        idle();
        assertFalse("Use model closes it", sheet.isShowing());
        assertEquals(LLAVA, engine().currentModel());
    }

    @Test
    public void aTransientPullFailureOffersRetryAndSaysWhyInPlainWords() throws Exception {
        OllamaProxy p = viaProxy();
        p.pullError = "write /root/.ollama/models/blobs/sha256-7462734796d6-partial: no space left on device";
        open("dark");
        pullField().setText("qwen3:14b");
        click("Pull model");
        waitFor("failed", () -> engine().pullState() != null && engine().pullState().done);
        advance(100);
        assertTrue(shows("Download failed"));
        assertTrue("the reason, in plain words", shows("disk is full"));
        assertTrue("Ollama's own words below it", shows("no space left on device"));
        assertNotNull(button("Retry download"));
        assertNull(button("Edit the model name"));
    }

    @Test
    public void identifiersKeepTheirCaseAndAmberTextIsReadable() throws Exception {
        ollama.clearModels();
        launch("cyber", MainActivity.TAB_MODELS);
        waitFor("empty", () -> shows("No models installed"));
        advance(200);
        TextView pull = textView("Pull llama3.2");
        assertNotNull(pull);
        assertEquals("the model tag keeps its case in Cyber", "Pull llama3.2", pull.getText().toString());
        assertEquals(act.theme().mono, pull.getTypeface());
        View gemma = button("Suggest gemma3");
        assertEquals("gemma3", ((TextView) gemma).getText().toString());
        assertEquals(act.theme().mono, ((TextView) gemma).getTypeface());

        // A model named inside a sentence is set as an identifier too.
        pullField().setText("missing-qwen9");
        click("Pull model");
        waitFor("failed", () -> engine().pullState() != null && engine().pullState().done);
        advance(100);
        TextView reason = textView("no model called missing-qwen9");
        assertNotNull(reason);
        assertTrue(reason.getText() instanceof android.text.Spanned);
        android.text.Spanned s = (android.text.Spanned) reason.getText();
        int at = s.toString().indexOf("missing-qwen9");
        com.omnideck.mobile.ui.Ui.IdentSpan[] spans = s.getSpans(at, at + 1, com.omnideck.mobile.ui.Ui.IdentSpan.class);
        assertEquals(1, spans.length);
        assertEquals(at + "missing-qwen9".length(), s.getSpanEnd(spans[0]));
    }

    @Test
    public void lightDeepChipUsesTheReadableAmberInk() throws Exception {
        stockShelf(ollama);
        open("light");
        activeLoadedDeep();
        Theme t = act.theme();
        TextView deep = textIn(QWEN, "Deep");
        assertNotNull(deep);
        assertEquals(t.engagedInk, deep.getCurrentTextColor());
        // The storage legend names only what the bar draws: llama is active (and loaded), the rest stored.
        assertTrue(shows("ACTIVE"));
        assertTrue(shows("STORED"));
        assertFalse(legendShows("LOADED"));
        click("Load " + LLAVA);
        waitFor("llava loaded", () -> engine().isLoaded(LLAVA) && !shows("Loading into memory"));
        advance(200);
        assertTrue(legendShows("LOADED"));
    }

    /** Legend keys are small caps words next to a swatch (the stat caption above also says LOADED). */
    private boolean legendShows(String word) {
        for (View v : views()) {
            if (v instanceof TextView && v.isShown() && word.contentEquals(((TextView) v).getText())
                    && v.getParent() instanceof ViewGroup && ((ViewGroup) v.getParent()).getChildCount() == 2
                    && !(((ViewGroup) v.getParent()).getChildAt(0) instanceof TextView)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // A pass-through proxy that can play an older, slower or failing Ollama
    // ------------------------------------------------------------------

    static final class OllamaProxy {
        private final HttpServer server;
        private final int target;
        /** Drop "capabilities" from /api/show (Ollama builds before 0.6 didn't send them). */
        volatile boolean stripCapabilities;
        /** Fail /api/show with a server error. */
        volatile boolean failShow;
        /** Hold load / unload requests (POST /api/chat and /api/embed) this long. */
        volatile long loadDelayMs;
        /** Fail /api/pull with this Ollama error (null = pass through). */
        volatile String pullError;
        /** Models /api/show was asked about. */
        final Set<String> shown = Collections.synchronizedSet(new HashSet<String>());

        OllamaProxy(int targetPort) throws IOException {
            target = targetPort;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    try {
                        forward(ex);
                    } finally {
                        ex.close();
                    }
                }
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void forward(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().toString();
            String method = ex.getRequestMethod();
            byte[] body = readAll(ex.getRequestBody());
            if (path.startsWith("/api/show")) {
                try {
                    shown.add(new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("model"));
                } catch (JSONException ignored) {
                }
                if (failShow) {
                    reply(ex, 500, "{\"error\":\"internal error\"}".getBytes(StandardCharsets.UTF_8), null);
                    return;
                }
            }
            if (path.startsWith("/api/pull") && pullError != null) {
                reply(ex, 500, ("{\"error\":" + JSONObject.quote(pullError) + "}").getBytes(StandardCharsets.UTF_8),
                        null);
                return;
            }
            if ("POST".equals(method) && (path.startsWith("/api/chat") || path.startsWith("/api/embed"))) {
                long until = System.currentTimeMillis() + loadDelayMs;
                while (System.currentTimeMillis() < until && loadDelayMs > 0) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + target + path).openConnection();
            c.setRequestMethod(method);
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct != null) c.setRequestProperty("Content-Type", ct);
            if (body.length > 0) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] resp = in == null ? new byte[0] : readAll(in);
            if (stripCapabilities && path.startsWith("/api/show") && code == 200) {
                try {
                    JSONObject o = new JSONObject(new String(resp, StandardCharsets.UTF_8));
                    o.remove("capabilities");
                    resp = o.toString().getBytes(StandardCharsets.UTF_8);
                } catch (JSONException e) {
                    throw new IOException(e);
                }
            }
            reply(ex, code, resp, c.getContentType());
        }

        private static void reply(HttpExchange ex, int code, byte[] body, String type) throws IOException {
            ex.getResponseHeaders().set("Content-Type", type == null ? "application/json" : type);
            ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(body);
                }
            }
        }

        private static byte[] readAll(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        }
    }
}
