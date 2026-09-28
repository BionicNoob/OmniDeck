package com.omnideck.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import com.omnideck.mobile.core.BridgeClient;
import com.omnideck.mobile.core.OllamaClient;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Typed access to the app's SharedPreferences. */
public final class Settings {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_FAST = "fast";
    public static final String MODE_DEEP = "deep";

    private final SharedPreferences sp;

    public Settings(Context c) {
        sp = c.getSharedPreferences("omnideck", Context.MODE_PRIVATE);
    }

    private String s(String k, String d) {
        String v = sp.getString(k, d);
        return v == null ? d : v;
    }

    private void put(String k, String v) {
        sp.edit().putString(k, v == null ? "" : v).apply();
    }

    private void put(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    private void put(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    /** Manually set AI address ("" = auto-detect). */
    public String server() { return s("server", ""); }
    public void setServer(String v) { put("server", v.trim()); }

    public String lastHost() { return s("last_host", ""); }
    public int lastPort() { return sp.getInt("last_port", OllamaClient.DEFAULT_PORT); }
    /** Whether the last server that worked was reached over https. */
    public boolean lastHttps() { return sp.getBoolean("last_https", false); }
    public void setLast(String host, int port) { setLast(host, port, false); }
    public void setLast(String host, int port, boolean https) {
        sp.edit().putString("last_host", host).putInt("last_port", port).putBoolean("last_https", https).apply();
    }

    public String model() { return s("model", ""); }
    public void setModel(String v) { put("model", v); }

    public String deepModel() { return s("deep_model", ""); }
    public void setDeepModel(String v) { put("deep_model", v.trim()); }

    public String mode() { return s("mode", MODE_AUTO); }
    public void setMode(String v) { put("mode", v); }

    /** num_ctx to send; 0 = match the loaded model (else 8192, OMNI-DECK's default). */
    public int numCtx() { return sp.getInt("num_ctx", 0); }
    public void setNumCtx(int v) { put("num_ctx", Math.max(0, v)); }

    /** num_thread to send; 0 = don't send. */
    public int numThread() { return sp.getInt("num_thread", 0); }
    public void setNumThread(int v) { put("num_thread", Math.max(0, v)); }

    public boolean keepLoaded() { return sp.getBoolean("keep_loaded", true); }
    public void setKeepLoaded(boolean v) { put("keep_loaded", v); }

    public String systemPrompt() { return s("system_prompt", ""); }
    public void setSystemPrompt(String v) { put("system_prompt", v); }

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_CYBER = "cyber";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    /** "system" (default: follows the phone, light or dark), "cyber", "light" or "dark". */
    public String theme() {
        String t = s("theme", THEME_SYSTEM);
        if ("auto".equals(t) || "modern".equals(t)) return "auto".equals(t) ? THEME_SYSTEM : THEME_LIGHT;
        if ("neon".equals(t)) return THEME_CYBER;
        if (!THEME_CYBER.equals(t) && !THEME_LIGHT.equals(t) && !THEME_DARK.equals(t)) return THEME_SYSTEM;
        return t;
    }
    public void setTheme(String v) { put("theme", v); }

    /** Turns off the core animation, scan line and boot sequence. */
    public boolean reduceMotion() { return sp.getBoolean("reduce_motion", false); }
    public void setReduceMotion(boolean v) { put("reduce_motion", v); }

    /** Cyber HUD texture: hairline grid, top bloom, scan line. */
    public boolean hudEffects() { return sp.getBoolean("hud_effects", true); }
    public void setHudEffects(boolean v) { put("hud_effects", v); }

    public boolean haptics() { return sp.getBoolean("haptics", true); }
    public void setHaptics(boolean v) { put("haptics", v); }

    /** Speak replies aloud as they stream (Android text-to-speech). */
    public boolean readAloud() { return sp.getBoolean("read_aloud", false); }
    public void setReadAloud(boolean v) { put("read_aloud", v); }

    /** 0.5–2.0, 1 = normal. */
    public float speechRate() { return sp.getFloat("speech_rate", 1f); }
    public void setSpeechRate(float v) { sp.edit().putFloat("speech_rate", Math.max(0.5f, Math.min(2f, v))).apply(); }

    /** Sampling temperature; negative = the model's default. */
    public float temperature() { return sp.getFloat("temperature", -1f); }
    public void setTemperature(float v) { sp.edit().putFloat("temperature", v).apply(); }

    /** top_p; negative = the model's default. */
    public float topP() { return sp.getFloat("top_p", -1f); }
    public void setTopP(float v) { sp.edit().putFloat("top_p", v).apply(); }

    /** num_predict (max reply tokens); 0 = unlimited / the model's default. */
    public int maxTokens() { return sp.getInt("max_tokens", 0); }
    public void setMaxTokens(int v) { put("max_tokens", Math.max(0, v)); }

    /** Last bottom-bar tab (0 command, 1 comms, 2 models, 3 pc). */
    public int lastTab() { return sp.getInt("last_tab", 0); }
    public void setLastTab(int v) { put("last_tab", v); }

    public int bridgePort() { return sp.getInt("bridge_port", BridgeClient.DEFAULT_PORT); }
    public void setBridgePort(int v) { put("bridge_port", v > 0 && v < 65536 ? v : BridgeClient.DEFAULT_PORT); }

    public String bridgeToken() { return s("bridge_token", ""); }
    public void setBridgeToken(String v) { put("bridge_token", v.trim()); }

    /**
     * Where LaunchBridge runs: "" = the same PC as the AI (default), else a
     * host/IP, so PC control works even before Ollama is reachable.
     */
    public String bridgeHost() { return s("bridge_host", ""); }
    public void setBridgeHost(String v) { put("bridge_host", v == null ? "" : v.trim()); }

    /** Host the current bridge token was issued by ("" = unknown / legacy). */
    public String bridgeTokenHost() { return s("bridge_token_host", ""); }
    public void setBridgeTokenHost(String v) { put("bridge_token_host", v == null ? "" : v.trim()); }

    /** MAC address of the PC for Wake-on-LAN ("" = not set). */
    public String pcMac() { return s("pc_mac", ""); }
    public void setPcMac(String v) { put("pc_mac", v == null ? "" : v.trim()); }

    /** Optional API key sent as "Authorization: Bearer …" to Ollama (reverse proxies). */
    public String apiKey() { return s("api_key", ""); }
    public void setApiKey(String v) { put("api_key", v == null ? "" : v.trim()); }

    /** Let the AI use PC tools (LaunchBridge) through tool calling, when the model supports tools. */
    public boolean aiTools() { return sp.getBoolean("ai_tools", true); }
    public void setAiTools(boolean v) { put("ai_tools", v); }

    /** Ask before the AI changes something on the PC (launch apps, volume, clipboard…). */
    public boolean confirmPcActions() { return sp.getBoolean("confirm_pc_actions", true); }
    public void setConfirmPcActions(boolean v) { put("confirm_pc_actions", v); }

    /** Hands-free conversation: after a spoken reply, listen again automatically. */
    public boolean handsFree() { return sp.getBoolean("hands_free", false); }
    public void setHandsFree(boolean v) { put("hands_free", v); }

    /** System notifications when a reply, download or timer finishes while the app is in the background. */
    public boolean notifications() { return sp.getBoolean("notifications", true); }
    public void setNotifications(boolean v) { put("notifications", v); }

    public boolean incognito() { return sp.getBoolean("incognito", false); }
    public void setIncognito(boolean v) { put("incognito", v); }

    public String currentChat() { return s("current_chat", ""); }
    public void setCurrentChat(String v) { put("current_chat", v); }

    public List<String> facts() {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(s("facts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                String f = a.optString(i, "").trim();
                if (f.length() > 0) out.add(f);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void setFacts(List<String> facts) {
        JSONArray a = new JSONArray();
        for (String f : facts) a.put(f);
        put("facts", a.toString());
    }
}
