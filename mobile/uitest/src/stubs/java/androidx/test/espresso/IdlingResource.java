// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.espresso;

public interface IdlingResource {
    String getName();

    boolean isIdleNow();

    void registerIdleTransitionCallback(ResourceCallback callback);

    interface ResourceCallback {
        void onTransitionToIdle();
    }
}
