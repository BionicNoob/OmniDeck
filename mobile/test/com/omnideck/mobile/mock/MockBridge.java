package com.omnideck.mobile.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

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
        if ("GET".equals(method) && "/health".equals(path)) {
            send(ex, 200, new JSONObject().put("ok", true).put("apps_indexed", 42).put("version", "2.1").toString());
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
        if ("GET".equals(method) && "/apps".equals(path)) {
            JSONArray m = new JSONArray().put(new JSONObject().put("id", "app-notepad").put("name", "Notepad"))
                    .put(new JSONObject().put("id", "app-spotify").put("name", "Spotify"));
            send(ex, 200, new JSONObject().put("matches", m).toString());
        } else if ("POST".equals(method) && "/launch".equals(path)) {
            JSONObject req = new JSONObject(MockOllama.readBody(ex));
            JSONObject app;
            if (req.has("app_id")) {
                String id = req.getString("app_id");
                app = new JSONObject().put("id", id).put("name", id.equals("app-spotify") ? "Spotify" : "Notepad");
            } else {
                String q = req.optString("query", "").toLowerCase();
                if (q.contains("code")) {
                    JSONArray c = new JSONArray().put(new JSONObject().put("id", "app-vscode").put("name", "Visual Studio Code"))
                            .put(new JSONObject().put("id", "app-vscodium").put("name", "VSCodium"));
                    send(ex, 200, new JSONObject().put("needs_choice", true).put("candidates", c).toString());
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

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(UTF8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        OutputStream os = ex.getResponseBody();
        os.write(b);
        os.close();
    }
}
