// Minimal stand-in for androidx.test (only on Google Maven, unreachable from this
// build machine). Implements just what Robolectric calls; test-only code.
package androidx.test.espresso;

import android.os.Looper;

import java.util.Collection;
import java.util.Collections;

/** Nothing registered: these tests don't use Espresso idling resources. */
public final class IdlingRegistry {
    private static final IdlingRegistry INSTANCE = new IdlingRegistry();

    public static IdlingRegistry getInstance() {
        return INSTANCE;
    }

    public Collection<IdlingResource> getResources() {
        return Collections.emptyList();
    }

    public Collection<Looper> getLoopers() {
        return Collections.emptyList();
    }
}
