package com.omnideck.mobile.core;

import java.net.HttpURLConnection;

/**
 * Cancellation handle for a blocking request. Cancelling closes the
 * underlying connection, which unblocks a read in progress immediately and
 * makes Ollama stop generating (it aborts when the client goes away).
 */
public final class Cancellable {
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;

    public boolean isCancelled() {
        return cancelled;
    }

    /** Safe to call from any thread, any number of times. */
    public void cancel() {
        cancelled = true;
        final HttpURLConnection c = connection;
        if (c != null) {
            // disconnect() closes a socket; keep that off the caller's
            // (possibly main) thread.
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }, "omni-cancel");
            t.setDaemon(true);
            t.start();
        }
    }

    void attach(HttpURLConnection c) {
        connection = c;
        if (cancelled) {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    void detach() {
        connection = null;
    }
}
