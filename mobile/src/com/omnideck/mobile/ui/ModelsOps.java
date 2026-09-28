package com.omnideck.mobile.ui;

import com.omnideck.mobile.Engine;

/**
 * Loads a specific model into the PC's memory, or releases it, without
 * touching the active model or posting chat notices (the model bay reports
 * in place). Callbacks arrive on the main thread. Also names the operations
 * the bay tracks per model while they run.
 */
public final class ModelsOps {
    private ModelsOps() {}

    /** Operation in flight on a model: loading it into memory. */
    public static final String LOAD = "load";
    /** Operation in flight on a model: releasing its memory. */
    public static final String UNLOAD = "unload";
    /** Operation in flight on a model: deleting it from the PC's disk. */
    public static final String DELETE = "delete";

    public interface Done {
        /** Called on the main thread; error is null on success. */
        void done(long elapsedMs, String error);
    }

    /**
     * Loads (unload=false) or unloads a model on the connected server via
     * {@link Engine#setLoaded}, which also logs it, records the load time and
     * refreshes the model list before calling back.
     */
    public static void setLoaded(Engine e, final String model, final boolean unload, final Done cb) {
        e.setLoaded(model, !unload, new Engine.Callback<Long>() {
            @Override
            public void done(Long ms, String error) {
                cb.done(ms == null ? 0 : ms, error);
            }
        });
    }
}
