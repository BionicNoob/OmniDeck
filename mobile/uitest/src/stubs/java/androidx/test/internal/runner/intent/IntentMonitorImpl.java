// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.internal.runner.intent;

import android.content.Intent;

import androidx.test.runner.intent.IntentMonitor;

public final class IntentMonitorImpl implements IntentMonitor {
    public void signalIntent(Intent intent) {
    }
}
