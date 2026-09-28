// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.runner.lifecycle;

import android.app.Activity;

import java.util.Collection;

public interface ActivityLifecycleMonitor {
    void addLifecycleCallback(ActivityLifecycleCallback callback);

    void removeLifecycleCallback(ActivityLifecycleCallback callback);

    Stage getLifecycleStageOf(Activity activity);

    Collection<Activity> getActivitiesInStage(Stage stage);
}
