// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.platform.ui;

public class InjectEventSecurityException extends Exception {
    private static final long serialVersionUID = 1L;

    public InjectEventSecurityException(String message) {
        super(message);
    }
}
