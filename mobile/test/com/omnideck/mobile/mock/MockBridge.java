package com.omnideck.mobile.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

/** A stand-in for OMNI-DECK's LaunchBridge with the same endpoints and auth header. */
public final class MockBridge {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 1x1 PNG. */
    public static final String PNG_1PX =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final HttpServer server;
    public volatile String token = "tok-" + Long.toHexString(System.nanoTime());
    public volatile int volume = 35;
    public final List<String> launched = Collections.synchronizedList(new ArrayList<String>());
    /** App ids launched (not dry runs), in order. */
    public final List<String> launchedIds = Collections.synchronizedList(new ArrayList<String>());

    // --- Rich mode (PC tab UI tests) ---------------------------------------
    /**
     * Answers like a real PC: a searchable app catalog with paths, a detailed
     * get_system_info (host, OS, battery, uptime, a moving CPU load), JSON
     * volume, a rendered desktop screenshot and more desktop tools. Off by
     * default so the original fixtures stay exactly as they were.
     */
    public volatile boolean rich;
    /** Desktop tools called through /desk/run in rich mode, in order. */
    public final List<String> ranTools = Collections.synchronizedList(new ArrayList<String>());
    /** Delay before answering any request, in ms (loading states). */
    public volatile int delayMs;
    /** Desktop tools that fail with an error in rich mode (error states). */
    public final Set<String> failing = Collections.synchronizedSet(new HashSet<String>());
    /** The PC clipboard in rich mode (set_clipboard writes it). */
    public volatile String clipboard = "ssh omni@atlas-pc -p 2222\ntail -f launchbridge.log";

    // --- PC tab extras (all off by default) ----------------------------------
    /** Rich mode also offers sleep_pc / restart_pc / shutdown_pc (the power strip). */
    public volatile boolean power;
    /** More tool descriptors ({name, description, parameters…}) listed after the rich catalog. */
    public final List<JSONObject> extraTools = Collections.synchronizedList(new ArrayList<JSONObject>());
    /** Arguments of every /desk/run in rich mode, as {"tool": name, "args": {...}}, in order. */
    public final List<JSONObject> ranArgs = Collections.synchronizedList(new ArrayList<JSONObject>());
    /** Extra delay before answering one desktop tool in rich mode, in ms (tool → delay). */
    public final Map<String, Integer> toolDelayMs = new ConcurrentHashMap<String, Integer>();
    /** A MAC address the rich get_system_info reports (Wake-on-LAN learning); null = none. */
    public volatile String mac;
    /** What /health reports as apps_indexed. */
    public volatile int appsIndexed = 42;
    /** Rich /apps finds nothing (an index that hasn't been built yet). */
    public volatile boolean emptyIndex;
    private int cpuTick;
    private static final int[] CPU_SEQ = {23, 31, 27, 42, 38, 29, 35, 47, 33, 26, 22, 30};
    private static final String[][] CATALOG = {
            {"app-spotify", "Spotify", "C:\\Users\\omni\\AppData\\Roaming\\Spotify\\Spotify.exe", "start_menu"},
            {"app-vscode", "Visual Studio Code", "C:\\Program Files\\Microsoft VS Code\\Code.exe", "start_menu"},
            {"app-chrome", "Google Chrome", "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", "start_menu"},
            {"app-discord", "Discord", "C:\\Users\\omni\\AppData\\Local\\Discord\\Update.exe", "start_menu"},
            {"app-obs", "OBS Studio", "C:\\Program Files\\obs-studio\\bin\\64bit\\obs64.exe", "start_menu"},
            {"app-steam", "Steam", "C:\\Program Files (x86)\\Steam\\steam.exe", "start_menu"},
            {"app-terminal", "Windows Terminal", "wt.exe", "uwp"},
            {"app-notepad", "Notepad", "C:\\Windows\\System32\\notepad.exe", "system"},
            {"app-blender", "Blender", "C:\\Program Files\\Blender Foundation\\Blender 4.2\\blender.exe", "start_menu"},
    };

    public MockBridge(InetAddress bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    route(ex);
                } catch (JSONException e) {
                    send(ex, 500, "{\"detail\":\"" + e.getMessage() + "\"}");
                } finally {
                    ex.close();
                }
            }
        });
    }

    public static MockBridge start(String bindHost, int port) throws IOException {
        MockBridge b = new MockBridge(InetAddress.getByName(bindHost), port);
        b.server.start();
        return b;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private void route(HttpExchange ex) throws IOException, JSONException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if ("GET".equals(method) && "/health".equals(path)) {
            send(ex, 200, new JSONObject().put("ok", true).put("apps_indexed", appsIndexed).put("version", "2.1")
                    .toString());
            return;
        }
        if ("POST".equals(method) && "/pair".equals(path)) {
            send(ex, 200, new JSONObject().put("token", token).toString());
            return;
        }
        if (!token.equals(ex.getRequestHeaders().getFirst("X-Bridge-Token"))) {
            send(ex, 401, "{\"detail\":\"Missing or wrong X-Bridge-Token.\"}");
            return;
        }
        if (rich && richRoute(ex, method, path)) return;
        if ("GET".equals(method) && "/apps".equals(path)) {
            JSONArray m = new JSONArray().put(new JSONObject().put("id", "app-notepad").put("name", "Notepad"))
                    .put(new JSONObject().put("id", "app-spotify").put("name", "Spotify"));
            send(ex, 200, new JSONObject().put("matches", m).toString());
        } else if ("POST".equals(method) && "/launch".equals(path)) {
            JSONObject req = new JSONObject(MockOllama.readBody(ex));
            JSONObject app;
            if (req.has("app_id")) {
                String id = req.getString("app_id");
                String name = id.equals("app-spotify") ? "Spotify" : id.equals("app-vscode") ? "Visual Studio Code"
                        : id.equals("app-vscodium") ? "VSCodium" : "Notepad";
                app = new JSONObject().put("id", id).put("name", name);
            } else {
                String q = req.optString("query", "").toLowerCase();
                if (q.contains("code")) {
                    // Ambiguous: the phone asks which one.
                    JSONArray c = new JSONArray().put(new JSONObject().put("id", "app-vscode").put("name", "Visual Studio Code")
                            .put("path", "C:\\Program Files\\Microsoft VS Code\\Code.exe"))
                            .put(new JSONObject().put("id", "app-vscodium").put("name", "VSCodium")
                                    .put("path", "C:\\Program Files\\VSCodium\\VSCodium.exe"));
                    send(ex, 200, new JSONObject().put("needs_choice", true).put("candidates", c).toString());
                    return;
                }
                if (q.contains("ghost")) {
                    send(ex, 200, new JSONObject().put("needs_choice", true).put("candidates", new JSONArray()).toString());
                    return;
                }
                if (q.contains("nothing")) {
                    send(ex, 404, "{\"detail\":\"No installed app matches that name.\"}");
                    return;
                }
                app = new JSONObject().put("id", q.contains("spot") ? "app-spotify" : "app-notepad")
                        .put("name", q.contains("spot") ? "Spotify" : "Notepad").put("path", "C:\\Apps\\app.exe");
            }
            if (req.optBoolean("dry_run", false)) {
                send(ex, 200, new JSONObject().put("would_launch", app).toString());
            } else {
                launched.add(app.getString("name"));
                launchedIds.add(app.getString("id"));
                send(ex, 200, new JSONObject().put("ok", true).put("app", app).toString());
            }
        } else if ("GET".equals(method) && "/desk/capabilities".equals(path)) {
            send(ex, 200, new JSONObject().put("tools", new JSONArray().put("get_system_info").put("get_volume")
                    .put("set_volume").put("screenshot").put("get_clipboard")).toString());
        } else if ("POST".equals(method) && "/desk/run".equals(path)) {
            JSONObject req = new JSONObject(MockOllama.readBody(ex));
            String tool = req.optString("tool");
            JSONObject args = req.optJSONObject("args");
            Object result;
            if ("get_volume".equals(tool)) {
                result = "Volume is " + volume + "%";
            } else if ("set_volume".equals(tool)) {
                volume = args == null ? volume : args.optInt("level", volume);
                result = "Volume set to " + volume + "%";
            } else if ("get_system_info".equals(tool)) {
                result = new JSONObject().put("cpu", "12%").put("ram", "8.1 / 16 GB").put("disk", "210 GB free")
                        .put("battery", "AC power");
            } else if ("screenshot".equals(tool)) {
                result = new JSONObject().put("image", "data:image/png;base64," + PNG_1PX).put("width", 1).put("height", 1);
            } else if ("get_clipboard".equals(tool)) {
                result = "text from the PC clipboard";
            } else {
                send(ex, 200, new JSONObject().put("ok", false).put("error", "Unknown tool " + tool).toString());
                return;
            }
            send(ex, 200, new JSONObject().put("ok", true).put("result", result).toString());
        } else {
            send(ex, 404, "{\"detail\":\"Not Found\"}");
        }
    }

    /** Rich-mode answers; returns false to fall through to the default routes. */
    private boolean richRoute(HttpExchange ex, String method, String path) throws IOException, JSONException {
        if ("GET".equals(method) && "/apps".equals(path)) {
            String raw = ex.getRequestURI().getRawQuery();
            String q = "";
            int limit = 30;
            if (raw != null) {
                for (String kv : raw.split("&")) {
                    int eq = kv.indexOf('=');
                    String k = eq < 0 ? kv : kv.substring(0, eq);
                    String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                    if ("q".equals(k)) q = v.trim().toLowerCase(Locale.US);
                    if ("limit".equals(k)) limit = Integer.parseInt(v);
                }
            }
            JSONArray m = new JSONArray();
            for (String[] app : CATALOG) {
                if (emptyIndex || m.length() >= limit) break;
                if (q.length() > 0 && !app[1].toLowerCase(Locale.US).contains(q)) continue;
                m.put(new JSONObject().put("id", app[0]).put("name", app[1]).put("path", app[2]).put("source", app[3]));
            }
            send(ex, 200, new JSONObject().put("matches", m).toString());
            return true;
        }
        if ("POST".equals(method) && "/launch".equals(path)) {
            JSONObject req = new JSONObject(MockOllama.readBody(ex));
            String id = req.optString("app_id", "");
            if (id.length() == 0) return false;
            for (String[] app : CATALOG) {
                if (app[0].equals(id)) {
                    launched.add(app[1]);
                    launchedIds.add(id);
                    send(ex, 200, new JSONObject().put("ok", true).put("app", new JSONObject().put("id", id)
                            .put("name", app[1]).put("path", app[2])).toString());
                    return true;
                }
            }
            send(ex, 404, "{\"detail\":\"No app with that id.\"}");
            return true;
        }
        if ("GET".equals(method) && "/desk/capabilities".equals(path)) {
            String[][] tools = {{"get_system_info", "CPU, memory, disk, battery"}, {"get_volume", "Master volume"},
                    {"set_volume", "Set master volume {level}"}, {"screenshot", "Capture the screen {save}"},
                    {"get_clipboard", "Read clipboard text"}, {"set_clipboard", "Write clipboard text {text}"},
                    {"lock_screen", "Lock the workstation"}, {"list_processes", "Top processes by CPU"}};
            JSONArray a = new JSONArray();
            for (String[] t : tools) a.put(new JSONObject().put("name", t[0]).put("description", t[1]));
            if (power) {
                String[][] keys = {{"sleep_pc", "Put the PC to sleep"}, {"restart_pc", "Restart the PC"},
                        {"shutdown_pc", "Shut the PC down"}};
                for (String[] t : keys) a.put(new JSONObject().put("name", t[0]).put("description", t[1]));
            }
            synchronized (extraTools) {
                for (JSONObject t : extraTools) a.put(t);
            }
            send(ex, 200, new JSONObject().put("tools", a).toString());
            return true;
        }
        if (!"POST".equals(method) || !"/desk/run".equals(path)) return false;
        JSONObject req = new JSONObject(MockOllama.readBody(ex));
        String tool = req.optString("tool");
        JSONObject args = req.optJSONObject("args");
        ranTools.add(tool);
        ranArgs.add(new JSONObject().put("tool", tool).put("args", args == null ? new JSONObject() : args));
        Integer wait = toolDelayMs.get(tool);
        if (wait != null && wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failing.contains(tool)) {
            send(ex, 200, new JSONObject().put("ok", false).put("error", tool + ": access denied by the PC")
                    .toString());
            return true;
        }
        Object result;
        if ("get_system_info".equals(tool)) {
            int cpu;
            synchronized (this) {
                cpu = CPU_SEQ[cpuTick++ % CPU_SEQ.length];
            }
            result = new JSONObject().put("hostname", "ATLAS-PC").put("os", "Windows 11 Pro 23H2")
                    .put("cpu", cpu + "%").put("ram", "8.1 / 16 GB").put("disk", "210 GB free of 476 GB")
                    .put("battery", "78%").put("power", "Charging").put("uptime", "3 days, 4:12:05");
            if (mac != null) ((JSONObject) result).put("mac_address", mac);
        } else if ("get_volume".equals(tool)) {
            result = new JSONObject().put("level", volume).put("muted", false);
        } else if ("set_volume".equals(tool)) {
            volume = args == null ? volume : args.optInt("level", volume);
            result = new JSONObject().put("level", volume).put("message", "Volume set to " + volume + "%");
        } else if ("screenshot".equals(tool)) {
            result = new JSONObject().put("image", "data:image/png;base64," + desktopPng()).put("width", 480)
                    .put("height", 270);
        } else if ("get_clipboard".equals(tool)) {
            result = new JSONObject().put("text", clipboard);
        } else if ("set_clipboard".equals(tool)) {
            clipboard = args == null ? "" : args.optString("text", "");
            result = "Clipboard set (" + clipboard.length() + " chars)";
        } else if ("lock_screen".equals(tool)) {
            result = "Workstation locked";
        } else if (power && ("sleep_pc".equals(tool) || "restart_pc".equals(tool) || "shutdown_pc".equals(tool))) {
            result = "sleep_pc".equals(tool) ? "Going to sleep" : "restart_pc".equals(tool) ? "Restarting in 5 s"
                    : "Shutting down in 5 s";
        } else if (isExtra(tool)) {
            result = new JSONObject().put("ran", tool).put("args", args == null ? new JSONObject() : args);
        } else if ("list_processes".equals(tool)) {
            result = new JSONArray()
                    .put(new JSONObject().put("name", "ollama.exe").put("cpu", 18.5).put("mem_mb", 5120))
                    .put(new JSONObject().put("name", "chrome.exe").put("cpu", 6.2).put("mem_mb", 1480))
                    .put(new JSONObject().put("name", "launchbridge.exe").put("cpu", 0.4).put("mem_mb", 96));
        } else {
            send(ex, 200, new JSONObject().put("ok", false).put("error", "Unknown tool " + tool).toString());
            return true;
        }
        send(ex, 200, new JSONObject().put("ok", true).put("result", result).toString());
        return true;
    }

    private boolean isExtra(String tool) {
        synchronized (extraTools) {
            for (JSONObject t : extraTools) {
                if (tool.equals(t.optString("name"))) return true;
            }
        }
        return false;
    }

    private static volatile String desktop;

    /** A small rendered "desktop" PNG (two windows and a taskbar), or the 1px PNG if AWT isn't usable. */
    static String desktopPng() {
        if (desktop != null) return desktop;
        String out;
        try {
            BufferedImage img = new BufferedImage(480, 270, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setPaint(new GradientPaint(0, 0, new Color(0x0B1F3A), 480, 270, new Color(0x24497F)));
            g.fillRect(0, 0, 480, 270);
            // An editor window with "code" lines.
            g.setColor(new Color(0x1E1E1E));
            g.fillRect(36, 26, 280, 176);
            g.setColor(new Color(0x333333));
            g.fillRect(36, 26, 280, 16);
            int[] widths = {150, 210, 120, 180, 90, 200, 140, 170, 110};
            Color[] ink = {new Color(0x569CD6), new Color(0xCE9178), new Color(0x9CDCFE), new Color(0x6A9955)};
            for (int i = 0; i < widths.length; i++) {
                g.setColor(ink[i % ink.length]);
                g.fillRect(52 + (i % 3) * 10, 54 + i * 15, widths[i], 5);
            }
            // A light window with a bar chart.
            g.setColor(new Color(0xF3F4F6));
            g.fillRect(262, 84, 186, 128);
            g.setColor(new Color(0xD1D5DB));
            g.fillRect(262, 84, 186, 14);
            int[] bars = {40, 66, 52, 84, 71, 95, 60};
            g.setColor(new Color(0x3B82F6));
            for (int i = 0; i < bars.length; i++) g.fillRect(278 + i * 23, 200 - bars[i], 14, bars[i]);
            // Taskbar.
            g.setColor(new Color(0x111827));
            g.fillRect(0, 248, 480, 22);
            for (int i = 0; i < 6; i++) {
                g.setColor(i == 2 ? new Color(0x38BDF8) : new Color(0x6B7280));
                g.fillRect(180 + i * 20, 253, 12, 12);
            }
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            out = Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Throwable t) {
            out = PNG_1PX;
        }
        desktop = out;
        return out;
    }

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(UTF8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        OutputStream os = ex.getResponseBody();
        os.write(b);
        os.close();
    }
}
