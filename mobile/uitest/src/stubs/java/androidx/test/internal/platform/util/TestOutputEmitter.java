// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.internal.platform.util;

public final class TestOutputEmitter {
    private TestOutputEmitter() {}

    public static void dumpThreadStates(String outputFileName) {
    }
}
