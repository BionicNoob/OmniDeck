package com.omnideck.mobile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Screenshots of every tab (and Settings) in each theme → build/screens/shell-{theme}-{tab}.png. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShellShotsTest extends Harness {
    private void all(String theme) throws Exception {
        withBridge(true);
        launch(theme, MainActivity.TAB_COMMAND);
        waitOnline();
        advance(600);
        for (int i = 0; i < MainActivity.TAB_NAMES.length; i++) {
            tab(i);
            advance(400);
            shoot("shell-" + theme + "-" + MainActivity.TAB_NAMES[i].toLowerCase(java.util.Locale.US));
        }
        tab(MainActivity.TAB_COMMS);
        submit("Status report, please.");
        waitFor("reply", () -> !engine().isBusy() && engine().conversation().messages.size() >= 2);
        advance(300);
        shoot("shell-" + theme + "-comms-chat");
        act.openSettings();
        advance(300);
        shoot("shell-" + theme + "-settings");
    }

    @Test
    public void cyber() throws Exception {
        all("cyber");
        assertTrue(shows("ONLINE"));
    }

    @Test
    public void light() throws Exception {
        all("light");
    }

    @Test
    public void dark() throws Exception {
        all("dark");
    }

    @Test
    public void navigatesEveryTab() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        for (String name : MainActivity.TAB_NAMES) {
            click(name);
            assertNotNull(act);
        }
        click("Settings");
        click("Settings");
    }
}
