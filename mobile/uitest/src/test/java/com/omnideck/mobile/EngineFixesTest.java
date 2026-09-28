package com.omnideck.mobile;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.speech.tts.TextToSpeech;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.Conversation;
import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.core.ReplyError;
import com.omnideck.mobile.core.Timers;
import com.omnideck.mobile.mock.MockOllama;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Engine behaviors from the review fixes: compaction vs. sending, server
 * switches, chat switches mid-reply, token binding, images for text-only
 * models, stoppable model tasks, background notifications, persistent
 * timers, plain-language failures, Wake-on-LAN, lock, chat models, speech.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 34})
@LooperMode(LooperMode.Mode.PAUSED)
public class EngineFixesTest extends Harness {
    static final String PNG_1PX =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private static Application app() {
        return RuntimeEnvironment.getApplication();
    }

    private void chat(String text) {
        int before = engine().conversation().messages.size();
        waitFor("sent " + text, () -> engine().send(text));
        waitFor("reply to " + text, () -> !engine().isBusy()
                && engine().conversation().messages.size() >= before + 2);
    }

    private static List<String> longReply() {
        List<String> t = new ArrayList<>();
        for (int i = 0; i < 400; i++) t.add("word" + i + " ");
        return t;
    }

    private static Conversation saved(String id) {
        return new ConversationStore(new File(app().getFilesDir(), "chats")).load(id);
    }

    private static List<Notification> notifications() {
        NotificationManager nm = (NotificationManager) app().getSystemService(Context.NOTIFICATION_SERVICE);
        return shadowOf(nm).getAllNotifications();
    }

    private static String title(Notification n) {
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        return t == null ? "" : t.toString();
    }

    // ------------------------------------------------------------------
    // Compaction, stop, regenerate
    // ------------------------------------------------------------------

    @Test
    public void compactionHoldsNewMessagesAndKeepsWhatWasAddedMeanwhile() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        chat("one");
        chat("two");
        chat("three");
        ollama.firstTokenDelayMs = 1200;
        engine().compact();
        assertTrue("compaction counts as work", engine().isWorking());
        assertFalse("but it isn't a streaming reply", engine().isBusy());
        assertFalse("sending waits for the compaction", engine().send("sent during compaction"));
        engine().regenerate();
        assertFalse(engine().isBusy());
        engine().notice("Noted while compacting", "info");
        waitFor("compacted", () -> !engine().isWorking());
        ollama.firstTokenDelayMs = 0;

        List<ChatMessage> ms = engine().conversation().messages;
        assertEquals(ChatMessage.SYSTEM, ms.get(0).role);
        assertTrue(ms.get(0).content.startsWith("Summary of the earlier conversation"));
        int compacted = -1, noted = -1;
        for (int i = 0; i < ms.size(); i++) {
            if (ms.get(i).content.startsWith("Compacted ")) compacted = i;
            if (ms.get(i).content.equals("Noted while compacting")) noted = i;
            assertNotEquals("sent during compaction", ms.get(i).content);
        }
        assertTrue("the notice added meanwhile survives, after the summary", noted > compacted && compacted > 0);
        chat("after");
        assertEquals("after", engine().conversation().lastOfRole(ChatMessage.USER).content);
    }

    @Test
    public void stopCancelsCompactionAndBenchmarkAtOnce() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        chat("one");
        chat("two");
        String firstUser = engine().conversation().messages.get(0).content;
        ollama.firstTokenDelayMs = 4000;
        engine().compact();
        assertTrue(engine().isWorking());
        engine().stop();
        assertFalse("stop clears the task right away", engine().isWorking());
        ChatMessage last = engine().conversation().messages.get(engine().conversation().messages.size() - 1);
        assertEquals("Compaction stopped.", last.content);
        assertEquals("warn", last.tone);
        assertTrue(engine().telemetry.lastEvent().text.startsWith("Compaction stopped"));
        // The late answer of the cancelled request changes nothing.
        advance(200);
        Thread.sleep(300);
        idle();
        assertEquals(firstUser, engine().conversation().messages.get(0).content);

        engine().bench();
        assertTrue(engine().isWorking());
        engine().stop();
        assertFalse(engine().isWorking());
        assertEquals("Benchmark stopped.", engine().conversation().lastOfRole(ChatMessage.NOTICE).content);
        ollama.firstTokenDelayMs = 0;
        chat("still works");
    }

    @Test
    public void regenerateRightAfterChangingTheServerDoesNotCrash() throws Exception {
        launch("cyber", MainActivity.TAB_COMMS);
        waitOnline();
        chat("hello");
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int dead = ss.getLocalPort();
        ss.close();
        engine().setServer("127.0.0.1:" + dead);
        assertNotEquals("no longer online while there is no client", Engine.State.ONLINE, engine().state());
        assertEquals(-1, engine().linkUptimeMs());
        engine().regenerate();
        idle();
        assertFalse(engine().isBusy());
        assertEquals("Your AI isn't connected.", ShadowToast.getTextOfLatestToast());
        assertFalse(engine().send("anyone?"));
    }

    @Test
    public void scanPortAndLinkUptime() throws Exception {
        launch("dark", MainActivity.TAB_COMMAND);
        waitOnline();
        assertEquals(ollama.port(), engine().scanPort());
        assertTrue(engine().linkUptimeMs() >= 0);
    }

    // ------------------------------------------------------------------
    // Chat switches mid-reply
    // ------------------------------------------------------------------

    @Test
    public void switchingChatsMidReplyKeepsThePartialReplyInItsOwnChat() throws Exception {
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        ollama.tokenDelayMs = 30;
        ollama.replier = (r, text) -> longReply();
        waitFor("sent", () -> engine().send("tell me a long story"));
        waitFor("streaming", () -> engine().streamingMessage() != null
                && engine().streamingMessage().content.contains("word3"));
        String first = engine().conversation().id;

        engine().newChat();
        assertFalse("the reply ends at once", engine().isBusy());
        assertTrue(engine().conversation().messages.isEmpty());
        waitFor("old chat saved with the partial reply", () -> {
            Conversation c = saved(first);
            ChatMessage a = c == null ? null : c.lastOfRole(ChatMessage.ASSISTANT);
            return a != null && a.stopped && a.content.startsWith("word0 ");
        });
        ChatMessage kept = saved(first).lastOfRole(ChatMessage.ASSISTANT);
        assertEquals("stopped", kept.stats);

        // Same in the other direction: opening a saved chat mid-reply.
        waitFor("sent again", () -> engine().send("another long one"));
        waitFor("streaming again", () -> engine().streamingMessage() != null
                && engine().streamingMessage().content.contains("word2"));
        String second = engine().conversation().id;
        engine().openChat(first);
        assertFalse(engine().isBusy());
        waitFor("reopened", () -> first.equals(engine().conversation().id));
        assertTrue(engine().conversation().lastOfRole(ChatMessage.ASSISTANT).content.startsWith("word0 "));
        waitFor("second chat saved too", () -> {
            Conversation c = saved(second);
            ChatMessage a = c == null ? null : c.lastOfRole(ChatMessage.ASSISTANT);
            return a != null && a.stopped && a.content.startsWith("word0 ");
        });
        // The cancelled streams never write into the chat on screen.
        Thread.sleep(300);
        idle();
        assertEquals(first, engine().conversation().id);
        ollama.replier = null;
    }

    @Test
    public void chatsSwitchBackToTheModelTheyUsed() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        engine().setModel("qwen3:8b");
        chat("hi qwen");
        String qwenChat = engine().conversation().id;
        assertEquals("qwen3:8b", engine().conversation().model);
        engine().newChat();
        engine().setModel("llama3.2:3b");
        chat("hi llama");
        waitFor("saved", () -> saved(qwenChat) != null && "qwen3:8b".equals(saved(qwenChat).model));
        engine().openChat(qwenChat);
        waitFor("reopened", () -> qwenChat.equals(engine().conversation().id));
        assertEquals("qwen3:8b", engine().currentModel());
        assertTrue(engine().telemetry.lastEvent().text.contains("qwen3:8b"));
    }

    // ------------------------------------------------------------------
    // PC bridge: token binding, tools, lock, Wake-on-LAN
    // ------------------------------------------------------------------

    /** A pretend LaunchBridge on another address that records the headers it gets. */
    private static HttpServer recordingBridge(String ip, int port, List<String> tokens, String systemInfo)
            throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName(ip), port), 8);
        s.createContext("/", (HttpExchange ex) -> {
            String tok = ex.getRequestHeaders().getFirst("X-Bridge-Token");
            tokens.add(ex.getRequestURI().getPath() + " " + (tok == null ? "-" : tok));
            String path = ex.getRequestURI().getPath();
            String body = "/health".equals(path) ? "{\"status\":\"ok\",\"apps_indexed\":3}"
                    : "/desk/run".equals(path) ? "{\"ok\":true,\"result\":" + systemInfo + "}"
                    : "{\"tools\":[\"get_system_info\"]}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    public void theBridgeTokenOnlyGoesToThePcThatIssuedIt() throws Exception {
        withBridge(false);
        launch("cyber", MainActivity.TAB_COMMAND);
        waitOnline();
        AtomicReference<String> paired = new AtomicReference<>();
        engine().bridgePair((tok, err) -> paired.set(tok != null ? tok : "error: " + err));
        waitFor("paired", () -> paired.get() != null);
        assertEquals(bridge.token, paired.get());
        assertEquals("127.0.0.1", engine().settings.bridgeTokenHost());
        assertTrue(engine().bridgePaired());

        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer stranger = recordingBridge("127.0.0.2", bridge.port(), seen, "{\"cpu\":\"5%\"}");
        try {
            engine().settings.setBridgeHost("127.0.0.2");
            assertFalse("not paired with this PC", engine().bridgePaired());
            AtomicReference<String> err = new AtomicReference<>();
            engine().bridgeVitals((v, e) -> err.set(e == null ? "ok" : e));
            waitFor("vitals refused locally", () -> err.get() != null);
            assertTrue(err.get(), err.get().contains("paired with the PC bridge at 127.0.0.1"));
            AtomicReference<String> health = new AtomicReference<>();
            engine().bridgeHealth((v, e) -> health.set(e == null ? "ok" : e));
            waitFor("health", () -> health.get() != null);
            assertEquals("health needs no token and still works", "ok", health.get());
            synchronized (seen) {
                assertFalse(seen.isEmpty());
                for (String s : seen) assertTrue("no token for a stranger: " + s, s.endsWith(" -"));
            }
        } finally {
            stranger.stop(0);
        }
        engine().settings.setBridgeHost("");
        assertTrue(engine().bridgePaired());
        AtomicReference<String> ok = new AtomicReference<>();
        engine().bridgeVitals((v, e) -> ok.set(e == null ? "ok" : e));
        waitFor("vitals from the paired PC", () -> ok.get() != null);
        assertEquals("ok", ok.get());

        // A token typed by hand is bound to the bridge host in use.
        engine().setBridgeToken("typed-token");
        assertEquals("127.0.0.1", engine().settings.bridgeTokenHost());
        engine().setBridgeToken("");
        assertFalse(engine().bridgePaired());
        assertEquals("", engine().settings.bridgeTokenHost());
    }

    @Test
    public void legacyTokensBindToTheFirstPcTheyAreUsedWith() throws Exception {
        withBridge(true); // a token saved without a host, like older versions did
        assertEquals("", prefs().getString("bridge_token_host", ""));
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        AtomicReference<String> r = new AtomicReference<>();
        engine().bridgeVitals((v, e) -> r.set(e == null ? "ok" : e));
        waitFor("vitals", () -> r.get() != null);
        assertEquals("ok", r.get());
        assertEquals("127.0.0.1", engine().settings.bridgeTokenHost());
    }

    @Test
    public void bridgeToolsCarryDescriptionsAndLockUsesTheLockTool() throws Exception {
        withBridge(true);
        bridge.rich = true;
        launch("light", MainActivity.TAB_PC);
        waitOnline();
        AtomicReference<List<com.omnideck.mobile.core.BridgeTool>> tools = new AtomicReference<>();
        engine().bridgeTools((t, e) -> tools.set(t));
        waitFor("tools", () -> tools.get() != null);
        assertEquals(8, tools.get().size());
        com.omnideck.mobile.core.BridgeTool vol = com.omnideck.mobile.core.BridgeTool.find(tools.get(), "set_volume");
        assertEquals("Set master volume {level}", vol.description);
        assertEquals("integer", vol.params.get(0).type);
        AtomicReference<List<String>> names = new AtomicReference<>();
        engine().bridgeCapabilities((n, e) -> names.set(n));
        waitFor("names", () -> names.get() != null);
        assertTrue(names.get().contains("lock_screen"));

        AtomicReference<String> locked = new AtomicReference<>();
        engine().lockPc((said, e) -> locked.set(said != null ? said : "error: " + e));
        waitFor("locked", () -> locked.get() != null);
        assertEquals("Workstation locked", locked.get());
        assertTrue(bridge.ranTools.contains("lock_screen"));

        bridge.rich = false;
        locked.set(null);
        engine().lockPc((said, e) -> locked.set(said != null ? said : "error: " + e));
        waitFor("no lock tool", () -> locked.get() != null);
        assertTrue(locked.get(), locked.get().startsWith("error: ") && locked.get().contains("lock"));
    }

    @Test
    public void wakeOnLanSendsTheMagicPacketAndLearnsTheMac() throws Exception {
        launch("dark", MainActivity.TAB_PC);
        AtomicReference<String> r = new AtomicReference<>();
        engine().wakePc((v, e) -> r.set(e == null ? v : "error: " + e));
        idle();
        assertTrue(r.get(), r.get().startsWith("error: ") && r.get().contains("MAC address"));

        // The PC's system info names its MAC: it's remembered for Wake-on-LAN.
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer pc = recordingBridge("127.0.0.1", 0, seen,
                "{\"hostname\":\"ATLAS-PC\",\"cpu\":\"9%\",\"mac_address\":\"3c-7c-3f-12-ab-cd\"}");
        try {
            engine().settings.setBridgePort(pc.getAddress().getPort());
            engine().settings.setBridgeHost("127.0.0.1");
            engine().setBridgeToken("tok");
            AtomicReference<String> v = new AtomicReference<>();
            engine().bridgeVitals((x, e) -> v.set(e == null ? "ok" : e));
            waitFor("vitals", () -> v.get() != null);
            assertEquals("ok", v.get());
            assertEquals("3C:7C:3F:12:AB:CD", engine().settings.pcMac());
        } finally {
            pc.stop(0);
        }

        try (DatagramSocket listener = new DatagramSocket(0)) {
            listener.setSoTimeout(5000);
            Engine.testWolPort = listener.getLocalPort();
            r.set(null);
            engine().wakePc((x, e) -> r.set(e == null ? x : "error: " + e));
            waitFor("sent", () -> r.get() != null);
            assertTrue(r.get(), r.get().startsWith("Wake-up packet sent to 3C:7C:3F:12:AB:CD"));
            byte[] buf = new byte[256];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            listener.receive(p); // the subnet broadcast (127.0.3.15 in tests) loops back here
            assertEquals(102, p.getLength());
            for (int i = 0; i < 6; i++) assertEquals((byte) 0xFF, buf[i]);
            assertEquals((byte) 0x3C, buf[6]);
            assertEquals((byte) 0xCD, buf[101]);
        } finally {
            Engine.testWolPort = 0;
        }
    }

    // ------------------------------------------------------------------
    // Images, failures
    // ------------------------------------------------------------------

    @Test
    public void imagesAreLeftOutForModelsThatCantSeeThem() throws Exception {
        ollama.addModel(new MockOllama.Model("llava:7b", 4_700_000_000L, "7B", "Q4_0", false));
        ollama.addModel(new MockOllama.Model("phi3:mini", 2_200_000_000L, "3.8B", "Q4_0", false));
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        engine().setModel("llava:7b");
        waitFor("vision known", () -> Boolean.TRUE.equals(engine().supportsVision("llava:7b")));
        List<String> imgs = new ArrayList<>();
        imgs.add(PNG_1PX);
        waitFor("sent", () -> engine().send("what is this?", imgs));
        waitFor("reply", () -> !engine().isBusy());
        JSONArray withImage = ollama.lastChatRequest().getJSONArray("messages");
        assertTrue(withImage.getJSONObject(withImage.length() - 1).has("images"));

        // Known text-only model: no images, a marker instead.
        engine().setModel("llama3.2:3b");
        waitFor("text-only known", () -> Boolean.FALSE.equals(engine().supportsVision("llama3.2:3b")));
        chat("and now?");
        JSONArray text = ollama.lastChatRequest().getJSONArray("messages");
        for (int i = 0; i < text.length(); i++) assertFalse(text.getJSONObject(i).has("images"));
        assertTrue(text.getJSONObject(0).getString("content").contains("[An image was attached here."));

        // Unknown capabilities: the request checks /api/show first.
        engine().settings.setModel("phi3:mini");
        assertNull(engine().supportsVision("phi3:mini"));
        chat("and with phi?");
        JSONObject req = ollama.lastChatRequest();
        assertEquals("phi3:mini", req.getString("model"));
        JSONArray phi = req.getJSONArray("messages");
        for (int i = 0; i < phi.length(); i++) assertFalse(phi.getJSONObject(i).has("images"));
        assertFalse(engine().conversation().lastOfRole(ChatMessage.ASSISTANT).error);
        waitFor("details cached", () -> Boolean.FALSE.equals(engine().supportsVision("phi3:mini")));
    }

    @Test
    public void failedRepliesSayWhatWentWrongInPlainWords() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        waitOnline();
        ollama.midStreamError = "model requires more system memory (12.3 GiB) than is available (7.9 GiB)";
        chat("big question");
        ChatMessage oom = engine().conversation().lastOfRole(ChatMessage.ASSISTANT);
        assertTrue(oom.error);
        assertEquals(ReplyError.OUT_OF_MEMORY, oom.errorKind);
        assertTrue(oom.stats, oom.stats.startsWith("The PC ran out of memory for llama3.2:3b."));
        assertTrue("raw error kept for diagnostics", oom.stats.contains("more system memory (12.3 GiB)"));
        ollama.midStreamError = null;

        // The model vanished from the PC since the list was read.
        ollama.clearModels();
        ollama.addModel(new MockOllama.Model("qwen3:8b", 5_225_388_164L, "8.2B", "Q4_K_M", true));
        chat("still there?");
        ChatMessage gone = engine().conversation().lastOfRole(ChatMessage.ASSISTANT);
        assertEquals(ReplyError.MODEL_MISSING, gone.errorKind);
        assertTrue(gone.stats, gone.stats.contains("/pull llama3.2:3b"));
    }

    // ------------------------------------------------------------------
    // Notifications and timers
    // ------------------------------------------------------------------

    @Test
    public void aReplyThatFinishesInTheBackgroundPostsANotification() throws Exception {
        shadowOf(app()).grantPermissions(Notifier.PERMISSION);
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        ollama.tokenDelayMs = 40;
        waitFor("sent", () -> engine().send("ping me when done"));
        ctl.pause().stop(); // the user switches to another app
        waitFor("reply", () -> !engine().isBusy());
        List<Notification> posted = notifications();
        assertEquals(1, posted.size());
        assertEquals("Reply ready · llama3.2:3b", title(posted.get(0)));
        String body = String.valueOf(posted.get(0).extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(body, body.startsWith("You said: ping me when done."));
        assertNotNull(posted.get(0).contentIntent);

        // Back in the app: the notification has served its purpose.
        ctl.start().resume();
        idle();
        assertTrue(notifications().isEmpty());

        // Turned off in Settings: nothing is posted.
        engine().settings.setNotifications(false);
        ollama.tokenDelayMs = 2;
        waitFor("sent", () -> engine().send("quietly"));
        ctl.pause().stop();
        waitFor("reply", () -> !engine().isBusy());
        assertTrue(notifications().isEmpty());
        ctl.start().resume();
    }

    @Test
    public void timersSurviveTheAppBeingClosedAndRingAsANotification() throws Exception {
        shadowOf(app()).grantPermissions(Notifier.PERMISSION);
        Engine e = Engine.get(app());
        e.timer("5m tea");
        assertTrue(e.settings.timers().contains("tea"));
        ShadowAlarmManager alarms = shadowOf((AlarmManager) app().getSystemService(Context.ALARM_SERVICE));
        assertEquals(1, alarms.getScheduledAlarms().size());
        long due = alarms.getScheduledAlarms().get(0).triggerAtTime;
        assertTrue(Math.abs(due - (System.currentTimeMillis() + 300_000)) < 5_000);

        // The process dies; a new one finds the timer again.
        Engine.reset();
        Engine again = Engine.get(app());
        List<Timers.Timer> restored = again.timers();
        assertEquals(1, restored.size());
        assertEquals("tea", restored.get(0).message);

        // Its alarm goes off while the app isn't on screen.
        alarms.fireAlarm(alarms.getScheduledAlarms().get(0));
        idle();
        List<Notification> posted = notifications();
        assertEquals(1, posted.size());
        assertEquals("Time's up", title(posted.get(0)));
        assertEquals("tea", String.valueOf(posted.get(0).extras.getCharSequence(Notification.EXTRA_TEXT)));
        assertTrue(again.timers().isEmpty());
        assertFalse(again.settings.timers().contains("tea"));
        assertTrue(again.conversation().lastOfRole(ChatMessage.NOTICE).content.contains("tea"));
        assertTrue("no alarm left behind", alarms.getScheduledAlarms().isEmpty());

        // Cancelling removes the saved timers and their alarms.
        again.timer("10m eggs");
        again.timer("20m bread");
        assertEquals(2, alarms.getScheduledAlarms().size());
        again.timer("cancel");
        assertTrue(again.timers().isEmpty());
        assertTrue(alarms.getScheduledAlarms().isEmpty());
        assertEquals("[]", again.settings.timers());
    }

    // ------------------------------------------------------------------
    // API key, speech
    // ------------------------------------------------------------------

    @Test
    public void theApiKeyGoesOnlyToTheTypedServer() throws Exception {
        ollama.requiredKey = "s3cret";
        prefs().edit().putString("api_key", "s3cret").commit();
        launch("dark", MainActivity.TAB_COMMS);
        waitOnline();
        chat("with a key");
        synchronized (ollama.authSeen) {
            for (String s : ollama.authSeen) assertTrue(s, s.endsWith("Bearer s3cret"));
        }
        engine().setApiKey("wrong");
        waitFor("refused", () -> engine().state() == Engine.State.OFFLINE);
        assertTrue(engine().stateDetail(), engine().stateDetail().contains("API key"));
    }

    @Test
    public void speechProblemsAreReportedInsteadOfSilence() throws Exception {
        launch("light", MainActivity.TAB_COMMS);
        engine().speakNow("Hello there.");
        TextToSpeech tts = ShadowTextToSpeech.getLastTextToSpeechInstance();
        assertNotNull(tts);
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.ERROR);
        idle();
        assertFalse(engine().speechAvailable());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("text-to-speech"));

        // Asking again tries again: an engine without any voice for the language says so too.
        ShadowToast.reset();
        engine().speakNow("Hello again.");
        TextToSpeech second = ShadowTextToSpeech.getLastTextToSpeechInstance();
        assertNotEquals(tts, second);
        shadowOf(second).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        idle();
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("No text-to-speech voice"));

        // With a voice installed it speaks, falling back to English.
        ShadowTextToSpeech.addLanguageAvailability(Locale.US);
        engine().speakNow("Third time lucky.");
        TextToSpeech third = ShadowTextToSpeech.getLastTextToSpeechInstance();
        shadowOf(third).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        idle();
        assertTrue(engine().speechAvailable());
        assertEquals("Third time lucky.", shadowOf(third).getLastSpokenText());
    }
}
