package com.omnideck.mobile.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** AI tool calling, the plain-Java parts: schemas, tool_calls, labels, risk, results, replay, approval. */
public class ToolKitTest {

    private static BridgeTool tool(String name, String desc) {
        return BridgeTool.parse(obj("{\"name\":\"" + name + "\",\"description\":\"" + desc + "\"}"));
    }

    private static JSONObject obj(String json) {
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<BridgeTool> richCatalog() {
        List<BridgeTool> l = new ArrayList<BridgeTool>();
        l.add(tool("get_system_info", "CPU, memory, disk, battery"));
        l.add(tool("get_volume", "Master volume"));
        l.add(tool("set_volume", "Set master volume {level}"));
        l.add(tool("screenshot", "Capture the screen {save}"));
        l.add(tool("set_clipboard", "Write clipboard text {text}"));
        l.add(tool("lock_screen", "Lock the workstation"));
        return l;
    }

    // ------------------------------------------------------------------
    // The "tools" array
    // ------------------------------------------------------------------

    @Test
    public void schemaFromAKnownTool() throws Exception {
        JSONObject s = ToolKit.schema(tool("set_volume", "Set master volume {level}"));
        assertEquals("function", s.getString("type"));
        JSONObject fn = s.getJSONObject("function");
        assertEquals("set_volume", fn.getString("name"));
        assertEquals("the {level} hint is dropped", "Set master volume", fn.getString("description"));
        JSONObject p = fn.getJSONObject("parameters");
        assertEquals("object", p.getString("type"));
        JSONObject level = p.getJSONObject("properties").getJSONObject("level");
        assertEquals("integer", level.getString("type"));
        assertEquals(0, level.getInt("minimum"));
        assertEquals(100, level.getInt("maximum"));
        assertTrue(level.getString("description").length() > 0);
        assertEquals("level", p.getJSONArray("required").getString(0));
    }

    @Test
    public void schemaFromAJsonSchemaWithChoicesArraysAndNoRequired() throws Exception {
        BridgeTool t = BridgeTool.parse(obj("{\"name\":\"media_key\",\"description\":\"Press a media key\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\","
                + "\"enum\":[\"play\",\"next\"]},\"times\":{\"type\":\"integer\"},\"tags\":{\"type\":\"array\"}}}}"));
        JSONObject p = ToolKit.schema(t).getJSONObject("function").getJSONObject("parameters");
        JSONObject props = p.getJSONObject("properties");
        assertEquals("next", props.getJSONObject("key").getJSONArray("enum").getString(1));
        assertEquals("a nameless parameter still gets a description", "Times",
                props.getJSONObject("times").getString("description"));
        assertEquals("string", props.getJSONObject("tags").getJSONObject("items").getString("type"));
        assertFalse("nothing required", p.has("required"));
        assertFalse(props.getJSONObject("times").has("minimum"));
    }

    @Test
    public void bareToolNamesGetKnownDescriptions() throws Exception {
        BridgeTool info = BridgeTool.parse("get_system_info");
        assertTrue(ToolKit.description(info).contains("CPU"));
        BridgeTool odd = BridgeTool.parse("toggle_night_light");
        assertEquals("Toggle night light on the PC.", ToolKit.description(odd));
    }

    @Test
    public void toolsArrayAddsOpenAppOnceAndSkipsDuplicates() throws Exception {
        List<BridgeTool> cat = richCatalog();
        cat.add(tool("GET_VOLUME", "dup"));
        JSONArray a = ToolKit.toolsArray(cat, true);
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < a.length(); i++) names.add(a.getJSONObject(i).getJSONObject("function").getString("name"));
        assertEquals(Arrays.asList("get_system_info", "get_volume", "set_volume", "screenshot", "set_clipboard",
                "lock_screen", "open_app"), names);
        JSONObject open = a.getJSONObject(a.length() - 1).getJSONObject("function");
        assertTrue(open.getString("description").contains("app_id"));
        assertEquals("query", open.getJSONObject("parameters").getJSONArray("required").getString(0));
        assertTrue(open.getJSONObject("parameters").getJSONObject("properties").has("app_id"));

        // The bridge's own open_app wins.
        List<BridgeTool> own = new ArrayList<BridgeTool>(Collections.singletonList(tool("open_app", "Open an app")));
        JSONArray b = ToolKit.toolsArray(own, true);
        assertEquals(1, b.length());
        assertEquals("Open an app", b.getJSONObject(0).getJSONObject("function").getString("description"));
        assertEquals(6, ToolKit.toolsArray(richCatalog(), false).length());
    }

    @Test
    public void missingRequiredArguments() {
        BridgeTool vol = tool("set_volume", "Set master volume {level}");
        assertEquals("level", ToolKit.missingArgs(vol, new JSONObject()));
        assertEquals("level", ToolKit.missingArgs(vol, obj("{\"level\":null}")));
        assertNull(ToolKit.missingArgs(vol, obj("{\"level\":40}")));
        assertNull(ToolKit.missingArgs(null, new JSONObject()));
    }

    // ------------------------------------------------------------------
    // tool_calls
    // ------------------------------------------------------------------

    @Test
    public void parsesOllamaToolCalls() throws Exception {
        JSONArray calls = new JSONArray("[{\"function\":{\"name\":\"set_volume\",\"arguments\":{\"level\":40}}},"
                + "{\"id\":\"call_7\",\"function\":{\"index\":1,\"name\":\"get_volume\",\"arguments\":\"{}\"}},"
                + "{\"function\":{\"name\":\"screenshot\",\"arguments\":\"{\\\"save\\\": true}\"}},"
                + "{\"name\":\"lock_screen\"},"
                + "{\"function\":{\"name\":\"bad\",\"arguments\":\"not json\"}},"
                + "{\"function\":{\"arguments\":{}}}, 42]");
        List<ToolCall> l = ToolCall.parseAll(calls);
        assertEquals(5, l.size());
        assertEquals("set_volume", l.get(0).name);
        assertEquals(40, l.get(0).args.getInt("level"));
        assertEquals("", l.get(0).id);
        assertEquals("call_7", l.get(1).id);
        assertEquals(0, l.get(1).args.length());
        assertTrue("arguments sent as a JSON string", l.get(2).args.getBoolean("save"));
        assertEquals("a flat entry", "lock_screen", l.get(3).name);
        assertEquals("bad", l.get(4).name);
        assertEquals("not json", l.get(4).argsError);
        assertEquals(ToolCall.QUEUED, l.get(0).state);
        assertTrue(ToolCall.parseAll(null).isEmpty());
    }

    @Test
    public void aCallGoesBackToTheModelAsItCame() throws Exception {
        ToolCall c = ToolCall.parse(new JSONObject("{\"id\":\"c1\",\"function\":{\"name\":\"set_volume\","
                + "\"arguments\":{\"level\":40}}}"));
        JSONObject r = c.toRequest();
        assertEquals("c1", r.getString("id"));
        assertEquals("set_volume", r.getJSONObject("function").getString("name"));
        assertEquals(40, r.getJSONObject("function").getJSONObject("arguments").getInt("level"));
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    @Test
    public void actionLogLabels() {
        BridgeTool vol = tool("set_volume", "Set master volume {level}");
        assertEquals("Set volume → 40%", ToolKit.label(vol, "set_volume", obj("{\"level\":40}")));
        assertEquals("a number sent as text", "Set volume → 40%", ToolKit.label(vol, "set_volume",
                obj("{\"level\":\"40\"}")));
        assertEquals("Get volume", ToolKit.label(tool("get_volume", ""), "get_volume", new JSONObject()));
        BridgeTool shot = tool("screenshot", "Capture the screen {save}");
        assertEquals("a switched-off flag says nothing", "Screenshot",
                ToolKit.label(shot, "screenshot", obj("{\"save\":false}")));
        assertEquals("Screenshot → save", ToolKit.label(shot, "screenshot", obj("{\"save\":true}")));
        assertEquals("Set clipboard → “ssh omni@atlas-pc -p 2222 a…”", ToolKit.label(tool("set_clipboard", ""),
                "set_clipboard", obj("{\"text\":\"ssh omni@atlas-pc -p 2222 and more text here\"}")));
        assertEquals("Open spotify", ToolKit.label(null, "open_app", obj("{\"query\":\"spotify\"}")));
        assertEquals("Open app-vscodium", ToolKit.label(null, "open_app", obj("{\"app_id\":\"app-vscodium\"}")));
        assertEquals("Kill process → “chrome.exe”", ToolKit.label(null, "kill_process",
                obj("{\"name\":\"chrome.exe\"}")));
        // The tool's own argument order; at most three values.
        BridgeTool move = BridgeTool.parse(obj("{\"name\":\"move_window\",\"params\":[\"x\",\"y\",\"w\",\"h\"]}"));
        assertEquals("Move window → 10, 20, 30, …", ToolKit.label(move, "move_window",
                obj("{\"h\":40,\"w\":30,\"y\":20,\"x\":10}")));
    }

    @Test
    public void approvalPhrases() {
        BridgeTool vol = tool("set_volume", "Set master volume {level}");
        assertEquals("set volume to 40%", ToolKit.phrase(vol, "set_volume", obj("{\"level\":40}"), ""));
        assertEquals("lock screen", ToolKit.phrase(tool("lock_screen", ""), "lock_screen", new JSONObject(), ""));
        assertEquals("kill process “chrome.exe”", ToolKit.phrase(null, "kill_process",
                obj("{\"name\":\"chrome.exe\"}"), ""));
        assertEquals("open Spotify", ToolKit.phrase(null, "open_app", obj("{\"query\":\"spotify\"}"),
                "Open Spotify"));
        assertEquals("PC names keep their capitals", "PC restart", ToolKit.lowerFirst("PC restart"));
        ToolApproval a = new ToolApproval("set_volume", "Set volume → 40%", "set volume to 40%", "ATLAS-PC",
                "10.0.0.2:8765", "{}", false);
        assertEquals("OMNI wants to set volume to 40% on ATLAS-PC", a.sentence());
    }

    // ------------------------------------------------------------------
    // Risk
    // ------------------------------------------------------------------

    @Test
    public void readOnlyChangesAndDestructiveTools() {
        JSONObject none = new JSONObject();
        assertEquals(ToolKit.READ, ToolKit.risk(null, "get_volume", none));
        assertEquals(ToolKit.READ, ToolKit.risk(null, "get_system_info", none));
        assertEquals(ToolKit.READ, ToolKit.risk(null, "list_processes", none));
        assertEquals(ToolKit.READ, ToolKit.risk(null, "read_file", none));
        assertEquals(ToolKit.READ, ToolKit.risk(null, "battery_status", none));
        assertEquals(ToolKit.READ, ToolKit.risk(null, "screenshot", obj("{\"save\":false}")));
        assertEquals("saving writes a file", ToolKit.CHANGE, ToolKit.risk(null, "screenshot",
                obj("{\"save\":true}")));
        assertEquals(ToolKit.CHANGE, ToolKit.risk(null, "set_volume", none));
        assertEquals(ToolKit.CHANGE, ToolKit.risk(null, "set_clipboard", none));
        assertEquals(ToolKit.CHANGE, ToolKit.risk(null, "lock_screen", none));
        assertEquals(ToolKit.CHANGE, ToolKit.risk(null, "open_app", none));
        assertEquals(ToolKit.CHANGE, ToolKit.risk(null, "media_play_pause", none));
        assertEquals(ToolKit.DESTRUCTIVE, ToolKit.risk(null, "shutdown_pc", none));
        assertEquals(ToolKit.DESTRUCTIVE, ToolKit.risk(null, "restart", none));
        assertEquals(ToolKit.DESTRUCTIVE, ToolKit.risk(null, "kill_process", none));
        assertEquals(ToolKit.DESTRUCTIVE, ToolKit.risk(null, "delete_file", none));
        assertEquals("a read that deletes is destructive", ToolKit.DESTRUCTIVE,
                ToolKit.risk(null, "get_and_delete_file", none));
        assertEquals(ToolKit.DESTRUCTIVE, ToolKit.risk(tool("sleep_pc", ""), "sleep_pc", none));
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    @Test
    public void resultsForTheModel() throws Exception {
        assertEquals("Done.", ToolKit.resultText(null));
        assertEquals("Done.", ToolKit.resultText(""));
        assertEquals("Done.", ToolKit.resultText(new JSONObject()));
        assertEquals("Volume set to 40%", ToolKit.resultText("Volume set to 40%"));
        assertEquals("{\"level\":40,\"muted\":false}", ToolKit.resultText(obj("{\"level\":40,\"muted\":false}")));
        StringBuilder b64 = new StringBuilder();
        for (int i = 0; i < 400; i++) b64.append("QUJD");
        String shot = ToolKit.resultText(obj("{\"image\":\"data:image/png;base64," + b64 + "\",\"width\":480}"));
        assertEquals("{\"image\":\"[image attached]\",\"width\":480}", shot);
        assertEquals("[image attached]", ToolKit.resultText(b64.toString()));
        JSONArray nested = new JSONArray().put(new JSONObject().put("png", b64.toString()));
        assertEquals("[{\"png\":\"[image attached]\"}]", ToolKit.resultText(nested));

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append("process ").append(i).append(", ");
        String cut = ToolKit.resultText(big.toString());
        assertTrue(cut.length() < ToolKit.RESULT_MAX + 80);
        assertTrue(cut, cut.endsWith("characters in all]"));
        assertTrue(cut.startsWith("process 0, "));
    }

    @Test
    public void severalAppsMatch() throws Exception {
        JSONArray c = new JSONArray("[{\"id\":\"app-vscode\",\"name\":\"Visual Studio Code\",\"path\":\"C:\\\\Code.exe\"},"
                + "{\"id\":\"app-vscodium\",\"name\":\"VSCodium\"}]");
        String s = ToolKit.candidatesText("code", c);
        assertTrue(s, s.startsWith("Several apps on the PC match \"code\": Visual Studio Code (app_id: app-vscode, "
                + "C:\\Code.exe); VSCodium (app_id: app-vscodium)."));
        assertTrue(s.contains("Nothing was opened"));
        assertTrue(s.contains("call open_app again with its app_id"));
    }

    @Test
    public void theSystemPromptNoteNamesThePc() {
        String p = ToolKit.systemPrompt("ATLAS-PC");
        assertTrue(p.contains("(ATLAS-PC)"));
        assertTrue(p.contains("call the matching tool"));
        assertTrue(p.contains("never claim"));
        assertTrue(p.contains("tell the user what you did"));
        assertTrue(ToolKit.systemPrompt("").startsWith("You can act on the user's PC through"));
    }

    // ------------------------------------------------------------------
    // /tools
    // ------------------------------------------------------------------

    @Test
    public void reportWhenActive() {
        ToolKit.Status s = new ToolKit.Status();
        s.enabled = true;
        s.paired = true;
        s.confirm = true;
        s.model = "llama3.2:3b";
        s.modelTools = Boolean.TRUE;
        s.pc = "ATLAS-PC";
        s.catalog = richCatalog();
        s.catalog.add(tool("shutdown_pc", "Shut the PC down"));
        assertTrue(s.active());
        String r = ToolKit.report(s);
        assertTrue(r, r.startsWith("**AI tools · active** — OMNI can act on **ATLAS-PC** with `llama3.2:3b`."));
        assertTrue(r.contains("**Tools** (8)"));
        assertTrue(r.contains("• `get_volume` — Master volume · read-only"));
        assertTrue(r.contains("• `set_volume` — Set master volume · asks first"));
        assertTrue(r.contains("• `shutdown_pc` — Shut the PC down · always asks"));
        assertTrue(r.contains("• `open_app` — Open an app on the PC by name · asks first"));
        assertTrue(r.contains("`/tools off`"));
        s.confirm = false;
        assertTrue(ToolKit.report(s).contains("• `set_volume` — Set master volume · runs right away"));
    }

    @Test
    public void reportSaysWhyNot() {
        ToolKit.Status s = new ToolKit.Status();
        s.model = "phi4:14b";
        s.modelTools = Boolean.FALSE;
        String r = ToolKit.report(s);
        assertTrue(r, r.startsWith("**AI tools · off**"));
        assertTrue(r.contains("turned off. `/tools on`"));
        assertTrue(r.contains("isn't paired. Run `/pair`"));
        assertTrue(r.contains("`phi4:14b` can't call tools"));
        assertFalse("no tool list without a bridge", r.contains("**Tools**"));

        s.enabled = true;
        s.paired = true;
        s.modelTools = null;
        s.catalogError = "Can't reach the PC bridge";
        r = ToolKit.report(s);
        assertFalse(r.contains("turned off"));
        assertFalse(r.contains("/pair"));
        assertTrue(r.contains("Couldn't check whether `phi4:14b` can call tools"));
        assertTrue(r.contains("Couldn't read the PC's tool list: Can't reach the PC bridge"));
    }

    // ------------------------------------------------------------------
    // Replaying a reply that used tools
    // ------------------------------------------------------------------

    /** "Let me check." → set_volume (done) → "Now a screenshot." → screenshot (image) → "All set." */
    private static Conversation toolChat() {
        Conversation c = new Conversation();
        c.messages.add(new ChatMessage(ChatMessage.USER, "set the volume to 40 and show me the screen"));
        ChatMessage a = new ChatMessage(ChatMessage.ASSISTANT, "");
        a.content = "Let me check.\n\nNow a screenshot.\n\nAll set.";
        ToolCall v = new ToolCall("set_volume", obj("{\"level\":40}"));
        v.id = "c1";
        v.round = 1;
        v.at = "Let me check.".length();
        v.state = ToolCall.DONE;
        v.result = "Volume set to 40%";
        ToolCall s = new ToolCall("screenshot", new JSONObject());
        s.round = 2;
        s.at = "Let me check.\n\nNow a screenshot.".length();
        s.state = ToolCall.DONE;
        s.result = "{\"image\":\"[image attached]\"}";
        s.image = "iVBORw0KGgo=";
        ToolCall no = new ToolCall("lock_screen", new JSONObject());
        no.round = 2;
        no.at = s.at;
        no.state = ToolCall.DECLINED;
        no.result = ToolKit.DECLINED_RESULT;
        a.tools.add(v);
        a.tools.add(s);
        a.tools.add(no);
        c.messages.add(a);
        c.messages.add(ChatMessage.notice("not sent", "info"));
        return c;
    }

    @Test
    public void aToolReplyIsReplayedRoundByRound() throws Exception {
        JSONArray m = toolChat().toRequestMessages("sys", null, true, true);
        assertEquals(8, m.length());
        assertEquals("system", m.getJSONObject(0).getString("role"));
        assertEquals("user", m.getJSONObject(1).getString("role"));
        JSONObject r1 = m.getJSONObject(2);
        assertEquals("assistant", r1.getString("role"));
        assertEquals("Let me check.", r1.getString("content"));
        assertEquals("set_volume", r1.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
                .getString("name"));
        JSONObject t1 = m.getJSONObject(3);
        assertEquals("tool", t1.getString("role"));
        assertEquals("Volume set to 40%", t1.getString("content"));
        assertEquals("set_volume", t1.getString("tool_name"));
        assertEquals("c1", t1.getString("tool_call_id"));
        JSONObject r2 = m.getJSONObject(4);
        assertEquals("Now a screenshot.", r2.getString("content"));
        assertEquals(2, r2.getJSONArray("tool_calls").length());
        JSONObject t2 = m.getJSONObject(5);
        assertEquals("screenshot", t2.getString("tool_name"));
        assertEquals("the screenshot goes to a vision model", "iVBORw0KGgo=", t2.getJSONArray("images").getString(0));
        assertFalse(t2.has("tool_call_id"));
        assertEquals(ToolKit.DECLINED_RESULT, m.getJSONObject(6).getString("content"));
        assertEquals("All set.", m.getJSONObject(7).getString("content"));
        assertFalse(m.getJSONObject(7).has("tool_calls"));

        // A text-only model: no image, a marker instead.
        JSONObject plain = toolChat().toRequestMessages("sys", null, false, true).getJSONObject(5);
        assertFalse(plain.has("images"));
        assertTrue(plain.getString("content").contains("can't see images"));
    }

    @Test
    public void aModelWithoutToolsGetsTheActionsFolded() throws Exception {
        JSONArray m = toolChat().toRequestMessages("", null, true, false);
        assertEquals(2, m.length());
        String s = m.getJSONObject(1).getString("content");
        assertTrue(s, s.startsWith("[PC actions: set_volume (done: Volume set to 40%); screenshot (done: "));
        assertTrue(s.contains("lock_screen (declined)]"));
        assertTrue(s.endsWith("Let me check.\n\nNow a screenshot.\n\nAll set."));
        assertFalse(m.getJSONObject(1).has("tool_calls"));
    }

    @Test
    public void toolOnlyRepliesAreSentAndCounted() throws Exception {
        ChatMessage a = new ChatMessage(ChatMessage.ASSISTANT, "");
        assertFalse(a.sentToModel());
        ToolCall c = new ToolCall("get_volume", new JSONObject());
        c.round = 1;
        c.state = ToolCall.DONE;
        a.tools.add(c);
        assertTrue("its calls are context", a.sentToModel());
        Conversation conv = new Conversation();
        conv.messages.add(a);
        int before = conv.estimateTokens("", true);
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < 400; i++) r.append("word ");
        c.result = r.toString();
        assertTrue(conv.estimateTokens("", true) >= before + 400);
        // No trailing empty assistant message while the reply is still being written.
        JSONArray m = conv.toRequestMessages("", null, true, true);
        assertEquals(2, m.length());
        assertEquals("tool", m.getJSONObject(1).getString("role"));
    }

    @Test
    public void toolCallsAreSavedWithTheChat() throws Exception {
        Conversation c = toolChat();
        ChatMessage a = c.messages.get(1);
        a.tools.get(0).label = "Set volume → 40%";
        a.tools.get(0).ms = 312;
        ToolCall running = new ToolCall("get_volume", new JSONObject());
        running.round = 3;
        running.state = ToolCall.RUNNING;
        ToolCall asking = new ToolCall("set_clipboard", obj("{\"text\":\"x\"}"));
        asking.round = 3;
        asking.state = ToolCall.ASKING;
        a.tools.add(running);
        a.tools.add(asking);
        Conversation back = Conversation.fromJson(new JSONObject(c.toJson().toString()));
        List<ToolCall> t = back.messages.get(1).tools;
        assertEquals(5, t.size());
        assertEquals("Set volume → 40%", t.get(0).label);
        assertEquals(312, t.get(0).ms);
        assertEquals(40, t.get(0).args.getInt("level"));
        assertEquals("c1", t.get(0).id);
        assertEquals("iVBORw0KGgo=", t.get(1).image);
        assertEquals(2, t.get(1).round);
        assertEquals(a.tools.get(1).at, t.get(1).at);
        assertEquals("a call saved mid-flight can't finish any more", ToolCall.FAILED, t.get(3).state);
        assertTrue(t.get(3).result.contains("closed"));
        assertEquals(ToolCall.DECLINED, t.get(4).state);
        assertTrue(back.hasImages());
        String md = back.toMarkdown();
        assertTrue(md, md.contains("> PC action: Set volume → 40% (done)"));
        assertTrue(md.contains("All set."));
    }

    @Test
    public void theActiveToolState() {
        ChatMessage a = new ChatMessage(ChatMessage.ASSISTANT, "");
        assertNull(a.activeToolState());
        ToolCall d = new ToolCall("get_volume", null);
        d.state = ToolCall.DONE;
        a.tools.add(d);
        assertNull(a.activeToolState());
        ToolCall q = new ToolCall("set_volume", null);
        a.tools.add(q);
        assertEquals(ToolCall.RUNNING, a.activeToolState());
        q.state = ToolCall.ASKING;
        assertEquals(ToolCall.ASKING, a.activeToolState());
    }

    // ------------------------------------------------------------------
    // Approval
    // ------------------------------------------------------------------

    @Test
    public void anApprovalIsAnsweredOnce() {
        final List<String> seen = new ArrayList<String>();
        ToolApproval a = new ToolApproval("set_volume", "Set volume → 40%", "set volume to 40%", "PC", "", "", false);
        a.setDecision(new ToolApproval.Decision() {
            @Override
            public void decided(int answer) {
                seen.add("decided " + answer);
            }
        });
        a.setOnSettled(new Runnable() {
            @Override
            public void run() {
                seen.add("settled");
            }
        });
        assertTrue(a.isPending());
        a.allowForChat();
        a.deny();
        a.withdraw();
        assertFalse(a.isPending());
        assertEquals(ToolApproval.ALLOW_CHAT, a.answer());
        assertEquals(Arrays.asList("settled", "decided " + ToolApproval.ALLOW_CHAT), seen);

        ToolApproval d = new ToolApproval("shutdown_pc", "Shutdown pc", "shutdown pc", "PC", "", "", true);
        d.allowForChat();
        assertEquals("a destructive tool is only ever allowed once", ToolApproval.ALLOW, d.answer());
    }
}
