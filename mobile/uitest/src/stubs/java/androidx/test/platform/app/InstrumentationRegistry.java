// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.platform.app;

import android.app.Instrumentation;
import android.os.Bundle;

public final class InstrumentationRegistry {
    private static volatile Instrumentation instrumentation;
    private static volatile Bundle arguments = new Bundle();

    private InstrumentationRegistry() {}

    public static void registerInstance(Instrumentation i, Bundle args) {
        instrumentation = i;
        arguments = args == null ? new Bundle() : new Bundle(args);
    }

    public static Instrumentation getInstrumentation() {
        if (instrumentation == null) throw new IllegalStateException("No instrumentation registered");
        return instrumentation;
    }

    public static Bundle getArguments() {
        return new Bundle(arguments);
    }
}
