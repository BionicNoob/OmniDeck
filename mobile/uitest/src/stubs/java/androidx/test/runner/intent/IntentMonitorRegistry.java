// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.runner.intent;

public final class IntentMonitorRegistry {
    private static volatile IntentMonitor instance;

    private IntentMonitorRegistry() {}

    public static void registerInstance(IntentMonitor monitor) {
        instance = monitor;
    }

    public static IntentMonitor getInstance() {
        return instance;
    }
}
