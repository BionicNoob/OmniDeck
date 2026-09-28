package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** v2 core: telemetry ring buffers + log, PC vitals parsing, model details, image messages, new commands. */
public class TelemetryVitalsTest {

    // ------------------------------------------------------------------
    // Telemetry
    // ------------------------------------------------------------------

    @Test
    public void seriesIsARingBuffer() {
        Telemetry.Series s = new Telemetry.Series(3);
        assertEquals(0, s.size());
        assertTrue(Double.isNaN(s.last()));
        assertTrue(Double.isNaN(s.average()));
        s.add(1);
        s.add(2);
        s.add(3);
        s.add(4);
        assertEquals(3, s.size());
        assertEquals(3, s.capacity());
        assertEquals(2, s.get(0), 0);
        assertEquals(4, s.last(), 0);
        assertEquals(4, s.max(), 0);
        assertEquals(3, s.average(), 1e-9);
        double[] a = s.toArray();
        assertEquals(3, a.length);
        assertEquals(2, a[0], 0);
        assertEquals(4, a[2], 0);
        s.clear();
        assertEquals(0, s.size());
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void seriesRejectsOutOfRange() {
        new Telemetry.Series(2).get(0);
    }

    @Test
    public void telemetryAccountsReplies() {
        Telemetry t = new Telemetry(1000);
        ChatStats s = new ChatStats();
        s.evalTokens = 100;
        s.evalMs = 2000;
        s.promptTokens = 700;
        s.loadMs = 1234;
        t.reply(s, 350, 8192);
        assertEquals(1, t.replies);
        assertEquals(100, t.tokensOut);
        assertEquals(700, t.tokensIn);
        assertEquals(50, t.tokensPerSec.last(), 1e-9);
        assertEquals(350, t.ttftMs.last(), 0);
        assertEquals(800 / 8192.0, t.contextFill.last(), 1e-9);
        assertEquals(1234, t.lastLoadMs);

        // No eval time, unknown TTFT and context: those samples are skipped.
        ChatStats empty = new ChatStats();
        t.reply(empty, -1, 0);
        assertEquals(2, t.replies);
        assertEquals(1, t.tokensPerSec.size());
        assertEquals(1, t.ttftMs.size());
        assertEquals(1, t.contextFill.size());

        // Context fill is capped at 100%.
        ChatStats big = new ChatStats();
        big.promptTokens = 9000;
        t.reply(big, 10, 8192);
        assertEquals(1.0, t.contextFill.last(), 0);
        assertEquals(4000, t.uptimeMs(5000));
        assertEquals(0, t.uptimeMs(10));
    }

    @Test
    public void logKeepsTheNewestEntries() {
        Telemetry t = new Telemetry(0);
        assertEquals(null, t.lastEvent());
        for (int i = 0; i < Telemetry.LOG_MAX + 25; i++) t.log(i, i % 2 == 0 ? "ok" : null, "event " + i);
        List<Telemetry.Event> ev = t.events();
        assertEquals(Telemetry.LOG_MAX, ev.size());
        assertEquals("event 25", ev.get(0).text);
        assertEquals("event " + (Telemetry.LOG_MAX + 24), t.lastEvent().text);
        assertEquals("info", ev.get(0).level);
        // events() is a copy.
        ev.clear();
        assertEquals(Telemetry.LOG_MAX, t.events().size());
    }

    // ------------------------------------------------------------------
    // Vitals (get_system_info results in whatever shape the bridge returns)
    // ------------------------------------------------------------------

    @Test
    public void vitalsFromFlatJson() throws Exception {
        JSONObject o = new JSONObject()
                .put("hostname", "BATTLESTATION")
                .put("os", "Windows 11 Pro")
                .put("cpu_percent", 23.5)
                .put("memory", "8.1 / 16 GB")
                .put("disk", "210 GB free of 500 GB")
                .put("battery", new JSONObject().put("percent", 87).put("plugged", true))
                .put("uptime", "3 days, 4:02");
        Vitals v = Vitals.parse(o);
        assertTrue(v.hasAny());
        assertEquals(23.5, v.cpuPercent, 1e-9);
        assertEquals(8.1, v.ramUsedGb, 1e-9);
        assertEquals(16, v.ramTotalGb, 1e-9);
        assertEquals(50.625, v.ramPercent, 1e-6);
        assertEquals(500, v.diskTotalGb, 1e-9);
        assertEquals(210, v.diskFreeGb, 1e-9);
        assertEquals(58, v.diskPercent, 1e-9);
        assertEquals(87, v.batteryPercent, 1e-9);
        assertEquals("AC power", v.power);
        assertEquals("BATTLESTATION", v.host);
        assertEquals("Windows 11 Pro", v.os);
        assertEquals("3 days, 4:02", v.uptime);
    }

    @Test
    public void vitalsFromText() {
        Vitals v = Vitals.parse("CPU: 41%\nRAM: 62%\nDisk C: 71% used\nBattery: 55% (discharging)");
        assertEquals(41, v.cpuPercent, 1e-9);
        assertEquals(62, v.ramPercent, 1e-9);
        assertEquals(71, v.diskPercent, 1e-9);
        assertEquals(55, v.batteryPercent, 1e-9);
        assertEquals("On battery", v.power);
    }

    @Test
    public void vitalsFromNestedJsonWithFreeMemory() throws Exception {
        JSONObject o = new JSONObject()
                .put("cpu", new JSONObject().put("load", "12%"))
                .put("memory", new JSONObject().put("total", "32 GB").put("used", "12 GB"))
                .put("drives", new JSONArray().put("C:"));
        Vitals v = Vitals.parse(o);
        assertEquals(12, v.cpuPercent, 1e-9);
        assertEquals(32, v.ramTotalGb, 1e-9);
        assertEquals(12, v.ramUsedGb, 1e-9);
        assertEquals(37.5, v.ramPercent, 1e-9);
    }

    @Test
    public void vitalsNeverThrow() {
        assertFalse(Vitals.parse(null).hasAny());
        assertFalse(Vitals.parse("").hasAny());
        assertFalse(Vitals.parse("nothing useful here").hasAny());
        assertNotNull(Vitals.parse(new JSONArray()).raw);
    }

    // ------------------------------------------------------------------
    // Model details (/api/show)
    // ------------------------------------------------------------------

    @Test
    public void parsesModelDetails() throws Exception {
        JSONObject show = new JSONObject()
                .put("capabilities", new JSONArray().put("completion").put("vision"))
                .put("model_info", new JSONObject().put("general.architecture", "llama")
                        .put("llama.context_length", 131072))
                .put("details", new JSONObject().put("family", "llama").put("parameter_size", "3.2B")
                        .put("quantization_level", "Q4_K_M").put("format", "gguf"))
                .put("license", "LLAMA 3.2 COMMUNITY LICENSE AGREEMENT\nLlama 3.2 Version Release Date: September 25, 2024")
                .put("parameters", "stop \"<|eot_id|>\"\n")
                .put("modified_at", "2025-01-01T00:00:00Z");
        OllamaClient.ModelDetails d = OllamaClient.parseShow(show);
        assertTrue(d.supports("vision"));
        assertFalse(d.supports("thinking"));
        assertEquals(131072, d.contextLength);
        assertEquals("llama", d.family);
        assertEquals("3.2B", d.parameterSize);
        assertEquals("Q4_K_M", d.quantization);
        assertEquals("gguf", d.format);
        assertEquals("LLAMA 3.2 COMMUNITY LICENSE AGREEMENT", d.license);
        assertEquals("stop \"<|eot_id|>\"", d.parameters);

        OllamaClient.ModelDetails empty = OllamaClient.parseShow(new JSONObject());
        assertEquals(0, empty.contextLength);
        assertTrue(empty.capabilities.isEmpty());
        assertEquals("", empty.family);
    }

    @Test
    public void longLicenseIsTrimmed() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("word ");
        OllamaClient.ModelDetails d = OllamaClient.parseShow(new JSONObject().put("license", sb.toString()));
        assertTrue(d.license.length() <= 81);
        assertTrue(d.license.endsWith("…"));
    }

    // ------------------------------------------------------------------
    // Messages with images
    // ------------------------------------------------------------------

    @Test
    public void imagesRoundTripAndGoToTheModel() throws Exception {
        Conversation c = new Conversation();
        ChatMessage u = new ChatMessage(ChatMessage.USER, "what is this?");
        u.images.add("AAAA");
        u.images.add("BBBB");
        c.messages.add(u);
        ChatMessage a = new ChatMessage(ChatMessage.ASSISTANT, "A cat.");
        a.ttftMs = 420;
        c.messages.add(a);
        assertTrue(c.hasImages());

        JSONArray req = c.toRequestMessages("", null);
        assertEquals(2, req.length());
        JSONArray imgs = req.getJSONObject(0).getJSONArray("images");
        assertEquals("AAAA", imgs.getString(0));
        assertEquals("BBBB", imgs.getString(1));
        assertFalse(req.getJSONObject(1).has("images"));

        Conversation back = Conversation.fromJson(c.toJson());
        assertEquals(2, back.messages.get(0).images.size());
        assertEquals(420, back.messages.get(1).ttftMs);
        assertEquals(-1, ChatMessage.fromJson(new JSONObject().put("role", "user").put("content", "x")).ttftMs);
        assertFalse(new Conversation().hasImages());
    }

    // ------------------------------------------------------------------
    // Commands added in v2
    // ------------------------------------------------------------------

    @Test
    public void newCommandsParse() {
        assertEquals("/rename", Commands.parse("/rename Trip plans").cmd.name);
        assertEquals("Trip plans", Commands.parse("/rename Trip plans").arg);
        assertEquals("/appearance", Commands.parse("/theme cyber").cmd.name);
        assertEquals("/appearance", Commands.parse("/appearance system").cmd.name);
        assertEquals("/voice", Commands.parse("/voice").cmd.name);
        assertEquals("/voice", Commands.parse("/talk").cmd.name);
        assertEquals("/mute", Commands.parse("/mute").cmd.name);
        assertEquals("/mute", Commands.parse("/speak").cmd.name);
        assertFalse("/mute works on the phone", Commands.parse("/mute").cmd.group == Commands.GROUP_PC_APP_ONLY);
    }
}
