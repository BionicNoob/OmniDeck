package com.omnideck.mobile;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.Commands;
import com.omnideck.mobile.core.Conversation;
import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.core.HostPort;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.OllamaClient;
import com.omnideck.mobile.core.ServerInfo;
import com.omnideck.mobile.ui.BubbleLayout;
import com.omnideck.mobile.ui.ChatScrollView;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.MarkdownRenderer;
import com.omnideck.mobile.ui.Palette;
import com.omnideck.mobile.ui.ShapeDrawable2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The one screen: status header, chat, command-aware composer. */
public final class MainActivity extends Activity implements Engine.Listener {
    static final int INITIAL_RENDER = 80;

    private Engine engine;
    private Palette p;
    private MarkdownRenderer md;
    private float dp;
    private String themePref;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Views
    private TextView subtitle;
    private LinearLayout statusPill;
    private View statusDot;
    private TextView statusText;
    private ObjectAnimator pulse;
    private TextView modelChip;
    private TextView modeChip;
    private TextView speedView;
    private LinearLayout offlineCard;
    private TextView offlineDetail;
    private ChatScrollView scroll;
    private LinearLayout list;
    private LinearLayout emptyState;
    private TextView emptySub;
    private View jumpBtn;
    private ScrollView suggestScroll;
    private LinearLayout suggestBox;
    private EditText input;
    private FrameLayout sendBtn;
    private IconDrawable sendIcon;

    private final Map<String, Holder> holders = new HashMap<String, Holder>();
    private final Set<String> expandedThoughts = new HashSet<String>();
    private int renderedFrom;
    private boolean suppressSuggest;

    /** Views for one message. */
    private final class Holder {
        final ChatMessage m;
        final LinearLayout row;
        final BubbleLayout bubble;
        final TextView header;
        final TextView thinkToggle;
        final TextView thinkBody;
        final TextView body;
        final ImageView image;
        final TextView footer;
        String shownContent;
        String shownThinking;
        boolean shownStreaming;
        String shownBg = "";

        Holder(ChatMessage m) {
            this.m = m;
            row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(5);
            rlp.bottomMargin = dp(5);
            row.setLayoutParams(rlp);
            row.setGravity(m.isUser() ? Gravity.END : (m.isNotice() || m.isSystem()) ? Gravity.CENTER_HORIZONTAL
                    : Gravity.START);

            bubble = new BubbleLayout(MainActivity.this, m.isNotice() || m.isSystem() ? 0.96f : 0.88f);
            int padL = m.isAssistant() && p.chamfer ? dp(17) : dp(14);
            bubble.setPadding(padL, dp(9), dp(14), dp(10));
            row.addView(bubble, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            header = text("", 10, labelColor(m), p.monoFace);
            header.setLetterSpacing(0.08f);
            header.setSingleLine(true);
            header.setEllipsize(TextUtils.TruncateAt.END);
            // WRAP_CONTENT (not the default MATCH_PARENT) so a longer text widens the
            // bubble instead of being clipped: TextView skips relayout for fixed widths.
            bubble.addView(header, wrap());

            thinkToggle = text("", 10.5f, p.dim, p.monoFace);
            thinkToggle.setPadding(0, dp(4), 0, dp(2));
            thinkToggle.setVisibility(View.GONE);
            thinkToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!expandedThoughts.remove(Holder.this.m.id)) expandedThoughts.add(Holder.this.m.id);
                    shownThinking = null;
                    bind(Holder.this);
                }
            });
            bubble.addView(thinkToggle, wrap());

            thinkBody = text("", 13, p.dim, p.bodyFace);
            thinkBody.setTypeface(p.bodyFace, Typeface.ITALIC);
            thinkBody.setLineSpacing(0, 1.2f);
            thinkBody.setPadding(0, 0, 0, dp(6));
            thinkBody.setVisibility(View.GONE);
            bubble.addView(thinkBody, wrap());

            body = text("", p.bodySp, bodyColor(m), p.bodyFace);
            body.setLineSpacing(0, 1.28f);
            body.setPadding(0, dp(3), 0, 0);
            bubble.addView(body, wrap());

            image = new ImageView(MainActivity.this);
            image.setAdjustViewBounds(true);
            image.setMaxHeight(dp(420));
            image.setScaleType(ImageView.ScaleType.FIT_START);
            image.setVisibility(View.GONE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = dp(6);
            bubble.addView(image, ilp);

            footer = text("", 10, p.dim, p.monoFace);
            footer.setPadding(0, dp(5), 0, 0);
            footer.setVisibility(View.GONE);
            bubble.addView(footer, wrap());

            View.OnLongClickListener lc = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showMessageActions(Holder.this.m);
                    return true;
                }
            };
            bubble.setOnLongClickListener(lc);
            body.setOnLongClickListener(lc);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        engine = Engine.get(this);
        themePref = engine.settings.theme();
        p = Palette.forPref(this, themePref);
        setTheme(p.themeRes);
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        md = new MarkdownRenderer(p, dp);
        styleSystemBars();
        setContentView(buildUi());
        engine.setListener(this);
        renderAll();
        onStateChanged();
        onBusyChanged();
        if (engine.draft.length() > 0) {
            input.setText(engine.draft);
            input.setSelection(input.getText().length());
        }
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared != null && shared.length() > 0) onInsertText(shared.toString());
        intent.setAction(Intent.ACTION_MAIN);
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.setListener(this);
        if (!themePref.equals(engine.settings.theme())) {
            recreate();
            return;
        }
        engine.setVisible(true);
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        engine.draft = input.getText().toString();
        engine.setVisible(false);
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pulse != null) pulse.cancel();
        ui.removeCallbacksAndMessages(null);
        if (engine.listener() == this) engine.setListener(null);
    }

    /** Refreshes the live "waiting 3s" label on a streaming reply. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            ChatMessage s = engine.streamingMessage();
            if (s != null) {
                Holder h = holders.get(s.id);
                if (h != null) h.header.setText(headerText(s));
            }
            ui.postDelayed(this, 500);
        }
    };

    private void styleSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(p.surface);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (!p.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!p.dark && Build.VERSION.SDK_INT >= 27) {
            flags |= 0x10; // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR (API 27)
            w.setNavigationBarColor(p.bg);
        } else {
            w.setNavigationBarColor(p.dark ? p.bg : 0xFF000000);
        }
        decor.setSystemUiVisibility(flags);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * dp);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private Drawable chip(int fill, int stroke) {
        return p.chamfer ? ShapeDrawable2.chamfer(dp(5), 0, dp(5), 0, fill, stroke, dp(1))
                : ShapeDrawable2.round(dp(8), dp(8), dp(8), dp(8), fill, stroke, dp(1));
    }

    private Drawable ripple() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId != 0 ? getDrawable(tv.resourceId) : null;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(p.edge);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2 + 1)));
        return v;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);

        // --- Top bar -------------------------------------------------
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(p.surface);
        top.setPadding(dp(14), dp(10), dp(4), dp(8));

        ImageView logo = new ImageView(this);
        logo.setImageDrawable(new IconDrawable(IconDrawable.LOGO, p.dark ? p.accent : p.accent, p.dark ? p.accent2 : p.danger, dp(26)));
        top.addView(logo, new LinearLayout.LayoutParams(dp(26), dp(26)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, dp(8), 0);
        TextView title = text("OMNI-DECK", p.dark ? 15 : 16.5f, p.text, p.titleFace);
        title.setLetterSpacing(p.dark ? 0.1f : 0.02f);
        titles.addView(title);
        subtitle = text("", 10, p.dim, p.monoFace);
        subtitle.setLetterSpacing(0.06f);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(dp(10), dp(6), dp(11), dp(6));
        statusDot = new View(this);
        statusPill.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        statusText = text("", 11, p.warn, p.monoFace);
        statusText.setLetterSpacing(0.1f);
        statusText.setPadding(dp(7), 0, 0, 0);
        statusPill.addView(statusText);
        statusPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConnectionDialog();
            }
        });
        top.addView(statusPill);

        ImageView menu = new ImageView(this);
        menu.setImageDrawable(new IconDrawable(IconDrawable.MENU, p.dim, p.dim, dp(22)));
        menu.setScaleType(ImageView.ScaleType.CENTER);
        menu.setBackground(ripple());
        menu.setContentDescription("Menu");
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        top.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(top);

        // --- Model / mode bar ----------------------------------------
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(p.surface);
        bar.setPadding(dp(12), 0, dp(14), dp(9));

        modelChip = text("", 12, p.text, p.monoFace);
        modelChip.setSingleLine(true);
        modelChip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        modelChip.setGravity(Gravity.CENTER_VERTICAL);
        modelChip.setPadding(dp(10), dp(6), dp(8), dp(6));
        modelChip.setBackground(chip(p.chipFill, p.inputEdge));
        IconDrawable chev = new IconDrawable(IconDrawable.CHEVRON, p.dim, p.dim, dp(14));
        chev.setBounds(0, 0, dp(14), dp(14));
        modelChip.setCompoundDrawables(null, null, chev, null);
        modelChip.setCompoundDrawablePadding(dp(4));
        modelChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelPicker();
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bar.addView(modelChip, mlp);

        modeChip = text("", 11, p.dim, p.monoFace);
        modeChip.setLetterSpacing(0.1f);
        modeChip.setPadding(dp(9), dp(6), dp(9), dp(6));
        modeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleMode();
            }
        });
        LinearLayout.LayoutParams mdlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mdlp.leftMargin = dp(8);
        bar.addView(modeChip, mdlp);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        speedView = text("", 10.5f, p.dim, p.monoFace);
        bar.addView(speedView);
        root.addView(bar);
        root.addView(hairline());

        // --- Offline card --------------------------------------------
        offlineCard = new LinearLayout(this);
        offlineCard.setOrientation(LinearLayout.VERTICAL);
        offlineCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        offlineCard.setBackground(p.chamfer
                ? ShapeDrawable2.chamfer(dp(10), dp(10), dp(10), dp(10), p.errorFill, p.errorStroke, dp(1))
                : ShapeDrawable2.round(dp(12), dp(12), dp(12), dp(12), p.errorFill, p.errorStroke, dp(1)));
        TextView ot = text(p.dark ? "AI NOT FOUND ON THIS NETWORK" : "Can't find your AI on this network", 12.5f,
                p.danger, p.dark ? p.monoFace : Typeface.create("sans-serif-medium", Typeface.BOLD));
        ot.setLetterSpacing(p.dark ? 0.08f : 0f);
        offlineCard.addView(ot);
        offlineDetail = text("", 12, p.text, p.bodyFace);
        offlineDetail.setPadding(0, dp(6), 0, 0);
        offlineCard.addView(offlineDetail);
        TextView how = text("On the PC:\n"
                        + "1. Let Ollama accept network connections: set the environment variable OLLAMA_HOST=0.0.0.0, "
                        + "then restart Ollama.\n"
                        + "2. Allow port 11434 through the firewall (private networks).\n"
                        + "3. Keep this phone on the same Wi-Fi as the PC.",
                12, p.dim, p.bodyFace);
        how.setLineSpacing(0, 1.2f);
        how.setPadding(0, dp(8), 0, dp(10));
        offlineCard.addView(how);
        LinearLayout ob = new LinearLayout(this);
        ob.setOrientation(LinearLayout.HORIZONTAL);
        ob.addView(actionButton("SCAN AGAIN", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.discover(false);
            }
        }));
        View gap = new View(this);
        ob.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        ob.addView(actionButton("SET ADDRESS", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptServerAddress();
            }
        }));
        offlineCard.addView(ob);
        LinearLayout.LayoutParams oclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        oclp.setMargins(dp(12), dp(10), dp(12), dp(4));
        offlineCard.setVisibility(View.GONE);
        root.addView(offlineCard, oclp);

        // --- Chat ----------------------------------------------------
        FrameLayout chat = new FrameLayout(this);
        scroll = new ChatScrollView(this);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(10), dp(12), dp(12));
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        chat.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        emptyState = buildEmptyState();
        chat.addView(emptyState, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView jump = new ImageView(this);
        jump.setImageDrawable(new IconDrawable(IconDrawable.DOWN, p.dark ? p.accent2 : p.accent, 0, dp(20)));
        jump.setScaleType(ImageView.ScaleType.CENTER);
        jump.setBackground(p.chamfer
                ? ShapeDrawable2.chamfer(dp(6), 0, dp(6), 0, p.panel, p.dark ? 0x9900F0FF : p.inputEdge, dp(1))
                : ShapeDrawable2.round(dp(20), dp(20), dp(20), dp(20), p.panel, p.inputEdge, dp(1)));
        jump.setElevation(dp(3));
        jump.setContentDescription("Jump to latest");
        jump.setVisibility(View.GONE);
        jump.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scroll.stickToBottom(true);
            }
        });
        jumpBtn = jump;
        FrameLayout.LayoutParams jlp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM | Gravity.END);
        jlp.setMargins(0, 0, dp(14), dp(12));
        chat.addView(jump, jlp);
        scroll.setStickListener(new ChatScrollView.StickListener() {
            @Override
            public void onStickChanged(boolean stuck) {
                jumpBtn.setVisibility(stuck ? View.GONE : View.VISIBLE);
            }
        });
        root.addView(chat, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // --- Command suggestions -------------------------------------
        suggestScroll = new ScrollView(this);
        suggestScroll.setBackgroundColor(p.panel);
        suggestBox = new LinearLayout(this);
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        suggestBox.setPadding(0, dp(4), 0, dp(4));
        suggestScroll.addView(suggestBox);
        suggestScroll.setVisibility(View.GONE);
        root.addView(hairline());
        root.addView(suggestScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Composer ------------------------------------------------
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setBackgroundColor(p.panel);
        composer.setPadding(dp(10), dp(8), dp(10), dp(10));

        input = new EditText(this);
        input.setBackground(p.chamfer
                ? ShapeDrawable2.chamfer(dp(8), 0, dp(8), 0, p.input, p.inputEdge, dp(1))
                : ShapeDrawable2.round(dp(12), dp(12), dp(12), dp(12), p.input, p.inputEdge, dp(1)));
        input.setPadding(dp(13), dp(11), dp(13), dp(11));
        input.setTextColor(p.text);
        input.setHintTextColor(p.dim);
        input.setHint("Message your AI · / for commands");
        input.setTypeface(p.bodyFace);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.dark ? 14.5f : 15.5f);
        input.setMinHeight(dp(46));
        input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSuggestions();
                updateSendButton();
            }
        });
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        sendBtn = new FrameLayout(this);
        sendBtn.setBackground(p.chamfer
                ? ShapeDrawable2.chamfer(dp(7), 0, dp(7), 0, p.sendBg, 0, 0)
                : ShapeDrawable2.round(dp(8), dp(8), dp(8), dp(8), p.sendBg, 0, 0));
        sendIcon = new IconDrawable(IconDrawable.SEND, p.sendFg, p.sendFg, dp(24));
        ImageView si = new ImageView(this);
        si.setImageDrawable(sendIcon);
        si.setScaleType(ImageView.ScaleType.CENTER);
        sendBtn.addView(si, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        sendBtn.setContentDescription("Send");
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.isBusy()) {
                    engine.stop();
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                } else {
                    submit();
                }
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(46), dp(46));
        slp.leftMargin = dp(8);
        composer.addView(sendBtn, slp);
        root.addView(composer);
        return root;
    }

    private TextView actionButton(String label, boolean primary, View.OnClickListener l) {
        TextView b = text(p.dark ? label : label.charAt(0) + label.substring(1).toLowerCase(Locale.US), 11.5f,
                primary ? p.sendFg : p.text, p.dark ? p.monoFace : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (primary && p.dark) b.setTypeface(p.monoFace, Typeface.BOLD);
        b.setLetterSpacing(p.dark ? 0.1f : 0f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        int fill = primary ? p.sendBg : p.chipFill;
        b.setBackground(p.chamfer ? ShapeDrawable2.chamfer(dp(6), 0, dp(6), 0, fill, primary ? 0 : p.inputEdge, dp(1))
                : ShapeDrawable2.round(dp(8), dp(8), dp(8), dp(8), fill, primary ? 0 : p.inputEdge, dp(1)));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout buildEmptyState() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        e.setPadding(dp(24), dp(24), dp(24), dp(24));
        ImageView big = new ImageView(this);
        big.setImageDrawable(new IconDrawable(IconDrawable.LOGO, p.accent, p.dark ? p.accent2 : p.danger, dp(64)));
        e.addView(big, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = text("OMNI-DECK", p.dark ? 22 : 24, p.text, p.titleFace);
        t.setLetterSpacing(p.dark ? 0.14f : 0.02f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, dp(6));
        e.addView(t);
        emptySub = text("", 13, p.dim, p.bodyFace);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setLineSpacing(0, 1.2f);
        e.addView(emptySub);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER);
        chips.setPadding(0, dp(18), 0, 0);
        String[] quick = {"/help", "/models", "/ps", "/warm"};
        for (final String q : quick) {
            TextView c = text(q, 12, p.dark ? p.accent2 : p.accent, p.monoFace);
            c.setPadding(dp(10), dp(7), dp(10), dp(7));
            c.setBackground(chip(p.chipFill, p.inputEdge));
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    runCommand(Commands.parse(q));
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(dp(4), 0, dp(4), 0);
            chips.addView(c, clp);
        }
        e.addView(chips);
        return e;
    }

    // ------------------------------------------------------------------
    // Rendering messages
    // ------------------------------------------------------------------

    private int labelColor(ChatMessage m) {
        if (m.isUser()) return p.userLabel;
        if (m.isAssistant()) return p.dark ? p.accent : p.dim;
        if (m.isSystem()) return p.dark ? p.accent2 : p.accent;
        return toneColor(m.tone);
    }

    private int bodyColor(ChatMessage m) {
        if (m.isUser()) return p.userText;
        if (m.isAssistant()) return p.aiText;
        return p.noticeText;
    }

    private int toneColor(String tone) {
        if ("ok".equals(tone)) return p.ok;
        if ("error".equals(tone)) return p.danger;
        return p.warn;
    }

    private String bgKey(ChatMessage m) {
        return m.role + "|" + m.tone + "|" + m.error;
    }

    private Drawable bubbleBg(ChatMessage m) {
        float d = dp;
        if (m.isUser()) {
            return p.chamfer ? ShapeDrawable2.chamfer(10 * d, 10 * d, 10 * d, 10 * d, p.userFill, p.userStroke, d)
                    : ShapeDrawable2.round(16 * d, 16 * d, 4 * d, 16 * d, p.userFill, 0, 0);
        }
        if (m.isAssistant()) {
            if (m.error) {
                return p.chamfer ? ShapeDrawable2.chamfer(0, 0, 8 * d, 0, p.errorFill, p.errorStroke, d).withBar(p.danger, 3 * d)
                        : ShapeDrawable2.round(16 * d, 16 * d, 16 * d, 4 * d, p.errorFill, p.errorStroke, d);
            }
            return p.chamfer ? ShapeDrawable2.chamfer(0, 0, 8 * d, 0, p.aiFill, 0, 0).withBar(p.aiBar, 3 * d)
                    : ShapeDrawable2.round(16 * d, 16 * d, 16 * d, 4 * d, p.aiFill, p.aiStroke, d);
        }
        int fill, stroke;
        if (m.isSystem()) {
            fill = p.dark ? 0xF0061418 : 0x144A6D8C;
            stroke = p.dark ? 0x8000F0FF : 0x804A6D8C;
        } else if ("error".equals(m.tone)) {
            fill = p.errorFill;
            stroke = p.errorStroke;
        } else if ("ok".equals(m.tone)) {
            fill = p.dark ? 0xF0061A0E : 0x1A2FA05C;
            stroke = p.dark ? 0x8000FF66 : 0x8C2FA05C;
        } else {
            fill = p.noticeFill;
            stroke = p.noticeStroke;
        }
        return p.chamfer ? ShapeDrawable2.chamfer(10 * d, 10 * d, 10 * d, 10 * d, fill, stroke, d)
                : ShapeDrawable2.round(10 * d, 10 * d, 10 * d, 10 * d, fill, stroke, d).dashed(4 * d);
    }

    private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);

    private String headerText(ChatMessage m) {
        String time = timeFormat.format(new Date(m.time));
        if (m.isUser()) return (p.dark ? "OPERATOR // YOU" : "You") + " · " + time;
        if (m.isSystem()) return p.dark ? "CONTEXT // SUMMARY" : "Earlier conversation (summary)";
        if (m.isNotice()) return (p.dark ? "SYSTEM // " : "") + ("ok".equals(m.tone) ? (p.dark ? "OK" : "Done")
                : "error".equals(m.tone) ? (p.dark ? "ERROR" : "Error") : "warn".equals(m.tone) ? (p.dark ? "NOTICE" : "Notice")
                : (p.dark ? "INFO" : "Info")) + " · " + time;
        String who = m.model.length() > 0 ? m.model : "AI";
        String s = (p.dark ? "OMNI // " + who : who) + " · " + time;
        if (m.streaming) {
            long secs = (System.currentTimeMillis() - m.startedAt) / 1000;
            if (m.content.length() == 0) {
                s += m.thinking.length() > 0 ? " · thinking " + secs + "s" : secs >= 2 ? " · loading model " + secs + "s" : " · waiting";
            } else {
                s += " · streaming";
            }
        }
        return s;
    }

    private Holder addHolder(ChatMessage m, int index) {
        Holder h = new Holder(m);
        holders.put(m.id, h);
        if (index < 0) list.addView(h.row);
        else list.addView(h.row, index);
        bind(h);
        return h;
    }

    private static int words(String s) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            boolean ws = Character.isWhitespace(s.charAt(i));
            if (!ws && !in) n++;
            in = !ws;
        }
        return n;
    }

    private void bind(Holder h) {
        ChatMessage m = h.m;
        String key = bgKey(m);
        if (!key.equals(h.shownBg)) {
            h.bubble.setBackground(bubbleBg(m));
            h.shownBg = key;
        }
        h.header.setText(headerText(m));

        // Thinking
        if (m.thinking.length() > 0) {
            boolean autoOpen = m.streaming && m.content.length() == 0;
            boolean open = autoOpen || expandedThoughts.contains(m.id);
            h.thinkToggle.setVisibility(View.VISIBLE);
            h.thinkToggle.setText((open ? "[−] " : "[+] ") + (p.dark ? "THOUGHTS" : "Thoughts") + " · "
                    + words(m.thinking) + " words");
            if (open) {
                if (!m.thinking.equals(h.shownThinking)) {
                    h.thinkBody.setText(m.thinking);
                    h.shownThinking = m.thinking;
                }
                h.thinkBody.setVisibility(View.VISIBLE);
            } else {
                h.thinkBody.setVisibility(View.GONE);
                h.shownThinking = null;
            }
        } else {
            h.thinkToggle.setVisibility(View.GONE);
            h.thinkBody.setVisibility(View.GONE);
        }

        // Body
        boolean contentChanged = !m.content.equals(h.shownContent) || m.streaming != h.shownStreaming;
        if (contentChanged) {
            if (m.isUser()) {
                h.body.setText(m.content);
            } else {
                boolean cursor = m.streaming;
                MarkdownRenderer.Rendered r = md.render(m.content, cursor);
                h.body.setText(r.text);
                if (r.hasLinks && !m.streaming) {
                    h.body.setMovementMethod(LinkMovementMethod.getInstance());
                    h.body.setLongClickable(true);
                }
            }
            h.shownContent = m.content;
            h.shownStreaming = m.streaming;
        }
        boolean showBody = m.content.length() > 0 || m.streaming;
        h.body.setVisibility(showBody ? View.VISIBLE : View.GONE);

        // Image
        if (m.image.length() > 0 && h.image.getVisibility() != View.VISIBLE) {
            Bitmap bmp = decodeImage(m.image);
            if (bmp != null) {
                h.image.setImageBitmap(bmp);
                h.image.setVisibility(View.VISIBLE);
            }
        }

        // Footer
        String foot = m.stats;
        if (foot.length() > 0 && !m.streaming) {
            h.footer.setText(foot);
            h.footer.setTextColor(m.error ? p.danger : m.stopped ? p.warn : p.dim);
            h.footer.setVisibility(View.VISIBLE);
        } else {
            h.footer.setVisibility(View.GONE);
        }
    }

    private Bitmap decodeImage(String b64) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            int maxW = getResources().getDisplayMetrics().widthPixels;
            while (o.outWidth / sample > maxW * 1.5) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void renderAll() {
        list.removeAllViews();
        holders.clear();
        List<ChatMessage> msgs = engine.conversation().messages;
        renderedFrom = Math.max(0, msgs.size() - INITIAL_RENDER);
        if (renderedFrom > 0) list.addView(earlierButton());
        for (int i = renderedFrom; i < msgs.size(); i++) addHolder(msgs.get(i), -1);
        updateEmptyState();
        scroll.stickToBottom(false);
    }

    private View earlierButton() {
        TextView b = text("Show " + renderedFrom + " earlier messages", 12, p.dark ? p.accent2 : p.accent, p.monoFace);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(10), dp(12), dp(14));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<ChatMessage> msgs = engine.conversation().messages;
                list.removeView(v);
                int from = renderedFrom;
                renderedFrom = 0;
                for (int i = from - 1; i >= 0; i--) addHolder(msgs.get(i), 0);
            }
        });
        return b;
    }

    private void updateEmptyState() {
        boolean empty = engine.conversation().messages.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        Engine.State s = engine.state();
        ServerInfo srv = engine.server();
        String model = engine.currentModel();
        if (s == Engine.State.ONLINE && srv != null) {
            emptySub.setText("OMNI-DECK is ready to take your command.\nConnected to " + srv.label()
                    + (model.length() > 0 ? " · " + model : "") + "\nType a message — or / for commands.");
        } else if (s == Engine.State.SEARCHING) {
            emptySub.setText("Searching this network for your AI…");
        } else {
            emptySub.setText("Your AI isn't reachable yet.\nYou can still type / for commands.");
        }
    }

    // ------------------------------------------------------------------
    // Engine.Listener
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged() {
        Engine.State s = engine.state();
        String key = s == Engine.State.ONLINE ? "online" : s == Engine.State.SEARCHING ? "searching" : "offline";
        int c = p.statusColor(key);
        statusText.setText(s == Engine.State.ONLINE ? "ONLINE" : s == Engine.State.SEARCHING ? "SCANNING" : "OFFLINE");
        statusText.setTextColor(c);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(c);
        statusDot.setBackground(dot);
        int fill = (c & 0x00FFFFFF) | 0x1A000000;
        int stroke = (c & 0x00FFFFFF) | 0x80000000;
        statusPill.setBackground(p.chamfer ? ShapeDrawable2.chamfer(dp(6), 0, dp(6), 0, fill, stroke, dp(1))
                : ShapeDrawable2.round(dp(20), dp(20), dp(20), dp(20), fill, stroke, dp(1)));
        if (s == Engine.State.SEARCHING) {
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.2f);
                pulse.setDuration(650);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
            }
            if (!pulse.isStarted()) pulse.start();
        } else if (pulse != null) {
            pulse.cancel();
            statusDot.setAlpha(1f);
        }

        ServerInfo srv = engine.server();
        if (s == Engine.State.ONLINE && srv != null) {
            subtitle.setText((p.dark ? "LINKED · " : "Connected · ") + srv.label());
        } else if (s == Engine.State.SEARCHING) {
            subtitle.setText(p.dark ? "SCANNING NETWORK…" : "Searching the network…");
        } else {
            subtitle.setText(p.dark ? "NO AI ON THIS NETWORK" : "AI not found");
        }

        String model = engine.currentModel();
        modelChip.setText(model.length() > 0 ? model : (s == Engine.State.ONLINE ? "no models" : "model"));
        modelChip.setTextColor(model.length() > 0 ? p.text : p.dim);
        String mode = engine.mode();
        modeChip.setText(Settings.MODE_DEEP.equals(mode) ? "DEEP" : Settings.MODE_FAST.equals(mode) ? "FAST" : "AUTO");
        int mc = Settings.MODE_DEEP.equals(mode) ? (p.dark ? p.accent2 : p.warn)
                : Settings.MODE_FAST.equals(mode) ? p.ok : p.dim;
        modeChip.setTextColor(mc);
        modeChip.setBackground(chip((mc & 0x00FFFFFF) | 0x14000000, (mc & 0x00FFFFFF) | 0x66000000));
        speedView.setText(engine.lastSpeed());

        offlineCard.setVisibility(s == Engine.State.OFFLINE ? View.VISIBLE : View.GONE);
        offlineDetail.setText(engine.stateDetail());
        updateEmptyState();
    }

    @Override
    public void onConversationReplaced() {
        renderAll();
    }

    @Override
    public void onMessageAdded(ChatMessage m) {
        if (holders.containsKey(m.id)) return;
        addHolder(m, -1);
        updateEmptyState();
        if (m.isUser() || m.isNotice()) scroll.stickToBottom(false);
    }

    @Override
    public void onMessageChanged(ChatMessage m) {
        Holder h = holders.get(m.id);
        if (h != null) bind(h);
    }

    @Override
    public void onMessageRemoved(ChatMessage m) {
        Holder h = holders.remove(m.id);
        if (h != null) list.removeView(h.row);
        updateEmptyState();
    }

    @Override
    public void onBusyChanged() {
        updateSendButton();
    }

    @Override
    public void onInsertText(String t) {
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        Editable e = input.getText();
        String prefix = start > 0 && e.charAt(start - 1) != '\n' && e.charAt(start - 1) != ' ' ? " " : "";
        e.replace(Math.min(start, end), Math.max(start, end), prefix + t);
        input.requestFocus();
    }

    @Override
    public void onToast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Composer
    // ------------------------------------------------------------------

    private void updateSendButton() {
        boolean busy = engine.isBusy();
        sendIcon = new IconDrawable(busy ? IconDrawable.STOP : IconDrawable.SEND, p.sendFg, p.sendFg, dp(24));
        ((ImageView) sendBtn.getChildAt(0)).setImageDrawable(sendIcon);
        sendBtn.setContentDescription(busy ? "Stop" : "Send");
        boolean has = input.getText().toString().trim().length() > 0;
        sendBtn.setAlpha(busy || has ? 1f : 0.45f);
    }

    private void submit() {
        String raw = input.getText().toString();
        if (raw.trim().length() == 0) return;
        Commands.Parsed pc = Commands.parse(raw);
        if (pc != null) {
            clearInput();
            runCommand(pc);
            return;
        }
        String msg = raw.trim();
        if (msg.startsWith("//")) msg = msg.substring(1);
        if (engine.send(msg)) {
            clearInput();
            sendBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            scroll.stickToBottom(false);
        }
    }

    private void clearInput() {
        suppressSuggest = true;
        input.setText("");
        suppressSuggest = false;
        updateSuggestions();
    }

    private void updateSuggestions() {
        if (suppressSuggest) return;
        String t = input.getText().toString();
        suggestBox.removeAllViews();
        if (!t.startsWith("/") || t.startsWith("//") || t.indexOf('\n') >= 0) {
            suggestScroll.setVisibility(View.GONE);
            return;
        }
        int sp = t.indexOf(' ');
        if (sp > 0) {
            // Typing arguments: show the usage line for the command.
            Commands.Cmd c = Commands.find(t.substring(0, sp));
            if (c == null) {
                suggestScroll.setVisibility(View.GONE);
                return;
            }
            suggestBox.addView(suggestionRow(c, false));
        } else {
            List<Commands.Cmd> s = Commands.suggest(t, 8);
            if (s.isEmpty()) {
                suggestScroll.setVisibility(View.GONE);
                return;
            }
            for (Commands.Cmd c : s) suggestBox.addView(suggestionRow(c, true));
        }
        suggestScroll.setVisibility(View.VISIBLE);
        int max = dp(236);
        suggestScroll.getLayoutParams().height = suggestBox.getChildCount() > 5 ? max
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        suggestScroll.requestLayout();
    }

    private View suggestionRow(final Commands.Cmd c, boolean clickable) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(7), dp(16), dp(7));
        TextView name = text(clickable ? c.name : c.usage, 13, p.dark ? p.accent2 : p.accent, p.monoFace);
        row.addView(name);
        TextView desc = text(c.desc, 11.5f, p.dim, p.bodyFace);
        desc.setSingleLine(true);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setPadding(0, dp(2), 0, 0);
        row.addView(desc);
        if (clickable) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (tv.resourceId != 0) row.setBackground(getDrawable(tv.resourceId));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (c.takesArg) {
                        input.setText(c.name + " ");
                        input.setSelection(input.getText().length());
                    } else {
                        clearInput();
                        runCommand(Commands.parse(c.name));
                    }
                }
            });
        }
        return row;
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    void runCommand(Commands.Parsed pc) {
        if (pc == null) return;
        if (pc.cmd == null) {
            engine.notice("Unknown command `" + pc.name + "`. Type `/help` for the list.", "warn");
            return;
        }
        String a = pc.arg;
        String name = pc.cmd.name;
        if (pc.cmd.group == Commands.GROUP_PC_APP_ONLY) {
            engine.notice("`" + name + "` runs inside OMNI-DECK on the PC (it uses the PC app's own tools), so the "
                    + "phone can't run it yet.", "info");
            return;
        }
        if ("/help".equals(name)) {
            engine.notice(Commands.helpText(), "info");
        } else if ("/reset".equals(name)) {
            engine.newChat();
            onToast("New chat");
        } else if ("/stop".equals(name)) {
            if (engine.isBusy()) engine.stop();
            else onToast("Nothing is streaming.");
        } else if ("/regen".equals(name)) {
            engine.regenerate();
        } else if ("/system".equals(name)) {
            if (a.length() == 0) {
                String sp = engine.settings.systemPrompt();
                engine.notice(sp.length() == 0 ? "No system prompt set. Set one with `/system <prompt>`."
                        : "**System prompt**\n" + sp + "\n\n`/system clear` removes it.", "info");
            } else if (a.equalsIgnoreCase("clear") || a.equalsIgnoreCase("off") || a.equalsIgnoreCase("none")) {
                engine.settings.setSystemPrompt("");
                engine.notice("System prompt cleared.", "ok");
            } else {
                engine.settings.setSystemPrompt(a);
                engine.notice("System prompt set. It applies to every message from now on.", "ok");
            }
        } else if ("/summarize".equals(name)) {
            engine.summarize();
        } else if ("/compact".equals(name)) {
            engine.compact();
        } else if ("/history".equals(name)) {
            if (a.length() == 0) showHistory();
            else openChatByName(a);
        } else if ("/export".equals(name)) {
            exportChat();
        } else if ("/incognito".equals(name)) {
            Boolean on = parseOnOff(a);
            if (on == null) {
                engine.notice("Incognito is **" + (engine.settings.incognito() ? "on" : "off")
                        + "**. Use `/incognito on` or `/incognito off`.", "info");
            } else {
                engine.settings.setIncognito(on);
                if (!on) engine.save();
                engine.notice(on ? "Incognito on — chats aren't saved on this phone until you turn it off."
                        : "Incognito off — chats are saved again.", "ok");
            }
        } else if ("/remember".equals(name)) {
            if (a.length() == 0) engine.notice("Usage: `/remember <fact>`", "info");
            else engine.remember(a);
        } else if ("/facts".equals(name)) {
            engine.listFacts();
        } else if ("/forget".equals(name)) {
            if (a.length() == 0) engine.notice("Usage: `/forget <number or text>` — see `/facts`.", "info");
            else engine.forget(a);
        } else if ("/model".equals(name)) {
            if (a.length() == 0) showModelPicker();
            else switchModel(a);
        } else if ("/models".equals(name)) {
            engine.listModels();
        } else if ("/ps".equals(name)) {
            engine.listRunning();
        } else if ("/warm".equals(name)) {
            engine.warm();
        } else if ("/unload".equals(name)) {
            engine.unload(a);
        } else if ("/pull".equals(name)) {
            engine.pull(a);
        } else if ("/deep".equals(name)) {
            if (a.length() > 0) {
                String m = engine.resolveInstalled(a);
                if (m == null) {
                    engine.notice("No installed model matches “" + a + "”. See `/models`.", "warn");
                    return;
                }
                engine.setDeepModel(m);
            }
            engine.setMode(Settings.MODE_DEEP);
            String dm = engine.resolveInstalled(engine.settings.deepModel());
            String target = dm != null ? dm : engine.currentModel();
            Boolean t = engine.supportsThinking(target);
            engine.notice("**Deep mode** — replies use **" + target + "**"
                    + (Boolean.FALSE.equals(t) ? " (this model can't think step by step; set a thinking model with "
                    + "`/deep <model>`)." : " with thinking on."), "ok");
        } else if ("/fast".equals(name)) {
            engine.setMode(Settings.MODE_FAST);
            engine.notice("**Fast mode** — thinking off, main model (" + engine.currentModel() + ").", "ok");
        } else if ("/auto".equals(name)) {
            engine.setMode(Settings.MODE_AUTO);
            String dm = engine.settings.deepModel();
            engine.notice("**Auto mode** — fast by default" + (dm.length() > 0 ? "; hard questions go to **" + dm
                    + "** with thinking on." : ". Set a deep model with `/deep <model>` to route hard questions to it."),
                    "ok");
        } else if ("/bench".equals(name)) {
            engine.bench();
        } else if ("/open".equals(name)) {
            if (a.length() == 0) engine.notice("Usage: `/open <app>` — e.g. `/open spotify`.", "info");
            else openApp(a);
        } else if ("/vol".equals(name)) {
            if (a.length() == 0) {
                engine.bridgeTool("get_volume", null, "PC volume");
            } else {
                int level;
                try {
                    level = Integer.parseInt(a.replace("%", "").trim());
                } catch (NumberFormatException e) {
                    level = -1;
                }
                if (level < 0 || level > 100) {
                    engine.notice("Usage: `/vol <0-100>` — or just `/vol` to read it.", "info");
                    return;
                }
                JSONObject args = new JSONObject();
                try {
                    args.put("level", level);
                } catch (JSONException ignored) {
                }
                engine.bridgeTool("set_volume", args, "PC volume");
            }
        } else if ("/sys".equals(name)) {
            engine.bridgeTool("get_system_info", null, "PC system");
        } else if ("/shot".equals(name)) {
            JSONObject args = new JSONObject();
            try {
                args.put("save", a.toLowerCase(Locale.US).contains("save"));
            } catch (JSONException ignored) {
            }
            engine.bridgeTool("screenshot", args, "Screenshot");
        } else if ("/pcclip".equals(name)) {
            engine.bridgeTool("get_clipboard", null, "PC clipboard");
        } else if ("/pair".equals(name)) {
            engine.bridgePair();
        } else if ("/desk".equals(name)) {
            engine.bridgeStatus();
        } else if ("/server".equals(name)) {
            if (a.length() == 0) {
                showConnectionDialog();
            } else if (a.equalsIgnoreCase("auto")) {
                engine.setServer("");
                engine.notice("Auto-detect on — searching the network for your AI.", "info");
            } else if (HostPort.parse(a, OllamaClient.DEFAULT_PORT) == null) {
                engine.notice("That doesn't look like an address. Try `/server 192.168.1.20` or `/server 192.168.1.20:11434`.",
                        "warn");
            } else {
                engine.setServer(a);
                engine.notice("AI address set to `" + a + "` — connecting…", "info");
            }
        } else if ("/scan".equals(name)) {
            engine.discover(true);
            onToast("Scanning the network…");
        } else if ("/appearance".equals(name)) {
            String t = a.toLowerCase(Locale.US);
            String v = t.startsWith("cyber") || t.equals("dark") ? Palette.CYBER
                    : t.startsWith("light") || t.equals("modern") ? Palette.LIGHT
                    : t.equals("auto") || t.equals("system") ? Palette.AUTO : null;
            if (v == null) {
                engine.notice("Usage: `/appearance cyber`, `/appearance light` or `/appearance auto` (follows the phone).",
                        "info");
                return;
            }
            applyTheme(v);
        } else if ("/timer".equals(name)) {
            engine.timer(a);
        } else if ("/clip".equals(name)) {
            pastePhoneClipboard();
        } else if ("/settings".equals(name)) {
            showSettings();
        } else if ("/debug".equals(name)) {
            engine.diagnostics(appVersion());
        } else {
            engine.notice("`" + name + "` isn't available here.", "warn");
        }
    }

    private static Boolean parseOnOff(String a) {
        String t = a.trim().toLowerCase(Locale.US);
        if (t.equals("on") || t.equals("enable") || t.equals("enabled") || t.equals("true") || t.equals("yes")) {
            return Boolean.TRUE;
        }
        if (t.equals("off") || t.equals("disable") || t.equals("disabled") || t.equals("false") || t.equals("no")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private void switchModel(String a) {
        String m = engine.resolveInstalled(a);
        if (m == null) {
            List<ModelInfo> ms = engine.models();
            StringBuilder sb = new StringBuilder();
            for (ModelInfo mi : ms) {
                if (sb.length() > 0) sb.append(", ");
                sb.append('`').append(mi.name).append('`');
            }
            engine.notice("No single installed model matches “" + a + "”."
                    + (ms.isEmpty() ? " (not connected, or no models installed)" : " Installed: " + sb), "warn");
            return;
        }
        engine.setModel(m);
        engine.notice("Model → **" + m + "**", "ok");
    }

    private void applyTheme(String v) {
        engine.settings.setTheme(v);
        if (Palette.wantsDark(this, v) != p.dark) {
            engine.draft = input.getText().toString();
            recreate();
        } else {
            themePref = v;
            onToast(v.equals(Palette.AUTO) ? "Theme follows the phone" : "Theme: " + v);
        }
    }

    private void pastePhoneClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            onToast("The clipboard is empty.");
            return;
        }
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (t == null || t.length() == 0) onToast("The clipboard is empty.");
        else onInsertText(t.toString());
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            onToast("Copied");
        }
    }

    private void share(String subject, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(i, "Share"));
        } catch (RuntimeException e) {
            onToast("No app can share text.");
        }
    }

    private void exportChat() {
        Conversation c = engine.conversation();
        if (c.isEmpty()) {
            onToast("Nothing to export yet.");
            return;
        }
        share(c.title.length() > 0 ? c.title : "OMNI-DECK chat", c.toMarkdown());
    }

    private void cycleMode() {
        String m = engine.mode();
        String next = Settings.MODE_AUTO.equals(m) ? Settings.MODE_FAST
                : Settings.MODE_FAST.equals(m) ? Settings.MODE_DEEP : Settings.MODE_AUTO;
        engine.setMode(next);
        onToast(Settings.MODE_DEEP.equals(next) ? "Deep: thinking on" : Settings.MODE_FAST.equals(next)
                ? "Fast: no thinking" : "Auto: fast, deep model for hard questions");
    }

    private void openApp(final String query) {
        engine.bridgePreviewLaunch(query, new Engine.Callback<JSONObject>() {
            @Override
            public void done(JSONObject r, String error) {
                if (error != null) {
                    engine.notice("Couldn't open “" + query + "”: " + error, "error");
                    return;
                }
                if (r.optBoolean("needs_choice", false)) {
                    JSONArray cands = r.optJSONArray("candidates");
                    if (cands == null || cands.length() == 0) {
                        engine.notice("No app on the PC matches “" + query + "”.", "warn");
                        return;
                    }
                    List<Row> rows = new ArrayList<Row>();
                    for (int i = 0; i < cands.length(); i++) {
                        final JSONObject c = cands.optJSONObject(i);
                        if (c == null) continue;
                        final String nm = OllamaClient.str(c, "name");
                        rows.add(new Row(nm, OllamaClient.str(c, "path"), false, new Runnable() {
                            @Override
                            public void run() {
                                engine.bridgeLaunch(OllamaClient.str(c, "id"), nm);
                            }
                        }, null));
                    }
                    pickDialog("Which app?", rows, null, null);
                    return;
                }
                final JSONObject target = r.optJSONObject("would_launch");
                if (target == null) {
                    JSONObject app = r.optJSONObject("app");
                    engine.notice(app != null ? "Opened **" + OllamaClient.str(app, "name") + "** on the PC."
                            : "The bridge didn't say what it would open.", app != null ? "ok" : "warn");
                    return;
                }
                final String nm = OllamaClient.str(target, "name");
                String path = OllamaClient.str(target, "path");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Open " + nm + " on the PC?")
                        .setMessage(path.length() > 0 ? path : "LaunchBridge will start it on your PC.")
                        .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.bridgeLaunch(OllamaClient.str(target, "id"), nm);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    // ------------------------------------------------------------------
    // Menus & dialogs
    // ------------------------------------------------------------------

    private Row menuRow(String title, String detail, final String command) {
        return new Row(title, detail, false, new Runnable() {
            @Override
            public void run() {
                runCommand(Commands.parse(command));
            }
        }, null);
    }

    private void showMenu() {
        List<Row> rows = new ArrayList<Row>();
        rows.add(menuRow("New chat", "/reset", "/reset"));
        rows.add(new Row("Chats…", "/history — open or delete saved chats", false, new Runnable() {
            @Override
            public void run() {
                showHistory();
            }
        }, null));
        rows.add(new Row("Models…", "/model — switch, or download new ones", false, new Runnable() {
            @Override
            public void run() {
                showModelPicker();
            }
        }, null));
        rows.add(menuRow("Commands", "/help", "/help"));
        rows.add(menuRow("Scan network", "/scan — list every AI server nearby", "/scan"));
        rows.add(menuRow(p.dark ? "Light theme" : "Cyber theme", p.dark ? "/appearance light" : "/appearance cyber",
                p.dark ? "/appearance light" : "/appearance cyber"));
        rows.add(menuRow("Export chat", "/export — share as text", "/export"));
        rows.add(menuRow("Settings…", "/settings", "/settings"));
        pickDialog("OMNI-DECK", rows, null, null);
    }

    /** A row in a pick dialog. */
    private static final class Row {
        final CharSequence title;
        final String detail;
        final boolean highlight;
        final Runnable onClick;
        final Runnable onLongClick;

        Row(CharSequence title, String detail, boolean highlight, Runnable onClick, Runnable onLongClick) {
            this.title = title;
            this.detail = detail;
            this.highlight = highlight;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }
    }

    private int dialogText() {
        return p.dark ? p.text : p.text;
    }

    private AlertDialog pickDialog(String title, List<Row> rows, String neutral, final Runnable onNeutral) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setView(sv)
                .setNegativeButton("Close", null);
        if (neutral != null) {
            b.setNeutralButton(neutral, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    if (onNeutral != null) onNeutral.run();
                }
            });
        }
        final AlertDialog dlg = b.create();
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        for (final Row r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(22), dp(10), dp(22), dp(10));
            if (tv.resourceId != 0) row.setBackground(getDrawable(tv.resourceId));
            TextView t = text("", 15, r.highlight ? (p.dark ? p.accent2 : p.accent) : dialogText(),
                    p.dark ? p.monoFace : Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setText(r.title);
            row.addView(t);
            if (r.detail != null && r.detail.length() > 0) {
                TextView d = text(r.detail, 12, p.dim, p.dark ? p.monoFace : p.bodyFace);
                d.setPadding(0, dp(3), 0, 0);
                row.addView(d);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    if (r.onClick != null) r.onClick.run();
                }
            });
            if (r.onLongClick != null) {
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        dlg.dismiss();
                        r.onLongClick.run();
                        return true;
                    }
                });
            }
            box.addView(row);
        }
        if (rows.isEmpty()) {
            TextView t = text("Nothing here yet.", 14, p.dim, p.bodyFace);
            t.setPadding(dp(22), dp(12), dp(22), dp(12));
            box.addView(t);
        }
        dlg.show();
        return dlg;
    }

    private void showModelPicker() {
        List<ModelInfo> ms = engine.models();
        if (engine.state() != Engine.State.ONLINE) {
            onToast("Connect to your AI first.");
            return;
        }
        String cur = engine.currentModel();
        List<Row> rows = new ArrayList<Row>();
        for (final ModelInfo m : ms) {
            SpannableStringBuilder title = new SpannableStringBuilder(m.name);
            if (engine.isLoaded(m.name)) {
                int st = title.length();
                title.append("  ● loaded");
                title.setSpan(new ForegroundColorSpan(p.ok), st, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                title.setSpan(new RelativeSizeSpan(0.75f), st, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            rows.add(new Row(title, m.describe(), m.name.equals(cur), new Runnable() {
                @Override
                public void run() {
                    engine.setModel(m.name);
                    onToast("Model: " + m.name);
                }
            }, null));
        }
        pickDialog(ms.isEmpty() ? "No models installed" : "Choose a model", rows, "Download…", new Runnable() {
            @Override
            public void run() {
                promptText("Download a model", "Name from ollama.com/library, e.g. llama3.2 or qwen3:8b", "", false,
                        new TextResult() {
                            @Override
                            public void onText(String t) {
                                if (t.trim().length() > 0) engine.pull(t.trim());
                            }
                        });
            }
        });
    }

    private void showHistory() {
        engine.listChats(new Engine.Callback<List<ConversationStore.Entry>>() {
            @Override
            public void done(List<ConversationStore.Entry> entries, String error) {
                String current = engine.conversation().id;
                DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
                List<Row> rows = new ArrayList<Row>();
                for (final ConversationStore.Entry e : entries) {
                    rows.add(new Row(e.title, e.count + " messages · " + df.format(new Date(e.updated)),
                            e.id.equals(current), new Runnable() {
                        @Override
                        public void run() {
                            engine.openChat(e.id);
                        }
                    }, new Runnable() {
                        @Override
                        public void run() {
                            new AlertDialog.Builder(MainActivity.this).setTitle("Delete this chat?")
                                    .setMessage(e.title)
                                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            engine.deleteChat(e.id);
                                            onToast("Deleted");
                                        }
                                    })
                                    .setNegativeButton("Cancel", null).show();
                        }
                    }));
                }
                pickDialog(entries.isEmpty() ? "No saved chats" : "Chats (long-press to delete)", rows, "New chat",
                        new Runnable() {
                            @Override
                            public void run() {
                                engine.newChat();
                            }
                        });
            }
        });
    }

    private void openChatByName(final String name) {
        engine.listChats(new Engine.Callback<List<ConversationStore.Entry>>() {
            @Override
            public void done(List<ConversationStore.Entry> entries, String error) {
                String n = name.toLowerCase(Locale.US);
                for (ConversationStore.Entry e : entries) {
                    if (e.title.toLowerCase(Locale.US).contains(n)) {
                        engine.openChat(e.id);
                        return;
                    }
                }
                engine.notice("No saved chat matches “" + name + "”. `/history` lists them.", "warn");
            }
        });
    }

    private void showMessageActions(final ChatMessage m) {
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        labels.add("Copy text");
        actions.add(new Runnable() {
            @Override
            public void run() {
                copy("message", m.content);
            }
        });
        if (m.thinking.length() > 0) {
            labels.add("Copy thoughts");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    copy("thoughts", m.thinking);
                }
            });
        }
        labels.add("Share");
        actions.add(new Runnable() {
            @Override
            public void run() {
                share("OMNI-DECK", m.content);
            }
        });
        if (m.isAssistant() && !engine.isBusy() && m == engine.conversation().lastOfRole(ChatMessage.ASSISTANT)) {
            labels.add("Regenerate");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    engine.regenerate();
                }
            });
        }
        if (m.isUser() && !engine.isBusy()) {
            labels.add("Edit & resend");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    String t = engine.editFrom(m);
                    input.setText(t);
                    input.setSelection(input.getText().length());
                    input.requestFocus();
                }
            });
        }
        labels.add("Delete");
        actions.add(new Runnable() {
            @Override
            public void run() {
                engine.deleteMessage(m);
            }
        });
        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                }).show();
    }

    private interface TextResult {
        void onText(String t);
    }

    private void promptText(String title, String hint, String value, boolean multiline, final TextResult r) {
        final EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value);
        et.setSelection(et.getText().length());
        et.setTextColor(p.text);
        et.setHintTextColor(p.dim);
        et.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);
        new AlertDialog.Builder(this).setTitle(title).setView(wrap)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        r.onText(et.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void promptServerAddress() {
        promptText("AI address", "e.g. 192.168.1.20 or 192.168.1.20:11434 — blank = auto-detect",
                engine.settings.server(), false, new TextResult() {
                    @Override
                    public void onText(String t) {
                        String v = t.trim();
                        if (v.length() > 0 && HostPort.parse(v, OllamaClient.DEFAULT_PORT) == null) {
                            onToast("That isn't a valid address.");
                            return;
                        }
                        engine.setServer(v);
                    }
                });
    }

    private void showConnectionDialog() {
        Engine.State s = engine.state();
        ServerInfo srv = engine.server();
        StringBuilder sb = new StringBuilder();
        if (s == Engine.State.ONLINE && srv != null) {
            sb.append("Connected to Ollama").append(srv.version.length() > 0 ? " " + srv.version : "")
                    .append(" at ").append(srv.label()).append(".\n\n");
            int loaded = 0;
            for (ModelInfo m : engine.models()) {
                if (engine.isLoaded(m.name)) loaded++;
            }
            sb.append(engine.models().size()).append(" models installed, ").append(loaded).append(" loaded.\n");
        } else {
            sb.append(engine.stateDetail()).append("\n\n");
        }
        String manual = engine.settings.server();
        sb.append(manual.length() > 0 ? "Address: " + manual + " (set manually)" : "Address: auto-detect");
        sb.append("\nPhone network: ").append(Net.describe(engine.subnets()));
        sb.append("\nPC bridge: port ").append(engine.settings.bridgePort())
                .append(engine.settings.bridgeToken().length() > 0 ? ", paired" : ", not paired");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(s == Engine.State.ONLINE ? "Connected" : s == Engine.State.SEARCHING ? "Searching…" : "Not connected")
                .setMessage(sb.toString())
                .setPositiveButton("Rescan", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        engine.discover(true);
                    }
                })
                .setNeutralButton("Set address", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        promptServerAddress();
                    }
                })
                .setNegativeButton("Close", null);
        b.show();
    }

    private TextView label(String s) {
        TextView t = text(p.dark ? s.toUpperCase(Locale.US) : s, 11, p.dark ? p.accent2 : p.accent,
                p.dark ? p.monoFace : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(p.dark ? 0.08f : 0f);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 11.5f, p.dim, p.bodyFace);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    private EditText field(String value, String hint, int inputType) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(p.text);
        e.setHintTextColor(p.dim);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setInputType(inputType);
        return e;
    }

    private void showSettings() {
        final Settings st = engine.settings;
        ScrollView sv = new ScrollView(this);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(22), dp(4), dp(22), dp(12));
        sv.addView(f);

        f.addView(label("AI address"));
        final EditText server = field(st.server(), "auto-detect", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        f.addView(server);
        f.addView(note("Blank = find it on the Wi-Fi automatically. Or type the PC's address, e.g. 192.168.1.20."));

        f.addView(label("Context size (num_ctx)"));
        final EditText ctx = field(st.numCtx() > 0 ? String.valueOf(st.numCtx()) : "", "auto — match the PC",
                InputType.TYPE_CLASS_NUMBER);
        f.addView(ctx);
        f.addView(label("CPU threads (num_thread)"));
        final EditText threads = field(st.numThread() > 0 ? String.valueOf(st.numThread()) : "", "auto — don't send",
                InputType.TYPE_CLASS_NUMBER);
        f.addView(threads);
        f.addView(note("Ollama reloads the model when these differ between requests. Auto matches whatever is "
                + "loaded (else 8192). If OMNI-DECK on the PC sets threads (it uses half your PC's cores), enter the "
                + "same number here."));

        final CheckBox keep = new CheckBox(this);
        keep.setText("Keep the model loaded between messages");
        keep.setTextColor(p.text);
        keep.setChecked(st.keepLoaded());
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(10);
        f.addView(keep, klp);

        f.addView(label("System prompt"));
        final EditText sys = field(st.systemPrompt(), "optional persona / instructions",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        sys.setMaxLines(6);
        f.addView(sys);

        f.addView(label("Deep model (for /deep and auto mode)"));
        final EditText deep = field(st.deepModel(), "none — use the main model with thinking",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        f.addView(deep);

        f.addView(label("Theme"));
        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] themes = {Palette.AUTO, Palette.CYBER, Palette.LIGHT};
        String[] names = {"Auto", "Cyber", "Light"};
        final int[] ids = new int[3];
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setTextColor(p.text);
            ids[i] = View.generateViewId();
            rb.setId(ids[i]);
            rg.addView(rb);
            if (themes[i].equals(st.theme())) rg.check(ids[i]);
        }
        f.addView(rg);

        f.addView(label("PC bridge (LaunchBridge)"));
        final EditText bport = field(String.valueOf(st.bridgePort()), "8765", InputType.TYPE_CLASS_NUMBER);
        f.addView(bport);
        final EditText btoken = field(st.bridgeToken(), "token — or run /pair",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        f.addView(btoken);

        final CheckBox inc = new CheckBox(this);
        inc.setText("Incognito — don't save chats on this phone");
        inc.setTextColor(p.text);
        inc.setChecked(st.incognito());
        f.addView(inc, klp);

        TextView ver = note("OmniDeck " + appVersion() + " · talks to Ollama's API directly over your network.");
        ver.setPadding(0, dp(16), 0, 0);
        f.addView(ver);

        new AlertDialog.Builder(this).setTitle("Settings").setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String srv = server.getText().toString().trim();
                        if (srv.length() > 0 && HostPort.parse(srv, OllamaClient.DEFAULT_PORT) == null) {
                            onToast("Ignored an invalid AI address.");
                            srv = st.server();
                        }
                        boolean serverChanged = !srv.equals(st.server());
                        st.setNumCtx(parseIntOr(ctx.getText().toString(), 0));
                        st.setNumThread(parseIntOr(threads.getText().toString(), 0));
                        st.setKeepLoaded(keep.isChecked());
                        st.setSystemPrompt(sys.getText().toString().trim());
                        String dm = deep.getText().toString().trim();
                        String resolved = dm.length() == 0 ? "" : engine.resolveInstalled(dm);
                        engine.setDeepModel(resolved != null ? resolved : dm);
                        st.setBridgePort(parseIntOr(bport.getText().toString(), 8765));
                        st.setBridgeToken(btoken.getText().toString());
                        boolean wasIncognito = st.incognito();
                        st.setIncognito(inc.isChecked());
                        if (wasIncognito && !inc.isChecked()) engine.save();
                        if (serverChanged) engine.setServer(srv);
                        int checked = rg.getCheckedRadioButtonId();
                        String theme = checked == ids[1] ? Palette.CYBER : checked == ids[2] ? Palette.LIGHT : Palette.AUTO;
                        onStateChanged();
                        onToast("Saved");
                        if (!theme.equals(st.theme())) applyTheme(theme);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static int parseIntOr(String s, int d) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }
}
