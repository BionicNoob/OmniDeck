package com.omnideck.mobile.ui;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.omnideck.mobile.core.BridgeTool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The sheet that runs one desktop tool: what it does, then a form built from
 * its arguments — a text field, a number field with its range, a switch, a
 * row of choices, or JSON for lists and objects — checked before anything
 * is sent. "Edit as JSON" swaps the form for the raw arguments (and back),
 * keeping what was typed. Tools that shut down, restart, sleep, kill or
 * delete something say so and get the danger button.
 */
public final class PcToolForm {
    /** Receives the arguments once they are valid (the sheet has closed). */
    public interface OnRun {
        void run(JSONObject args, boolean fromJson);
    }

    /** A value that doesn't pass (message for the user). */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    private final Ui ui;
    private final Theme t;
    private final PcKit kit;
    private final BridgeTool tool;
    private final OnRun onRun;
    private final Sheet sheet;
    private final List<Field> fields = new ArrayList<Field>();
    private final LinearLayout form;
    private final LinearLayout jsonBox;
    private final EditText json;
    private final TextView jsonError;
    private final TextView toJson;
    private final TextView toForm;
    /** Arguments the form has no field for (typed as JSON): kept and sent along. */
    private JSONObject extras = new JSONObject();
    private boolean jsonMode;

    /**
     * Opens the sheet for {@code tool}, filled from {@code initial} (null =
     * the tool's defaults); {@code startInJson} opens the raw JSON editor.
     */
    public static PcToolForm show(Ui ui, PcKit kit, BridgeTool tool, JSONObject initial, boolean startInJson,
                                  OnRun onRun) {
        PcToolForm f = new PcToolForm(ui, kit, tool, initial, onRun);
        if (startInJson) f.showJson();
        f.sheet.show();
        return f;
    }

    private PcToolForm(Ui ui, PcKit kit, BridgeTool tool, JSONObject initial, OnRun onRun) {
        this.ui = ui;
        this.t = ui.t;
        this.kit = kit;
        this.tool = tool;
        this.onRun = onRun;
        boolean danger = tool.destructive();
        sheet = ui.sheet(danger ? "Run · confirm" : "Run tool", tool.label());
        if (danger) sheet.eyebrowColor(t.danger);
        LinearLayout body = sheet.body;

        TextView id = ui.readout(tool.name, 12.5f, t.dim);
        id.setContentDescription("Tool id " + tool.name);
        body.addView(id, Ui.fillW());
        String summary = tool.summary();
        if (summary.length() > 0) sheet.message(summary);
        if (danger) {
            TextView warn = kit.notice(BridgeTool.POWER.equals(tool.category())
                    ? "This acts on the PC right away — unsaved work there may be lost."
                    : "This can't be undone from the phone.", t.danger);
            body.addView(warn, ui.margins(Ui.fillW(), 0, 12, 0, 0));
        }

        form = ui.vbox();
        for (BridgeTool.Param p : tool.params) {
            Field f = field(p);
            fields.add(f);
            form.addView(f.block, ui.margins(Ui.fillW(), 0, 16, 0, 0));
        }
        if (fields.isEmpty()) {
            TextView none = ui.dim("No settings — Run starts it right away.", 13);
            form.addView(none, ui.margins(Ui.fillW(), 0, 14, 0, 0));
        }
        body.addView(form, Ui.fillW());

        jsonBox = ui.vbox();
        jsonBox.addView(ui.label("Arguments · JSON"), Ui.fillW());
        json = ui.field("", "{}", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        json.setTypeface(t.mono);
        json.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        json.setMinLines(3);
        json.setMaxLines(12);
        json.setGravity(Gravity.TOP | Gravity.START);
        json.setContentDescription("Tool arguments");
        jsonBox.addView(json, ui.margins(Ui.fillW(), 0, 8, 0, 0));
        jsonError = errorLine();
        jsonBox.addView(jsonError, ui.margins(Ui.fillW(), 0, 6, 0, 0));
        jsonBox.setVisibility(View.GONE);
        body.addView(jsonBox, ui.margins(Ui.fillW(), 0, 16, 0, 0));

        // The mode switch lives in the body: the sheet stays open while switching.
        LinearLayout modes = ui.hbox();
        modes.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toJson = kit.quiet("Edit as JSON", IconDrawable.TERMINAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showJson();
            }
        });
        toJson.setContentDescription("Edit as JSON");
        modes.addView(toJson, Ui.wrap());
        toForm = kit.quiet("Back to form", IconDrawable.EDIT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showForm();
            }
        });
        toForm.setContentDescription("Back to form");
        toForm.setVisibility(View.GONE);
        modes.addView(toForm, Ui.wrap());
        body.addView(modes, ui.margins(Ui.fillW(), 0, 16, 0, 4));

        fill(initial != null ? initial : tool.template());

        sheet.negative("Cancel", null);
        sheet.positive("Run", danger ? Ui.DANGER : Ui.PRIMARY, false, new Runnable() {
            @Override
            public void run() {
                submit();
            }
        });
    }

    /** The sheet (tests, and to check whether it's still open). */
    public Sheet sheet() {
        return sheet;
    }

    public boolean isJsonMode() {
        return jsonMode;
    }

    // ------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------

    private void showJson() {
        JSONObject now = collectLoose();
        try {
            json.setText(now.length() == 0 ? "{}" : now.toString(2));
        } catch (JSONException e) {
            json.setText(now.toString());
        }
        json.setSelection(json.getText().length());
        jsonError.setVisibility(View.GONE);
        form.setVisibility(View.GONE);
        jsonBox.setVisibility(View.VISIBLE);
        toJson.setVisibility(View.GONE);
        toForm.setVisibility(fields.isEmpty() ? View.GONE : View.VISIBLE);
        jsonMode = true;
        json.requestFocus();
    }

    private void showForm() {
        JSONObject o;
        try {
            o = parseJson();
        } catch (Invalid e) {
            jsonError.setText(e.getMessage());
            jsonError.setVisibility(View.VISIBLE);
            return;
        }
        fill(o);
        jsonBox.setVisibility(View.GONE);
        form.setVisibility(View.VISIBLE);
        toForm.setVisibility(View.GONE);
        toJson.setVisibility(View.VISIBLE);
        jsonMode = false;
    }

    /** Puts argument values into the fields; unknown keys are kept as extras. */
    private void fill(JSONObject o) {
        extras = new JSONObject();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (find(k) == null) {
                try {
                    extras.put(k, o.opt(k));
                } catch (JSONException ignored) {
                    // a key from a parsed object; can't fail
                }
            }
        }
        for (Field f : fields) {
            f.clearError();
            f.set(o.has(f.p.name) && !o.isNull(f.p.name) ? o.opt(f.p.name) : null);
        }
    }

    private Field find(String name) {
        for (Field f : fields) {
            if (f.p.name.equals(name)) return f;
        }
        return null;
    }

    private JSONObject parseJson() throws Invalid {
        String raw = json.getText().toString().trim();
        if (raw.length() == 0) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException ex) {
            throw new Invalid("That isn't a JSON object — " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Run
    // ------------------------------------------------------------------

    private void submit() {
        JSONObject args;
        if (jsonMode) {
            try {
                args = parseJson();
            } catch (Invalid e) {
                jsonError.setText(e.getMessage());
                jsonError.setVisibility(View.VISIBLE);
                return;
            }
        } else {
            args = collectStrict();
            if (args == null) return;
        }
        sheet.dismiss();
        onRun.run(args, jsonMode);
    }

    /** Every field checked (errors shown under the fields); null when one doesn't pass. */
    private JSONObject collectStrict() {
        JSONObject o = copy(extras);
        boolean ok = true;
        for (Field f : fields) {
            f.clearError();
            try {
                Object v = f.value();
                if (v != null) o.put(f.p.name, v);
            } catch (Invalid e) {
                f.showError(e.getMessage());
                ok = false;
            } catch (JSONException e) {
                f.showError(e.getMessage());
                ok = false;
            }
        }
        return ok ? o : null;
    }

    /** What the form holds so far, for the JSON view: a value that doesn't pass is kept as typed. */
    private JSONObject collectLoose() {
        JSONObject o = copy(extras);
        for (Field f : fields) {
            try {
                Object v;
                try {
                    v = f.value();
                } catch (Invalid e) {
                    v = f.raw();
                }
                if (v != null) o.put(f.p.name, v);
            } catch (JSONException ignored) {
                // plain names; can't fail
            }
        }
        return o;
    }

    private static JSONObject copy(JSONObject src) {
        JSONObject o = new JSONObject();
        Iterator<String> keys = src.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            try {
                o.put(k, src.opt(k));
            } catch (JSONException ignored) {
                // copied keys; can't fail
            }
        }
        return o;
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private TextView errorLine() {
        TextView e = ui.text("", 12, t.danger, t.body);
        e.setLineSpacing(0, 1.15f);
        e.setVisibility(View.GONE);
        return e;
    }

    private Field field(BridgeTool.Param p) {
        if (!p.choices.isEmpty()) return new ChoiceField(p);
        if ("boolean".equals(p.type)) return new BoolField(p);
        if (p.numeric()) return new NumberField(p);
        if ("array".equals(p.type) || "object".equals(p.type)) return new JsonField(p);
        return new TextField(p);
    }

    /** "0 – 100", "≥ 0", "≤ 10", or "" without bounds. */
    static String range(BridgeTool.Param p) {
        boolean lo = !Double.isNaN(p.min), hi = !Double.isNaN(p.max);
        if (lo && hi) return num(p.min) + " – " + num(p.max);
        if (lo) return "≥ " + num(p.min);
        if (hi) return "≤ " + num(p.max);
        return "";
    }

    static String num(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** One argument: a caps label (with "optional" or its range), what it's for, the control, an error line. */
    private abstract class Field {
        final BridgeTool.Param p;
        final LinearLayout block;
        final TextView error;

        Field(BridgeTool.Param p, boolean inlineControl) {
            this.p = p;
            block = ui.vbox();
            error = errorLine();
            if (inlineControl) return; // a switch row builds its own head
            LinearLayout head = ui.hbox();
            TextView l = ui.label(p.label() + (p.required ? "" : " · optional"));
            head.addView(l, Ui.weight(1));
            String r = range(p);
            if (r.length() > 0) head.addView(ui.readout(r, 11, t.dim), Ui.wrap());
            block.addView(head, Ui.fillW());
            if (p.description.length() > 0) {
                TextView d = ui.dim(p.description, 12.5f);
                block.addView(d, ui.margins(Ui.fillW(), 0, 4, 0, 0));
            }
        }

        void addControl(View v) {
            block.addView(v, ui.margins(Ui.fillW(), 0, 8, 0, 0));
            block.addView(error, ui.margins(Ui.fillW(), 0, 6, 0, 0));
        }

        void showError(String s) {
            error.setText(s);
            error.setVisibility(View.VISIBLE);
        }

        void clearError() {
            error.setVisibility(View.GONE);
        }

        /** Shows a value (null = the default, or empty). */
        abstract void set(Object v);

        /** The value to send, null to leave it out; Invalid when it doesn't pass. */
        abstract Object value() throws Invalid;

        /** The value as typed, for the JSON view when it doesn't pass. */
        Object raw() {
            return null;
        }

        String required() {
            return p.label() + " is required.";
        }
    }

    private final class TextField extends Field {
        final EditText et;

        TextField(BridgeTool.Param p) {
            super(p, false);
            boolean multi = p.name.contains("text") || p.name.contains("content") || p.name.contains("body")
                    || p.name.contains("message");
            int type = InputType.TYPE_CLASS_TEXT | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0);
            String n = p.name.toLowerCase(Locale.US);
            if (n.contains("url") || n.contains("link")) type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
            et = ui.field("", p.defaultValue.length() > 0 ? p.defaultValue : p.required ? "Required" : "Optional",
                    type);
            if (multi) {
                et.setMinLines(2);
                et.setMaxLines(6);
                et.setGravity(Gravity.TOP | Gravity.START);
            } else {
                et.setSingleLine(true);
            }
            et.setContentDescription(p.label());
            addControl(et);
        }

        @Override
        void set(Object v) {
            et.setText(v == null ? p.defaultValue : String.valueOf(v));
        }

        @Override
        Object value() throws Invalid {
            String s = et.getText().toString();
            if (s.trim().length() == 0) {
                if (p.required) throw new Invalid(required());
                return null;
            }
            return s;
        }
    }

    private final class NumberField extends Field {
        final EditText et;
        final boolean integer;

        NumberField(BridgeTool.Param p) {
            super(p, false);
            integer = "integer".equals(p.type);
            int type = InputType.TYPE_CLASS_NUMBER;
            if (!integer) type |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
            if (Double.isNaN(p.min) || p.min < 0) type |= InputType.TYPE_NUMBER_FLAG_SIGNED;
            String r = range(p);
            et = ui.field("", r.length() > 0 ? r : integer ? "Whole number" : "Number", type);
            et.setSingleLine(true);
            et.setTypeface(t.mono);
            et.setContentDescription(p.label());
            addControl(et);
        }

        @Override
        void set(Object v) {
            if (v instanceof Number) {
                et.setText(num(((Number) v).doubleValue()));
            } else {
                et.setText(v == null ? p.defaultValue : String.valueOf(v));
            }
        }

        @Override
        Object value() throws Invalid {
            String s = et.getText().toString().trim();
            if (s.length() == 0) {
                if (p.required) throw new Invalid(required());
                return null;
            }
            double d;
            try {
                d = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new Invalid(integer ? "Enter a whole number." : "Enter a number.");
            }
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new Invalid("Enter a number.");
            if (integer && d != Math.rint(d)) throw new Invalid("Enter a whole number.");
            boolean lo = !Double.isNaN(p.min), hi = !Double.isNaN(p.max);
            if ((lo && d < p.min) || (hi && d > p.max)) {
                throw new Invalid(lo && hi ? "Between " + num(p.min) + " and " + num(p.max) + "."
                        : lo ? num(p.min) + " or more." : num(p.max) + " or less.");
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.valueOf((long) d);
            return Double.valueOf(d);
        }

        @Override
        Object raw() {
            String s = et.getText().toString().trim();
            return s.length() == 0 ? null : s;
        }
    }

    private final class BoolField extends Field {
        final Widgets.Toggle toggle;
        boolean touched;

        BoolField(BridgeTool.Param p) {
            super(p, true);
            toggle = ui.toggle(false, new Widgets.Toggle.OnChange() {
                @Override
                public void changed(boolean on) {
                    touched = true;
                }
            });
            toggle.setContentDescription(p.label());
            String sub = p.description;
            if (!p.required) sub = sub.length() > 0 ? sub : "Optional";
            LinearLayout row = ui.settingRow(p.label(), sub, toggle);
            row.setPadding(0, ui.dp(2), 0, ui.dp(2));
            block.addView(row, Ui.fillW());
            block.addView(error, ui.margins(Ui.fillW(), 0, 4, 0, 0));
        }

        @Override
        void set(Object v) {
            boolean on = v instanceof Boolean ? (Boolean) v
                    : v != null ? Boolean.parseBoolean(String.valueOf(v)) : Boolean.parseBoolean(p.defaultValue);
            toggle.setChecked(on, false);
            touched = v != null;
        }

        @Override
        Object value() {
            if (p.required || touched || p.defaultValue.length() > 0) return Boolean.valueOf(toggle.isChecked());
            return null;
        }
    }

    private final class ChoiceField extends Field {
        final List<TextView> chips = new ArrayList<TextView>();
        String selected;

        ChoiceField(BridgeTool.Param p) {
            super(p, false);
            PcFlow flow = new PcFlow(ui.c, ui.dp(6), ui.dp(6));
            for (final String c : p.choices) {
                TextView chip = ui.text(c, 13, t.ink, t.mono);
                chip.setSingleLine(true);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(ui.dp(11), ui.dp(7), ui.dp(11), ui.dp(7));
                chip.setMinHeight(ui.dp(34));
                chip.setContentDescription(p.label() + ": " + c);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ui.tick(v);
                        // Tapping the chosen one again clears an optional choice.
                        select(c.equals(selected) && !ChoiceField.this.p.required ? null : c);
                        clearError();
                    }
                });
                chips.add(chip);
                flow.addView(chip);
            }
            addControl(flow);
        }

        void select(String c) {
            selected = c;
            int on = t.id == Theme.DARK ? t.data : t.accent;
            for (int i = 0; i < chips.size(); i++) {
                TextView chip = chips.get(i);
                boolean sel = p.choices.get(i).equals(c);
                chip.setTextColor(sel ? on : t.ink);
                chip.setBackground(sel ? ui.rounded(Theme.alpha(on, t.isDark ? 0x24 : 0x17), on, 8)
                        : kit.pressable(8));
                chip.setSelected(sel);
            }
        }

        @Override
        void set(Object v) {
            String s = v != null ? String.valueOf(v) : p.defaultValue;
            select(p.choices.contains(s) ? s : null);
        }

        @Override
        Object value() throws Invalid {
            if (selected == null) {
                if (p.required) throw new Invalid("Pick one.");
                return null;
            }
            return selected;
        }
    }

    private final class JsonField extends Field {
        final EditText et;
        final boolean array;

        JsonField(BridgeTool.Param p) {
            super(p, false);
            array = "array".equals(p.type);
            et = ui.field("", array ? "[ … ]" : "{ … }", InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            et.setTypeface(t.mono);
            et.setMinLines(2);
            et.setGravity(Gravity.TOP | Gravity.START);
            et.setContentDescription(p.label());
            et.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    clearError();
                }
            });
            addControl(et);
        }

        @Override
        void set(Object v) {
            if (v == null) {
                et.setText(p.defaultValue);
                return;
            }
            et.setText(PcKit.pretty(v));
        }

        @Override
        Object value() throws Invalid {
            String s = et.getText().toString().trim();
            if (s.length() == 0) {
                if (p.required) throw new Invalid(required());
                return null;
            }
            try {
                return array ? new JSONArray(s) : new JSONObject(s);
            } catch (JSONException e) {
                throw new Invalid(array ? "Enter a JSON list, e.g. [\"a\", \"b\"]." : "Enter a JSON object, e.g. {\"a\": 1}.");
            }
        }

        @Override
        Object raw() {
            String s = et.getText().toString().trim();
            return s.length() == 0 ? null : s;
        }
    }

    /** The positive (Run) button, for tests. */
    public Button runButton() {
        return sheet.dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
    }
}
