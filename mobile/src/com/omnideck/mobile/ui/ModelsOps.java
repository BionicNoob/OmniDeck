package com.omnideck.mobile.ui;

import android.os.Handler;
import android.os.Looper;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.core.Http;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.ServerInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Loads a specific model into the PC's memory, or releases it, without
 * touching the active model. ({@code Engine.warm()} only warms the current
 * model and posts chat notices; the model bay reports in place instead.)
 * The request runs on a background thread and reports on the main thread.
 */
public final class ModelsOps {
    private ModelsOps() {}

    /** Context size used when nothing else is known (matches the Engine's default). */
    static final int DEFAULT_CTX = 8192;

    public interface Done {
        /** Called on the main thread; error is null on success. */
        void done(long elapsedMs, String error);
    }

    /** Loads (unload=false) or unloads a model on the connected server. */
    public static void setLoaded(Engine e, final String model, final boolean unload, final Done cb) {
        final ServerInfo s = e.server();
        final Handler main = new Handler(Looper.getMainLooper());
        if (s == null || e.state() != Engine.State.ONLINE) {
            cb.done(0, "Not connected to your AI.");
            return;
        }
        final Object keepAlive = unload ? Integer.valueOf(0)
                : e.settings.keepLoaded() ? Integer.valueOf(-1) : "5m";
        final JSONObject options = unload ? null : runnerOptions(e, model);
        OllamaClient.ModelDetails d = e.details(model);
        // Embedding models have no "completion" capability, so /api/chat refuses them.
        final boolean embedding = d != null && d.supports("embedding") && !d.supports("completion");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long ms = 0;
                String err = null;
                try {
                    if (embedding) ms = viaEmbed(s, model, keepAlive);
                    else ms = new OllamaClient(s.host, s.port).loadModel(model, keepAlive, options);
                } catch (IOException ex) {
                    err = ex.getMessage() == null ? "request failed" : ex.getMessage();
                }
                final long fMs = ms;
                final String fErr = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(fMs, fErr);
                    }
                });
            }
        }, "omni-models");
        t.setDaemon(true);
        t.start();
    }

    /** Loads / unloads an embedding model: POST /api/embed with no input and a keep_alive. */
    static long viaEmbed(ServerInfo s, String model, Object keepAlive) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("model", model);
            b.put("input", new JSONArray());
            b.put("keep_alive", keepAlive);
        } catch (JSONException ex) {
            throw new IOException(ex.getMessage());
        }
        long t0 = System.nanoTime();
        Http.Response r = Http.postJson(s.baseUrl() + "/api/embed", b.toString(), 4000, 5 * 60 * 1000, null);
        if (!r.ok()) {
            String msg = "";
            try {
                msg = new JSONObject(r.body).optString("error", "");
            } catch (JSONException ignored) {
            }
            throw new IOException((msg.length() > 0 ? msg + " " : "") + "(HTTP " + r.code + ")");
        }
        return (System.nanoTime() - t0) / 1000000L;
    }

    /** num_ctx / num_thread exactly as chat will send them, so Ollama doesn't reload on the first reply. */
    static JSONObject runnerOptions(Engine e, String model) {
        JSONObject o = new JSONObject();
        try {
            int ctx = e.settings.numCtx();
            if (ctx <= 0) {
                ModelInfo r = e.runningInfo(model);
                ctx = r != null && r.contextLength > 0 ? r.contextLength : DEFAULT_CTX;
            }
            o.put("num_ctx", ctx);
            if (e.settings.numThread() > 0) o.put("num_thread", e.settings.numThread());
        } catch (JSONException ignored) {
        }
        return o;
    }
}
