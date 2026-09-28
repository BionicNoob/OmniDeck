package com.omnideck.mobile;

import com.omnideck.mobile.mock.MockOllama;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Engine model control: silent per-model load/unload, embedding models, a stable active model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 34})
@LooperMode(LooperMode.Mode.PAUSED)
public class EngineModelsTest extends Harness {

    @Test
    public void activeModelSkipsEmbeddingModelsAndStaysPut() throws Exception {
        ollama.clearModels();
        ollama.addModel(new MockOllama.Model("all-embed-first:latest", 270_000_000L, "137M", "F16", false));
        ollama.addModel(new MockOllama.Model("qwen3:8b", 5_225_388_164L, "8.2B", "Q4_K_M", true));
        ollama.addModel(new MockOllama.Model("llama3.2:3b", 2_019_393_189L, "3.2B", "Q4_K_M", false));
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        // Sorted by name the embedding model comes first, but it can't chat.
        assertTrue(engine().isEmbeddingOnly("all-embed-first:latest"));
        assertFalse(engine().isEmbeddingOnly("qwen3:8b"));
        String active = engine().currentModel();
        assertEquals("llama3.2:3b", active);
        assertEquals("the automatic pick is remembered", active, engine().settings.model());

        // Loading another model doesn't move the active one.
        AtomicReference<String> err = new AtomicReference<>("pending");
        engine().setLoaded("qwen3:8b", true, (ms, e) -> err.set(e == null ? "" : e));
        waitFor("loaded", () -> !"pending".equals(err.get()));
        assertEquals("", err.get());
        assertTrue(ollama.isLoaded("qwen3:8b"));
        assertEquals("llama3.2:3b", engine().currentModel());
        assertTrue("logged", engine().telemetry.lastEvent().text.startsWith("Model online · qwen3:8b"));
        assertTrue("no chat notices", engine().conversation().messages.isEmpty());
    }

    @Test
    public void embeddingModelsLoadAndUnloadThroughEmbed() throws Exception {
        ollama.addModel(new MockOllama.Model("nomic-embed-text:latest", 274_000_000L, "137M", "F16", false));
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        AtomicReference<String> err = new AtomicReference<>("pending");
        engine().setLoaded("nomic-embed-text:latest", true, (ms, e) -> err.set(e == null ? "" : e));
        waitFor("loaded", () -> !"pending".equals(err.get()));
        assertEquals("", err.get());
        assertTrue(ollama.isLoaded("nomic-embed-text:latest"));
        assertTrue("no /api/chat for an embedding model", ollama.chatRequests.isEmpty());

        err.set("pending");
        engine().setLoaded("nomic-embed-text:latest", false, (ms, e) -> err.set(e == null ? "" : e));
        waitFor("unloaded", () -> !"pending".equals(err.get()));
        assertFalse(ollama.isLoaded("nomic-embed-text:latest"));

        // /unload also takes the embed route.
        engine().setLoaded("nomic-embed-text:latest", true, (ms, e) -> { });
        waitFor("reloaded", () -> ollama.isLoaded("nomic-embed-text:latest"));
        engine().unload("nomic-embed-text:latest");
        waitFor("unloaded by command", () -> !ollama.isLoaded("nomic-embed-text:latest"));
        assertTrue(ollama.chatRequests.isEmpty());
    }

    @Test
    public void setLoadedReportsErrorsAndOfflineState() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        AtomicReference<String> err = new AtomicReference<>("pending");
        engine().setLoaded("missing:1b", true, (ms, e) -> err.set(e == null ? "" : e));
        waitFor("error", () -> !"pending".equals(err.get()));
        assertFalse(err.get().isEmpty());
        assertTrue(engine().telemetry.lastEvent().text.startsWith("Load failed · missing:1b"));

        ollama.stop();
        waitFor("offline", () -> engine().state() != Engine.State.ONLINE);
        err.set("pending");
        engine().setLoaded("llama3.2:3b", true, (ms, e) -> err.set(e == null ? "" : e));
        idle();
        assertEquals("Not connected to your AI.", err.get());
        ollama = MockOllama.start("127.0.0.1", 0); // for tearDown
    }

    @Test
    public void exactResolverIgnoresPartialMatches() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        assertEquals("qwen3:8b", engine().resolveInstalled("qwen"));
        assertNull(engine().resolveExact("qwen"));
        assertEquals("qwen3:8b", engine().resolveExact("QWEN3:8B"));
        assertEquals("llama3.2:3b", engine().resolveExact("llama3.2:3b"));
        assertNull(engine().resolveExact(" "));
        assertNull(engine().resolveExact(null));
    }
}
