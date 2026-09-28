package com.omnideck.mobile.core;

import org.junit.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReplyErrorTest {

    private static String kind(String raw) {
        return ReplyError.explain(raw, "llama3.2:3b", false).kind;
    }

    @Test
    public void missingModelSuggestsPullingIt() {
        ReplyError e = ReplyError.explain("model \"qwen3:14b\" not found, try pulling it first (HTTP 404)",
                "qwen3:14b", false);
        assertEquals(ReplyError.MODEL_MISSING, e.kind);
        assertTrue(e.message, e.message.contains("`/pull qwen3:14b`"));
        assertTrue(e.message, e.message.contains("pick another model"));
        // The name comes from the error when the server names it.
        assertTrue(ReplyError.explain("model 'mistral' not found", "x", false).message.contains("**mistral**"));
    }

    @Test
    public void imagesRejectedMeansTheModelCantSee() {
        assertEquals(ReplyError.NO_VISION, ReplyError.explain(
                "this model is missing data required for image input (HTTP 500)", "llama3.2:3b", true).kind);
        assertEquals(ReplyError.NO_VISION, ReplyError.explain("model does not support images", "m", false).kind);
        // A plain "bad request" only points at images when the request carried some.
        assertEquals(ReplyError.NO_VISION, ReplyError.explain("invalid request (HTTP 400)", "m", true).kind);
        assertEquals(ReplyError.OTHER, ReplyError.explain("invalid request (HTTP 400)", "m", false).kind);
        assertTrue(ReplyError.explain("x (HTTP 400)", "llama3.2:3b", true).message.contains("can't see images"));
    }

    @Test
    public void memoryCrashesAndServerErrors() {
        assertEquals(ReplyError.OUT_OF_MEMORY, kind("model requires more system memory (12.3 GiB) than is available "
                + "(7.9 GiB) (HTTP 500)"));
        assertEquals(ReplyError.OUT_OF_MEMORY, kind("llama runner process has terminated: cudaMalloc failed: out of "
                + "memory"));
        assertEquals(ReplyError.OUT_OF_MEMORY, kind("CUDA error: OOM"));
        assertEquals("'room' is not an out-of-memory hint", ReplyError.OTHER, kind("no room at the inn"));
        assertEquals(ReplyError.CRASHED, kind("llama runner process has terminated: exit status 2"));
        assertEquals(ReplyError.SERVER, kind("internal error (HTTP 502)"));
        assertEquals(ReplyError.UNAUTHORIZED, kind("unauthorized (HTTP 401)"));
    }

    @Test
    public void networkFailuresUseTheClientsOwnWording() {
        assertEquals(ReplyError.DROPPED, kind("The connection closed before the reply finished."));
        assertEquals(ReplyError.DROPPED, kind("Connection to 10.0.0.2:11434 failed: unexpected end of stream"));
        assertEquals(ReplyError.DROPPED, kind("Connection to 10.0.0.2:11434 failed: Connection reset"));
        assertEquals(ReplyError.DROPPED, kind("unexpected EOF"));
        assertEquals(ReplyError.TIMEOUT, kind(OllamaClient.describe(new SocketTimeoutException(), "10.0.0.2",
                11434)));
        assertEquals(ReplyError.UNREACHABLE, kind(OllamaClient.describe(new ConnectException("refused"),
                "10.0.0.2", 11434)));
        assertEquals(ReplyError.UNREACHABLE, kind("Not connected to your AI."));
    }

    @Test
    public void statsKeepTheRawErrorForDiagnostics() {
        ReplyError e = ReplyError.explain("model \"qwen3:14b\" not found, try pulling it first (HTTP 404)",
                "qwen3:14b", false);
        String s = e.stats();
        assertTrue(s, s.startsWith("qwen3:14b isn't installed on the PC."));
        assertFalse("footers are plain text", s.contains("**") || s.contains("`"));
        assertTrue(s, s.endsWith(" · model \"qwen3:14b\" not found, try pulling it first (HTTP 404)"));
        // Unknown errors are shown as they are.
        assertEquals("weird failure", ReplyError.explain("weird failure", "m", false).stats());
        assertEquals(ReplyError.OTHER, ReplyError.explain(null, "m", false).kind);
        assertTrue(ReplyError.explain("", "m", false).message.length() > 0);
    }

    @Test
    public void downloadFailures() {
        ReplyError e = ReplyError.explainPull("pull model manifest: file does not exist (HTTP 500)", "missing-model");
        assertEquals(ReplyError.NOT_IN_LIBRARY, e.kind);
        assertTrue(e.message, e.message.contains("ollama.com/library"));
        assertEquals(ReplyError.DISK_FULL, ReplyError.explainPull("write /models/blobs: no space left on device",
                "llama3").kind);
        assertEquals(ReplyError.DROPPED, ReplyError.explainPull("The connection closed before the download finished.",
                "llama3").kind);
        assertEquals("a b", ReplyError.plain("**a** `b`"));
    }
}
