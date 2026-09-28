// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.internal.runner.lifecycle;

import android.app.Activity;

import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.Stage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tracks each activity's current lifecycle stage and notifies callbacks. */
public final class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    private final Map<Activity, Stage> stages = new WeakHashMap<Activity, Stage>();
    private final List<ActivityLifecycleCallback> callbacks = new CopyOnWriteArrayList<ActivityLifecycleCallback>();

    public ActivityLifecycleMonitorImpl() {}

    public ActivityLifecycleMonitorImpl(boolean declawThreadCheck) {}

    public void signalLifecycleChange(Stage stage, Activity activity) {
        synchronized (stages) {
            if (stage == Stage.DESTROYED) stages.remove(activity);
            else stages.put(activity, stage);
        }
        for (ActivityLifecycleCallback c : callbacks) c.onActivityLifecycleChanged(activity, stage);
    }

    @Override
    public void addLifecycleCallback(ActivityLifecycleCallback callback) {
        callbacks.add(callback);
    }

    @Override
    public void removeLifecycleCallback(ActivityLifecycleCallback callback) {
        callbacks.remove(callback);
    }

    @Override
    public Stage getLifecycleStageOf(Activity activity) {
        synchronized (stages) {
            Stage s = stages.get(activity);
            if (s == null) throw new IllegalArgumentException("Unknown activity: " + activity);
            return s;
        }
    }

    @Override
    public Collection<Activity> getActivitiesInStage(Stage stage) {
        List<Activity> out = new ArrayList<Activity>();
        synchronized (stages) {
            for (Map.Entry<Activity, Stage> e : stages.entrySet()) {
                if (e.getValue() == stage) out.add(e.getKey());
            }
        }
        return out;
    }
}
