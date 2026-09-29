package com.omnideck.mobile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.omnideck.mobile.core.BridgeClient;
import com.omnideck.mobile.core.BridgeTool;
import com.omnideck.mobile.core.Cancellable;
import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.ChatStats;
import com.omnideck.mobile.core.Conversation;
import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.core.HostPort;
import com.omnideck.mobile.core.LanScanner;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.ReplyError;
import com.omnideck.mobile.core.ServerInfo;
import com.omnideck.mobile.core.SpeechText;
import com.omnideck.mobile.core.Telemetry;
import com.omnideck.mobile.core.Timers;
import com.omnideck.mobile.core.ToolApproval;
import com.omnideck.mobile.core.ToolCall;
import com.omnideck.mobile.core.ToolKit;
import com.omnideck.mobile.core.Vitals;
import com.omnideck.mobile.core.WakeOnLan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Everything that isn't drawing: finding the AI on the network, keeping the
 * connection healthy, streaming replies, commands, and saving chats. Lives
 * for the whole process so a reply keeps streaming across screen rotation
 * or theme changes. All public methods must be called on the main thread;
 * listener callbacks arrive on the main thread.
 */
public final class Engine {
    public enum State { SEARCHING, ONLINE, OFFLINE }

    public interface Listener {
        void onStateChanged();

        /** The whole conversation changed (new/opened/compacted chat). */
        void onConversationReplaced();

        void onMessageAdded(ChatMessage m);

        void onMessageChanged(ChatMessage m);

        void onMessageRemoved(ChatMessage m);

        void onBusyChanged();

        void onInsertText(String text);

        void onToast(String text);

        /** New telemetry samples (latency, reply speed, …). */
        void onTelemetry();

        /** A new entry in the system log. */
        void onLog(Telemetry.Event e);

        /** Model download progress changed (see {@link #pullState()}). */
        void onPull();

        /**
         * The AI wants to run a PC tool that needs the user's OK. Show it and
         * answer through {@code request} (allow / allow for this chat / deny);
         * the Engine may withdraw it (see {@link ToolApproval#setOnSettled}).
         * Return false when it can't be shown right now: it is then declined
         * and the model is told the user wasn't available.
         */
        boolean onToolApproval(ToolApproval request);
    }

    public interface Callback<T> {
        /** Exactly one of value/error is non-null. Called on the main thread. */
        void done(T value, String error);
    }

    static final int FLUSH_MS = 40;
    static final int HEALTH_MS = 10000;
    static final int DEFAULT_CTX = 8192;
    /** How long a PC action may wait for approval while the app is in the background. */
    static final long APPROVAL_BACKGROUND_MS = 60000;
    /** A cached tool list older than this is re-read in the background (it's used meanwhile). */
    static final long TOOL_CATALOG_TTL_MS = 10 * 60 * 1000;
    /** Screenshots a tool returns are kept (and sent to vision models) at most this many pixels wide or tall. */
    static final int TOOL_IMAGE_MAX_SIDE = 1280;
    static final String COMPACT_PROMPT = "Summarize our conversation so far into a compact briefing that keeps every "
            + "fact, decision, name, number and open question needed to continue it. Write it as notes, not as a reply.";
    static final String SUMMARIZE_PROMPT = "Summarize our conversation so far in a few short bullet points.";
    private static final Pattern HARD = Pattern.compile("(?i)\\b(prove|proof|derive|step[- ]by[- ]step|debug|refactor|"
            + "analy[sz]e|algorithm|optimi[sz]e|calculate|complexity|reason through|in detail|compare|trade-?offs?)\\b");

    private static Engine instance;

    /** Tests: scan these subnets instead of the device's real ones. */
    static volatile List<LanScanner.Subnet> testSubnets;
    /** Tests: send Wake-on-LAN packets to this UDP port instead of 9. */
    static volatile int testWolPort;

    public static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    /** For tests: forget the singleton. */
    static synchronized void reset() {
        if (instance != null) instance.shutdown();
        instance = null;
    }

    private final Context app;
    public final Settings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool(daemon("omni-io"));
    private final ExecutorService disk = Executors.newSingleThreadExecutor(daemon("omni-disk"));
    private final ConversationStore store;
    private Listener listener;

    // Connection
    private State state = State.SEARCHING;
    private String stateDetail = "Looking for your AI on this network…";
    private ServerInfo server;
    private OllamaClient client;
    private final List<ModelInfo> models = new ArrayList<ModelInfo>();
    private final Map<String, ModelInfo> running = new LinkedHashMap<String, ModelInfo>();
    private final Map<String, Boolean> thinkSupport = new LinkedHashMap<String, Boolean>();
    /** Models that rejected a request because of its images (text-only, whatever /api/show said). */
    private final java.util.Set<String> noVision = new java.util.HashSet<String>();
    private List<ServerInfo> lastScan = new ArrayList<ServerInfo>();
    private List<LanScanner.Subnet> subnets = new ArrayList<LanScanner.Subnet>();
    private boolean scanning;
    /** Whether the running scan is a full sweep (/scan), so a restart keeps it one. */
    private boolean scanFull;
    private int scanPort;
    private Cancellable scanCancel;
    /** Wall-clock ms when the link came up; 0 while not online. */
    private long onlineSince;
    private int healthFailures;
    private boolean visible;
    private int offlineRetryMs = 10000;
    private boolean reloadHintShown;
    private String lastSpeed = "";
    private ConnectivityManager.NetworkCallback netCallback;

    // Command-center state
    public final Telemetry telemetry = new Telemetry(System.currentTimeMillis());
    private final Map<String, OllamaClient.ModelDetails> details = new LinkedHashMap<String, OllamaClient.ModelDetails>();
    private Speech speech;
    private PullState pullState;
    private Boolean bridgeOnline;
    private Vitals lastVitals;
    private long lastVitalsAt;

    /** A model download in progress (or just finished). */
    public static final class PullState {
        public final String name;
        public String status = "starting";
        public long completed;
        public long total;
        public double bytesPerSec;
        public boolean done;
        /** The raw failure ("stopped" when cancelled); null while fine. */
        public String error;
        /** The failure in plain words with what to do (e.g. check the name); null unless it failed. */
        public String reason;
        final long startedAt = System.currentTimeMillis();
        long lastBytes;
        long lastAt = startedAt;

        PullState(String name) {
            this.name = name;
        }

        public int percent() {
            return total > 0 ? (int) Math.min(100, completed * 100 / total) : 0;
        }

        /** Seconds left, or -1 when unknown. */
        public long etaSeconds() {
            if (bytesPerSec <= 1 || total <= 0) return -1;
            return (long) ((total - completed) / bytesPerSec);
        }
    }

    // Chat
    private Conversation conv;
    private Job job;
    /** A background model task (compact, benchmark) is running; see {@link #startAux}. */
    private boolean auxBusy;
    private Cancellable auxCancel;
    /** "Compaction" / "Benchmark": names the running task in stop notices. */
    private String auxWhat = "";
    private ChatMessage auxNotice;
    private Conversation auxOwner;
    private boolean auxCompacts;
    private Cancellable pullCancel;
    public String draft = "";
    /** Running /timer timers, persisted so they ring even after the app was closed. */
    private final Timers timers;
    private Notifier notifier;

    // AI tool calling (the model acts on the PC through LaunchBridge)
    /** The bridge's tools, cached for {@link #toolCatalogKey}; null until read. */
    private List<BridgeTool> toolCatalog;
    private String toolCatalogKey = "";
    private long toolCatalogAt;
    private boolean toolCatalogLoading;
    /** Why the tool list couldn't be read last time ("" when it could). */
    private String toolCatalogError = "";
    /** Tools the user allowed "for this chat": chat id → tool names. */
    private final Map<String, java.util.Set<String>> chatGrants = new java.util.HashMap<String, java.util.Set<String>>();

    private Engine(Context app) {
        this.app = app;
        this.settings = new Settings(app);
        this.store = new ConversationStore(new File(app.getFilesDir(), "chats"));
        Net.install();
        String id = settings.currentChat();
        Conversation c = id.length() > 0 ? store.load(id) : null;
        conv = c != null ? c : new Conversation();
        settings.setCurrentChat(conv.id);
        timers = Timers.parse(settings.timers());
        restoreTimers();
    }

    private static ThreadFactory daemon(final String name) {
        final AtomicInteger n = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    void shutdown() {
        if (job != null) job.cancel.cancel();
        if (auxCancel != null) auxCancel.cancel();
        if (scanCancel != null) scanCancel.cancel();
        if (pullCancel != null) pullCancel.cancel();
        main.removeCallbacksAndMessages(null);
        if (speech != null) speech.shutdown();
        unregisterNetworkCallback();
        io.shutdownNow();
        disk.shutdown();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public void setListener(Listener l) {
        listener = l;
    }

    public Listener listener() {
        return listener;
    }

    public State state() {
        return state;
    }

    public String stateDetail() {
        return stateDetail;
    }

    public ServerInfo server() {
        return server;
    }

    public boolean isScanning() {
        return scanning;
    }

    /**
     * The port the current (or last) network search probes. Before the first
     * search: the port it will use (manual address, else the last server's,
     * else Ollama's default).
     */
    public int scanPort() {
        return scanPort > 0 ? scanPort : sweepPort(HostPort.parse(settings.server(), OllamaClient.DEFAULT_PORT));
    }

    /** Milliseconds since the link to the AI came up; -1 while not online. */
    public long linkUptimeMs() {
        if (state != State.ONLINE || onlineSince <= 0) return -1;
        return Math.max(0, System.currentTimeMillis() - onlineSince);
    }

    public List<ModelInfo> models() {
        return Collections.unmodifiableList(models);
    }

    public boolean isLoaded(String model) {
        return running.containsKey(model);
    }

    public ModelInfo runningInfo(String model) {
        return running.get(model);
    }

    public List<ServerInfo> lastScan() {
        return lastScan;
    }

    public List<LanScanner.Subnet> subnets() {
        return subnets;
    }

    public Conversation conversation() {
        return conv;
    }

    public boolean isBusy() {
        return job != null;
    }

    /**
     * True while a reply streams OR a background model task (compact,
     * benchmark) runs. New messages wait until this is false.
     */
    public boolean isWorking() {
        return job != null || auxBusy;
    }

    public ChatMessage streamingMessage() {
        return job == null ? null : job.target;
    }

    public String lastSpeed() {
        return lastSpeed;
    }

    public String mode() {
        return settings.mode();
    }

    public boolean pulling() {
        return pullCancel != null;
    }

    /**
     * The model replies go to: the saved choice if installed, else a loaded
     * chat model, else the first chat model. Embedding-only models are never
     * picked. When nothing was ever chosen, the pick is saved so the active
     * model doesn't jump around as other models load and unload.
     */
    public String currentModel() {
        String m = settings.model();
        if (models.isEmpty()) return m;
        String installed = resolveInstalled(m);
        if (installed != null) return installed;
        String pick = null;
        for (String r : running.keySet()) {
            String i = resolveInstalled(r);
            if (i != null && !isEmbeddingOnly(i)) {
                pick = i;
                break;
            }
        }
        if (pick == null) {
            for (ModelInfo mi : models) {
                if (!isEmbeddingOnly(mi.name)) {
                    pick = mi.name;
                    break;
                }
            }
        }
        if (pick == null) pick = models.get(0).name;
        if (m.length() == 0) settings.setModel(pick);
        return pick;
    }

    /**
     * True for models that can only embed (no chat), e.g. nomic-embed-text:
     * from /api/show capabilities when known, else by name.
     */
    public boolean isEmbeddingOnly(String model) {
        if (model == null) return false;
        OllamaClient.ModelDetails d = details.get(model);
        if (d != null && !d.capabilities.isEmpty()) return d.supports("embedding") && !d.supports("completion");
        return model.toLowerCase(Locale.US).contains("embed");
    }

    /** Exact name or name + ":latest" (no partial matches); null if not installed. */
    public String resolveExact(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.length() == 0) return null;
        for (ModelInfo mi : models) {
            if (mi.name.equalsIgnoreCase(n) || mi.name.equalsIgnoreCase(n + ":latest")) return mi.name;
        }
        return null;
    }

    /** Exact name, name + ":latest", or a unique partial match; null if none. */
    public String resolveInstalled(String name) {
        if (name == null || name.length() == 0) return null;
        String n = name.trim().toLowerCase(Locale.US);
        for (ModelInfo mi : models) {
            if (mi.name.toLowerCase(Locale.US).equals(n) || mi.name.toLowerCase(Locale.US).equals(n + ":latest")) {
                return mi.name;
            }
        }
        String hit = null;
        for (ModelInfo mi : models) {
            if (mi.name.toLowerCase(Locale.US).contains(n)) {
                if (hit != null) return null; // ambiguous
                hit = mi.name;
            }
        }
        return hit;
    }

    public Boolean supportsThinking(String model) {
        return thinkSupport.get(model);
    }

    /** /api/show details, or null until fetched. */
    public OllamaClient.ModelDetails details(String model) {
        return details.get(model);
    }

    /** True / false once known, null while unknown. */
    public Boolean supportsVision(String model) {
        if (noVision.contains(model)) return Boolean.FALSE;
        OllamaClient.ModelDetails d = details.get(model);
        return d == null ? null : d.supports("vision");
    }

    public PullState pullState() {
        return pullState;
    }

    /** Last known LaunchBridge reachability (null = not checked yet). */
    public Boolean bridgeOnline() {
        return bridgeOnline;
    }

    /**
     * True when the phone holds a bridge token for the PC bridge in use. A
     * token only ever goes to the PC that issued it, so after moving to
     * another network (or another PC) this is false until pairing again.
     */
    public boolean bridgePaired() {
        return bridgeTokenFor(bridgeHost()).length() > 0;
    }

    /** The token to send to {@code host}: the saved one when that host issued it (or it isn't bound yet), else "". */
    private String bridgeTokenFor(String host) {
        String tok = settings.bridgeToken();
        if (bridgeOverTls()) return "";
        if (tok.length() == 0) return "";
        String bound = settings.bridgeTokenHost();
        return bound.length() == 0 || HostPort.sameHost(bound, host) ? tok : "";
    }

    /**
     * Saves a bridge token typed by the user, bound to the bridge host in use
     * (so it is never sent anywhere else); "" unpairs.
     */
    public void setBridgeToken(String token) {
        String t = token == null ? "" : token.trim();
        settings.setBridgeToken(t);
        settings.setBridgeTokenHost(t.length() > 0 ? bridgeHost() : "");
        toolCatalog = null;
        if (t.length() > 0 && settings.aiTools()) refreshToolCatalog(null);
        notifyState();
    }

    public Vitals lastVitals() {
        return lastVitals;
    }

    public long lastVitalsAt() {
        return lastVitalsAt;
    }

    /** The model a message would go to right now (after /deep and auto routing). */
    public String effectiveModel() {
        return routeModel("", false);
    }

    /**
     * The model a message goes to, after Fast / Deep / Auto routing: the deep
     * model in Deep mode (or in Auto for a hard-looking prompt), else the
     * current model. A message with images never goes to a deep model that
     * is known to be text-only — it stays on the current model.
     */
    public String routeModel(String prompt, boolean hasImages) {
        String base = currentModel();
        String deepModel = resolveInstalled(settings.deepModel());
        if (deepModel == null || deepModel.equals(base)) return base;
        String mode = settings.mode();
        boolean deep = Settings.MODE_DEEP.equals(mode)
                || (Settings.MODE_AUTO.equals(mode) && looksHard(prompt == null ? "" : prompt));
        if (!deep) return base;
        if (hasImages && Boolean.FALSE.equals(supportsVision(deepModel))) return base;
        // Auto mode never trades PC tools for depth: with tools on, a deep model that
        // can't call them doesn't get the message (Deep mode is the user's explicit choice).
        if (Settings.MODE_AUTO.equals(mode) && toolsWanted() && Boolean.TRUE.equals(supportsTools(base))
                && !Boolean.TRUE.equals(supportsTools(deepModel))) {
            return base;
        }
        return deepModel;
    }

    /** Whether this message is answered in deep (thinking) mode. */
    boolean deepFor(String prompt) {
        String mode = settings.mode();
        if (Settings.MODE_DEEP.equals(mode)) return true;
        return Settings.MODE_AUTO.equals(mode) && resolveInstalled(settings.deepModel()) != null
                && looksHard(prompt == null ? "" : prompt);
    }

    // ------------------------------------------------------------------
    // Log / telemetry
    // ------------------------------------------------------------------

    /** Adds a line to the system log shown on the command center. */
    public void log(String level, String text) {
        telemetry.log(System.currentTimeMillis(), level, text);
        if (listener != null) listener.onLog(telemetry.lastEvent());
    }

    private void notifyTelemetry() {
        if (listener != null) listener.onTelemetry();
    }

    // ------------------------------------------------------------------
    // Listener helpers
    // ------------------------------------------------------------------

    private void notifyState() {
        if (listener != null) listener.onStateChanged();
    }

    private void notifyBusy() {
        syncWork();
        if (listener != null) listener.onBusyChanged();
    }

    /** Keeps the process alive while a reply, aux task or download runs (see {@link WorkService}). */
    private void syncWork() {
        WorkService.sync(app, job != null || auxBusy || pullCancel != null, visible);
    }

    private void toast(String s) {
        if (listener != null) listener.onToast(s);
    }

    private void setState(State s, String detail) {
        if (s != State.ONLINE) onlineSince = 0;
        else if (state != State.ONLINE || onlineSince == 0) onlineSince = System.currentTimeMillis();
        state = s;
        stateDetail = detail;
        notifyState();
    }

    // ------------------------------------------------------------------
    // Lifecycle / discovery / health
    // ------------------------------------------------------------------

    /** The UI is showing (true) or hidden (false). */
    public void setVisible(boolean v) {
        if (visible == v) return;
        visible = v;
        if (v) {
            syncWork();
            registerNetworkCallback();
            if (state == State.ONLINE) checkHealth();
            else discover(false);
            main.removeCallbacks(healthTick);
            main.postDelayed(healthTick, HEALTH_MS);
            // Back on screen: finished-work notifications are stale, timers tick in the app again.
            if (notifier != null) notifier.clearFinished();
            checkTimers();
            main.removeCallbacks(timerTick);
            if (!timers.isEmpty()) main.postDelayed(timerTick, 1000);
            main.removeCallbacks(approvalTimeout);
        } else {
            unregisterNetworkCallback();
            main.removeCallbacks(healthTick);
            main.removeCallbacks(offlineRetry);
            main.removeCallbacks(timerTick);
            // A PC action waiting for the user's OK: they may come right back; if not, it's declined.
            if (pendingApproval() != null) main.postDelayed(approvalTimeout, APPROVAL_BACKGROUND_MS);
        }
    }

    /** The app stayed in the background while a PC action waited for approval. */
    private final Runnable approvalTimeout = new Runnable() {
        @Override
        public void run() {
            ToolApproval a = pendingApproval();
            if (a != null && !visible) a.unavailable();
        }
    };

    /** How long after a Wake-on-LAN packet the app keeps looking for the PC. */
    static final long WAKE_WATCH_MS = 150_000;
    static final long WAKE_POLL_MS = 8_000;
    private long wakingUntil;

    /** A wake-up packet went out and the PC isn't back yet: the app re-checks every few seconds. */
    public boolean isWaking() {
        return wakingUntil > System.currentTimeMillis() && state != State.ONLINE;
    }

    private final Runnable wakeWatch = new Runnable() {
        @Override
        public void run() {
            if (!isWaking()) {
                if (wakingUntil != 0) {
                    wakingUntil = 0;
                    notifyState();
                }
                return;
            }
            if (!scanning) discover(false);
            main.postDelayed(this, WAKE_POLL_MS);
        }
    };

    private void watchWake() {
        wakingUntil = System.currentTimeMillis() + WAKE_WATCH_MS;
        main.removeCallbacks(wakeWatch);
        main.postDelayed(wakeWatch, 3_000);
        notifyState();
    }

    private final Runnable healthTick = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            if (state == State.ONLINE && job == null) checkHealth();
            main.postDelayed(this, HEALTH_MS);
        }
    };

    private final Runnable offlineRetry = new Runnable() {
        @Override
        public void run() {
            if (visible && state == State.OFFLINE) discover(false);
        }
    };

    private final Runnable networkChanged = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            if (scanning) {
                // Android also reports the networks that already exist right after the
                // callback is registered: only a real move (other subnets) restarts the
                // scan, and a full sweep (/scan) stays a full sweep.
                List<LanScanner.Subnet> now = testSubnets != null ? testSubnets : Net.refresh(app);
                if (LanScanner.sameSubnets(now, subnets)) return;
                if (scanCancel != null) scanCancel.cancel();
                scanning = false;
                discover(scanFull);
            } else if (state == State.ONLINE) {
                checkHealth();
            } else {
                discover(false);
            }
        }
    };

    private void registerNetworkCallback() {
        if (netCallback != null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    changed();
                }

                @Override
                public void onLost(Network network) {
                    changed();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, android.net.LinkProperties lp) {
                    changed();
                }

                private void changed() {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            main.removeCallbacks(networkChanged);
                            main.postDelayed(networkChanged, 1500);
                        }
                    });
                }
            };
            cm.registerNetworkCallback(req, netCallback);
        } catch (RuntimeException e) {
            netCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (netCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
        }
        netCallback = null;
        main.removeCallbacks(networkChanged);
    }

    /**
     * Finds the AI: the manually set address, then the last one that worked,
     * then this phone, then a sweep of the local network. With {@code full}
     * the sweep always runs and lists every server it finds.
     */
    public void discover(final boolean full) {
        if (scanning) return;
        scanning = true;
        scanFull = full;
        main.removeCallbacks(offlineRetry);
        final HostPort manual = HostPort.parse(settings.server(), OllamaClient.DEFAULT_PORT);
        if (state != State.ONLINE || full) {
            setState(State.SEARCHING, full ? "Scanning the network…" : manual != null
                    ? "Connecting to " + manual.label(OllamaClient.DEFAULT_PORT) + "…"
                    : "Looking for your AI on this network…");
        } else {
            notifyState();
        }
        final Cancellable c = scanCancel = new Cancellable();
        final String lastHost = settings.lastHost();
        final int lastPort = settings.lastPort();
        final boolean lastHttps = settings.lastHttps();
        final String key = settings.apiKey();
        final List<LanScanner.Subnet> nets = testSubnets != null ? testSubnets : Net.refresh(app);
        subnets = nets;
        final int scanPort = this.scanPort = sweepPort(manual);
        io.execute(new Runnable() {
            @Override
            public void run() {
                ServerInfo found = null;
                final List<ServerInfo> all = new ArrayList<ServerInfo>();
                // Only the address the user typed gets the API key; hosts found otherwise never do.
                final OllamaClient.Probe typed = manual == null ? null
                        : OllamaClient.probeDetailed(manual.host, manual.port, manual.https, key, 3000);
                if (typed != null) found = typed.server;
                if (found == null && lastHost.length() > 0 && !c.isCancelled()
                        && (manual == null || !manual.host.equals(lastHost))) {
                    found = OllamaClient.probeDetailed(lastHost, lastPort, lastHttps, null, 1500).server;
                }
                if (found == null && !c.isCancelled()) {
                    // Ollama running on the phone itself (e.g. in Termux).
                    found = OllamaClient.probe("127.0.0.1", OllamaClient.DEFAULT_PORT, 400);
                }
                if (found != null) all.add(found);
                if ((found == null || full) && !c.isCancelled()) {
                    List<String> hosts = LanScanner.candidateHosts(nets, LanScanner.MAX_HOSTS);
                    List<ServerInfo> hits = LanScanner.scan(hosts, scanPort, 64, 450, 2000, c, !full, null);
                    if (hits.isEmpty() && !c.isCancelled()) {
                        // Second, slower pass for sleepy Wi-Fi.
                        hits = LanScanner.scan(hosts, scanPort, 96, 1500, 2500, c, !full, null);
                    }
                    for (ServerInfo s : hits) {
                        boolean dup = false;
                        for (ServerInfo a : all) dup |= a.host.equals(s.host) && a.port == s.port;
                        if (!dup) all.add(s);
                    }
                    if (found == null && !hits.isEmpty()) {
                        found = hits.get(0);
                        for (ServerInfo s : hits) {
                            if (s.host.equals(lastHost)) found = s;
                        }
                    }
                }
                final ServerInfo result = found;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (c != scanCancel || c.isCancelled()) return;
                        scanning = false;
                        lastScan = all;
                        if (result != null) {
                            connect(result);
                            if (full) announceScan(all);
                        } else if (typed != null && typed.refused()) {
                            // Something answers at the typed address but wants (another) API key.
                            String who = manual.label(OllamaClient.DEFAULT_PORT);
                            setState(State.OFFLINE, "The AI at " + who + " refused the connection (HTTP " + typed.code
                                    + "). " + (key.length() > 0 ? "Check the API key" : "It needs an API key")
                                    + " in Settings › Connection.");
                            if (!loggedOffline) {
                                loggedOffline = true;
                                log("warn", "Refused by " + who + " · API key "
                                        + (key.length() > 0 ? "rejected" : "missing"));
                            }
                            scheduleOfflineRetry();
                        } else if (typed != null && typed.failure != null) {
                            // The address the user typed failed for a reason worth saying.
                            setState(State.OFFLINE, typed.failure);
                            if (full) notice("Couldn't connect to **" + manual.label(OllamaClient.DEFAULT_PORT)
                                    + "** — " + typed.failure, "warn");
                            if (!loggedOffline) {
                                loggedOffline = true;
                                log("warn", "No AI at " + manual.label(OllamaClient.DEFAULT_PORT));
                            }
                            scheduleOfflineRetry();
                        } else {
                            String where = Net.describe(nets);
                            setState(State.OFFLINE, nets.isEmpty()
                                    ? "This phone isn't on Wi-Fi. Join the same network as your PC."
                                    : "No AI answered on port " + scanPort + " (" + where + ").");
                            if (full) notice("Scan finished: no Ollama server answered on port " + scanPort
                                    + " (" + where + ").", "warn");
                            if (!loggedOffline) {
                                loggedOffline = true;
                                log("warn", nets.isEmpty() ? "No Wi-Fi — waiting for a network"
                                        : "No AI on " + where + " · port " + scanPort);
                            }
                            scheduleOfflineRetry();
                        }
                    }
                });
            }
        });
    }

    /**
     * Port the LAN sweep uses: the manual address's, else the last server's,
     * else Ollama's default. An https (remote, proxied) address says nothing
     * about the LAN, so the sweep then uses Ollama's default port.
     */
    private int sweepPort(HostPort manual) {
        if (manual != null) return manual.https ? OllamaClient.DEFAULT_PORT : manual.port;
        int last = settings.lastPort();
        return last > 0 && !settings.lastHttps() ? last : OllamaClient.DEFAULT_PORT;
    }

    /**
     * The API key for a server: only the one the user typed in (same scheme,
     * host and port) ever gets it — a scan may find anyone's Ollama.
     */
    private String apiKeyFor(ServerInfo s) {
        String key = settings.apiKey();
        if (key.length() == 0) return "";
        HostPort manual = HostPort.parse(settings.server(), OllamaClient.DEFAULT_PORT);
        return manual != null && manual.matches(s.host, s.port, s.https) ? key : "";
    }

    /** Saves the API key (sent as "Authorization: Bearer …" to the typed-in server) and reconnects. */
    public void setApiKey(String key) {
        settings.setApiKey(key == null ? "" : key);
        // The key only ever goes to the address the user typed; without one
        // there's nothing to reconnect (and no reason to rescan the network).
        if (settings.server().length() > 0) setServer(settings.server());
    }

    private void announceScan(List<ServerInfo> all) {
        StringBuilder sb = new StringBuilder("**Scan finished** — ").append(all.size())
                .append(all.size() == 1 ? " AI server" : " AI servers").append(" found:\n");
        for (ServerInfo s : all) {
            sb.append("• `").append(s.label()).append("` — Ollama ").append(s.version)
                    .append(server != null && server.host.equals(s.host) && server.port == s.port ? " (connected)" : "")
                    .append('\n');
        }
        if (all.size() > 1) sb.append("Switch with `/server <address>`.");
        notice(sb.toString().trim(), "ok");
    }

    private boolean loggedOffline;

    private void scheduleOfflineRetry() {
        main.removeCallbacks(offlineRetry);
        if (!visible) return;
        main.postDelayed(offlineRetry, offlineRetryMs);
        offlineRetryMs = Math.min(offlineRetryMs * 2, 60000);
    }

    private void connect(ServerInfo s) {
        boolean changed = server == null || !server.host.equals(s.host) || server.port != s.port
                || server.https != s.https;
        server = s;
        client = new OllamaClient(s.host, s.port, s.https, apiKeyFor(s));
        settings.setLast(s.host, s.port, s.https);
        healthFailures = 0;
        offlineRetryMs = 10000;
        if (changed) {
            running.clear();
            thinkSupport.clear();
            noVision.clear();
            details.clear();
        }
        boolean wasOnline = state == State.ONLINE;
        setState(State.ONLINE, "Ollama " + (s.version.length() > 0 ? s.version + " " : "") + "at " + s.label());
        loggedOffline = false;
        if (s.latencyMs >= 0) {
            telemetry.latencyMs.add(s.latencyMs);
            notifyTelemetry();
        }
        if (!wasOnline || changed) {
            log("ok", "Link established · Ollama " + (s.version.length() > 0 ? s.version + " " : "") + "@ " + s.label());
        }
        refreshModels(null);
    }

    /** Re-reads installed and loaded models. */
    public void refreshModels(final Runnable after) {
        final OllamaClient c = client;
        if (c == null) {
            if (after != null) after.run();
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<ModelInfo> tags = null;
                List<ModelInfo> ps = null;
                try {
                    tags = c.listModels();
                } catch (IOException ignored) {
                }
                try {
                    ps = c.listRunning();
                } catch (IOException ignored) {
                }
                final List<ModelInfo> fTags = tags, fPs = ps;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (c != client) {
                            // The server changed meanwhile; still let the caller finish.
                            if (after != null) after.run();
                            return;
                        }
                        if (fTags != null) {
                            models.clear();
                            models.addAll(fTags);
                            Collections.sort(models, new java.util.Comparator<ModelInfo>() {
                                @Override
                                public int compare(ModelInfo a, ModelInfo b) {
                                    return a.name.compareToIgnoreCase(b.name);
                                }
                            });
                        }
                        if (fPs != null) setRunning(fPs);
                        ensureCapabilities(currentModel());
                        notifyState();
                        if (after != null) after.run();
                    }
                });
            }
        });
    }

    private void setRunning(List<ModelInfo> ps) {
        running.clear();
        for (ModelInfo m : ps) running.put(m.name, m);
    }

    private void ensureCapabilities(final String model) {
        final OllamaClient c = client;
        if (c == null || model == null || model.length() == 0 || thinkSupport.containsKey(model)) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                OllamaClient.ModelDetails d = null;
                try {
                    d = c.show(model);
                } catch (IOException ignored) {
                }
                final OllamaClient.ModelDetails fd = d;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (c == client && fd != null) {
                            thinkSupport.put(model, fd.supports("thinking"));
                            details.put(model, fd);
                            notifyState();
                        }
                    }
                });
            }
        });
    }

    /** Pings the server; two misses in a row mean it's gone and triggers a search. */
    public void checkHealth() {
        final OllamaClient c = client;
        if (c == null) {
            discover(false);
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                List<ModelInfo> ps = null;
                long latency = -1;
                try {
                    long t0 = System.nanoTime();
                    c.version(3000);
                    latency = (System.nanoTime() - t0) / 1000000L;
                    ok = true;
                    try {
                        ps = c.listRunning();
                    } catch (IOException ignored) {
                    }
                } catch (IOException e) {
                    ok = false;
                }
                final boolean fOk = ok;
                final List<ModelInfo> fPs = ps;
                final long fLatency = latency;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (c != client) return;
                        if (fOk) {
                            healthFailures = 0;
                            if (fLatency >= 0) {
                                telemetry.latencyMs.add(fLatency);
                                notifyTelemetry();
                            }
                            if (state != State.ONLINE) setState(State.ONLINE, "Ollama at " + server.label());
                            if (fPs != null && !sameNames(fPs)) {
                                setRunning(fPs);
                                notifyState();
                            }
                        } else if (++healthFailures >= 2 && job == null) {
                            healthFailures = 0;
                            setState(State.SEARCHING, "Lost " + server.label() + " — reconnecting…");
                            log("error", "Link lost · " + server.label() + " — reconnecting");
                            telemetry.errors++;
                            discover(false);
                        } else if (healthFailures == 1) {
                            main.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    checkHealth();
                                }
                            }, 2000);
                        }
                    }
                });
            }
        });
    }

    private boolean sameNames(List<ModelInfo> ps) {
        if (ps.size() != running.size()) return false;
        for (ModelInfo m : ps) {
            if (!running.containsKey(m.name)) return false;
        }
        return true;
    }

    /** Sets (or with "auto"/"", clears) the manual AI address and reconnects. */
    public void setServer(String address) {
        String a = address == null ? "" : address.trim();
        if (a.equalsIgnoreCase("auto")) a = "";
        settings.setServer(a);
        server = null;
        client = null;
        models.clear();
        running.clear();
        thinkSupport.clear();
        noVision.clear();
        details.clear();
        if (scanCancel != null) scanCancel.cancel();
        scanning = false;
        // Leave ONLINE right away: nothing may start a request while there is no client.
        setState(State.SEARCHING, a.length() > 0 ? "Connecting to " + a + "…" : "Looking for your AI on this network…");
        discover(false);
    }

    // ------------------------------------------------------------------
    // Conversation
    // ------------------------------------------------------------------

    private void add(ChatMessage m) {
        conv.messages.add(m);
        conv.updated = System.currentTimeMillis();
        if (listener != null) listener.onMessageAdded(m);
    }

    /** Adds a local notice (command output, errors, tips) to the chat. */
    public ChatMessage notice(String text, String tone) {
        ChatMessage m = ChatMessage.notice(text, tone);
        add(m);
        save();
        return m;
    }

    /**
     * Rewrites a notice once its task finishes (download, load, benchmark…).
     * {@code owner} is the chat the notice was posted in: when the user has
     * switched chats since, the update still lands in that chat — the
     * reopened copy when it is open again, else the saved file.
     */
    private void updateNotice(Conversation owner, ChatMessage m, String text, String tone) {
        m.content = text;
        if (tone != null) m.tone = tone;
        if (owner == conv) {
            if (listener != null && conv.messages.contains(m)) listener.onMessageChanged(m);
            save(conv);
        } else if (owner.id.equals(conv.id)) {
            ChatMessage open = conv.find(m.id);
            if (open == null) return;
            open.content = text;
            if (tone != null) open.tone = tone;
            if (listener != null) listener.onMessageChanged(open);
            save(conv);
        } else {
            patchSaved(owner.id, m.id, text, tone);
        }
    }

    /** Live progress text for a notice; drawn only while its chat is on screen (saved when it finishes). */
    private void showProgress(ChatMessage m, String text) {
        m.content = text;
        if (listener != null && conv.messages.contains(m)) listener.onMessageChanged(m);
    }

    /** Updates one notice inside a saved chat that is no longer open. */
    private void patchSaved(final String chatId, final String messageId, final String text, final String tone) {
        if (settings.incognito()) return;
        disk.execute(new Runnable() {
            @Override
            public void run() {
                Conversation c = store.load(chatId);
                ChatMessage m = c == null ? null : c.find(messageId);
                if (m == null) return;
                m.content = text;
                if (tone != null) m.tone = tone;
                try {
                    store.save(c);
                } catch (IOException ignored) {
                }
            }
        });
    }

    public void save() {
        save(conv);
    }

    /** Saves {@code c} (serialized here, written on the disk thread); skipped while incognito. */
    private void save(Conversation c) {
        if (settings.incognito() || c == null) return;
        if (c.isEmpty()) return;
        final String json;
        try {
            json = c.toJson().toString();
        } catch (JSONException e) {
            return;
        }
        final String id = c.id;
        disk.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    store.write(id, json);
                } catch (IOException ignored) {
                }
            }
        });
    }

    public void newChat() {
        leaveChat();
        conv = new Conversation();
        settings.setCurrentChat(conv.id);
        if (listener != null) listener.onConversationReplaced();
    }

    /**
     * Before the open chat is replaced: a streaming reply ends now and keeps
     * its partial text (as "stopped") in this chat, a compaction of it is
     * cancelled, and the chat is saved.
     */
    private void leaveChat() {
        settleJob();
        if (auxCompacts && auxOwner == conv) stopAux();
        save(conv);
    }

    /**
     * Ends the streaming reply synchronously: cancels the request and marks
     * the partial reply "stopped" in the chat it belongs to. The request's own
     * late finish() is ignored afterwards (it no longer owns {@link #job}).
     * Callers save the chat. Returns the reply, or null when none streamed.
     */
    private ChatMessage settleJob() {
        final Job j = job;
        if (j == null) return null;
        j.cancel.cancel();
        main.removeCallbacks(j);
        j.copy();
        endTools(j);
        ChatMessage t = j.target;
        t.thinking = t.thinking.trim();
        t.streaming = false;
        t.stopped = true;
        t.stats = "stopped";
        t.ttftMs = j.ttft.get();
        job = null;
        log("warn", "Reply stopped · " + t.model);
        speechStop();
        if (listener != null && j.conv == conv) listener.onMessageChanged(t);
        notifyBusy();
        return t;
    }

    public void listChats(final Callback<List<ConversationStore.Entry>> cb) {
        save();
        disk.execute(new Runnable() {
            @Override
            public void run() {
                final List<ConversationStore.Entry> list = store.list();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(list, null);
                    }
                });
            }
        });
    }

    public void openChat(final String id) {
        if (id.equals(conv.id)) return;
        leaveChat();
        disk.execute(new Runnable() {
            @Override
            public void run() {
                final Conversation c = store.load(id);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (c == null) {
                            toast("Couldn't open that chat.");
                            return;
                        }
                        conv = c;
                        settings.setCurrentChat(c.id);
                        // The chat continues on the model it was used with, if that's still installed.
                        String m = resolveExact(c.model);
                        if (m != null && !m.equals(currentModel()) && !isEmbeddingOnly(m)) {
                            settings.setModel(m);
                            ensureCapabilities(m);
                            log("info", "Model → " + m + " (the model this chat used)");
                            notifyState();
                        }
                        if (listener != null) listener.onConversationReplaced();
                    }
                });
            }
        });
    }

    public void deleteChat(final String id) {
        final boolean current = id.equals(conv.id);
        if (current) {
            settleJob();
            if (auxCompacts && auxOwner == conv) stopAux();
            conv = new Conversation();
            settings.setCurrentChat(conv.id);
            if (listener != null) listener.onConversationReplaced();
        }
        disk.execute(new Runnable() {
            @Override
            public void run() {
                store.delete(id);
            }
        });
    }

    /** Renames the current chat. */
    public void renameChat(String title) {
        conv.title = title == null ? "" : title.trim();
        conv.updated = System.currentTimeMillis();
        save();
        notifyState();
    }

    /** Renames a saved chat by id (the current one or any other). */
    public void renameChat(final String id, final String title) {
        if (id.equals(conv.id)) {
            renameChat(title);
            return;
        }
        disk.execute(new Runnable() {
            @Override
            public void run() {
                Conversation c = store.load(id);
                if (c == null) return;
                c.title = title == null ? "" : title.trim();
                try {
                    store.save(c);
                } catch (IOException ignored) {
                }
            }
        });
    }

    public void deleteMessage(ChatMessage m) {
        if (job != null && job.target == m) settleJob();
        if (conv.messages.remove(m)) {
            if (listener != null) listener.onMessageRemoved(m);
            save();
        }
    }

    /** Drops this user message and everything after it; returns its text for re-editing. */
    public String editFrom(ChatMessage m) {
        settleJob();
        int i = conv.messages.indexOf(m);
        if (i < 0) return m.content;
        while (conv.messages.size() > i) conv.messages.remove(conv.messages.size() - 1);
        if (listener != null) listener.onConversationReplaced();
        save();
        return m.content;
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /**
     * A reply being written. Tokens accumulate off the main thread and are
     * flushed to the UI per frame. With PC tools the reply takes several
     * requests ("rounds"): each response that asks for tools has them run
     * (after the user's OK where needed), and the conversation continues
     * with their results — all into the same message.
     */
    private final class Job implements Runnable {
        final ChatMessage target;
        /** The chat the reply belongs to (saved when it ends, even after a chat switch). */
        final Conversation conv;
        final Cancellable cancel = new Cancellable();
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final AtomicBoolean posted = new AtomicBoolean();
        final long startNanos = System.nanoTime();
        /** ms from request to the first token; -1 until it arrives. */
        final java.util.concurrent.atomic.AtomicLong ttft = new java.util.concurrent.atomic.AtomicLong(-1);
        int numCtx;
        /** Whether the request that went out carried images (explains a rejected request). */
        volatile boolean sentImages;

        // The same for every round of this reply.
        String model = "";
        boolean deep;
        Object think;
        Object keepAlive;
        JSONObject opts;
        boolean wasLoaded;
        OllamaClient client;
        boolean withImages;

        // PC tools (toolsJson == null: this reply has none).
        List<BridgeTool> catalog;
        JSONArray toolsJson;
        String pc = "";
        /** Tool rounds run so far. */
        int round;
        /** Apps the bridge listed during this reply, by id (open_app opens only these). */
        final Map<String, JSONObject> appsSeen = new HashMap<String, JSONObject>();
        /** A request is on the wire: stop() cancels it and its end finishes the reply. */
        boolean inRequest;
        /** Tool calls in the response streaming now (collected off the main thread). */
        final List<ToolCall> incoming = new ArrayList<ToolCall>();
        /** Text already came before this round's tool calls: the next text starts a new paragraph. */
        boolean newParagraph;
        /** The PC action waiting for the user's OK, if any. */
        ToolApproval approval;

        Job(ChatMessage target, Conversation conv) {
            this.target = target;
            this.conv = conv;
        }

        void firstToken() {
            if (ttft.get() < 0) ttft.compareAndSet(-1, (System.nanoTime() - startNanos) / 1000000L);
        }

        /** Appends streamed text (worker thread); a new round's text starts a new paragraph. */
        void addContent(String delta) {
            synchronized (this) {
                if (newParagraph) {
                    int i = 0;
                    while (i < delta.length() && Character.isWhitespace(delta.charAt(i))) i++;
                    if (i == delta.length()) return;
                    content.append("\n\n");
                    newParagraph = false;
                    delta = delta.substring(i);
                }
                content.append(delta);
            }
        }

        void schedule() {
            if (posted.compareAndSet(false, true)) main.postDelayed(this, FLUSH_MS);
        }

        /** Flush (main thread). */
        @Override
        public void run() {
            posted.set(false);
            if (job != this) return;
            copy();
            if (listener != null) listener.onMessageChanged(target);
            if (settings.readAloud()) speech().feed(target.id, target.content, false);
        }

        /**
         * Copies the buffers into the message. Leading blank lines (common after
         * an inline think block) are dropped here, on every flush, so the text
         * read aloud while streaming and the final text share one set of offsets.
         */
        void copy() {
            synchronized (this) {
                target.content = stripLeadingBlank(content.toString());
                target.thinking = thinking.toString();
            }
        }
    }

    /** Sends a chat message. Returns false (and keeps the text for the composer) when it can't. */
    public boolean send(String text) {
        return send(text, null);
    }

    /** Sends a message with optional base64 JPEG/PNG images (for vision models). */
    public boolean send(String text, List<String> images) {
        return send(text, images, false);
    }

    /** {@code voice}: the message was spoken, so the answer should be plain speech. */
    public boolean send(String text, List<String> images, boolean voice) {
        String t = text == null ? "" : text.trim();
        boolean hasImages = images != null && !images.isEmpty();
        if (t.length() == 0 && !hasImages) return false;
        if (t.length() == 0) t = "Describe this image.";
        if (job != null) {
            toast("Wait for the reply to finish, or tap stop.");
            return false;
        }
        if (auxBusy) {
            // Compact rewrites the chat when it finishes; a message sent now would be lost.
            toast("OMNI is finishing a task (compact / benchmark) — try again in a moment.");
            return false;
        }
        if (state != State.ONLINE || client == null) {
            toast(scanning ? "Still looking for your AI…" : "Your AI isn't connected — searching again.");
            if (!scanning) discover(false);
            return false;
        }
        if (currentModel().length() == 0) {
            notice("No models are installed on the PC yet. Try `/pull llama3.2`.", "warn");
            return false;
        }
        ChatMessage u = new ChatMessage(ChatMessage.USER, t);
        u.voice = voice;
        if (hasImages) u.images.addAll(images);
        add(u);
        conv.autoTitle();
        startReply();
        return true;
    }

    private void startReply() {
        String lastUser = "";
        ChatMessage lu = conv.lastOfRole(ChatMessage.USER);
        if (lu != null) lastUser = lu.content;
        boolean deep = deepFor(lastUser);
        final String model = routeModel(lastUser, lu != null && !lu.images.isEmpty());
        ensureCapabilities(model);
        conv.model = currentModel();

        final ChatMessage target = new ChatMessage(ChatMessage.ASSISTANT, "");
        target.model = model;
        target.streaming = true;
        target.startedAt = System.currentTimeMillis();
        add(target);
        final Job j = job = new Job(target, conv);
        j.model = model;
        j.deep = deep;
        JSONObject opts = runnerOptions(model);
        addGenerationOptions(opts);
        j.opts = opts;
        j.numCtx = opts.optInt("num_ctx", 0);
        j.keepAlive = keepAlive();
        j.wasLoaded = running.containsKey(model);
        j.client = client;
        if (settings.readAloud()) speech().stop();
        notifyBusy();
        save();
        if (j.client == null) {
            // The link dropped between the caller's check and now: fail the reply cleanly.
            finish(j, null, "Not connected to your AI.", false, false);
            return;
        }
        prepare(j);
    }

    /**
     * Before the first request, learns off the main thread what isn't known
     * yet: whether the model can see images (a text-only model rejects a
     * request that carries any, so it gets a marker instead), whether it can
     * call tools, and which tools the PC offers. Then streams.
     */
    private void prepare(final Job j) {
        final String model = j.model;
        final Boolean vision = supportsVision(model);
        final boolean images = j.conv.hasImages();
        final boolean wantTools = toolsWanted();
        final Boolean toolCap = wantTools ? supportsTools(model) : Boolean.FALSE;
        final List<BridgeTool> cached = wantTools ? toolCatalog() : null;
        final boolean needShow = (images && vision == null) || (wantTools && toolCap == null);
        final boolean needCatalog = wantTools && !Boolean.FALSE.equals(toolCap) && cached == null;
        if (!needShow && !needCatalog) {
            // An old tool list is used now and re-read for next time.
            if (cached != null && Boolean.TRUE.equals(toolCap) && staleCatalog()) refreshToolCatalog(null);
            begin(j, cached);
            return;
        }
        final OllamaClient c = j.client;
        final BridgeClient b = needCatalog ? bridge() : null;
        final String key = toolKey();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final OllamaClient.ModelDetails d = needShow ? showQuietly(c, model) : null;
                boolean canCall = toolCap != null ? toolCap : d != null && d.supports("tools");
                List<BridgeTool> cat = null;
                String err = null;
                if (needCatalog && canCall && b != null) {
                    try {
                        cat = BridgeTool.parseAll(b.deskCapabilities());
                    } catch (BridgeClient.BridgeException e) {
                        err = e.getMessage();
                    } catch (RuntimeException e) {
                        err = "Bridge error: " + e;
                    }
                }
                final List<BridgeTool> fCat = cat;
                final String fErr = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (d != null && c == client) {
                            details.put(model, d);
                            thinkSupport.put(model, d.supports("thinking"));
                        }
                        if (fCat != null) cacheCatalog(key, fCat);
                        else if (fErr != null) catalogFailed(fErr);
                        if (job != j) return;
                        if (j.cancel.isCancelled()) {
                            finish(j, null, "Stopped.", true, j.wasLoaded);
                            return;
                        }
                        begin(j, fCat != null ? fCat : cached);
                    }
                });
            }
        });
    }

    /** /api/show on the calling (worker) thread; null when it fails. */
    private static OllamaClient.ModelDetails showQuietly(OllamaClient c, String model) {
        try {
            return c.show(model);
        } catch (IOException e) {
            return null;
        }
    }

    /** Settles what every round of the reply sends — thinking, the PC's tools — then asks. */
    private void begin(Job j, List<BridgeTool> catalog) {
        String model = j.model;
        j.think = thinkFor(model, j.deep);
        if (catalog != null && toolsWanted() && Boolean.TRUE.equals(supportsTools(model))) {
            j.catalog = catalog;
            j.toolsJson = ToolKit.toolsArray(catalog, true);
            j.pc = pcName();
        }
        streamRound(j);
    }

    /**
     * Sends the conversation so far — this reply's finished tool rounds
     * included — and streams the answer. Tools are offered for up to
     * {@link ToolKit#MAX_ROUNDS} rounds; after that the model has to answer.
     */
    private void streamRound(final Job j) {
        // toolsWanted(): PC tools switched off (or unpaired) mid-reply → no tools from here on.
        boolean offer = j.toolsJson != null && j.round < ToolKit.MAX_ROUNDS && toolsWanted();
        // A screenshot a tool just took may be the chat's first image.
        j.withImages = j.conv.hasImages() && !Boolean.FALSE.equals(supportsVision(j.model));
        String sys = systemPromptFor(j.conv.lastOfRole(ChatMessage.USER));
        if (j.toolsJson != null) {
            String add = ToolKit.systemPrompt(j.pc) + (offer ? "" : "\n\n" + ToolKit.LIMIT_PROMPT);
            sys = sys.length() > 0 ? sys + "\n\n" + add : add;
        }
        JSONArray msgs = j.conv.toRequestMessages(sys, null, j.withImages,
                Boolean.TRUE.equals(supportsTools(j.model)));
        final JSONObject body = OllamaClient.chatBody(j.model, msgs, j.think, j.keepAlive, j.opts,
                offer ? j.toolsJson : null);
        j.sentImages = j.withImages;
        j.inRequest = true;
        synchronized (j) {
            j.newParagraph = j.content.toString().trim().length() > 0;
            if (j.thinking.length() > 0) j.thinking.append("\n\n");
        }
        final OllamaClient c = j.client;
        io.execute(new Runnable() {
            @Override
            public void run() {
                c.chat(body, j.cancel, new OllamaClient.ChatListener() {
                    @Override
                    public void onThinking(String delta) {
                        j.firstToken();
                        synchronized (j) {
                            j.thinking.append(delta);
                        }
                        j.schedule();
                    }

                    @Override
                    public void onContent(String delta) {
                        j.firstToken();
                        j.addContent(delta);
                        j.schedule();
                    }

                    @Override
                    public void onToolCalls(List<ToolCall> calls) {
                        j.firstToken();
                        synchronized (j) {
                            j.incoming.addAll(calls);
                        }
                    }

                    @Override
                    public void onDone(final ChatStats stats) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                roundDone(j, stats);
                            }
                        });
                    }

                    @Override
                    public void onError(final String message, final boolean cancelled) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                finish(j, null, message, cancelled, j.wasLoaded);
                            }
                        });
                    }
                });
            }
        });
    }

    /** A response ended: run the PC tools it asked for (then ask again), or finish the reply. */
    private void roundDone(Job j, ChatStats stats) {
        if (job != j) return;
        j.inRequest = false;
        List<ToolCall> calls;
        synchronized (j) {
            calls = new ArrayList<ToolCall>(j.incoming);
            j.incoming.clear();
        }
        // No tools asked for — or none offered (the round limit): this is the answer.
        if (calls.isEmpty() || j.toolsJson == null || j.round >= ToolKit.MAX_ROUNDS) {
            finish(j, stats, null, false, j.wasLoaded);
            return;
        }
        if (j.cancel.isCancelled()) {
            finish(j, null, "Stopped.", true, j.wasLoaded);
            return;
        }
        j.copy();
        telemetry.tokensIn += stats.promptTokens;
        telemetry.tokensOut += stats.evalTokens;
        j.round++;
        int at = j.target.content.length();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall tc = calls.get(i);
            tc.round = j.round;
            tc.at = at;
            tc.state = ToolCall.QUEUED;
            tc.label = ToolKit.label(ToolKit.find(j.catalog, tc.name), tc.name, tc.args);
            if (i >= ToolKit.MAX_CALLS_PER_ROUND) {
                // A runaway response: the extra calls are answered, not run.
                tc.state = ToolCall.FAILED;
                tc.result = ToolKit.TOO_MANY_RESULT;
            }
            j.target.tools.add(tc);
            if (names.length() > 0) names.append(", ");
            names.append(tc.name);
        }
        log("info", "AI → PC · " + names);
        changed(j);
        nextCall(j);
    }

    /** Runs this round's next waiting call; once none is left, the model gets the results. */
    private void nextCall(Job j) {
        if (job != j) return;
        if (j.cancel.isCancelled()) {
            finish(j, null, "Stopped.", true, j.wasLoaded);
            return;
        }
        for (ToolCall tc : j.target.tools) {
            if (tc.round == j.round && ToolCall.QUEUED.equals(tc.state)) {
                runCall(j, tc);
                return;
            }
        }
        streamRound(j);
    }

    /** Checks a call against the PC's tools, gets the user's OK when it needs one, then runs it. */
    private void runCall(final Job j, final ToolCall tc) {
        if (!toolsWanted()) {
            // PC tools were switched off (or the bridge unpaired) mid-reply: nothing more runs.
            for (ToolCall c : j.target.tools) {
                if (c.round == j.round && ToolCall.QUEUED.equals(c.state)) {
                    c.state = ToolCall.DECLINED;
                    c.result = "Not run: PC tools were turned off.";
                }
            }
            changed(j);
            j.round = ToolKit.MAX_ROUNDS; // the next request carries no tools
            streamRound(j);
            return;
        }
        if (tc.argsError.length() > 0) {
            settleCall(j, tc, ToolCall.FAILED, "The arguments weren't a JSON object: " + tc.argsError);
            nextCall(j);
            return;
        }
        final BridgeTool bt = ToolKit.find(j.catalog, tc.name);
        if (bt == null && ToolKit.OPEN_APP.equalsIgnoreCase(tc.name)) {
            openApp(j, tc);
            return;
        }
        if (bt == null) {
            settleCall(j, tc, ToolCall.FAILED, "There is no tool called \"" + tc.name + "\". The tools are: "
                    + toolNames(j.catalog) + ".");
            nextCall(j);
            return;
        }
        String missing = ToolKit.missingArgs(bt, tc.args);
        if (missing != null) {
            settleCall(j, tc, ToolCall.FAILED, "Missing required argument(s): " + missing + ". Call " + bt.name
                    + " again with them.");
            nextCall(j);
            return;
        }
        approve(j, tc, ToolKit.risk(bt, tc.name, tc.args), ToolKit.phrase(bt, tc.name, tc.args, tc.label),
                ToolKit.prettyArgs(tc.args), new Runnable() {
                    @Override
                    public void run() {
                        execute(j, tc, bt);
                    }
                });
    }

    private static String toolNames(List<BridgeTool> catalog) {
        StringBuilder sb = new StringBuilder();
        if (catalog != null) {
            for (BridgeTool t : catalog) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(t.name);
            }
        }
        if (ToolKit.find(catalog, ToolKit.OPEN_APP) == null) sb.append(sb.length() > 0 ? ", " : "").append(ToolKit.OPEN_APP);
        return sb.toString();
    }

    /**
     * Runs {@code onAllow} right away, or once the user allows it: always for
     * destructive tools, for other changes while "Ask before PC actions" is on
     * (unless allowed for this chat). When nobody can answer — the app is in
     * the background — the call is declined and the model is told why.
     */
    private void approve(final Job j, final ToolCall tc, int risk, String phrase, String detail,
                         final Runnable onAllow) {
        boolean destructive = risk == ToolKit.DESTRUCTIVE;
        boolean ask = destructive || (risk == ToolKit.CHANGE && settings.confirmPcActions()
                && !granted(j.conv.id, tc.name));
        if (!ask) {
            onAllow.run();
            return;
        }
        if (!visible || listener == null) {
            settleCall(j, tc, ToolCall.DECLINED, ToolKit.UNAVAILABLE_RESULT);
            log("warn", "PC action not approved · " + tc.label + " (app in the background)");
            nextCall(j);
            return;
        }
        tc.state = ToolCall.ASKING;
        changed(j);
        BridgeClient b = bridge();
        // The PC's name as known now (a system-info call earlier in this reply may have told it).
        final ToolApproval a = new ToolApproval(tc.name, tc.label, phrase, pcName(), b == null ? "" : b.where(),
                detail, destructive);
        j.approval = a;
        a.setDecision(new ToolApproval.Decision() {
            @Override
            public void decided(int answer) {
                if (j.approval == a) j.approval = null;
                main.removeCallbacks(approvalTimeout);
                if (job != j || answer == ToolApproval.WITHDRAWN) return;
                if (answer == ToolApproval.ALLOW || answer == ToolApproval.ALLOW_CHAT) {
                    if (answer == ToolApproval.ALLOW_CHAT) grant(j.conv.id, tc.name);
                    log("ok", "PC action approved · " + tc.label);
                    onAllow.run();
                } else {
                    boolean away = answer == ToolApproval.UNAVAILABLE;
                    settleCall(j, tc, ToolCall.DECLINED, away ? ToolKit.UNAVAILABLE_RESULT : ToolKit.DECLINED_RESULT);
                    log("warn", "PC action declined · " + tc.label + (away ? " (no answer)" : ""));
                    nextCall(j);
                }
            }
        });
        boolean shown;
        try {
            shown = listener.onToolApproval(a);
        } catch (RuntimeException e) {
            shown = false;
        }
        if (!shown) a.unavailable();
    }

    /** Runs one bridge tool and records what it returned (a screenshot is kept, downscaled). */
    private void execute(final Job j, final ToolCall tc, final BridgeTool bt) {
        if (job != j) return;
        tc.state = ToolCall.RUNNING;
        changed(j);
        final long t0 = System.currentTimeMillis();
        final JSONObject args = tc.args;
        bridgeAsync(new BridgeCall<Object[]>() {
            @Override
            public Object[] run(BridgeClient b) throws BridgeClient.BridgeException {
                Object r = b.deskRun(bt.name, args);
                String img = extractImage(r);
                return new Object[]{r, img == null ? null : shrinkImage(img)};
            }
        }, new Callback<Object[]>() {
            @Override
            public void done(Object[] v, String error) {
                tc.ms = System.currentTimeMillis() - t0;
                if (error != null) {
                    tc.state = ToolCall.FAILED;
                    tc.result = "Error: " + error;
                    log("error", "PC action failed · " + tc.label);
                } else {
                    tc.state = ToolCall.DONE;
                    tc.result = ToolKit.resultText(v[0]);
                    if (v[1] != null) tc.image = (String) v[1];
                    if ("get_system_info".equals(bt.name)) {
                        learnMac(v[0]);
                        noteVitals(v[0]);
                    }
                    log("ok", "PC · " + tc.label);
                }
                afterCall(j);
            }
        });
    }

    /**
     * The built-in open_app. The app is looked up with the bridge's app search
     * (GET /apps, which can never open anything — a /launch "dry run" would
     * open the app on a bridge that ignores dry_run). Exactly one match (or one
     * exact name) is opened by id once allowed; several go back to the model
     * as a list with their app ids. An app_id from the model opens only if the
     * bridge really lists an app under that id, and the question names it.
     */
    private void openApp(final Job j, final ToolCall tc) {
        final String query = OllamaClient.str(tc.args, "query").trim();
        final String appId = OllamaClient.str(tc.args, "app_id").trim();
        if (query.length() == 0 && appId.length() == 0) {
            settleCall(j, tc, ToolCall.FAILED, "open_app needs the app's name in \"query\".");
            nextCall(j);
            return;
        }
        JSONObject seen = appId.length() > 0 ? j.appsSeen.get(appId) : null;
        if (seen != null) {
            approveOpen(j, tc, seen);
            return;
        }
        tc.state = ToolCall.RUNNING;
        changed(j);
        final long t0 = System.currentTimeMillis();
        bridgeAsync(new BridgeCall<JSONArray>() {
            @Override
            public JSONArray run(BridgeClient b) throws BridgeClient.BridgeException {
                JSONArray found = b.apps(query, 30);
                if (appId.length() == 0 || listsId(found, appId)) return found;
                // An id the name search didn't turn up: look it up in the whole list.
                JSONArray all = b.apps("", 500);
                for (int i = 0; i < all.length(); i++) found.put(all.opt(i));
                return found;
            }
        }, new Callback<JSONArray>() {
            @Override
            public void done(JSONArray r, String error) {
                tc.ms = System.currentTimeMillis() - t0;
                if (job != j) {
                    // Stopped while the app was looked up: nothing was opened.
                    if (!tc.isFinal()) {
                        tc.state = ToolCall.DECLINED;
                        tc.result = ToolKit.STOPPED_RESULT;
                    }
                    afterCall(j);
                    return;
                }
                if (error != null) {
                    settleCall(j, tc, ToolCall.FAILED, "Error: " + error);
                    nextCall(j);
                    return;
                }
                JSONArray apps = new JSONArray();
                JSONObject exact = null;
                int exactCount = 0;
                for (int i = 0; i < r.length(); i++) {
                    JSONObject a = r.optJSONObject(i);
                    if (a == null) continue;
                    String id = OllamaClient.str(a, "id");
                    if (id.length() == 0) id = OllamaClient.str(a, "app_id");
                    if (id.length() == 0) continue;
                    try {
                        a.put("id", id);
                    } catch (JSONException ignored) {
                    }
                    j.appsSeen.put(id, a);
                    apps.put(a);
                    if (query.length() > 0 && OllamaClient.str(a, "name").equalsIgnoreCase(query)) {
                        exact = a;
                        exactCount++;
                    }
                }
                if (appId.length() > 0) {
                    JSONObject match = j.appsSeen.get(appId);
                    if (match != null) {
                        approveOpen(j, tc, match);
                    } else {
                        settleCall(j, tc, ToolCall.FAILED, "No app with app_id \"" + appId + "\" on the PC.");
                        nextCall(j);
                    }
                    return;
                }
                if (apps.length() == 0) {
                    settleCall(j, tc, ToolCall.FAILED, "No app on the PC matches \"" + query + "\".");
                    nextCall(j);
                    return;
                }
                JSONObject target = apps.length() == 1 ? apps.optJSONObject(0) : exactCount == 1 ? exact : null;
                if (target != null) {
                    approveOpen(j, tc, target);
                    return;
                }
                tc.label = "Find “" + Fmt.ellipsize(query, 32) + "” → " + apps.length()
                        + (apps.length() == 1 ? " match" : " matches");
                settleCall(j, tc, ToolCall.DONE, ToolKit.candidatesText(query, apps));
                nextCall(j);
            }
        });
    }

    private static boolean listsId(JSONArray apps, String id) {
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a != null && (id.equals(OllamaClient.str(a, "id")) || id.equals(OllamaClient.str(a, "app_id")))) {
                return true;
            }
        }
        return false;
    }

    /** Asks to open a resolved app (its real name and path in the question), then opens it by id. */
    private void approveOpen(Job j, ToolCall tc, JSONObject app) {
        String id = OllamaClient.str(app, "id");
        String name = OllamaClient.str(app, "name");
        if (name.length() == 0) name = id;
        tc.label = "Open " + name;
        tc.state = ToolCall.QUEUED;
        approve(j, tc, ToolKit.CHANGE, "open " + name, OllamaClient.str(app, "path"), launchApp(j, tc, id, name));
    }

    private Runnable launchApp(final Job j, final ToolCall tc, final String appId, final String name) {
        return new Runnable() {
            @Override
            public void run() {
                if (job != j) return;
                tc.state = ToolCall.RUNNING;
                changed(j);
                final long t0 = System.currentTimeMillis();
                bridgeAsync(new BridgeCall<JSONObject>() {
                    @Override
                    public JSONObject run(BridgeClient b) throws BridgeClient.BridgeException {
                        return b.launchId(appId);
                    }
                }, new Callback<JSONObject>() {
                    @Override
                    public void done(JSONObject r, String error) {
                        tc.ms += System.currentTimeMillis() - t0;
                        if (error != null) {
                            tc.state = ToolCall.FAILED;
                            tc.result = "Error: " + error;
                            log("error", "PC action failed · " + tc.label);
                        } else {
                            JSONObject app = r.optJSONObject("app");
                            String n = app != null ? OllamaClient.str(app, "name") : "";
                            tc.state = ToolCall.DONE;
                            tc.result = "Opened " + (n.length() > 0 ? n : name) + " on the PC.";
                            log("ok", "PC · opened " + (n.length() > 0 ? n : name));
                        }
                        afterCall(j);
                    }
                });
            }
        };
    }

    private void settleCall(Job j, ToolCall tc, String state, String result) {
        tc.state = state;
        tc.result = result;
        changed(j);
    }

    /**
     * A call ended: show it and carry on. After the reply ended (stopped, or
     * its chat left) the late result is still kept — in the open chat, or in
     * the reopened copy of it; a chat that is closed (or was deleted) isn't
     * written again.
     */
    private void afterCall(Job j) {
        changed(j);
        if (job == j) {
            nextCall(j);
        } else if (j.conv == conv) {
            save(conv);
        } else if (j.conv.id.equals(conv.id)) {
            ChatMessage open = conv.find(j.target.id);
            if (open == null) return;
            open.tools = j.target.tools;
            if (listener != null) listener.onMessageChanged(open);
            save(conv);
        }
    }

    /** Redraws the reply (its action log) when its chat is on screen. */
    private void changed(Job j) {
        if (listener != null && j.conv == conv && conv.messages.contains(j.target)) listener.onMessageChanged(j.target);
    }

    /** The reply ends: an open question is withdrawn, and calls that never ran say so. */
    private void endTools(Job j) {
        j.inRequest = false;
        ToolApproval a = j.approval;
        j.approval = null;
        main.removeCallbacks(approvalTimeout);
        for (ToolCall tc : j.target.tools) {
            if (ToolCall.QUEUED.equals(tc.state) || ToolCall.ASKING.equals(tc.state)) {
                tc.state = ToolCall.DECLINED;
                tc.result = ToolKit.STOPPED_RESULT;
            }
        }
        if (a != null) a.withdraw();
    }

    private boolean granted(String chatId, String tool) {
        java.util.Set<String> s = chatGrants.get(chatId);
        return s != null && s.contains(tool.toLowerCase(Locale.US));
    }

    private void grant(String chatId, String tool) {
        java.util.Set<String> s = chatGrants.get(chatId);
        if (s == null) {
            s = new java.util.HashSet<String>();
            chatGrants.put(chatId, s);
        }
        s.add(tool.toLowerCase(Locale.US));
    }

    /**
     * A tool's screenshot as a JPEG of at most {@link #TOOL_IMAGE_MAX_SIDE}
     * px (worker thread): what the chat keeps and a vision model is sent. A
     * small image stays as it is. Null when it doesn't decode: then it wasn't
     * an image at all (just a long base64-looking string), and nothing is shown
     * or sent as one.
     */
    static String shrinkImage(String b64) {
        android.graphics.Bitmap bmp = ImageUtil.decode(b64, TOOL_IMAGE_MAX_SIDE);
        if (bmp == null) return null;
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            float scale = Math.min(1f, TOOL_IMAGE_MAX_SIDE / (float) Math.max(1, Math.max(w, h)));
            if (scale >= 1f && b64.length() < 512 * 1024) return b64;
            if (scale < 1f) {
                android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
                if (s != bmp) bmp.recycle();
                bmp = s;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos);
            return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (RuntimeException e) {
            return b64;
        } catch (OutOfMemoryError e) {
            return b64;
        }
    }

    private void finish(Job j, ChatStats stats, String error, boolean cancelled, boolean wasLoaded) {
        if (job != j) return;
        main.removeCallbacks(j);
        j.copy();
        endTools(j);
        ChatMessage t = j.target;
        String failure = null;
        t.thinking = t.thinking.trim();
        t.streaming = false;
        t.ttftMs = j.ttft.get();
        if (stats != null) {
            t.stats = stats.summary() + (t.ttftMs >= 0 ? " · first token " + Fmt.seconds(t.ttftMs) : "");
            if (stats.evalMs > 0) lastSpeed = Fmt.oneDecimal(stats.tokensPerSecond()) + " tok/s";
            if (t.content.length() == 0 && t.thinking.length() == 0 && t.tools.isEmpty()) {
                t.stats = "empty reply · " + t.stats;
            }
            telemetry.reply(stats, t.ttftMs, j.numCtx);
            log("ok", "Reply · " + t.model + " · " + stats.evalTokens + " tok"
                    + (stats.evalMs > 0 ? " @ " + Fmt.oneDecimal(stats.tokensPerSecond()) + " tok/s" : "")
                    + (stats.reloaded() ? " · load " + Fmt.seconds(stats.loadMs) : ""));
            notifyTelemetry();
            if (settings.readAloud()) speech().feed(t.id, t.content, true);
        } else if (cancelled) {
            t.stopped = true;
            t.stats = "stopped";
            log("warn", "Reply stopped · " + t.model);
            speechStop();
        } else {
            ReplyError why = ReplyError.explain(error, t.model, j.sentImages);
            t.error = true;
            t.errorKind = why.kind;
            t.stats = why.stats();
            failure = ReplyError.plain(why.message);
            // Retrying (regenerate) then leaves the images out for this model.
            if (ReplyError.NO_VISION.equals(why.kind)) noVision.add(t.model);
            telemetry.errors++;
            log("error", "Reply failed · " + (error == null ? "unknown error" : Fmt.ellipsize(error, 90)));
            speechStop();
        }
        job = null;
        if (listener != null && j.conv == conv) listener.onMessageChanged(t);
        notifyBusy();
        save(j.conv);
        if (!visible && (stats != null || failure != null)) {
            // The user switched away while waiting: tell them it's done.
            notifier().reply(t.model, Fmt.ellipsize(SpeechText.speakable(t.content), 600), failure,
                    settings.incognito());
        }
        if (stats != null && wasLoaded && stats.reloaded() && !reloadHintShown) {
            reloadHintShown = true;
            notice("The PC had to reload **" + t.model + "** (" + Fmt.seconds(stats.loadMs) + "). That happens when "
                    + "the phone and OMNI-DECK on the PC ask for different context sizes or CPU thread counts. Set "
                    + "*Context size* and *CPU threads* in Settings to match the PC app to avoid it.", "info");
        }
        if (stats == null && !cancelled) checkHealth();
        refreshModels(null);
    }

    private static String stripLeadingBlank(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == ' ')) i++;
        return s.substring(i);
    }

    /**
     * Stops the reply — its request, the PC tool loop, a pending approval —
     * and a compaction or benchmark in progress.
     */
    public void stop() {
        Job j = job;
        if (j != null) {
            j.cancel.cancel();
            // Between requests (getting ready, running a PC tool, waiting for approval) nothing else
            // would end the reply: end it now. A request on the wire ends it when it's cut.
            if (!j.inRequest) finish(j, null, "Stopped.", true, j.wasLoaded);
        }
        stopAux();
    }

    /**
     * Marks a background model task (compact, benchmark) as running: new
     * messages wait for it ({@link #isWorking()}) and {@link #stop()} cancels
     * it. {@code notice} is the chat notice that reports on it.
     */
    private Cancellable startAux(String what, ChatMessage notice, boolean compacts) {
        auxBusy = true;
        auxCancel = new Cancellable();
        auxWhat = what;
        auxNotice = notice;
        auxOwner = conv;
        auxCompacts = compacts;
        notifyBusy();
        return auxCancel;
    }

    /**
     * Called when a background task's request returns. False when the task
     * was stopped meanwhile — the stop already cleared it and reported it, so
     * the late result is dropped.
     */
    private boolean endAux(Cancellable c) {
        if (c != auxCancel || c.isCancelled()) return false;
        clearAux();
        return true;
    }

    private void clearAux() {
        auxBusy = false;
        auxCancel = null;
        auxNotice = null;
        auxOwner = null;
        auxCompacts = false;
        notifyBusy();
    }

    /** Cancels the running compact / benchmark right away and says so in its notice. */
    private void stopAux() {
        Cancellable c = auxCancel;
        if (c == null) return;
        c.cancel();
        String what = auxWhat;
        ChatMessage n = auxNotice;
        Conversation owner = auxOwner;
        clearAux();
        log("warn", what + " stopped");
        if (n != null && owner != null) updateNotice(owner, n, what + " stopped.", "warn");
    }

    public void regenerate() {
        if (job != null) {
            toast("A reply is still streaming.");
            return;
        }
        if (auxBusy) {
            toast("OMNI is finishing a task (compact / benchmark) — try again in a moment.");
            return;
        }
        if (state != State.ONLINE || client == null) {
            toast("Your AI isn't connected.");
            return;
        }
        // Drop everything after the last user message, then answer it again.
        int lastUser = -1;
        for (int i = conv.messages.size() - 1; i >= 0; i--) {
            if (conv.messages.get(i).isUser()) {
                lastUser = i;
                break;
            }
        }
        if (lastUser < 0) {
            notice("Nothing to regenerate yet.", "info");
            return;
        }
        boolean removed = false;
        for (int i = conv.messages.size() - 1; i > lastUser; i--) {
            if (!conv.messages.get(i).isNotice()) {
                conv.messages.remove(i);
                removed = true;
            }
        }
        if (removed && listener != null) listener.onConversationReplaced();
        startReply();
    }

    /**
     * Roughly how full the model's context window is with the open chat
     * (0 = empty, 1 = full; can exceed 1). Past about 0.8 the oldest messages
     * are about to be dropped by Ollama — the moment to suggest /compact.
     */
    public double contextFill() {
        String model = currentModel();
        int ctx = runnerOptions(model).optInt("num_ctx", DEFAULT_CTX);
        if (ctx <= 0) return 0;
        long tokens = conv.estimateTokens(systemPrompt(), !Boolean.FALSE.equals(supportsVision(model)));
        List<BridgeTool> cat = toolsReady(model) ? toolCatalog() : null;
        if (cat != null) {
            // The tools' descriptions and their system-prompt note go with every request.
            tokens += (ToolKit.toolsArray(cat, true).toString().length() + ToolKit.systemPrompt(pcName()).length()) / 4;
        }
        return tokens / (double) ctx;
    }

    /**
     * The system prompt for a reply: who the assistant is and today's date
     * (the model knows neither), the user's persona and facts, and — when the
     * question was spoken — how to answer for the ear. Only the date changes,
     * once a day, so Ollama keeps reusing the cached prompt prefix.
     */
    String systemPromptFor(ChatMessage lastUser) {
        // The user's persona and facts lead; the context line follows.
        StringBuilder sb = new StringBuilder(systemPrompt());
        if (settings.assistantContext()) {
            StringBuilder ctx = new StringBuilder();
            if (settings.systemPrompt().trim().length() == 0) {
                ctx.append("You are OMNI, the user's personal AI assistant. You run on their own PC and they reach "
                        + "you from their phone through the OmniDeck app. ");
            }
            ctx.append("Today is ")
                    .append(new java.text.SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).format(new Date()))
                    .append('.');
            if (sb.length() == 0) sb.append(ctx);
            else sb.append("\n\n").append(ctx);
        }
        if (lastUser != null && lastUser.voice) {
            sb.append(sb.length() > 0 ? "\n\n" : "").append("This question was spoken and your answer will be "
                    + "read aloud. Answer in short, natural sentences, without Markdown, lists, tables, code or "
                    + "emoji, unless the user asks for detail.");
        }
        return sb.toString();
    }

    public String systemPrompt() {
        StringBuilder sb = new StringBuilder(settings.systemPrompt().trim());
        List<String> facts = settings.facts();
        if (!facts.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Facts to remember about the user:");
            for (String f : facts) sb.append("\n- ").append(f);
        }
        return sb.toString();
    }

    Object keepAlive() {
        return settings.keepLoaded() ? Integer.valueOf(-1) : "5m";
    }

    /** num_ctx/num_thread, matched to what's loaded so Ollama doesn't reload the model. */
    JSONObject runnerOptions(String model) {
        JSONObject o = new JSONObject();
        try {
            int ctx = settings.numCtx();
            if (ctx <= 0) {
                ModelInfo r = running.get(model);
                ctx = r != null && r.contextLength > 0 ? r.contextLength : DEFAULT_CTX;
            }
            o.put("num_ctx", ctx);
            if (settings.numThread() > 0) o.put("num_thread", settings.numThread());
        } catch (JSONException ignored) {
        }
        return o;
    }

    /**
     * Sampling settings (temperature, top_p, max tokens). They are not runner
     * options, so changing them never makes Ollama reload the model.
     */
    void addGenerationOptions(JSONObject o) {
        try {
            if (settings.temperature() >= 0) o.put("temperature", round2(settings.temperature()));
            if (settings.topP() >= 0) o.put("top_p", round2(settings.topP()));
            if (settings.maxTokens() > 0) o.put("num_predict", settings.maxTokens());
        } catch (JSONException ignored) {
        }
    }

    private static double round2(float v) {
        return Math.round(v * 100.0) / 100.0;
    }

    Speech speech() {
        if (speech == null) {
            speech = new Speech(app);
            speech.setRate(settings.speechRate());
            speech.setListener(new Speech.Listener() {
                @Override
                public void onUnavailable(String reason) {
                    // Said once for read-aloud in the background; every time the user asks directly.
                    if (!speechWarned || speechAsked) toast(reason);
                    if (!speechWarned) log("warn", "Voice unavailable · no working text-to-speech voice");
                    speechWarned = true;
                    speechAsked = false;
                }
            });
        }
        return speech;
    }

    private boolean speechWarned;
    /** The user just asked for speech (test voice, read a message, read-aloud on): report failures. */
    private boolean speechAsked;

    /** False once the phone's text-to-speech turned out to be missing or broken (true while unknown). */
    public boolean speechAvailable() {
        return speech == null || speech.available();
    }

    /** Turns read-aloud on or off (the /mute command and the speaker toggles). */
    public void setReadAloud(boolean on) {
        settings.setReadAloud(on);
        if (!on) {
            speechStop();
        } else {
            Speech s = speech();
            speechAsked = true;
            s.retry();
            s.setRate(settings.speechRate());
            s.prepare();
            if (job != null) s.skip(job.target.id, job.target.content.length());
        }
        notifyState();
    }

    public void setSpeechRate(float rate) {
        settings.setSpeechRate(rate);
        if (speech != null) speech.setRate(settings.speechRate());
    }

    /** Speaks one line if read-aloud is on (status announcements). */
    public void announce(String line) {
        if (settings.readAloud()) speech().say(line);
    }

    public void speechStop() {
        if (speech != null) speech.stop();
    }

    /** Reads one message aloud right away, interrupting anything already speaking. */
    public void speakNow(String text) {
        if (text == null || text.trim().length() == 0) return;
        Speech s = speech();
        speechAsked = true;
        s.retry();
        s.setRate(settings.speechRate());
        s.stop();
        s.say(text);
    }

    /** True while the phone is speaking a reply or announcement. */
    public boolean speaking() {
        return speech != null && speech.speaking();
    }

    /** Only sent to models that support thinking — others reject the field. */
    Object thinkFor(String model, boolean deep) {
        Boolean sup = thinkSupport.get(model);
        if (sup == null || !sup) return null;
        if (!deep) return Boolean.FALSE;
        return model.toLowerCase(Locale.US).contains("gpt-oss") ? "high" : Boolean.TRUE;
    }

    static boolean looksHard(String prompt) {
        return prompt.length() > 600 || HARD.matcher(prompt).find();
    }

    // ------------------------------------------------------------------
    // Model control
    // ------------------------------------------------------------------

    public void setModel(String name) {
        settings.setModel(name);
        ensureCapabilities(name);
        notifyState();
    }

    public void setMode(String mode) {
        settings.setMode(mode);
        notifyState();
    }

    public void setDeepModel(String name) {
        settings.setDeepModel(name);
        if (name.length() > 0) ensureCapabilities(name);
        notifyState();
    }

    private boolean requireOnline() {
        if (state == State.ONLINE && client != null) return true;
        notice("Your AI isn't connected yet — it's being searched for on this network.", "warn");
        return false;
    }

    public void listModels() {
        if (!requireOnline()) return;
        refreshModels(new Runnable() {
            @Override
            public void run() {
                if (models.isEmpty()) {
                    notice("No models installed on the PC. Download one with `/pull llama3.2`.", "warn");
                    return;
                }
                String cur = currentModel();
                StringBuilder sb = new StringBuilder("**Models on the PC** (").append(models.size()).append(")\n");
                for (ModelInfo m : models) {
                    sb.append(m.name.equals(cur) ? "▸ " : "• ").append('`').append(m.name).append('`');
                    String d = m.describe();
                    if (d.length() > 0) sb.append(" — ").append(d);
                    if (running.containsKey(m.name)) sb.append(" · **loaded**");
                    sb.append('\n');
                }
                sb.append("Switch with `/model <name>`.");
                notice(sb.toString(), "info");
            }
        });
    }

    public void listRunning() {
        if (!requireOnline()) return;
        refreshModels(new Runnable() {
            @Override
            public void run() {
                if (running.isEmpty()) {
                    notice("No model is loaded in memory right now. `/warm` loads the current one.", "info");
                    return;
                }
                StringBuilder sb = new StringBuilder("**Loaded in memory**\n");
                for (ModelInfo m : running.values()) {
                    sb.append("• `").append(m.name).append('`');
                    if (m.size > 0) {
                        sb.append(" — ").append(Fmt.bytes(m.size));
                        if (m.sizeVram > 0) sb.append(" (").append(m.sizeVram >= m.size ? "all" : Fmt.bytes(m.sizeVram))
                                .append(" on GPU)");
                    }
                    if (m.contextLength > 0) sb.append(" · ctx ").append(m.contextLength);
                    if (m.expiresAt.startsWith("2") && m.expiresAt.compareTo("2200") > 0) sb.append(" · stays loaded");
                    sb.append('\n');
                }
                notice(sb.toString().trim(), "info");
            }
        });
    }

    public void warm() {
        if (!requireOnline()) return;
        final String model = currentModel();
        final Conversation owner = conv;
        final ChatMessage n = notice("Loading **" + model + "** into memory…", "info");
        final OllamaClient c = client;
        final JSONObject opts = runnerOptions(model);
        final Object ka = keepAlive();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                long ms = 0;
                try {
                    ms = c.loadModel(model, ka, opts);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String fErr = err;
                final long fMs = ms;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fErr != null) {
                            updateNotice(owner, n, "Couldn't load **" + model + "**: " + fErr, "error");
                            log("error", "Load failed · " + model);
                        } else {
                            updateNotice(owner, n, "**" + model + "** is loaded and ready (" + Fmt.seconds(fMs) + ").", "ok");
                            log("ok", "Model online · " + model + " (" + Fmt.seconds(fMs) + ")");
                            telemetry.lastLoadMs = fMs;
                            notifyTelemetry();
                        }
                        refreshModels(null);
                    }
                });
            }
        });
    }

    public void unload(String name) {
        if (!requireOnline()) return;
        final String model = name == null || name.trim().length() == 0 ? currentModel() : resolveOrSelf(name);
        final Conversation owner = conv;
        final ChatMessage n = notice("Unloading **" + model + "**…", "info");
        final OllamaClient c = client;
        final boolean embed = isEmbeddingOnly(model);
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (embed) c.loadEmbedModel(model, 0);
                    else c.loadModel(model, 0, null);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String fErr = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fErr != null) {
                            updateNotice(owner, n, "Couldn't unload **" + model + "**: " + fErr, "error");
                        } else {
                            updateNotice(owner, n, "Unloaded **" + model + "** — its memory is free on the PC.", "ok");
                            log("info", "Model offline · " + model + " (memory released)");
                        }
                        refreshModels(null);
                    }
                });
            }
        });
    }

    /**
     * Loads ({@code load} true) or releases one specific model without
     * switching to it and without chat notices — the model bay reports in
     * place. Uses the same runner options chat will send, so the first reply
     * doesn't reload it. Logs, records the load time, refreshes the model
     * list, then calls back (value = elapsed ms) on the main thread.
     */
    public void setLoaded(final String model, final boolean load, final Callback<Long> cb) {
        if (state != State.ONLINE || client == null) {
            cb.done(null, "Not connected to your AI.");
            return;
        }
        final OllamaClient c = client;
        final boolean embed = isEmbeddingOnly(model);
        final Object ka = load ? keepAlive() : Integer.valueOf(0);
        final JSONObject opts = load && !embed ? runnerOptions(model) : null;
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                long ms = 0;
                try {
                    ms = embed ? c.loadEmbedModel(model, ka) : c.loadModel(model, ka, opts);
                } catch (IOException e) {
                    err = e.getMessage() == null ? "request failed" : e.getMessage();
                }
                final String fErr = err;
                final long fMs = ms;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fErr != null) {
                            log("error", (load ? "Load failed · " : "Unload failed · ") + model);
                            cb.done(null, fErr);
                            return;
                        }
                        if (load) {
                            telemetry.lastLoadMs = fMs;
                            log("ok", "Model online · " + model + " (" + Fmt.seconds(fMs) + ")");
                        } else {
                            log("info", "Model offline · " + model + " (memory released)");
                        }
                        notifyTelemetry();
                        refreshModels(new Runnable() {
                            @Override
                            public void run() {
                                cb.done(fMs, null);
                            }
                        });
                    }
                });
            }
        });
    }

    private String resolveOrSelf(String name) {
        String r = resolveInstalled(name);
        return r != null ? r : name.trim();
    }

    public void pull(final String name) {
        final String n0 = name == null ? "" : name.trim();
        if (n0.equalsIgnoreCase("stop") || n0.equalsIgnoreCase("cancel")) {
            if (pullCancel != null) pullCancel.cancel();
            else notice("No download is running.", "info");
            return;
        }
        if (n0.length() == 0) {
            notice("Usage: `/pull <model>` — e.g. `/pull llama3.2` or `/pull qwen3:8b`. Browse names at ollama.com/library.", "info");
            return;
        }
        if (!requireOnline()) return;
        if (pullCancel != null) {
            notice("A download is already running. `/pull stop` cancels it.", "warn");
            return;
        }
        final Cancellable cancel = pullCancel = new Cancellable();
        syncWork();
        final PullState ps = pullState = new PullState(n0);
        if (listener != null) listener.onPull();
        log("info", "Download started · " + n0);
        final Conversation owner = conv;
        final ChatMessage n = notice("Downloading **" + n0 + "** onto the PC…", "info");
        final OllamaClient c = client;
        final long[] lastUi = {0};
        io.execute(new Runnable() {
            @Override
            public void run() {
                c.pull(n0, cancel, new OllamaClient.PullListener() {
                    @Override
                    public void onProgress(final String status, final long completed, final long total) {
                        long now = System.currentTimeMillis();
                        if (now - lastUi[0] < 250) return;
                        lastUi[0] = now;
                        final long at = now;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                ps.status = status;
                                if (total > 0) {
                                    long dt = at - ps.lastAt;
                                    if (completed >= ps.lastBytes && dt > 0 && ps.total == total) {
                                        double inst = (completed - ps.lastBytes) * 1000.0 / dt;
                                        ps.bytesPerSec = ps.bytesPerSec <= 0 ? inst : ps.bytesPerSec * 0.7 + inst * 0.3;
                                    }
                                    ps.total = total;
                                    ps.completed = completed;
                                    ps.lastBytes = completed;
                                    ps.lastAt = at;
                                }
                                if (listener != null) listener.onPull();
                            }
                        });
                        final String text = "Downloading **" + n0 + "** — " + status
                                + (total > 0 ? " · " + (completed * 100 / total) + "% (" + Fmt.bytes(completed) + " / "
                                + Fmt.bytes(total) + ")" : "") + "\n`/pull stop` cancels.";
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                showProgress(n, text);
                            }
                        });
                    }

                    @Override
                    public void onDone() {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                pullCancel = null;
                                syncWork();
                                ps.done = true;
                                ps.status = "success";
                                ps.completed = ps.total;
                                if (listener != null) listener.onPull();
                                updateNotice(owner, n, "**" + n0 + "** is downloaded. Use it with `/model " + n0 + "`.", "ok");
                                log("ok", "Download complete · " + n0);
                                if (!visible) notifier().download(n0, true, null);
                                refreshModels(null);
                            }
                        });
                    }

                    @Override
                    public void onError(final String message, final boolean cancelled) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                pullCancel = null;
                                syncWork();
                                ps.done = true;
                                ps.error = cancelled ? "stopped" : message;
                                ReplyError why = cancelled ? null : ReplyError.explainPull(message, n0);
                                ps.reason = why == null ? null : ReplyError.plain(why.message);
                                if (listener != null) listener.onPull();
                                updateNotice(owner, n, cancelled ? "Download of **" + n0 + "** stopped."
                                        : "Download of **" + n0 + "** failed: " + why.message
                                        + (why.kind.equals(ReplyError.OTHER) ? "" : "\n`" + message + "`"),
                                        cancelled ? "warn" : "error");
                                log(cancelled ? "warn" : "error", (cancelled ? "Download stopped · " : "Download failed · ") + n0);
                                if (!visible && why != null) notifier().download(n0, false, ps.reason);
                            }
                        });
                    }
                });
            }
        });
    }

    /** Fetches /api/show details for a model (cached). */
    public void fetchDetails(final String model, final Callback<OllamaClient.ModelDetails> cb) {
        OllamaClient.ModelDetails cached = details.get(model);
        if (cached != null) {
            cb.done(cached, null);
            return;
        }
        final OllamaClient c = client;
        if (c == null) {
            cb.done(null, "Not connected.");
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                OllamaClient.ModelDetails d = null;
                String err = null;
                try {
                    d = c.show(model);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final OllamaClient.ModelDetails fd = d;
                final String fe = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fd != null && c == client) {
                            boolean fresh = !details.containsKey(model);
                            details.put(model, fd);
                            thinkSupport.put(model, fd.supports("thinking"));
                            // What the active model can do (tools, vision…) shows up in the UI.
                            if (fresh && model.equals(currentModel())) notifyState();
                        }
                        cb.done(fd, fd == null ? (fe == null ? "No details." : fe) : null);
                    }
                });
            }
        });
    }

    /** Deletes a model from the PC's disk. */
    public void deleteModel(final String model, final Callback<Boolean> cb) {
        final OllamaClient c = client;
        if (c == null) {
            cb.done(null, "Not connected.");
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    c.deleteModel(model);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String fe = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fe == null) {
                            log("warn", "Model deleted · " + model);
                            boolean wasDeep = model.equals(resolveInstalled(settings.deepModel()));
                            // Forget it everywhere BEFORE anyone asks currentModel() again, or
                            // the fallback could pick (and save) the model that's just gone.
                            for (int i = models.size() - 1; i >= 0; i--) {
                                if (models.get(i).name.equals(model)) models.remove(i);
                            }
                            running.remove(model);
                            details.remove(model);
                            thinkSupport.remove(model);
                            if (model.equals(settings.model())) settings.setModel("");
                            if (wasDeep) settings.setDeepModel("");
                            notifyState();
                            refreshModels(null);
                            cb.done(Boolean.TRUE, null);
                        } else {
                            cb.done(null, fe);
                        }
                    }
                });
            }
        });
    }

    /** Checks whether LaunchBridge answers (no auth needed). */
    public void bridgeHealth(final Callback<JSONObject> cb) {
        bridgeAsync(new BridgeCall<JSONObject>() {
            @Override
            public JSONObject run(BridgeClient b) throws BridgeClient.BridgeException {
                return b.health();
            }
        }, new Callback<JSONObject>() {
            @Override
            public void done(JSONObject v, String error) {
                Boolean was = bridgeOnline;
                bridgeOnline = error == null;
                if (was == null || was != bridgeOnline) {
                    log(bridgeOnline ? "ok" : "warn", bridgeOnline ? "PC bridge online" : "PC bridge unreachable");
                    notifyState();
                }
                cb.done(v, error);
            }
        });
    }

    /** Reads CPU / RAM / disk / battery from the PC (get_system_info). */
    public void bridgeVitals(final Callback<Vitals> cb) {
        bridgeAsync(new BridgeCall<Object>() {
            @Override
            public Object run(BridgeClient b) throws BridgeClient.BridgeException {
                Object r = b.deskRun("get_system_info", null);
                return r == null ? "" : r;
            }
        }, new Callback<Object>() {
            @Override
            public void done(Object raw, String error) {
                Vitals v = raw == null ? null : Vitals.parse(raw);
                if (v != null) {
                    lastVitals = v;
                    lastVitalsAt = System.currentTimeMillis();
                    bridgeOnline = Boolean.TRUE;
                    learnMac(raw);
                    notifyTelemetry();
                }
                cb.done(v, error);
            }
        });
    }

    /** The AI read the PC's system info: the command center's vitals (and the PC's name) are fresh too. */
    private void noteVitals(Object raw) {
        Vitals v = Vitals.parse(raw);
        if (!v.hasAny()) return;
        lastVitals = v;
        lastVitalsAt = System.currentTimeMillis();
        bridgeOnline = Boolean.TRUE;
        notifyTelemetry();
    }

    /**
     * Remembers the PC's MAC address for Wake-on-LAN when its system info
     * names one and none is set yet (a MAC typed by the user always wins).
     */
    private void learnMac(Object systemInfo) {
        if (settings.pcMac().length() > 0) return;
        String mac = WakeOnLan.findMac(systemInfo);
        if (mac == null) return;
        settings.setPcMac(mac);
        log("ok", "Wake-on-LAN ready · learned the PC's MAC " + mac);
    }

    /** Searches the PC's installed apps (LaunchBridge index). */
    public void bridgeApps(final String query, final int limit, Callback<JSONArray> cb) {
        bridgeAsync(new BridgeCall<JSONArray>() {
            @Override
            public JSONArray run(BridgeClient b) throws BridgeClient.BridgeException {
                return b.apps(query, limit);
            }
        }, cb);
    }

    /** Names of the desktop tools the bridge offers. */
    public void bridgeCapabilities(final Callback<List<String>> cb) {
        bridgeTools(new Callback<List<BridgeTool>>() {
            @Override
            public void done(List<BridgeTool> tools, String error) {
                if (tools == null) {
                    cb.done(null, error);
                    return;
                }
                List<String> names = new ArrayList<String>();
                for (BridgeTool t : tools) names.add(t.name);
                cb.done(names, null);
            }
        });
    }

    /**
     * The desktop tools the bridge offers, each with its description and
     * arguments (types, required, choices, defaults) for building forms.
     */
    public void bridgeTools(final Callback<List<BridgeTool>> cb) {
        final String key = toolKey();
        bridgeAsync(new BridgeCall<List<BridgeTool>>() {
            @Override
            public List<BridgeTool> run(BridgeClient b) throws BridgeClient.BridgeException {
                return BridgeTool.parseAll(b.deskCapabilities());
            }
        }, new Callback<List<BridgeTool>>() {
            @Override
            public void done(List<BridgeTool> tools, String error) {
                // The same list the AI's tools come from: keep it.
                if (tools != null && bridgePaired()) cacheCatalog(key, tools);
                cb.done(tools, error);
            }
        });
    }

    /** Runs any desktop tool and returns its raw result. */
    public void bridgeRun(final String tool, final JSONObject args, final Callback<Object> cb) {
        bridgeAsync(new BridgeCall<Object>() {
            @Override
            public Object run(BridgeClient b) throws BridgeClient.BridgeException {
                Object r = b.deskRun(tool, args);
                return r == null ? "" : r;
            }
        }, new Callback<Object>() {
            @Override
            public void done(Object v, String error) {
                if (error == null) {
                    log("info", "PC · " + tool);
                    if ("get_system_info".equals(tool)) learnMac(v);
                }
                cb.done(v, error);
            }
        });
    }

    /** Locks the PC's screen with the bridge's lock tool (lock_screen or similar); value = what the PC said. */
    public void lockPc(final Callback<String> cb) {
        bridgeAsync(new BridgeCall<String>() {
            @Override
            public String run(BridgeClient b) throws BridgeClient.BridgeException {
                BridgeTool lock = BridgeTool.lockTool(BridgeTool.parseAll(b.deskCapabilities()));
                if (lock == null) {
                    throw new BridgeClient.BridgeException("The PC bridge has no tool to lock the screen — update "
                            + "LaunchBridge on the PC.", 404);
                }
                String said = formatResult(b.deskRun(lock.name, null)).trim();
                return said.length() > 0 ? said : "PC locked.";
            }
        }, new Callback<String>() {
            @Override
            public void done(String said, String error) {
                if (error == null) log("ok", "PC locked");
                cb.done(said, error);
            }
        });
    }

    /**
     * Wakes the sleeping PC with a Wake-on-LAN magic packet: a UDP broadcast
     * on port 9 to 255.255.255.255 and to each Wi-Fi subnet's broadcast
     * address, for the MAC in Settings › PC bridge (learned automatically
     * from the PC's system info once paired). The value is a line for the
     * user; the PC must have Wake-on-LAN enabled.
     */
    public void wakePc(final Callback<String> cb) {
        final String typed = settings.pcMac();
        final byte[] mac = WakeOnLan.parseMac(typed);
        if (mac == null) {
            cb.done(null, typed.length() == 0
                    ? "Wake-on-LAN needs the PC's MAC address — enter it in Settings › PC bridge. Once the bridge is "
                    + "paired, the phone also picks it up from the PC's system info."
                    : "“" + typed + "” isn't a MAC address (it looks like AA:BB:CC:DD:EE:FF).");
            return;
        }
        final List<LanScanner.Subnet> nets = testSubnets != null ? testSubnets : Net.refresh(app);
        final int port = testWolPort > 0 ? testWolPort : WakeOnLan.PORT;
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<String> targets = new ArrayList<String>();
                targets.add("255.255.255.255");
                for (LanScanner.Subnet s : nets) {
                    String b = WakeOnLan.broadcast(s.address, s.prefix);
                    if (!targets.contains(b)) targets.add(b);
                }
                byte[] packet = WakeOnLan.packet(mac);
                int sent = 0;
                String err = null;
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    socket.setBroadcast(true);
                    Net.bindToLan(socket);
                    for (String t : targets) {
                        // Three copies each: a single UDP packet is easily lost on Wi-Fi.
                        for (int i = 0; i < 3; i++) {
                            try {
                                socket.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(t), port));
                                sent++;
                            } catch (IOException e) {
                                err = e.getMessage();
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    err = e.getMessage();
                } catch (RuntimeException e) {
                    err = String.valueOf(e);
                } finally {
                    if (socket != null) socket.close();
                }
                final int fSent = sent;
                final String fErr = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        String m = WakeOnLan.format(mac);
                        if (fSent == 0) {
                            log("error", "Wake-on-LAN failed · " + m);
                            cb.done(null, "Couldn't send the wake-up packet" + (fErr != null ? ": " + fErr : "")
                                    + ". Is the phone on the PC's Wi-Fi?");
                            return;
                        }
                        log("ok", "Wake-on-LAN sent · " + m);
                        if (state != State.ONLINE) watchWake();
                        cb.done("Wake-up packet sent to " + m + ". If Wake-on-LAN is on in the PC's BIOS and network "
                                + "adapter, it will be up in a few seconds.", null);
                    }
                });
            }
        });
    }

    /** Pairs with the bridge; callback gets the token (saved, bound to the host that issued it). */
    public void bridgePair(final Callback<String> cb) {
        final String host = bridgeHost();
        if (bridgeOverTls()) {
            cb.done(null, TLS_BRIDGE_HINT);
            return;
        }
        bridgeAsync(new BridgeCall<String>() {
            @Override
            public String run(BridgeClient b) throws BridgeClient.BridgeException {
                return b.pair();
            }
        }, new Callback<String>() {
            @Override
            public void done(String token, String error) {
                if (token != null) {
                    settings.setBridgeToken(token);
                    settings.setBridgeTokenHost(host);
                    bridgeOnline = Boolean.TRUE;
                    log("ok", "PC bridge paired");
                    notifyState();
                    // What the AI can do on this PC.
                    if (settings.aiTools()) refreshToolCatalog(null);
                }
                cb.done(token, error);
            }
        });
    }

    /** Runs a short fixed prompt and reports load / prompt / generation speed. */
    public void bench() {
        if (!requireOnline()) return;
        if (auxBusy) {
            toast("Already working on it.");
            return;
        }
        final String model = currentModel();
        final Conversation owner = conv;
        final ChatMessage n = notice("Benchmarking **" + model + "**…", "info");
        final Cancellable cancel = startAux("Benchmark", n, false);
        final OllamaClient c = client;
        JSONObject opts = runnerOptions(model);
        try {
            opts.put("num_predict", 64);
            opts.put("temperature", 0);
        } catch (JSONException ignored) {
        }
        JSONArray msgs = new JSONArray();
        try {
            msgs.put(new JSONObject().put("role", "user").put("content", "Write two sentences about the ocean."));
        } catch (JSONException ignored) {
        }
        final JSONObject body = OllamaClient.chatBody(model, msgs, thinkFor(model, false), keepAlive(), opts);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final ChatStats[] st = new ChatStats[1];
                final String[] err = new String[1];
                c.chat(body, cancel, new OllamaClient.ChatListener() {
                    @Override
                    public void onThinking(String delta) {
                    }

                    @Override
                    public void onContent(String delta) {
                    }

                    @Override
                    public void onToolCalls(List<ToolCall> calls) {
                    }

                    @Override
                    public void onDone(ChatStats stats) {
                        st[0] = stats;
                    }

                    @Override
                    public void onError(String message, boolean cancelled) {
                        err[0] = message;
                    }
                });
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!endAux(cancel)) return;
                        if (st[0] == null) {
                            updateNotice(owner, n, "Benchmark failed: " + err[0], "error");
                            return;
                        }
                        ChatStats s = st[0];
                        updateNotice(owner, n, "**Benchmark — " + model + "**\n"
                                + "Load: " + Fmt.seconds(s.loadMs) + "\n"
                                + "Prompt: " + s.promptTokens + " tok @ " + Fmt.oneDecimal(s.promptTokensPerSecond()) + " tok/s\n"
                                + "Generate: " + s.evalTokens + " tok @ " + Fmt.oneDecimal(s.tokensPerSecond()) + " tok/s\n"
                                + "Total: " + Fmt.seconds(s.totalMs), "ok");
                        if (s.evalMs > 0) lastSpeed = Fmt.oneDecimal(s.tokensPerSecond()) + " tok/s";
                        notifyState();
                        refreshModels(null);
                    }
                });
            }
        });
    }

    public void summarize() {
        int sent = 0;
        for (ChatMessage m : conv.messages) {
            if (m.sentToModel()) sent++;
        }
        if (sent < 2) {
            notice("Nothing to summarize yet.", "info");
            return;
        }
        send(SUMMARIZE_PROMPT);
    }

    /** Replaces the history with a model-written summary plus the last exchange. */
    public void compact() {
        if (!requireOnline()) return;
        if (job != null || auxBusy) {
            toast("Wait for the current reply first.");
            return;
        }
        final List<ChatMessage> sent = new ArrayList<ChatMessage>();
        for (ChatMessage m : conv.messages) {
            if (m.sentToModel()) sent.add(m);
        }
        if (sent.size() < 4) {
            notice("Nothing to compact yet — the chat is still short.", "info");
            return;
        }
        final Conversation target = conv;
        final String model = currentModel();
        JSONArray msgs = conv.toRequestMessages(systemPrompt(), null, Boolean.TRUE.equals(supportsVision(model)),
                Boolean.TRUE.equals(supportsTools(model)));
        try {
            msgs.put(new JSONObject().put("role", "user").put("content", COMPACT_PROMPT));
        } catch (JSONException ignored) {
        }
        // Everything already in the chat; what's added while the summary is written is kept after it.
        final List<ChatMessage> before = new ArrayList<ChatMessage>(conv.messages);
        final ChatMessage n = notice("Compacting the conversation…", "info");
        final Cancellable cancel = startAux("Compaction", n, true);
        final JSONObject body = OllamaClient.chatBody(model, msgs, thinkFor(model, false), keepAlive(),
                runnerOptions(model));
        final OllamaClient c = client;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final StringBuilder out = new StringBuilder();
                final String[] err = new String[1];
                c.chat(body, cancel, new OllamaClient.ChatListener() {
                    @Override
                    public void onThinking(String delta) {
                    }

                    @Override
                    public void onContent(String delta) {
                        out.append(delta);
                    }

                    @Override
                    public void onToolCalls(List<ToolCall> calls) {
                        // Compaction offers no tools.
                    }

                    @Override
                    public void onDone(ChatStats stats) {
                    }

                    @Override
                    public void onError(String message, boolean cancelled) {
                        err[0] = message;
                    }
                });
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!endAux(cancel)) return;
                        String summary = out.toString().trim();
                        if (err[0] != null || summary.length() == 0) {
                            updateNotice(target, n, "Couldn't compact: " + (err[0] != null ? err[0] : "empty summary"),
                                    "error");
                            return;
                        }
                        if (conv != target) return;
                        if (job != null) {
                            // A reply streams into this chat: rewriting it now would detach that reply.
                            updateNotice(target, n, "Compaction skipped — a reply started meanwhile. Run `/compact` "
                                    + "again when it's done.", "warn");
                            return;
                        }
                        for (ChatMessage m : sent) {
                            if (!conv.messages.contains(m)) {
                                // Edited or deleted meanwhile: rewriting now would bring it back.
                                updateNotice(target, n, "Compaction skipped — the chat changed while the summary "
                                        + "was written. Run `/compact` again.", "warn");
                                return;
                            }
                        }
                        List<ChatMessage> added = new ArrayList<ChatMessage>();
                        for (ChatMessage m : conv.messages) {
                            if (m != n && !before.contains(m)) added.add(m);
                        }
                        List<ChatMessage> keep = new ArrayList<ChatMessage>();
                        int keepFrom = Math.max(0, sent.size() - 2);
                        if (!sent.get(keepFrom).isUser() && keepFrom + 1 < sent.size()) keepFrom++;
                        for (int i = keepFrom; i < sent.size(); i++) keep.add(sent.get(i));
                        int dropped = sent.size() - keep.size();
                        conv.messages.clear();
                        conv.messages.add(new ChatMessage(ChatMessage.SYSTEM,
                                "Summary of the earlier conversation:\n" + summary));
                        conv.messages.addAll(keep);
                        conv.messages.add(ChatMessage.notice("Compacted " + dropped
                                + " messages into a summary to free up context.", "ok"));
                        conv.messages.addAll(added);
                        log("ok", "Chat compacted · " + dropped + " messages → summary");
                        if (listener != null) listener.onConversationReplaced();
                        save();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Facts & system prompt
    // ------------------------------------------------------------------

    public void remember(String fact) {
        List<String> f = settings.facts();
        f.add(fact.trim());
        settings.setFacts(f);
        notice("Remembered: *" + fact.trim() + "* (" + f.size() + (f.size() == 1 ? " fact)" : " facts)"), "ok");
    }

    public void listFacts() {
        List<String> f = settings.facts();
        if (f.isEmpty()) {
            notice("Nothing remembered yet. Add one with `/remember <fact>`.", "info");
            return;
        }
        StringBuilder sb = new StringBuilder("**Remembered facts** (sent with every message)\n");
        for (int i = 0; i < f.size(); i++) sb.append(i + 1).append(". ").append(f.get(i)).append('\n');
        sb.append("Remove one with `/forget <number or text>`.");
        notice(sb.toString(), "info");
    }

    public void forget(String arg) {
        List<String> f = settings.facts();
        String a = arg.trim();
        if (a.startsWith("#")) a = a.substring(1);
        int idx = -1;
        try {
            idx = Integer.parseInt(a) - 1;
        } catch (NumberFormatException e) {
            for (int i = 0; i < f.size(); i++) {
                if (f.get(i).toLowerCase(Locale.US).contains(a.toLowerCase(Locale.US))) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0 || idx >= f.size()) {
            notice("No remembered fact matches “" + arg + "”. See `/facts`.", "warn");
            return;
        }
        String removed = f.remove(idx);
        settings.setFacts(f);
        notice("Forgot: *" + removed + "*", "ok");
    }

    // ------------------------------------------------------------------
    // PC control via LaunchBridge
    // ------------------------------------------------------------------

    /**
     * The host LaunchBridge is reached at: the Bridge address from settings
     * when set, else the PC running the AI (current link, or the last one
     * that worked). "" when neither is known yet.
     */
    public String bridgeHost() {
        String h = settings.bridgeHost();
        if (h.length() > 0) return h;
        return server != null ? server.host : settings.lastHost();
    }

    /**
     * A client for the bridge host in use. The token is attached only when
     * that host issued it: the Ollama host (and so the default bridge host)
     * can be any machine on a foreign network, and the token opens the PC.
     */
    static final String TLS_BRIDGE_HINT = "Your AI is reached over https, but the PC bridge speaks plain http — "
            + "enter the bridge's own address (a LAN or VPN IP) in Settings › PC bridge, so its token never "
            + "travels unencrypted.";

    /** No bridge address of its own, and the AI's address is https: the bridge would ride that route in the clear. */
    boolean bridgeOverTls() {
        if (settings.bridgeHost().length() > 0) return false;
        return server != null ? server.https : settings.lastHttps() && settings.lastHost().length() > 0;
    }

    private BridgeClient bridge() {
        String host = bridgeHost();
        if (host.length() == 0) return null;
        String saved = settings.bridgeToken();
        if (saved.length() > 0 && settings.bridgeTokenHost().length() == 0) {
            // A token from before tokens were bound (or typed in Settings): bind it to the PC it's
            // first used with, so it is never sent to another host afterwards.
            settings.setBridgeTokenHost(host);
        }
        if (bridgeOverTls()) {
            // The AI is reached over https (a reverse proxy, often across the internet) but the bridge
            // speaks plain http: never send the token that opens the PC along that route.
            return new BridgeClient(host, settings.bridgePort(), "", TLS_BRIDGE_HINT);
        }
        String token = bridgeTokenFor(host);
        String hint = saved.length() > 0 && token.length() == 0
                ? "This phone is paired with the PC bridge at " + settings.bridgeTokenHost() + ", not " + host
                + " — run /pair to pair with this PC." : null;
        return new BridgeClient(host, settings.bridgePort(), token, hint);
    }

    private interface BridgeCall<T> {
        T run(BridgeClient b) throws BridgeClient.BridgeException;
    }

    private <T> void bridgeAsync(final BridgeCall<T> call, final Callback<T> cb) {
        final BridgeClient b = bridge();
        if (b == null) {
            cb.done(null, "Set the PC's address first — connect to your AI, or enter the bridge address in Settings › PC bridge.");
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                T v = null;
                String err = null;
                try {
                    v = call.run(b);
                } catch (BridgeClient.BridgeException e) {
                    err = e.getMessage();
                } catch (RuntimeException e) {
                    err = "Bridge error: " + e;
                }
                final T fv = v;
                final String fe = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(fe == null ? fv : null, fe);
                    }
                });
            }
        });
    }

    public void bridgePair() {
        final BridgeClient b0 = bridge();
        final String where = b0 == null ? "the PC" : b0.where();
        bridgePair(new Callback<String>() {
            @Override
            public void done(String token, String error) {
                if (error != null) {
                    notice("Pairing failed: " + error, "error");
                    return;
                }
                notice("Paired with LaunchBridge on " + where + ". `/open`, `/vol`, `/sys`, `/shot` and "
                        + "`/pcclip` now control the PC.", "ok");
            }
        });
    }

    public void bridgeStatus() {
        bridgeAsync(new BridgeCall<String>() {
            @Override
            public String run(BridgeClient b) throws BridgeClient.BridgeException {
                JSONObject h = b.health();
                StringBuilder sb = new StringBuilder("**PC bridge** — LaunchBridge at `").append(b.where()).append("`\n");
                sb.append("Status: online");
                if (h.has("apps_indexed")) sb.append(" · ").append(h.optInt("apps_indexed")).append(" apps indexed");
                sb.append("\nPaired: ").append(b.paired() ? "yes" : "no — run `/pair`");
                if (b.paired()) {
                    try {
                        List<BridgeTool> tools = BridgeTool.parseAll(b.deskCapabilities());
                        if (!tools.isEmpty()) {
                            sb.append("\nDesktop tools: ");
                            for (int i = 0; i < tools.size(); i++) {
                                if (i > 0) sb.append(", ");
                                sb.append('`').append(tools.get(i).name).append('`');
                            }
                        }
                    } catch (BridgeClient.BridgeException e) {
                        sb.append("\nDesktop control: ").append(e.getMessage());
                    }
                }
                return sb.toString();
            }
        }, new Callback<String>() {
            @Override
            public void done(String text, String error) {
                if (error != null) notice(error, "warn");
                else notice(text, "ok");
            }
        });
    }

    /** Runs a desktop tool and posts its result as a notice. */
    public void bridgeTool(final String tool, final JSONObject args, final String title) {
        bridgeAsync(new BridgeCall<Object>() {
            @Override
            public Object run(BridgeClient b) throws BridgeClient.BridgeException {
                Object r = b.deskRun(tool, args);
                return r == null ? "" : r;
            }
        }, new Callback<Object>() {
            @Override
            public void done(Object r, String error) {
                if (error != null) {
                    notice(title + " failed: " + error, "error");
                    return;
                }
                if ("get_system_info".equals(tool)) learnMac(r);
                if ("get_clipboard".equals(tool)) {
                    String text = r instanceof String ? (String) r : formatResult(r);
                    if (listener != null) listener.onInsertText(text);
                    toast("PC clipboard pasted into the composer.");
                    return;
                }
                if ("screenshot".equals(tool)) {
                    String img = extractImage(r);
                    ChatMessage m = ChatMessage.notice(img != null ? "**PC screenshot**" : "**Screenshot** — "
                            + formatResult(r), "ok");
                    if (img != null) m.image = img;
                    add(m);
                    save();
                    return;
                }
                String text = formatResult(r);
                notice("**" + title + "**" + (text.indexOf('\n') >= 0 ? "\n" : " — ") + text, "ok");
            }
        });
    }

    /** Resolves an app name on the PC without opening it (for confirmation). */
    public void bridgePreviewLaunch(final String query, Callback<JSONObject> cb) {
        bridgeAsync(new BridgeCall<JSONObject>() {
            @Override
            public JSONObject run(BridgeClient b) throws BridgeClient.BridgeException {
                return b.launchQuery(query, true);
            }
        }, cb);
    }

    public void bridgeLaunch(final String appId, final String name) {
        bridgeAsync(new BridgeCall<JSONObject>() {
            @Override
            public JSONObject run(BridgeClient b) throws BridgeClient.BridgeException {
                return b.launchId(appId);
            }
        }, new Callback<JSONObject>() {
            @Override
            public void done(JSONObject r, String error) {
                if (error != null) notice("Couldn't open " + name + ": " + error, "error");
                else {
                    JSONObject app = r.optJSONObject("app");
                    String n = app != null ? OllamaClient.str(app, "name") : "";
                    notice("Opened **" + (n.length() > 0 ? n : name) + "** on the PC.", "ok");
                }
            }
        });
    }

    static String formatResult(Object r) {
        if (r == null) return "";
        if (r instanceof String) return (String) r;
        if (r instanceof JSONObject) {
            JSONObject o = (JSONObject) r;
            StringBuilder sb = new StringBuilder();
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (sb.length() > 0) sb.append('\n');
                Object v = o.opt(k);
                sb.append("• ").append(k.replace('_', ' ')).append(": ").append(v instanceof String ? v : String.valueOf(v));
            }
            return sb.toString();
        }
        if (r instanceof JSONArray) {
            JSONArray a = (JSONArray) r;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("• ").append(formatResult(a.opt(i)));
            }
            return sb.toString();
        }
        return String.valueOf(r);
    }

    static String extractImage(Object r) {
        if (r instanceof String) return base64Image((String) r);
        if (r instanceof JSONObject) {
            JSONObject o = (JSONObject) r;
            String[] keys = {"image", "data_url", "dataUrl", "png", "jpeg", "base64", "data", "screenshot"};
            for (String k : keys) {
                String b = base64Image(OllamaClient.str(o, k));
                if (b != null) return b;
            }
        }
        return null;
    }

    static String base64Image(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = t.indexOf("base64,");
        if (t.startsWith("data:image") && i > 0) return t.substring(i + 7);
        if (t.length() > 64 && t.matches("[A-Za-z0-9+/=\\r\\n]+")) return t;
        return null;
    }

    // ------------------------------------------------------------------
    // AI tool calling: the switch, the PC's tool list, /tools
    // ------------------------------------------------------------------

    private boolean toolsWanted() {
        return settings.aiTools() && bridgePaired();
    }

    /** Turns AI tool calling on or off (/tools on|off, Settings). */
    public void setAiTools(boolean on) {
        settings.setAiTools(on);
        if (on && bridgePaired() && toolCatalog() == null) refreshToolCatalog(null);
        // A question on screen can no longer be honored: decline it; the reply then ends without tools.
        if (!on && job != null && job.approval != null) job.approval.deny();
        notifyState();
    }

    /** Whether the model can call tools ("tools" in /api/show capabilities); null until known. */
    public Boolean supportsTools(String model) {
        OllamaClient.ModelDetails d = model == null ? null : details.get(model);
        return d == null ? null : d.supports("tools");
    }

    /**
     * True when a message to {@code model} lets the AI act on the PC: tool
     * calling is on, the bridge is paired and the model can call tools (the
     * PC's tool list is read, if need be, when the message goes out).
     */
    public boolean toolsReady(String model) {
        return toolsWanted() && Boolean.TRUE.equals(supportsTools(model));
    }

    /** The PC action waiting for the user's OK, if any (e.g. to show it again after a recreate). */
    public ToolApproval pendingApproval() {
        return job == null ? null : job.approval;
    }

    /**
     * What the reply's PC tools are doing: {@link ToolCall#ASKING} (waiting for
     * the user's OK), {@link ToolCall#RUNNING}, or null — for status displays.
     */
    public String toolActivity() {
        return job == null ? null : job.target.activeToolState();
    }

    /** The PC's name for "OMNI wants to … on {pc}": its hostname when known, else the bridge address. */
    public String pcName() {
        if (lastVitals != null && lastVitals.host.length() > 0) return lastVitals.host;
        return bridgeHost();
    }

    /** Which bridge (and token) a tool list belongs to. */
    private String toolKey() {
        String host = bridgeHost();
        return host + ":" + settings.bridgePort() + "#" + bridgeTokenFor(host);
    }

    /** The PC's tools as last read from the bridge in use; null until read (or once the bridge changed). */
    public List<BridgeTool> toolCatalog() {
        return toolCatalog != null && toolCatalogKey.equals(toolKey()) ? toolCatalog : null;
    }

    private boolean staleCatalog() {
        return System.currentTimeMillis() - toolCatalogAt > TOOL_CATALOG_TTL_MS;
    }

    private void cacheCatalog(String key, List<BridgeTool> tools) {
        toolCatalog = Collections.unmodifiableList(new ArrayList<BridgeTool>(tools));
        toolCatalogKey = key;
        toolCatalogAt = System.currentTimeMillis();
        toolCatalogError = "";
    }

    private void catalogFailed(String error) {
        if (!error.equals(toolCatalogError)) log("warn", "PC tools unavailable · " + Fmt.ellipsize(error, 80));
        toolCatalogError = error;
    }

    /** Reads the PC's tool list again (after pairing, or when it's old). {@code cb} may be null. */
    public void refreshToolCatalog(final Callback<List<BridgeTool>> cb) {
        if (!bridgePaired()) {
            if (cb != null) cb.done(null, "Not paired with the PC bridge — run /pair.");
            return;
        }
        if (toolCatalogLoading && cb == null) return;
        toolCatalogLoading = true;
        final String key = toolKey();
        bridgeAsync(new BridgeCall<List<BridgeTool>>() {
            @Override
            public List<BridgeTool> run(BridgeClient b) throws BridgeClient.BridgeException {
                return BridgeTool.parseAll(b.deskCapabilities());
            }
        }, new Callback<List<BridgeTool>>() {
            @Override
            public void done(List<BridgeTool> tools, String error) {
                toolCatalogLoading = false;
                if (tools != null) cacheCatalog(key, tools);
                else if (error != null) catalogFailed(error);
                if (cb != null) cb.done(tools, error);
            }
        });
    }

    /**
     * What /tools reports: whether the AI can act on the PC right now (and
     * why not), and the PC's tools. Reads the tool list and the model's
     * capabilities fresh when it can.
     */
    public void toolsStatus(final Callback<ToolKit.Status> cb) {
        final ToolKit.Status s = new ToolKit.Status();
        s.enabled = settings.aiTools();
        s.paired = bridgePaired();
        s.confirm = settings.confirmPcActions();
        s.model = effectiveModel(); // the model replies actually go to (Deep mode routes elsewhere)
        final Runnable compose = new Runnable() {
            @Override
            public void run() {
                s.modelTools = s.model.length() > 0 ? supportsTools(s.model) : null;
                s.catalog = s.paired ? toolCatalog() : null;
                s.catalogError = toolCatalogError;
                s.pc = pcName();
                cb.done(s, null);
            }
        };
        final Runnable withModel = new Runnable() {
            @Override
            public void run() {
                if (s.model.length() > 0 && supportsTools(s.model) == null && client != null) {
                    fetchDetails(s.model, new Callback<OllamaClient.ModelDetails>() {
                        @Override
                        public void done(OllamaClient.ModelDetails d, String error) {
                            compose.run();
                        }
                    });
                } else {
                    compose.run();
                }
            }
        };
        if (s.paired) {
            refreshToolCatalog(new Callback<List<BridgeTool>>() {
                @Override
                public void done(List<BridgeTool> tools, String error) {
                    withModel.run();
                }
            });
        } else {
            withModel.run();
        }
    }

    // ------------------------------------------------------------------
    // Timers
    // ------------------------------------------------------------------

    public void timer(String arg) {
        String a = arg.trim();
        if (a.length() == 0) {
            if (timers.isEmpty()) {
                notice("No timers running. Start one with `/timer 5m tea`.", "info");
                return;
            }
            StringBuilder sb = new StringBuilder("**Timers**\n");
            long now = System.currentTimeMillis();
            for (Timers.Timer t : timers.all()) {
                sb.append("• ").append(Fmt.duration(t.secondsLeft(now))).append(" left — ").append(t.message)
                        .append('\n');
            }
            notice(sb.toString().trim(), "info");
            return;
        }
        if (a.matches("(?i)(cancel|stop|clear)( all)?")) {
            for (Timers.Timer t : timers.all()) cancelAlarm(t);
            int n = timers.clear();
            saveTimers();
            main.removeCallbacks(timerTick);
            notice(n == 0 ? "No timers to cancel." : "Cancelled " + n + (n == 1 ? " timer." : " timers."), "info");
            return;
        }
        String[] parts = a.split("\\s+", 2);
        long secs = Fmt.parseDuration(parts[0]);
        String msg = parts.length > 1 ? parts[1].trim() : "";
        if (secs < 0 && parts.length > 1) {
            // "5 min tea" → try the first two words together.
            String[] p3 = a.split("\\s+", 3);
            secs = Fmt.parseDuration(p3[0] + p3[1]);
            msg = p3.length > 2 ? p3[2].trim() : "";
        }
        if (secs <= 0) {
            notice("Usage: `/timer 5m <message>` (also `90s`, `1h30m`). `/timer` lists, `/timer cancel` stops.", "warn");
            return;
        }
        Timers.Timer t = timers.add(System.currentTimeMillis(), secs, msg);
        saveTimers();
        scheduleAlarm(t);
        String blocked = notifier().blockedReason();
        notice("Timer set for " + Fmt.duration(secs) + " — *" + t.message + "*. " + (blocked.length() == 0
                ? "It rings here, or as a notification while OmniDeck is in the background."
                : "It rings while OmniDeck is open, but can't notify you in the background: "
                + Character.toLowerCase(blocked.charAt(0)) + blocked.substring(1)), "ok");
        log("info", "Timer set · " + Fmt.duration(secs) + " · " + t.message);
        if (visible) {
            main.removeCallbacks(timerTick);
            main.postDelayed(timerTick, 1000);
        }
    }

    /** Checks the timers every second while the app is on screen; alarms cover the background. */
    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            checkTimers();
            if (visible && !timers.isEmpty()) main.postDelayed(this, 1000);
        }
    };

    private void checkTimers() {
        for (Timers.Timer t : timers.due(System.currentTimeMillis())) fireTimer(t);
    }

    /** A timer's alarm went off (TimerReceiver); rings it unless the app already did. */
    void timerAlarm(long id) {
        Timers.Timer t = timers.find(id);
        if (t != null) fireTimer(t);
    }

    /**
     * Rings a timer once: a notice in the chat, then a toast and sound while
     * the app is on screen, or a system notification while it isn't.
     */
    private void fireTimer(Timers.Timer t) {
        if (timers.remove(t.id) == null) return;
        saveTimers();
        cancelAlarm(t);
        long late = System.currentTimeMillis() - t.endsAt;
        notice(late > 60000
                ? "Timer **" + t.message + "** went off at " + clock(t.endsAt) + " while OmniDeck was closed."
                : "Time's up — **" + t.message + "**", "warn");
        log("warn", "Timer · " + t.message);
        boolean notified = false;
        if (visible) {
            toast("Time's up — " + t.message);
        } else if (notifier().canPost()) {
            notifier().timer(t.id, t.message);
            notified = true;
        }
        if (!notified) {
            try {
                Ringtone r = RingtoneManager.getRingtone(app,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                if (r != null) r.play();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String clock(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
    }

    private void saveTimers() {
        settings.setTimers(timers.toJson());
    }

    /** Timers still running at start-up (the app was closed): make sure each still has its alarm. */
    private void restoreTimers() {
        long now = System.currentTimeMillis();
        for (Timers.Timer t : timers.all()) {
            if (t.endsAt > now) scheduleAlarm(t);
        }
    }

    /** The alarm that wakes the app when {@code t} ends; its data Uri makes it unique per timer. */
    private PendingIntent timerIntent(Timers.Timer t, boolean create) {
        Intent i = new Intent(app, TimerReceiver.class)
                .setAction(TimerReceiver.ACTION)
                .setData(android.net.Uri.parse("omnideck://timer/" + t.id))
                .putExtra(TimerReceiver.EXTRA_ID, t.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (!create) flags |= PendingIntent.FLAG_NO_CREATE;
        return PendingIntent.getBroadcast(app, 0, i, flags);
    }

    /**
     * Exact and allowed in Doze when the phone lets the app (Android 12+ asks
     * the user for exact alarms); otherwise as close as Android allows.
     */
    private void scheduleAlarm(Timers.Timer t) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = timerIntent(t, true);
        try {
            if (canScheduleExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, pi);
        } catch (SecurityException e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, t.endsAt, pi);
            } catch (RuntimeException ignored) {
            }
        } catch (RuntimeException ignored) {
            // No alarm: the timer still rings when the app is next open.
        }
    }

    private static boolean canScheduleExact(AlarmManager am) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            return Boolean.TRUE.equals(AlarmManager.class.getMethod("canScheduleExactAlarms").invoke(am));
        } catch (ReflectiveOperationException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void cancelAlarm(Timers.Timer t) {
        try {
            PendingIntent pi = timerIntent(t, false);
            if (pi == null) return;
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi);
            pi.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    /** Pending timers, soonest first. */
    public List<Timers.Timer> timers() {
        return timers.all();
    }

    Notifier notifier() {
        if (notifier == null) notifier = new Notifier(app, settings);
        return notifier;
    }

    /** Why background notifications (replies, downloads, timers) can't show; "" when they can. */
    public String notificationsBlocked() {
        return notifier().blockedReason();
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public void diagnostics(final String appVersion) {
        final ServerInfo s = server;
        final OllamaClient c = client;
        final BridgeClient b = bridge();
        final List<LanScanner.Subnet> nets = testSubnets != null ? testSubnets : Net.refresh(app);
        subnets = nets;
        final String model = currentModel();
        // Everything owned by the main thread is read here, before going async.
        final String detail = stateDetail;
        final String mode = settings.mode();
        final Boolean think = thinkSupport.get(model);
        final JSONObject opts = runnerOptions(model);
        final boolean ctxAuto = settings.numCtx() <= 0;
        final int threads = settings.numThread();
        final Object keepAlive = keepAlive();
        final String speed = lastSpeed;
        final Conversation owner = conv;
        final ChatMessage n = notice("Running diagnostics…", "info");
        io.execute(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder("**Diagnostics**\n");
                sb.append("App: OmniDeck ").append(appVersion).append(" · Android ").append(Build.VERSION.RELEASE)
                        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("Phone networks: ").append(Net.describe(nets)).append('\n');
                if (s == null || c == null) {
                    sb.append("AI: not connected (").append(detail).append(")\n");
                } else {
                    long t0 = System.nanoTime();
                    String v;
                    try {
                        v = "Ollama " + c.version(3000) + " · ping " + (System.nanoTime() - t0) / 1000000L + " ms";
                    } catch (IOException e) {
                        v = "unreachable (" + e.getMessage() + ")";
                    }
                    sb.append("AI: `").append(s.label()).append("` — ").append(v)
                            .append(c.hasApiKey() ? " · API key sent" : "").append('\n');
                    sb.append("Model: ").append(model.length() > 0 ? model : "none").append(" · mode ").append(mode);
                    sb.append(" · thinking ").append(think == null ? "unknown" : think ? "supported" : "not supported")
                            .append('\n');
                }
                sb.append("Sends: num_ctx ").append(opts.optInt("num_ctx")).append(ctxAuto ? " (auto)" : "")
                        .append(" · num_thread ").append(threads > 0 ? String.valueOf(threads) : "not sent")
                        .append(" · keep_alive ").append(keepAlive).append('\n');
                if (b != null) {
                    String bs;
                    try {
                        b.health();
                        bs = "online" + (b.paired() ? ", paired" : ", not paired");
                    } catch (BridgeClient.BridgeException e) {
                        bs = "not reachable";
                    }
                    sb.append("PC bridge: `").append(b.where()).append("` — ").append(bs).append('\n');
                }
                if (speed.length() > 0) sb.append("Last reply speed: ").append(speed).append('\n');
                final String text = sb.toString().trim();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        updateNotice(owner, n, text, "info");
                    }
                });
            }
        });
    }
}
