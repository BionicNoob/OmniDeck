// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.runner.lifecycle;

public final class ApplicationLifecycleMonitorRegistry {
    private static volatile ApplicationLifecycleMonitor instance;

    private ApplicationLifecycleMonitorRegistry() {}

    public static ApplicationLifecycleMonitor getInstance() {
        if (instance == null) throw new IllegalStateException("No application monitor registered");
        return instance;
    }

    public static void registerInstance(ApplicationLifecycleMonitor monitor) {
        instance = monitor;
    }
}
