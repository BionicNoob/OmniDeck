// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.runner.lifecycle;

public interface ApplicationLifecycleMonitor {
    void addLifecycleCallback(ApplicationLifecycleCallback callback);

    void removeLifecycleCallback(ApplicationLifecycleCallback callback);
}
