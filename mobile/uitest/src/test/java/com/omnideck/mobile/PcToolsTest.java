package com.omnideck.mobile;

import android.app.AlertDialog;
import android.widget.EditText;

import com.omnideck.mobile.ui.Widgets;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The tool runner: every tool the bridge offers, grouped and described, each
 * opening a form built from its arguments (checked before anything is sent),
 * with raw JSON one tap away, results in a themed sheet, and "Run again".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PcToolsTest extends PcBaseTest {

    /** Tools that describe their arguments with a JSON schema (and one with a "{url}" hint). */
    static void addSchemaTools(com.omnideck.mobile.mock.MockBridge b) throws Exception {
        b.extraTools.add(new JSONObject("{\"name\":\"set_brightness\",\"description\":\"Screen brightness\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{"
                + "\"level\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100,\"description\":\"Percent of full\"},"
                + "\"smooth\":{\"type\":\"boolean\",\"default\":true,\"description\":\"Fade to the new level\"}},"
                + "\"required\":[\"level\"]}}"));
        b.extraTools.add(new JSONObject("{\"name\":\"media_key\",\"description\":\"Send a media key\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\","
                + "\"enum\":[\"play_pause\",\"next\",\"previous\"]}},\"required\":[\"key\"]}}"));
        b.extraTools.add(new JSONObject("{\"name\":\"notify\",\"description\":\"Show a notification on the PC\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},"
                + "\"message\":{\"type\":\"string\",\"description\":\"What it says\"},"
                + "\"tags\":{\"type\":\"array\"}},\"required\":[\"message\"]}}"));
        b.extraTools.add(new JSONObject("{\"name\":\"open_url\",\"description\":\"Open a web page {url}\"}"));
    }

    private JSONObject lastArgs(String tool) throws Exception {
        JSONObject found = null;
        synchronized (bridge.ranArgs) {
            for (JSONObject o : bridge.ranArgs) {
                if (tool.equals(o.getString("tool"))) found = o.getJSONObject("args");
            }
        }
        assertNotNull("no run of " + tool, found);
        return found;
    }

    private void openRunner(String theme) throws Exception {
        openPc(theme);
        waitPaired();
        click("Tool runner");
        waitFor("tools", () -> button("Run list_processes") != null);
    }

    @Test
    public void toolsAreGroupedWithWhatTheyDo() throws Exception {
        richBridge(true);
        bridge.power = true;
        openRunner("cyber");
        assertTrue(shows("Media & sound"));
        assertTrue(shows("Information"));
        assertTrue(shows("Other tools"));
        assertTrue(shows("Top processes by CPU"));
        assertTrue(shows("Lock the workstation"));
        assertTrue(shows("11 tools"));
        assertNotNull(button("Run shutdown_pc"));
    }

    @Test
    public void formsComeFromTheToolsArgumentsAndAreCheckedFirst() throws Exception {
        richBridge(true);
        addSchemaTools(bridge);
        openRunner("dark");

        // Numbers: required, then within their range; the switch keeps its default.
        click("Run set_brightness");
        AlertDialog f = latestAlert();
        assertTrue(dialogShows(f, "Screen brightness"));
        assertTrue(dialogShows(f, "Percent of full"));
        assertTrue(dialogShows(f, "0 – 100"));
        positive(f);
        assertTrue(f.isShowing());
        assertTrue(dialogShows(f, "Level is required."));
        dialogField(f, "Level").setText("150");
        positive(f);
        assertTrue(dialogShows(f, "Between 0 and 100."));
        assertFalse(bridge.ranTools.contains("set_brightness"));
        dialogField(f, "Level").setText("70");
        positive(f);
        assertFalse(f.isShowing());
        AlertDialog result = waitSheet("result", f);
        assertTrue(dialogShows(result, "OK"));
        JSONObject args = lastArgs("set_brightness");
        assertEquals(70, args.getInt("level"));
        assertTrue(args.getBoolean("smooth"));
        negative(result);

        // Choices: pick one of the allowed values.
        click("Run media_key");
        AlertDialog m = latestAlert();
        positive(m);
        assertTrue(dialogShows(m, "Pick one."));
        dialogClick(m, "Key: next");
        positive(m);
        negative(waitSheet("media result", m));
        assertEquals("next", lastArgs("media_key").getString("key"));

        // A list argument must be JSON; optional text can stay empty.
        click("Run notify");
        AlertDialog n = latestAlert();
        dialogField(n, "Message").setText("Render finished");
        dialogField(n, "Tags").setText("not json");
        positive(n);
        assertTrue(dialogShows(n, "Enter a JSON list"));
        dialogField(n, "Tags").setText("[\"render\", \"blender\"]");
        positive(n);
        negative(waitSheet("notify result", n));
        JSONObject na = lastArgs("notify");
        assertEquals("Render finished", na.getString("message"));
        assertEquals(2, na.getJSONArray("tags").length());
        assertFalse("empty optional text is left out", na.has("title"));

        // A "{url}" hint in the description becomes a required text field.
        click("Run open_url");
        AlertDialog u = latestAlert();
        dialogField(u, "Url").setText("https://ollama.com");
        positive(u);
        negative(waitSheet("url result", u));
        assertEquals("https://ollama.com", lastArgs("open_url").getString("url"));
    }

    @Test
    public void jsonAndTheFormStayInStep() throws Exception {
        richBridge(true);
        addSchemaTools(bridge);
        openRunner("light");
        click("Run set_brightness");
        AlertDialog f = latestAlert();
        dialogField(f, "Level").setText("40");
        dialogClick(f, "Edit as JSON");
        EditText json = dialogField(f, "Tool arguments");
        String shown = json.getText().toString();
        assertTrue(shown, shown.contains("\"level\": 40"));
        assertTrue(shown, shown.contains("\"smooth\": true"));
        json.setText("{\"level\": 55, \"smooth\": false, \"curve\": \"ease\"}");
        dialogClick(f, "Back to form");
        assertEquals("55", dialogField(f, "Level").getText().toString());
        Widgets.Toggle smooth = (Widgets.Toggle) dialogView(f, "Smooth");
        assertFalse(smooth.isChecked());
        positive(f);
        negative(waitSheet("result", f));
        JSONObject args = lastArgs("set_brightness");
        assertEquals(55, args.getInt("level"));
        assertFalse(args.getBoolean("smooth"));
        assertEquals("keys the form has no field for are kept", "ease", args.getString("curve"));
    }

    @Test
    public void destructiveToolsWarnAndUseTheDangerButton() throws Exception {
        richBridge(true);
        bridge.power = true;
        openRunner("cyber");
        click("Run shutdown_pc");
        AlertDialog f = latestAlert();
        assertTrue(dialogShows(f, "unsaved work"));
        assertTrue(dialogShows(f, "Run · confirm"));
        assertEquals(act.theme().danger, f.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());
        negative(f);
        advance(300);
        assertFalse(bridge.ranTools.contains("shutdown_pc"));
    }

    @Test
    public void runAgainReopensTheFormWithTheSameArguments() throws Exception {
        richBridge(true);
        openRunner("dark");
        click("Run set_volume");
        AlertDialog f = latestAlert();
        dialogField(f, "Level").setText("20");
        positive(f);
        AlertDialog result = waitSheet("result", f);
        waitFor("volume 20", () -> bridge.volume == 20);
        // The runner changed the volume: the slider follows.
        waitFor("slider follows", () -> slider().value() == 20);
        neutral(result);
        AlertDialog again = latestAlert();
        assertEquals("20", dialogField(again, "Level").getText().toString());
        dialogField(again, "Level").setText("30");
        positive(again);
        waitFor("volume 30", () -> bridge.volume == 30);
    }

    @Test
    public void aToolWithoutArgumentsRunsFromItsSheet() throws Exception {
        richBridge(true);
        openRunner("light");
        click("Run list_processes");
        AlertDialog f = latestAlert();
        assertTrue(dialogShows(f, "Top processes by CPU"));
        assertTrue(dialogShows(f, "No settings"));
        positive(f);
        AlertDialog r = waitSheet("result", f);
        assertTrue(dialogShows(r, "ollama.exe"));
        assertTrue(dialogShows(r, "list_processes"));
        // Copy puts the output on the phone's clipboard.
        positive(r);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) act.getSystemService(
                android.content.Context.CLIPBOARD_SERVICE);
        assertTrue(cm.getPrimaryClip().getItemAt(0).getText().toString().contains("launchbridge.exe"));
    }
}
