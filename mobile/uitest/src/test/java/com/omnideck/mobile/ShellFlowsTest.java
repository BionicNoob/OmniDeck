package com.omnideck.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import android.os.Parcelable;
import android.speech.RecognizerIntent;
import android.view.View;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.mock.MockBridge;
import com.omnideck.mobile.mock.MockOllama;
import com.omnideck.mobile.ui.Widgets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * The shell's side of the app: what other apps and launcher shortcuts hand
 * in, results that arrive after a recreate, the unread dot, the link banner,
 * the top-bar pulse in the background and the notifications permission.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShellFlowsTest extends Harness {
    private static final byte[] PNG = Base64.getDecoder().decode(MockBridge.PNG_1PX);

    private ChatMessage last(String role) {
        return engine().conversation().lastOfRole(role);
    }

    private void chatAndWait(String text) {
        int before = engine().conversation().messages.size();
        submit(text);
        waitFor("reply to " + text, () -> !engine().isBusy()
                && engine().conversation().messages.size() >= before + 2);
    }

    /** A content:// Uri another app would share, serving {@code bytes} (fresh stream each open). */
    private static Uri shared(String name, byte[] bytes) {
        Uri u = Uri.parse("content://com.example.gallery/" + name);
        shadowOf(RuntimeEnvironment.getApplication().getContentResolver())
                .registerInputStreamSupplier(u, () -> new ByteArrayInputStream(bytes));
        return u;
    }

    private void start(Intent intent, String theme, int tab) {
        prefs().edit().putString("theme", theme).putInt("last_tab", tab).commit();
        ctl = Robolectric.buildActivity(MainActivity.class, intent).setup();
        act = ctl.get();
        idle();
    }

    private static Intent shortcut(String id) {
        return new Intent(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_SHORTCUT, id);
    }

    // ------------------------------------------------------------------
    // Shares from other apps
    // ------------------------------------------------------------------

    @Test
    public void sharedTextLandsInTheComposerOnlyOnce() throws Exception {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Summarize this article");
        start(share, "dark", MainActivity.TAB_COMMAND);
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        assertEquals("Summarize this article", composer().getText().toString());
        assertEquals("handled once", Intent.ACTION_MAIN, act.getIntent().getAction());

        // A recreate (rotation, dark mode) with saved state doesn't insert it again.
        ctl.recreate();
        act = ctl.get();
        idle();
        assertEquals("Summarize this article", composer().getText().toString());

        // The user sends something else; later the process dies and the task is
        // reopened from Recents, which replays the task's original SEND intent.
        composer().setText("");
        idle();
        ctl.pause().stop();
        ctl.destroy();
        ctl = null;
        CommsActionsTest.killProcess();
        Intent replay = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Summarize this article")
                .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        start(replay, "dark", MainActivity.TAB_COMMS);
        assertEquals("not inserted again", "", composer().getText().toString());

        // A share while the app is open goes in at the cursor.
        composer().setText("Look:");
        composer().setSelection(composer().length());
        ctl.newIntent(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "this"));
        idle();
        assertEquals("Look: this", composer().getText().toString());
    }

    @Test
    public void sharedImagesAndTextFilesBecomeComposerContent() throws Exception {
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false));
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        engine().setModel("llava:7b");
        waitFor("vision", () -> Boolean.TRUE.equals(engine().supportsVision("llava:7b")));

        ctl.newIntent(new Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, shared("photo1.png", PNG)));
        waitFor("one attachment", () -> act.comms().pendingImages().size() == 1);
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());

        ArrayList<Parcelable> two = new ArrayList<>();
        two.add(shared("photo2.png", PNG));
        two.add(shared("photo3.png", PNG));
        ctl.newIntent(new Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, two));
        waitFor("three attachments", () -> act.comms().pendingImages().size() == 3);
        advance(200);
        shoot("comms-light-shared-images");

        submit("what are these?");
        waitFor("reply", () -> !engine().isBusy() && last(ChatMessage.ASSISTANT) != null);
        assertEquals(3, last(ChatMessage.USER).images.size());

        // A shared .txt file (EXTRA_STREAM, no EXTRA_TEXT) is read into the composer.
        ctl.newIntent(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, shared("notes.txt",
                        "meeting notes: ship v2 on Friday".getBytes(StandardCharsets.UTF_8))));
        waitFor("file text", () -> composer().getText().toString().contains("ship v2 on Friday"));
    }

    // ------------------------------------------------------------------
    // Launcher shortcuts
    // ------------------------------------------------------------------

    @Test
    public void launcherShortcutsArePublishedAndOpenTheRightPlace() throws Exception {
        java.lang.reflect.Field published = MainActivity.class.getDeclaredField("shortcutsPublished");
        published.setAccessible(true);
        published.setBoolean(null, false);
        withBridge(true);
        launch("cyber", MainActivity.TAB_COMMAND);
        ShortcutManager sm = act.getSystemService(ShortcutManager.class);
        List<ShortcutInfo> list = sm.getDynamicShortcuts();
        assertEquals(4, list.size());
        List<String> ids = new ArrayList<>();
        for (ShortcutInfo s : list) ids.add(s.getId());
        assertTrue(ids.containsAll(java.util.Arrays.asList(MainActivity.SHORTCUT_TALK,
                MainActivity.SHORTCUT_NEW_CHAT, MainActivity.SHORTCUT_SCREENSHOT, MainActivity.SHORTCUT_COMMAND)));
        waitOnline();

        tab(MainActivity.TAB_COMMS);
        chatAndWait("hello");
        tab(MainActivity.TAB_MODELS);
        ctl.newIntent(shortcut(MainActivity.SHORTCUT_NEW_CHAT));
        idle();
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        assertTrue("a fresh chat", engine().conversation().messages.isEmpty());

        ctl.newIntent(shortcut(MainActivity.SHORTCUT_COMMAND));
        idle();
        assertEquals(MainActivity.TAB_COMMAND, act.currentTab());

        ctl.newIntent(shortcut(MainActivity.SHORTCUT_SCREENSHOT));
        idle();
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        waitFor("screenshot", () -> last(ChatMessage.NOTICE) != null && last(ChatMessage.NOTICE).image.length() > 0);

        ctl.newIntent(shortcut(MainActivity.SHORTCUT_TALK));
        idle();
        ShadowActivity.IntentForResult r = shadowOf(act).getNextStartedActivityForResult();
        assertNotNull("the recognizer opens", r);
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, r.intent.getAction());
    }

    @Test
    @Config(sdk = 23)
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    public void shortcutExtrasRouteOnOldPhonesToo() throws Exception {
        // API 23 has no ShortcutManager (nothing is published), but a shortcut
        // pinned by an older launcher still carries the extra.
        start(shortcut(MainActivity.SHORTCUT_COMMAND), "light", MainActivity.TAB_COMMS);
        assertEquals(MainActivity.TAB_COMMAND, act.currentTab());
    }

    // ------------------------------------------------------------------
    // Results that arrive after the activity was recreated
    // ------------------------------------------------------------------

    @Test
    public void voiceAndPhotoResultsSurviveARecreate() throws Exception {
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false));
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        engine().setModel("llava:7b");
        waitFor("vision", () -> Boolean.TRUE.equals(engine().supportsVision("llava:7b")));
        act.commander().run("/voice");
        idle();
        ShadowActivity.IntentForResult r = shadowOf(act).getNextStartedActivityForResult();
        assertEquals(MainActivity.REQ_VOICE, r.requestCode);
        // Dark mode flips while the recognizer is up: a new activity gets the result.
        ctl.recreate();
        act = ctl.get();
        idle();
        Intent heard = new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,
                new ArrayList<>(Collections.singletonList("status report")));
        act.onActivityResult(MainActivity.REQ_VOICE, Activity.RESULT_OK, heard);
        waitFor("sent", () -> last(ChatMessage.USER) != null && "status report".equals(last(ChatMessage.USER).content));
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        waitFor("reply", () -> !engine().isBusy());

        act.onActivityResult(MainActivity.REQ_IMAGE, Activity.RESULT_OK,
                new Intent().setData(shared("picked.png", PNG)));
        waitFor("attached", () -> act.comms().pendingImages().size() == 1);
    }

    // ------------------------------------------------------------------
    // Unread dot, banner, background pulse, notifications
    // ------------------------------------------------------------------

    @Test
    public void unreadDotClearsWhenSettingsCloseOnTheChat() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        ollama.tokenDelayMs = 40;
        submit("a long answer please");
        act.openSettings();
        idle();
        waitFor("reply", () -> !engine().isBusy());
        assertTrue("finished while Settings covered the chat", act.hasBadge(MainActivity.TAB_COMMS));
        act.onBackPressed();
        idle();
        assertEquals(MainActivity.TAB_COMMS, act.currentTab());
        assertFalse(act.hasBadge(MainActivity.TAB_COMMS));
    }

    @Test
    public void linkLostBannerOnTheFirstStepAwayFromOnline() throws Exception {
        launch("light", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(3500); // the "Link established" banner times out
        ollama.stop();
        waitFor("reconnecting", () -> engine().state() != Engine.State.ONLINE);
        assertEquals(Engine.State.SEARCHING, engine().state());
        assertTrue(shows("Link lost"));
    }

    @Test
    public void topBarPulseStopsInTheBackground() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        Widgets.StatusDot dot = null;
        View pill = button("Connection status");
        List<View> all = new ArrayList<>();
        collect(pill, all);
        for (View v : all) {
            if (v instanceof Widgets.StatusDot) dot = (Widgets.StatusDot) v;
        }
        assertNotNull(dot);
        assertTrue(dot.isPulsing());
        assertTrue(dot.isAnimating());
        ctl.pause().stop();
        idle();
        assertFalse("no frames while the app is in the background", dot.isAnimating());
        ctl.start().resume();
        idle();
        assertTrue(dot.isPulsing());
        assertTrue(dot.isAnimating());
    }

    @Test
    public void theFirstMessageAsksForNotificationsOnce() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        assertNull(shadowOf(act).getLastRequestedPermission());
        chatAndWait("hi");
        ShadowActivity.PermissionsRequest p = shadowOf(act).getLastRequestedPermission();
        assertNotNull(p);
        assertEquals("android.permission.POST_NOTIFICATIONS", p.requestedPermissions[0]);
        assertEquals(MainActivity.REQ_NOTIFY, p.requestCode);
        assertTrue(act.getSharedPreferences("omnideck-shell", 0).getBoolean("notify_asked", false));
    }

    @Test
    public void noNotificationPromptWhenTheyreOff() throws Exception {
        prefs().edit().putBoolean("notifications", false).commit();
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        chatAndWait("hi");
        assertNull(shadowOf(act).getLastRequestedPermission());
    }

    /** The narrow end (360dp): a long address loses its middle in the top bar, never its port. */
    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    public void topBarAddressKeepsItsPortOnANarrowPhone() throws Exception {
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        advance(3500);
        String full = "127.0.0.1:" + ollama.port();
        android.widget.TextView addr = textView(full);
        assertNotNull(addr);
        shoot("shell-cyber-narrow-topbar");
        assertEquals(full, addr.getText().toString());
        android.text.Layout l = addr.getLayout();
        int cut = l.getEllipsisCount(0);
        int portAt = full.indexOf(':');
        assertTrue("the port stays visible", cut == 0 || l.getEllipsisStart(0) + cut <= portAt);
    }
}
