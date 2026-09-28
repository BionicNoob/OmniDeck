// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.runner.lifecycle;

import android.app.Application;

public interface ApplicationLifecycleCallback {
    void onApplicationLifecycleChanged(Application app, ApplicationStage stage);
}
