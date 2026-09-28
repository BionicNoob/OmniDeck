package com.omnideck.mobile;

import android.view.View;
import android.widget.FrameLayout;

import com.omnideck.mobile.ui.BootOverlay;
import com.omnideck.mobile.ui.Theme;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The cold-start boot sequence: rendered mid-way in each theme, and it removes itself. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class BootShotTest extends Harness {
    private void frame(int themeId, String name) throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        FrameLayout host = new FrameLayout(act);
        BootOverlay b = new BootOverlay(act, Theme.of(act, themeId));
        b.setProgress(0.62f);
        host.addView(b, new FrameLayout.LayoutParams(-1, -1));
        int w = act.getWindow().getDecorView().getWidth(), h = act.getWindow().getDecorView().getHeight();
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);
        shoot(host, name);
    }

    @Test
    public void frames() throws Exception {
        frame(Theme.CYBER, "boot-cyber");
        frame(Theme.LIGHT, "boot-light");
        frame(Theme.DARK, "boot-dark");
    }

    @Test
    public void playsOnceOnColdStartAndRemovesItself() throws Exception {
        MainActivity.skipBoot = false;
        java.lang.reflect.Field f = MainActivity.class.getDeclaredField("bootShown");
        f.setAccessible(true);
        f.setBoolean(null, false);
        prefs().edit().putString("theme", "cyber").commit();
        ctl = Robolectric.buildActivity(MainActivity.class).setup();
        act = ctl.get();
        advance(3000);
        assertTrue("played", f.getBoolean(null));
        assertNull("overlay gone", button("Starting up, tap to skip"));
        MainActivity.skipBoot = true;
    }
}
