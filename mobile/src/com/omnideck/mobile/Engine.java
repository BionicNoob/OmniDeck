package com.omnideck.mobile;

import android.content.Context;
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
import com.omnideck.mobile.core.ServerInfo;
import com.omnideck.mobile.core.Telemetry;
import com.omnideck.mobile.core.Vitals;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    }

    public interface Callback<T> {
        /** Exactly one of value/error is non-null. Called on the main thread. */
        void done(T value, String error);
    }

    static final int FLUSH_MS = 40;
    static final int HEALTH_MS = 10000;
    static final int DEFAULT_CTX = 8192;
    static final String COMPACT_PROMPT = "Summarize our conversation so far into a compact briefing that keeps every "
            + "fact, decision, name, number and open question needed to continue it. Write it as notes, not as a reply.";
    static final String SUMMARIZE_PROMPT = "Summarize our conversation so far in a few short bullet points.";
    private static final Pattern HARD = Pattern.compile("(?i)\\b(prove|proof|derive|step[- ]by[- ]step|debug|refactor|"
            + "analy[sz]e|algorithm|optimi[sz]e|calculate|complexity|reason through|in detail|compare|trade-?offs?)\\b");

    private static Engine instance;

    /** Tests: scan these subnets instead of the device's real ones. */
    static volatile List<LanScanner.Subnet> testSubnets;

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
    private List<ServerInfo> lastScan = new ArrayList<ServerInfo>();
    private List<LanScanner.Subnet> subnets = new ArrayList<LanScanner.Subnet>();
    private boolean scanning;
    private Cancellable scanCancel;
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
        public String error;
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
    private boolean auxBusy;
    private Cancellable pullCancel;
    public String draft = "";
    private final List<long[]> timerEnds = new ArrayList<long[]>();
    private final List<String> timerMessages = new ArrayList<String>();

    private Engine(Context app) {
        this.app = app;
        this.settings = new Settings(app);
        this.store = new ConversationStore(new File(app.getFilesDir(), "chats"));
        Net.install();
        String id = settings.currentChat();
        Conversation c = id.length() > 0 ? store.load(id) : null;
        conv = c != null ? c : new Conversation();
        settings.setCurrentChat(conv.id);
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

    public boolean bridgePaired() {
        return settings.bridgeToken().length() > 0;
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
        if (listener != null) listener.onBusyChanged();
    }

    private void toast(String s) {
        if (listener != null) listener.onToast(s);
    }

    private void setState(State s, String detail) {
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
            registerNetworkCallback();
            if (state == State.ONLINE) checkHealth();
            else discover(false);
            main.removeCallbacks(healthTick);
            main.postDelayed(healthTick, HEALTH_MS);
            checkTimers();
        } else {
            unregisterNetworkCallback();
            main.removeCallbacks(healthTick);
            main.removeCallbacks(offlineRetry);
        }
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
                // The network moved under a running scan; start over on the new one.
                if (scanCancel != null) scanCancel.cancel();
                scanning = false;
                discover(false);
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
        main.removeCallbacks(offlineRetry);
        if (state != State.ONLINE || full) {
            setState(State.SEARCHING, full ? "Scanning the network…" : "Looking for your AI on this network…");
        } else {
            notifyState();
        }
        final Cancellable c = scanCancel = new Cancellable();
        final HostPort manual = HostPort.parse(settings.server(), OllamaClient.DEFAULT_PORT);
        final String lastHost = settings.lastHost();
        final int lastPort = settings.lastPort();
        final List<LanScanner.Subnet> nets = testSubnets != null ? testSubnets : Net.refresh(app);
        subnets = nets;
        final int scanPort = manual != null ? manual.port : lastPort > 0 ? lastPort : OllamaClient.DEFAULT_PORT;
        io.execute(new Runnable() {
            @Override
            public void run() {
                ServerInfo found = null;
                final List<ServerInfo> all = new ArrayList<ServerInfo>();
                if (manual != null) found = OllamaClient.probe(manual.host, manual.port, 3000);
                if (found == null && lastHost.length() > 0 && !c.isCancelled()
                        && (manual == null || !manual.host.equals(lastHost))) {
                    found = OllamaClient.probe(lastHost, lastPort, 1500);
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
        boolean changed = server == null || !server.host.equals(s.host) || server.port != s.port;
        server = s;
        client = new OllamaClient(s.host, s.port);
        settings.setLast(s.host, s.port);
        healthFailures = 0;
        offlineRetryMs = 10000;
        if (changed) {
            running.clear();
            thinkSupport.clear();
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
        if (scanCancel != null) scanCancel.cancel();
        scanning = false;
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

    private void updateNotice(ChatMessage m, String text, String tone) {
        m.content = text;
        if (tone != null) m.tone = tone;
        if (listener != null) listener.onMessageChanged(m);
        save();
    }

    public void save() {
        if (settings.incognito()) return;
        final Conversation c = conv;
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
        if (job != null) stop();
        save();
        conv = new Conversation();
        settings.setCurrentChat(conv.id);
        if (listener != null) listener.onConversationReplaced();
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
        if (job != null) stop();
        save();
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
                        if (listener != null) listener.onConversationReplaced();
                    }
                });
            }
        });
    }

    public void deleteChat(final String id) {
        final boolean current = id.equals(conv.id);
        if (current) {
            if (job != null) stop();
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
        if (job != null && job.target == m) stop();
        if (conv.messages.remove(m)) {
            if (listener != null) listener.onMessageRemoved(m);
            save();
        }
    }

    /** Drops this user message and everything after it; returns its text for re-editing. */
    public String editFrom(ChatMessage m) {
        if (job != null) stop();
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

    /** A reply being streamed. Tokens accumulate off the main thread and are flushed to the UI per frame. */
    private final class Job implements Runnable {
        final ChatMessage target;
        final Cancellable cancel = new Cancellable();
        final StringBuilder content = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final AtomicBoolean posted = new AtomicBoolean();
        final long startNanos = System.nanoTime();
        /** ms from request to the first token; -1 until it arrives. */
        final java.util.concurrent.atomic.AtomicLong ttft = new java.util.concurrent.atomic.AtomicLong(-1);
        int numCtx;

        Job(ChatMessage target) {
            this.target = target;
        }

        void firstToken() {
            if (ttft.get() < 0) ttft.compareAndSet(-1, (System.nanoTime() - startNanos) / 1000000L);
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

        void copy() {
            synchronized (this) {
                target.content = content.toString();
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

        JSONArray msgs = conv.toRequestMessages(systemPrompt(), null);
        final ChatMessage target = new ChatMessage(ChatMessage.ASSISTANT, "");
        target.model = model;
        target.streaming = true;
        target.startedAt = System.currentTimeMillis();
        add(target);
        final Job j = job = new Job(target);
        JSONObject opts = runnerOptions(model);
        addGenerationOptions(opts);
        j.numCtx = opts.optInt("num_ctx", 0);
        final JSONObject body = OllamaClient.chatBody(model, msgs, thinkFor(model, deep), keepAlive(), opts);
        final boolean wasLoaded = running.containsKey(model);
        if (settings.readAloud()) speech().stop();
        final OllamaClient c = client;
        notifyBusy();
        save();
        if (c == null) {
            // The link dropped between the caller's check and now: fail the reply cleanly.
            finish(j, null, "Not connected to your AI.", false, false);
            return;
        }
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
                        synchronized (j) {
                            j.content.append(delta);
                        }
                        j.schedule();
                    }

                    @Override
                    public void onDone(final ChatStats stats) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                finish(j, stats, null, false, wasLoaded);
                            }
                        });
                    }

                    @Override
                    public void onError(final String message, final boolean cancelled) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                finish(j, null, message, cancelled, wasLoaded);
                            }
                        });
                    }
                });
            }
        });
    }

    private void finish(Job j, ChatStats stats, String error, boolean cancelled, boolean wasLoaded) {
        if (job != j) return;
        main.removeCallbacks(j);
        j.copy();
        ChatMessage t = j.target;
        t.content = stripLeadingBlank(t.content);
        t.thinking = t.thinking.trim();
        t.streaming = false;
        t.ttftMs = j.ttft.get();
        if (stats != null) {
            t.stats = stats.summary() + (t.ttftMs >= 0 ? " · first token " + Fmt.seconds(t.ttftMs) : "");
            if (stats.evalMs > 0) lastSpeed = Fmt.oneDecimal(stats.tokensPerSecond()) + " tok/s";
            if (t.content.length() == 0 && t.thinking.length() == 0) t.stats = "empty reply · " + t.stats;
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
            t.error = true;
            t.stats = error == null ? "failed" : error;
            telemetry.errors++;
            log("error", "Reply failed · " + (error == null ? "unknown error" : Fmt.ellipsize(error, 90)));
            speechStop();
        }
        job = null;
        if (listener != null) listener.onMessageChanged(t);
        notifyBusy();
        save();
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

    public void stop() {
        if (job != null) job.cancel.cancel();
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
        }
        return speech;
    }

    /** Turns read-aloud on or off (the /mute command and the speaker toggles). */
    public void setReadAloud(boolean on) {
        settings.setReadAloud(on);
        if (!on) {
            speechStop();
        } else {
            speech().setRate(settings.speechRate());
            if (job != null) speech().skip(job.target.id, job.target.content.length());
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
                            updateNotice(n, "Couldn't load **" + model + "**: " + fErr, "error");
                            log("error", "Load failed · " + model);
                        } else {
                            updateNotice(n, "**" + model + "** is loaded and ready (" + Fmt.seconds(fMs) + ").", "ok");
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
                            updateNotice(n, "Couldn't unload **" + model + "**: " + fErr, "error");
                        } else {
                            updateNotice(n, "Unloaded **" + model + "** — its memory is free on the PC.", "ok");
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
        final PullState ps = pullState = new PullState(n0);
        if (listener != null) listener.onPull();
        log("info", "Download started · " + n0);
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
                                n.content = text;
                                if (listener != null) listener.onMessageChanged(n);
                            }
                        });
                    }

                    @Override
                    public void onDone() {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                pullCancel = null;
                                ps.done = true;
                                ps.status = "success";
                                ps.completed = ps.total;
                                if (listener != null) listener.onPull();
                                updateNotice(n, "**" + n0 + "** is downloaded. Use it with `/model " + n0 + "`.", "ok");
                                log("ok", "Download complete · " + n0);
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
                                ps.done = true;
                                ps.error = cancelled ? "stopped" : message;
                                if (listener != null) listener.onPull();
                                updateNotice(n, cancelled ? "Download of **" + n0 + "** stopped."
                                        : "Download of **" + n0 + "** failed: " + message, cancelled ? "warn" : "error");
                                log(cancelled ? "warn" : "error", (cancelled ? "Download stopped · " : "Download failed · ") + n0);
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
                            details.put(model, fd);
                            thinkSupport.put(model, fd.supports("thinking"));
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
                            details.remove(model);
                            thinkSupport.remove(model);
                            if (model.equals(settings.model())) settings.setModel("");
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
        bridgeAsync(new BridgeCall<Vitals>() {
            @Override
            public Vitals run(BridgeClient b) throws BridgeClient.BridgeException {
                return Vitals.parse(b.deskRun("get_system_info", null));
            }
        }, new Callback<Vitals>() {
            @Override
            public void done(Vitals v, String error) {
                if (v != null) {
                    lastVitals = v;
                    lastVitalsAt = System.currentTimeMillis();
                    bridgeOnline = Boolean.TRUE;
                    notifyTelemetry();
                }
                cb.done(v, error);
            }
        });
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

    /** Desktop tools the bridge offers. */
    public void bridgeCapabilities(Callback<List<String>> cb) {
        bridgeAsync(new BridgeCall<List<String>>() {
            @Override
            public List<String> run(BridgeClient b) throws BridgeClient.BridgeException {
                JSONObject caps = b.deskCapabilities();
                List<String> out = new ArrayList<String>();
                JSONArray tools = caps.optJSONArray("tools");
                if (tools != null) {
                    for (int i = 0; i < tools.length(); i++) {
                        Object t = tools.opt(i);
                        String name = t instanceof JSONObject ? OllamaClient.str((JSONObject) t, "name") : String.valueOf(t);
                        if (name.length() > 0) out.add(name);
                    }
                }
                return out;
            }
        }, cb);
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
                if (error == null) log("info", "PC · " + tool);
                cb.done(v, error);
            }
        });
    }

    /** Pairs with the bridge; callback gets the token. */
    public void bridgePair(final Callback<String> cb) {
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
                    bridgeOnline = Boolean.TRUE;
                    log("ok", "PC bridge paired");
                    notifyState();
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
        auxBusy = true;
        final String model = currentModel();
        final ChatMessage n = notice("Benchmarking **" + model + "**…", "info");
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
                c.chat(body, new Cancellable(), new OllamaClient.ChatListener() {
                    @Override
                    public void onThinking(String delta) {
                    }

                    @Override
                    public void onContent(String delta) {
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
                        auxBusy = false;
                        if (st[0] == null) {
                            updateNotice(n, "Benchmark failed: " + err[0], "error");
                            return;
                        }
                        ChatStats s = st[0];
                        updateNotice(n, "**Benchmark — " + model + "**\n"
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
        auxBusy = true;
        final Conversation target = conv;
        final String model = currentModel();
        JSONArray msgs = conv.toRequestMessages(systemPrompt(), null);
        try {
            msgs.put(new JSONObject().put("role", "user").put("content", COMPACT_PROMPT));
        } catch (JSONException ignored) {
        }
        final ChatMessage n = notice("Compacting the conversation…", "info");
        final JSONObject body = OllamaClient.chatBody(model, msgs, thinkFor(model, false), keepAlive(),
                runnerOptions(model));
        final OllamaClient c = client;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final StringBuilder out = new StringBuilder();
                final String[] err = new String[1];
                c.chat(body, new Cancellable(), new OllamaClient.ChatListener() {
                    @Override
                    public void onThinking(String delta) {
                    }

                    @Override
                    public void onContent(String delta) {
                        out.append(delta);
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
                        auxBusy = false;
                        String summary = out.toString().trim();
                        if (err[0] != null || summary.length() == 0) {
                            updateNotice(n, "Couldn't compact: " + (err[0] != null ? err[0] : "empty summary"), "error");
                            return;
                        }
                        if (conv != target) return;
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

    private BridgeClient bridge() {
        String host = bridgeHost();
        if (host.length() == 0) return null;
        return new BridgeClient(host, settings.bridgePort(), settings.bridgeToken());
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
                        JSONObject caps = b.deskCapabilities();
                        JSONArray tools = caps.optJSONArray("tools");
                        if (tools != null && tools.length() > 0) {
                            sb.append("\nDesktop tools: ");
                            for (int i = 0; i < tools.length(); i++) {
                                if (i > 0) sb.append(", ");
                                sb.append(tools.optString(i));
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
    // Timers (in-app)
    // ------------------------------------------------------------------

    public void timer(String arg) {
        String a = arg.trim();
        if (a.length() == 0) {
            if (timerEnds.isEmpty()) {
                notice("No timers running. Start one with `/timer 5m tea`.", "info");
                return;
            }
            StringBuilder sb = new StringBuilder("**Timers**\n");
            long now = System.currentTimeMillis();
            for (int i = 0; i < timerEnds.size(); i++) {
                sb.append("• ").append(Fmt.duration(Math.max(0, (timerEnds.get(i)[0] - now) / 1000)))
                        .append(" left — ").append(timerMessages.get(i)).append('\n');
            }
            notice(sb.toString().trim(), "info");
            return;
        }
        if (a.matches("(?i)(cancel|stop|clear)( all)?")) {
            int n = timerEnds.size();
            timerEnds.clear();
            timerMessages.clear();
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
        if (msg.length() == 0) msg = "Timer";
        timerEnds.add(new long[]{System.currentTimeMillis() + secs * 1000});
        timerMessages.add(msg);
        notice("Timer set for " + Fmt.duration(secs) + " — *" + msg + "*. It rings in the app, so keep OmniDeck open.",
                "ok");
        main.removeCallbacks(timerTick);
        main.postDelayed(timerTick, 1000);
    }

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            checkTimers();
            if (!timerEnds.isEmpty()) main.postDelayed(this, 1000);
        }
    };

    private void checkTimers() {
        long now = System.currentTimeMillis();
        for (int i = timerEnds.size() - 1; i >= 0; i--) {
            if (timerEnds.get(i)[0] <= now) {
                String msg = timerMessages.get(i);
                timerEnds.remove(i);
                timerMessages.remove(i);
                notice("⏰ Time's up — **" + msg + "**", "warn");
                toast("⏰ " + msg);
                try {
                    Ringtone r = RingtoneManager.getRingtone(app,
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                    if (r != null) r.play();
                } catch (RuntimeException ignored) {
                }
            }
        }
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
                    sb.append("AI: `").append(s.label()).append("` — ").append(v).append('\n');
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
                        updateNotice(n, text, "info");
                    }
                });
            }
        });
    }
}
