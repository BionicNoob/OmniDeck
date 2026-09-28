// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.internal.runner.lifecycle;

import android.app.Application;

import androidx.test.runner.lifecycle.ApplicationLifecycleCallback;
import androidx.test.runner.lifecycle.ApplicationLifecycleMonitor;
import androidx.test.runner.lifecycle.ApplicationStage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ApplicationLifecycleMonitorImpl implements ApplicationLifecycleMonitor {
    private final List<ApplicationLifecycleCallback> callbacks = new CopyOnWriteArrayList<ApplicationLifecycleCallback>();

    public void signalLifecycleChange(Application app, ApplicationStage stage) {
        for (ApplicationLifecycleCallback c : callbacks) c.onApplicationLifecycleChanged(app, stage);
    }

    @Override
    public void addLifecycleCallback(ApplicationLifecycleCallback callback) {
        callbacks.add(callback);
    }

    @Override
    public void removeLifecycleCallback(ApplicationLifecycleCallback callback) {
        callbacks.remove(callback);
    }
}
