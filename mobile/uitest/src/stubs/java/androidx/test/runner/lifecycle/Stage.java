// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.runner.lifecycle;

public enum Stage { PRE_ON_CREATE, CREATED, STARTED, RESUMED, PAUSED, STOPPED, RESTARTED, DESTROYED }
