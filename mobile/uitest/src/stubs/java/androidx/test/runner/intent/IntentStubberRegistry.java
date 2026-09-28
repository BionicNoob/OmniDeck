// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.runner.intent;

/** No intent stubbing (Espresso-Intents) in these tests. */
public final class IntentStubberRegistry {
    private IntentStubberRegistry() {}

    public static boolean isLoaded() {
        return false;
    }

    public static IntentStubber getInstance() {
        throw new IllegalStateException("No IntentStubber loaded");
    }
}
