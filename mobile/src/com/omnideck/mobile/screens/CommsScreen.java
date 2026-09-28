package com.omnideck.mobile.screens;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.omnideck.mobile.Engine;
import com.omnideck.mobile.MainActivity;
import com.omnideck.mobile.Settings;
import com.omnideck.mobile.core.ChatMessage;
import com.omnideck.mobile.core.Commands;
import com.omnideck.mobile.core.Conversation;
import com.omnideck.mobile.core.ConversationStore;
import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.ui.BubbleLayout;
import com.omnideck.mobile.ui.ChatScrollView;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.MarkdownRenderer;
import com.omnideck.mobile.ui.Panel;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * COMMS — the conversation with the AI: streaming replies, slash commands,
 * voice input, read-aloud, image attachments for vision models, code copy,
 * and the chat archive.
 */
public final class CommsScreen extends Screen {
    static final int INITIAL_RENDER = 80;

    private MarkdownRenderer md;
    private final Map<String, Holder> holders = new HashMap<String, Holder>();
    private final Set<String> expandedThoughts = new HashSet<String>();
    private final List<String> pendingImages = new ArrayList<String>();
    private final List<Bitmap> pendingPreviews = new ArrayList<Bitmap>();
    private int renderedFrom;
    private boolean suppressSuggest;

    private TextView chatTitle;
    private TextView chatSub;
    private ImageView speakerBtn;
    private ChatScrollView scroll;
    private LinearLayout list;
    private LinearLayout emptyState;
    private TextView emptySub;
    private View jumpBtn;
    private ScrollView suggestScroll;
    private LinearLayout suggestBox;
    private HorizontalScrollView attachScroll;
    private LinearLayout attachStrip;
    private EditText input;
    private FrameLayout sendBtn;
    private ImageView sendIconView;
    private ImageView attachBtn;
    private FrameLayout historyLayer;
    private LinearLayout historyList;
    private EditText historySearch;
    private List<ConversationStore.Entry> historyEntries = new ArrayList<ConversationStore.Entry>();
    private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);

    public CommsScreen(MainActivity a) {
        super(a);
    }

    /** Views for one message. */
    private final class Holder {
        final ChatMessage m;
        final LinearLayout row;
        final BubbleLayout bubble;
        final TextView header;
        final TextView thinkToggle;
        final TextView thinkBody;
        final Dots dots;
        final TextView body;
        final LinearLayout images;
        final TextView footer;
        String shownContent;
        String shownThinking;
        boolean shownStreaming;
        String shownBg = "";
        int shownImages = -1;

        Holder(ChatMessage msg) {
            this.m = msg;
            row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = Ui.fillW();
            rlp.topMargin = ui.dp(5);
            rlp.bottomMargin = ui.dp(5);
            row.setLayoutParams(rlp);
            row.setGravity(m.isUser() ? Gravity.END : (m.isNotice() || m.isSystem()) ? Gravity.CENTER_HORIZONTAL
                    : Gravity.START);

            bubble = new BubbleLayout(a, m.isAssistant() ? 1f : m.isNotice() || m.isSystem() ? 0.97f : 0.86f);
            row.addView(bubble, Ui.wrap());

            header = ui.text("", t.hud ? 9 : 11, labelColor(m), t.hud ? t.labelFace : t.bodyMedium);
            header.setLetterSpacing(t.hud ? 0.12f : 0.01f);
            header.setSingleLine(true);
            header.setEllipsize(TextUtils.TruncateAt.END);
            // WRAP_CONTENT (not MATCH_PARENT) so longer text widens the bubble:
            // TextView skips relayout when its width is fixed.
            bubble.addView(header, Ui.wrap());

            thinkToggle = ui.text("", 11, t.thinkText, t.hud ? t.mono : t.bodyMedium);
            thinkToggle.setPadding(ui.dp(9), ui.dp(5), ui.dp(10), ui.dp(5));
            thinkToggle.setBackground(ui.rounded(t.thinkFill, t.thinkStroke, 6));
            thinkToggle.setVisibility(View.GONE);
            thinkToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!expandedThoughts.remove(m.id)) expandedThoughts.add(m.id);
                    shownThinking = null;
                    bind(Holder.this);
                }
            });
            LinearLayout.LayoutParams tlp = Ui.wrap();
            tlp.topMargin = ui.dp(7);
            bubble.addView(thinkToggle, tlp);

            thinkBody = ui.text("", 13, t.thinkText, t.body);
            thinkBody.setTypeface(t.body, Typeface.ITALIC);
            thinkBody.setLineSpacing(0, 1.25f);
            thinkBody.setPadding(ui.dp(10), ui.dp(6), 0, ui.dp(4));
            thinkBody.setVisibility(View.GONE);
            bubble.addView(thinkBody, Ui.wrap());

            dots = new Dots(a, m.isAssistant() ? t.accent : t.dim);
            dots.setVisibility(View.GONE);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(16));
            dlp.topMargin = ui.dp(8);
            bubble.addView(dots, dlp);

            body = ui.text("", t.bodySp, bodyColor(m), t.body);
            body.setLineSpacing(0, 1.32f);
            body.setPadding(0, ui.dp(5), 0, 0);
            bubble.addView(body, Ui.wrap());

            images = ui.hbox();
            images.setVisibility(View.GONE);
            LinearLayout.LayoutParams ilp = Ui.wrap();
            ilp.topMargin = ui.dp(8);
            bubble.addView(images, ilp);

            footer = ui.text("", t.hud ? 10 : 10.5f, t.faint, t.mono);
            footer.setPadding(0, ui.dp(7), 0, 0);
            footer.setVisibility(View.GONE);
            bubble.addView(footer, Ui.wrap());

            View.OnLongClickListener lc = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showMessageActions(m);
                    return true;
                }
            };
            bubble.setOnLongClickListener(lc);
            body.setOnLongClickListener(lc);
        }
    }

    /** Three pulsing dots while the AI hasn't produced text yet. */
    private static final class Dots extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;
        private float phase;

        Dots(Context c, int color) {
            super(c);
            p.setColor(color);
        }

        @Override
        public void setVisibility(int v) {
            super.setVisibility(v);
            if (v == VISIBLE) start();
            else stop();
        }

        private void start() {
            if (anim != null) return;
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(1100);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    phase = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        private void stop() {
            if (anim != null) anim.cancel();
            anim = null;
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), r = h * 0.2f, gap = getWidth() / 3f;
            for (int i = 0; i < 3; i++) {
                float ph = (phase - i * 0.18f + 1f) % 1f;
                float s = ph < 0.5f ? ph * 2 : (1 - ph) * 2;
                p.setAlpha((int) (70 + 185 * s));
                c.drawCircle(gap * i + gap / 2f, h / 2f, r * (0.75f + 0.35f * s), p);
            }
        }
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    @Override
    protected View build() {
        md = new MarkdownRenderer(t, ui.density);
        FrameLayout root = new FrameLayout(a);
        LinearLayout column = ui.vbox();
        column.addView(buildHeader(), Ui.fillW());
        View line = ui.divider();
        if (t.hud) line.setBackgroundColor(t.edge);
        column.addView(line);

        FrameLayout chat = new FrameLayout(a);
        scroll = new ChatScrollView(a);
        scroll.setClipToPadding(false);
        list = ui.vbox();
        list.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12));
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        chat.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        emptyState = buildEmptyState();
        chat.addView(emptyState, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ImageView jump = new ImageView(a);
        jump.setImageDrawable(new IconDrawable(IconDrawable.DOWN, t.accent, 0, ui.dp(20)));
        jump.setScaleType(ImageView.ScaleType.CENTER);
        jump.setBackground(t.hud ? Panel.builder().fill(t.surface2).edge(t.edgeStrong, Math.max(1, ui.dp(1)))
                .radius(ui.dp(20)).build() : ui.rounded(t.surface, t.edge, 20));
        jump.setElevation(ui.dp(3));
        jump.setContentDescription("Jump to latest");
        jump.setVisibility(View.GONE);
        jump.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scroll.stickToBottom(true);
            }
        });
        jumpBtn = jump;
        FrameLayout.LayoutParams jlp = new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.BOTTOM | Gravity.END);
        jlp.setMargins(0, 0, ui.dp(14), ui.dp(12));
        chat.addView(jump, jlp);
        scroll.setStickListener(new ChatScrollView.StickListener() {
            @Override
            public void onStickChanged(boolean stuck) {
                jumpBtn.setVisibility(stuck ? View.GONE : View.VISIBLE);
            }
        });
        column.addView(chat, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        suggestScroll = new ScrollView(a);
        suggestScroll.setBackgroundColor(Theme.alpha(MainActivityColors.opaque(t.surface2, t.bg), 0xFF));
        suggestBox = ui.vbox();
        suggestBox.setPadding(0, ui.dp(4), 0, ui.dp(4));
        suggestScroll.addView(suggestBox);
        suggestScroll.setVisibility(View.GONE);
        column.addView(suggestScroll, Ui.fillW());

        attachStrip = ui.hbox();
        attachStrip.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), 0);
        attachScroll = new HorizontalScrollView(a);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.addView(attachStrip);
        attachScroll.setVisibility(View.GONE);
        column.addView(attachScroll, Ui.fillW());

        column.addView(buildComposer(), Ui.fillW());
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        historyLayer = buildHistoryLayer();
        historyLayer.setVisibility(View.GONE);
        root.addView(historyLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        renderAll();
        onStateChanged();
        onBusyChanged();
        if (e.draft.length() > 0) {
            input.setText(e.draft);
            input.setSelection(input.getText().length());
        }
        return root;
    }

    /** Solid color helper (surface colors can be translucent glass). */
    private static final class MainActivityColors {
        static int opaque(int c, int base) {
            int al = (c >>> 24) & 0xFF;
            if (al == 0xFF) return c;
            int r = (((c >> 16) & 0xFF) * al + ((base >> 16) & 0xFF) * (255 - al)) / 255;
            int g = (((c >> 8) & 0xFF) * al + ((base >> 8) & 0xFF) * (255 - al)) / 255;
            int b = ((c & 0xFF) * al + (base & 0xFF) * (255 - al)) / 255;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private View buildHeader() {
        LinearLayout h = ui.hbox();
        h.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        if (t.hud) h.setBackgroundColor(Theme.alpha(t.surface2, 0x99));
        else h.setBackgroundColor(t.surface);
        h.addView(ui.iconButton(IconDrawable.HISTORY, "Chat history", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHistory();
            }
        }));
        LinearLayout titles = ui.vbox();
        titles.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        chatTitle = ui.text("", 15, t.inkStrong, t.bodySemi);
        chatTitle.setSingleLine(true);
        chatTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(chatTitle);
        chatSub = ui.label("");
        chatSub.setPadding(0, ui.dp(3), 0, 0);
        titles.addView(chatSub);
        titles.setClickable(true);
        titles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                quickModelPicker();
            }
        });
        titles.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                a.commander().run("/rename");
                return true;
            }
        });
        h.addView(titles, Ui.weight(1));
        speakerBtn = ui.iconButton(IconDrawable.SPEAKER_OFF, "Read replies aloud", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.setReadAloud(!e.settings.readAloud());
                ui.toast(e.settings.readAloud() ? "Reading replies aloud" : "Read-aloud off");
            }
        });
        h.addView(speakerBtn);
        h.addView(ui.iconButton(IconDrawable.PLUS, "New chat", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.newChat();
                clearAttachments();
            }
        }));
        return h;
    }

    private LinearLayout buildEmptyState() {
        LinearLayout box = ui.vbox();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ui.dp(28), ui.dp(24), ui.dp(28), ui.dp(24));
        ImageView big = new ImageView(a);
        big.setImageDrawable(new IconDrawable(IconDrawable.LOGO, t.accent, t.hud ? t.inkStrong : t.accent2, ui.dp(56)));
        box.addView(big, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        TextView title = ui.title(t.hud ? "Comms channel open" : "Talk to OMNI", t.hud ? 14 : 20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, ui.dp(16), 0, ui.dp(8));
        box.addView(title, Ui.fillW());
        emptySub = ui.dim("", 13.5f);
        emptySub.setGravity(Gravity.CENTER);
        box.addView(emptySub, Ui.fillW());
        LinearLayout row1 = ui.hbox();
        row1.setGravity(Gravity.CENTER);
        row1.setPadding(0, ui.dp(18), 0, 0);
        LinearLayout row2 = ui.hbox();
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(0, ui.dp(8), 0, 0);
        String[][] prompts = {{"What can you do?", ""}, {"/help", "/help"}, {"/models", "/models"},
                {"Speak", "/voice"}};
        for (int i = 0; i < prompts.length; i++) {
            final String label = prompts[i][0];
            final String cmd = prompts[i][1];
            TextView c = ui.chip(label, t.accent);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.hud ? 10 : 12.5f);
            c.setPadding(ui.dp(11), ui.dp(7), ui.dp(11), ui.dp(7));
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    if (cmd.length() > 0) a.commander().run(cmd);
                    else submitText(label);
                }
            });
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.setMargins(ui.dp(4), 0, ui.dp(4), 0);
            (i < 2 ? row1 : row2).addView(c, lp);
        }
        box.addView(row1);
        box.addView(row2);
        return box;
    }

    private View buildComposer() {
        LinearLayout wrap = ui.vbox();
        View line = ui.divider();
        if (t.hud) line.setBackgroundColor(t.edge);
        wrap.addView(line);
        LinearLayout composer = ui.hbox();
        composer.setGravity(Gravity.BOTTOM);
        composer.setBackgroundColor(t.hud ? Theme.alpha(t.surface2, 0xE6) : t.surface);
        composer.setPadding(ui.dp(6), ui.dp(8), ui.dp(10), ui.dp(10));

        attachBtn = ui.iconButton(IconDrawable.IMAGE, "Attach image", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attachImage();
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(46));
        composer.addView(attachBtn, alp);

        input = new EditText(a);
        input.setContentDescription("Message");
        input.setBackground(Panel.builder().fill(t.composerFill).edge(t.composerEdge, ui.density * 1.5f)
                .radius(ui.dp(14)).build());
        input.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        input.setTextColor(t.ink);
        input.setHintTextColor(t.faint);
        input.setHint(t.hud ? "Transmit to OMNI · / cmds" : "Message OMNI · / for commands");
        input.setTypeface(t.body);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        input.setMinHeight(ui.dp(46));
        input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSuggestions();
                updateSendButton();
            }
        });
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        sendBtn = new FrameLayout(a);
        sendBtn.setBackground(Panel.builder().fill(t.accent).radius(ui.dp(12)).build());
        if (t.hud) sendBtn.setElevation(0);
        sendIconView = new ImageView(a);
        sendIconView.setScaleType(ImageView.ScaleType.CENTER);
        sendBtn.addView(sendIconView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (e.isBusy()) {
                    e.stop();
                } else if (input.getText().toString().trim().length() == 0 && pendingImages.isEmpty()) {
                    voice();
                } else {
                    submit();
                }
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46));
        slp.leftMargin = ui.dp(8);
        composer.addView(sendBtn, slp);
        wrap.addView(composer, Ui.fillW());
        return wrap;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private int labelColor(ChatMessage m) {
        if (m.isUser()) return t.userLabel;
        if (m.isAssistant()) return t.aiLabel;
        if (m.isSystem()) return t.accent;
        return toneColor(m.tone);
    }

    private int bodyColor(ChatMessage m) {
        if (m.isUser()) return t.userText;
        if (m.isAssistant()) return t.aiText;
        return t.noticeText;
    }

    private int toneColor(String tone) {
        if ("ok".equals(tone)) return t.ok;
        if ("error".equals(tone)) return t.danger;
        return t.warn;
    }

    private Drawable bubbleBg(ChatMessage m) {
        float d = ui.density;
        float r = t.radiusBubble * d;
        if (m.isUser()) {
            if (t.hud) {
                return Panel.builder().fill(t.userFill).edge(t.userStroke, Math.max(1, d)).radii(r, r, 3 * d, r)
                        .build();
            }
            return Panel.builder().fill(t.userFill).radii(r, r, 4 * d, r).build();
        }
        if (m.isAssistant()) {
            if (m.error || t.aiFill != 0) {
                int fill = m.error ? Theme.alpha(t.danger, t.isDark ? 0x1A : 0x12) : t.aiFill;
                int edge = m.error ? Theme.alpha(t.danger, 0x80) : t.aiStroke;
                Panel.Builder b = Panel.builder().fill(fill).edge(edge, Math.max(1, d)).radii(r, r, r, 4 * d);
                if (t.hud) b.brackets(7 * d, 1.2f * d, Theme.alpha(edge, 0xCC)).bracketInset(3 * d);
                return b.build();
            }
            // Like the web app, replies sit straight on the surface. Cyber adds
            // a faint accent "signal line" down the left edge.
            if (t.hud && t.aiBar != 0) return new BarPanel(Panel.builder().build(), t.aiBar, 2f * d, 2 * d);
            return null;
        }
        int fill, stroke;
        if (m.isSystem()) {
            fill = t.accentSoft;
            stroke = Theme.alpha(t.accent, 0x66);
        } else if ("error".equals(m.tone)) {
            fill = Theme.alpha(t.danger, t.isDark ? 0x1A : 0x12);
            stroke = Theme.alpha(t.danger, 0x80);
        } else if ("ok".equals(m.tone)) {
            fill = Theme.alpha(t.ok, t.isDark ? 0x17 : 0x12);
            stroke = Theme.alpha(t.ok, 0x66);
        } else {
            fill = t.noticeFill;
            stroke = t.noticeStroke;
        }
        Panel.Builder b = Panel.builder().fill(fill).edge(stroke, Math.max(1, d)).radius(10 * d);
        if (!m.isSystem() && !"error".equals(m.tone) && !"ok".equals(m.tone)) b.dash(4 * d, 3 * d);
        if (t.hud) b.brackets(7 * d, 1.2f * d, Theme.alpha(stroke, 0xCC)).bracketsAll();
        return b.build();
    }

    /** A panel with an accent bar down its left edge (the AI's "signal line"). */
    private static final class BarPanel extends Drawable {
        private final Panel inner;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float width;
        private final float inset;

        BarPanel(Panel inner, int color, float width, float inset) {
            this.inner = inner;
            this.width = width;
            this.inset = inset;
            bar.setColor(color);
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            inner.setBounds(b);
        }

        @Override
        public void draw(Canvas c) {
            inner.draw(c);
            android.graphics.Rect b = getBounds();
            c.drawRoundRect(new android.graphics.RectF(b.left, b.top + inset * 3, b.left + width,
                    b.bottom - inset * 3), width, width, bar);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            inner.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private String bgKey(ChatMessage m) {
        return m.role + "|" + m.tone + "|" + m.error;
    }

    String headerText(ChatMessage m) {
        String time = timeFormat.format(new Date(m.time));
        if (m.isUser()) return (t.hud ? "OPERATOR // YOU" : "You") + " · " + time;
        if (m.isSystem()) return t.hud ? "CONTEXT // SUMMARY" : "Earlier conversation (summary)";
        if (m.isNotice()) {
            String k = "ok".equals(m.tone) ? "OK" : "error".equals(m.tone) ? "Error" : "warn".equals(m.tone) ? "Notice" : "Info";
            return (t.hud ? "SYSTEM // " + k.toUpperCase(Locale.US) : k) + " · " + time;
        }
        String who = m.model.length() > 0 ? m.model : "AI";
        String s = (t.hud ? "OMNI // " + who.toUpperCase(Locale.US) : who) + " · " + time;
        if (m.streaming) {
            long secs = (System.currentTimeMillis() - m.startedAt) / 1000;
            if (m.content.length() == 0) {
                s += m.thinking.length() > 0 ? " · thinking " + secs + "s"
                        : secs >= 2 ? " · loading model " + secs + "s" : " · connecting";
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
            Drawable bg = bubbleBg(m);
            h.bubble.setBackground(bg);
            boolean bare = m.isAssistant() && !m.error && t.aiFill == 0;
            if (bare) h.bubble.setPadding(ui.dp(t.hud ? 12 : 2), ui.dp(4), ui.dp(4), ui.dp(6));
            else h.bubble.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(11));
            h.shownBg = key;
        }
        h.header.setText(headerText(m));
        h.header.setTextColor(m.isNotice() ? toneColor(m.tone) : labelColor(m));

        if (m.thinking.length() > 0) {
            boolean autoOpen = m.streaming && m.content.length() == 0;
            boolean open = autoOpen || expandedThoughts.contains(m.id);
            h.thinkToggle.setVisibility(View.VISIBLE);
            String word = t.hud ? "REASONING" : "Thoughts";
            h.thinkToggle.setText((open ? "▾ " : "▸ ") + word + " · " + words(m.thinking) + " words");
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

        boolean waiting = m.streaming && m.content.length() == 0 && m.thinking.length() == 0;
        if ((h.dots.getVisibility() == View.VISIBLE) != waiting) {
            h.dots.setVisibility(waiting ? View.VISIBLE : View.GONE);
        }

        boolean contentChanged = !m.content.equals(h.shownContent) || m.streaming != h.shownStreaming;
        if (contentChanged) {
            if (m.isUser()) {
                h.body.setText(m.content);
            } else {
                MarkdownRenderer.Rendered r = md.render(m.content, m.streaming && m.content.length() > 0);
                h.body.setText(r.text);
                if (r.hasLinks) {
                    h.body.setMovementMethod(LinkMovementMethod.getInstance());
                    h.body.setLongClickable(true);
                }
            }
            h.shownContent = m.content;
            h.shownStreaming = m.streaming;
        }
        h.body.setVisibility(m.content.length() > 0 ? View.VISIBLE : View.GONE);

        int imgCount = m.images.size() + (m.image.length() > 0 ? 1 : 0);
        if (imgCount != h.shownImages) {
            h.shownImages = imgCount;
            h.images.removeAllViews();
            List<String> all = new ArrayList<String>(m.images);
            if (m.image.length() > 0) all.add(m.image);
            for (final String b64 : all) {
                boolean single = all.size() == 1 && m.image.length() > 0;
                int side = single ? ui.dp(260) : ui.dp(96);
                Bitmap bmp = decode(b64, side * 2);
                if (bmp == null) continue;
                ImageView iv = new ImageView(a);
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(single ? ImageView.ScaleType.FIT_START : ImageView.ScaleType.CENTER_CROP);
                iv.setContentDescription("Image");
                iv.setBackground(ui.rounded(0, t.hud ? t.edge : t.hair, 6));
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showImage(b64);
                    }
                });
                LinearLayout.LayoutParams lp = single ? new LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(side, side);
                lp.rightMargin = ui.dp(6);
                h.images.addView(iv, lp);
            }
            h.images.setVisibility(h.images.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        }

        String foot = m.stats;
        if (foot.length() > 0 && !m.streaming) {
            h.footer.setText(t.hud ? foot.toUpperCase(Locale.US) : foot);
            h.footer.setTextColor(m.error ? t.danger : m.stopped ? t.warn : t.faint);
            h.footer.setVisibility(View.VISIBLE);
        } else {
            h.footer.setVisibility(View.GONE);
        }
    }

    private static Bitmap decode(String b64, int maxSide) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
            d.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
        } catch (RuntimeException ex) {
            return null;
        } catch (OutOfMemoryError ex) {
            return null;
        }
    }

    private void showImage(String b64) {
        Bitmap bmp = decode(b64, 2048);
        if (bmp == null) return;
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF000000);
        iv.setContentDescription("Image, tap to close");
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        d.setContentView(iv);
        d.show();
    }

    private void renderAll() {
        list.removeAllViews();
        holders.clear();
        List<ChatMessage> msgs = e.conversation().messages;
        renderedFrom = Math.max(0, msgs.size() - INITIAL_RENDER);
        if (renderedFrom > 0) list.addView(earlierButton());
        for (int i = renderedFrom; i < msgs.size(); i++) addHolder(msgs.get(i), -1);
        updateEmptyState();
        updateHeader();
        scroll.stickToBottom(false);
    }

    private View earlierButton() {
        TextView b = ui.text("Show " + renderedFrom + " earlier messages", 12.5f, t.accent,
                t.hud ? t.labelFace : t.bodyMedium);
        b.setGravity(Gravity.CENTER);
        b.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(14));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<ChatMessage> msgs = e.conversation().messages;
                list.removeView(v);
                int from = renderedFrom;
                renderedFrom = 0;
                for (int i = from - 1; i >= 0; i--) addHolder(msgs.get(i), 0);
            }
        });
        return b;
    }

    private void updateEmptyState() {
        if (emptyState == null) return;
        boolean empty = e.conversation().messages.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        Engine.State s = e.state();
        String model = e.currentModel();
        if (s == Engine.State.ONLINE) {
            emptySub.setText("Linked to " + (e.server() != null ? e.server().label() : "your PC")
                    + (model.length() > 0 ? " · " + model : "") + ".\nType, tap the mic to speak, or / for commands.");
        } else if (s == Engine.State.SEARCHING) {
            emptySub.setText("Searching the network for your AI…");
        } else {
            emptySub.setText("Your AI isn't reachable yet. Commands still work — type /help.");
        }
    }

    private void updateHeader() {
        if (chatTitle == null) return;
        Conversation c = e.conversation();
        String title = c.title.length() > 0 ? c.title : (t.hud ? "New session" : "New chat");
        chatTitle.setText(title);
        String model = e.currentModel();
        String mode = e.mode();
        String modeLabel = Settings.MODE_DEEP.equals(mode) ? "Deep" : Settings.MODE_FAST.equals(mode) ? "Fast" : "Auto";
        chatSub.setText(t.label((model.length() > 0 ? model : "No model") + " · " + modeLabel
                + (e.settings.incognito() ? " · incognito" : "")));
        boolean speaking = e.settings.readAloud();
        speakerBtn.setImageDrawable(new IconDrawable(speaking ? IconDrawable.SPEAKER : IconDrawable.SPEAKER_OFF,
                speaking ? t.accent : t.dim, 0, ui.dp(20)));
        Boolean vision = e.supportsVision(model);
        attachBtn.setImageDrawable(new IconDrawable(IconDrawable.IMAGE,
                Boolean.TRUE.equals(vision) ? t.accent : t.faint, 0, ui.dp(20)));
    }

    // ------------------------------------------------------------------
    // Engine events
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged() {
        updateEmptyState();
        updateHeader();
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
        updateHeader();
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
    public void onInsertText(String text) {
        if (input == null) view();
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        Editable ed = input.getText();
        String prefix = start > 0 && ed.charAt(start - 1) != '\n' && ed.charAt(start - 1) != ' ' ? " " : "";
        ed.replace(Math.min(start, end), Math.max(start, end), prefix + text);
        input.requestFocus();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            ChatMessage s = e.streamingMessage();
            if (s != null) {
                Holder h = holders.get(s.id);
                if (h != null) h.header.setText(headerText(s));
            }
            if (isShown()) scroll.postDelayed(this, 500);
        }
    };

    @Override
    protected void onShow() {
        updateHeader();
        scroll.removeCallbacks(ticker);
        scroll.post(ticker);
    }

    @Override
    protected void onHide() {
        scroll.removeCallbacks(ticker);
        e.draft = input.getText().toString();
    }

    @Override
    public void onActivityStop() {
        if (input != null) e.draft = input.getText().toString();
    }

    @Override
    public boolean onBack() {
        if (historyLayer != null && historyLayer.getVisibility() == View.VISIBLE) {
            closeHistory();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Composer
    // ------------------------------------------------------------------

    private void updateSendButton() {
        if (sendBtn == null) return;
        boolean busy = e.isBusy();
        boolean has = input.getText().toString().trim().length() > 0 || !pendingImages.isEmpty();
        int kind = busy ? IconDrawable.STOP : has ? IconDrawable.SEND : IconDrawable.MIC;
        IconDrawable d = new IconDrawable(kind, t.onAccent, t.onAccent, ui.dp(22));
        if (kind == IconDrawable.MIC) d.stroke(2.1f);
        sendIconView.setImageDrawable(d);
        sendBtn.setContentDescription(busy ? "Stop" : has ? "Send" : "Voice input");
    }

    /** Sends text as if typed (commands included). */
    public void submitText(String text) {
        if (input == null) view();
        input.setText(text);
        submit();
    }

    private void submit() {
        String raw = input.getText().toString();
        if (raw.trim().length() == 0 && pendingImages.isEmpty()) return;
        Commands.Parsed pc = Commands.parse(raw);
        if (pc != null && pendingImages.isEmpty()) {
            clearInput();
            a.commander().run(pc);
            return;
        }
        String msg = raw.trim();
        if (msg.startsWith("//")) msg = msg.substring(1);
        String model = e.currentModel();
        if (!pendingImages.isEmpty() && Boolean.FALSE.equals(e.supportsVision(model))) {
            ui.toast(model + " can't see images — pick a vision model (e.g. llava, gemma3, qwen2.5vl).");
            return;
        }
        if (e.send(msg, pendingImages.isEmpty() ? null : new ArrayList<String>(pendingImages))) {
            clearInput();
            clearAttachments();
            scroll.stickToBottom(false);
        }
    }

    private void clearInput() {
        suppressSuggest = true;
        input.setText("");
        suppressSuggest = false;
        updateSuggestions();
    }

    private void voice() {
        a.startVoice(t.hud ? "Speak to OMNI" : "Speak your message", new MainActivity.TextResult() {
            @Override
            public void onText(String text) {
                submitText(text);
            }
        });
    }

    private void attachImage() {
        String model = e.currentModel();
        Boolean vision = e.supportsVision(model);
        if (Boolean.FALSE.equals(vision)) {
            ui.toast((model.length() > 0 ? model : "This model") + " can't see images. Switch to a vision model "
                    + "(llava, gemma3, qwen2.5vl…) to attach photos.");
            return;
        }
        if (pendingImages.size() >= 4) {
            ui.toast("Up to 4 images per message.");
            return;
        }
        a.pickImage(new MainActivity.ImageResult() {
            @Override
            public void onImage(String base64, Bitmap preview) {
                pendingImages.add(base64);
                pendingPreviews.add(preview);
                renderAttachments();
                updateSendButton();
            }
        });
    }

    private void renderAttachments() {
        attachStrip.removeAllViews();
        for (int i = 0; i < pendingPreviews.size(); i++) {
            final int idx = i;
            FrameLayout f = new FrameLayout(a);
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(pendingPreviews.get(i));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(ui.rounded(0, t.edge, 8));
            f.addView(iv, new FrameLayout.LayoutParams(ui.dp(58), ui.dp(58)));
            ImageView x = new ImageView(a);
            x.setImageDrawable(new IconDrawable(IconDrawable.CLOSE, 0xFFFFFFFF, 0, ui.dp(12)));
            x.setScaleType(ImageView.ScaleType.CENTER);
            x.setBackground(ui.rounded(0xCC000000, 0, 10));
            x.setContentDescription("Remove image");
            x.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pendingImages.remove(idx);
                    pendingPreviews.remove(idx);
                    renderAttachments();
                    updateSendButton();
                }
            });
            f.addView(x, new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.TOP | Gravity.END));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(58), ui.dp(58));
            lp.rightMargin = ui.dp(8);
            attachStrip.addView(f, lp);
        }
        attachScroll.setVisibility(pendingPreviews.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void clearAttachments() {
        pendingImages.clear();
        pendingPreviews.clear();
        if (attachStrip != null) renderAttachments();
        updateSendButton();
    }

    private void updateSuggestions() {
        if (suppressSuggest || suggestBox == null) return;
        String text = input.getText().toString();
        suggestBox.removeAllViews();
        if (!text.startsWith("/") || text.startsWith("//") || text.indexOf('\n') >= 0) {
            suggestScroll.setVisibility(View.GONE);
            return;
        }
        int sp = text.indexOf(' ');
        if (sp > 0) {
            Commands.Cmd c = Commands.find(text.substring(0, sp));
            if (c == null) {
                suggestScroll.setVisibility(View.GONE);
                return;
            }
            suggestBox.addView(suggestionRow(c, false));
        } else {
            List<Commands.Cmd> s = Commands.suggest(text, 8);
            if (s.isEmpty()) {
                suggestScroll.setVisibility(View.GONE);
                return;
            }
            for (Commands.Cmd c : s) suggestBox.addView(suggestionRow(c, true));
        }
        suggestScroll.setVisibility(View.VISIBLE);
        suggestScroll.getLayoutParams().height = suggestBox.getChildCount() > 5 ? ui.dp(236)
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        suggestScroll.requestLayout();
    }

    private View suggestionRow(final Commands.Cmd c, boolean clickable) {
        LinearLayout row = ui.vbox();
        row.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        TextView name = ui.text(clickable ? c.name : c.usage, 13.5f, t.accent, t.mono);
        row.addView(name);
        TextView desc = ui.dim(c.desc, 12);
        desc.setSingleLine(true);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setPadding(0, ui.dp(2), 0, 0);
        row.addView(desc);
        if (clickable) {
            TypedValue tv = new TypedValue();
            a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (tv.resourceId != 0) row.setBackground(a.getDrawable(tv.resourceId));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (c.takesArg) {
                        input.setText(c.name + " ");
                        input.setSelection(input.getText().length());
                    } else {
                        clearInput();
                        a.commander().run(Commands.parse(c.name));
                    }
                }
            });
        }
        return row;
    }

    private void quickModelPicker() {
        List<ModelInfo> ms = e.models();
        if (e.state() != Engine.State.ONLINE || ms.isEmpty()) {
            a.select(MainActivity.TAB_MODELS, true);
            return;
        }
        String cur = e.currentModel();
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        for (final ModelInfo m : ms) {
            rows.add(new Ui.Row(m.name + (e.isLoaded(m.name) ? "  ●" : ""), m.describe(), m.name.equals(cur),
                    new Runnable() {
                        @Override
                        public void run() {
                            e.setModel(m.name);
                            ui.toast("Model: " + m.name);
                        }
                    }, null));
        }
        ui.pick("Switch model", rows, "Mode: " + e.mode(), new Runnable() {
            @Override
            public void run() {
                a.commander().cycleMode();
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
                a.copy("message", m.content);
            }
        });
        if (m.thinking.length() > 0) {
            labels.add("Copy reasoning");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    a.copy("reasoning", m.thinking);
                }
            });
        }
        if (m.isAssistant() && m.content.length() > 0) {
            labels.add("Read aloud");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    e.speakNow(m.content);
                }
            });
        }
        labels.add("Share");
        actions.add(new Runnable() {
            @Override
            public void run() {
                a.share("OMNI-DECK", m.content);
            }
        });
        if (m.isAssistant() && !e.isBusy() && m == e.conversation().lastOfRole(ChatMessage.ASSISTANT)) {
            labels.add("Regenerate");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    e.regenerate();
                }
            });
        }
        if (m.isUser() && !e.isBusy()) {
            labels.add("Edit & resend");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    String text = e.editFrom(m);
                    input.setText(text);
                    input.setSelection(input.getText().length());
                    input.requestFocus();
                }
            });
        }
        labels.add("Delete");
        actions.add(new Runnable() {
            @Override
            public void run() {
                e.deleteMessage(m);
            }
        });
        new AlertDialog.Builder(a).setItems(labels.toArray(new CharSequence[0]),
                new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                }).show();
    }

    // ------------------------------------------------------------------
    // History (the archive)
    // ------------------------------------------------------------------

    private FrameLayout buildHistoryLayer() {
        FrameLayout layer = new FrameLayout(a);
        layer.setBackgroundColor(t.hud ? 0xF003060B : t.bg);
        layer.setClickable(true);
        LinearLayout col = ui.vbox();
        LinearLayout head = ui.hbox();
        head.setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4));
        head.addView(ui.iconButton(IconDrawable.BACK, "Close history", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeHistory();
            }
        }));
        TextView title = ui.title(t.hud ? "Archive" : "Chats", t.hud ? 14 : 18);
        head.addView(title, Ui.weight(1));
        head.addView(ui.button("New", IconDrawable.PLUS, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.newChat();
                clearAttachments();
                closeHistory();
            }
        }));
        col.addView(head, Ui.fillW());
        historySearch = ui.field("", "Search chats", InputType.TYPE_CLASS_TEXT);
        IconDrawable sd = new IconDrawable(IconDrawable.SEARCH, t.faint, 0, ui.dp(18));
        sd.setBounds(0, 0, ui.dp(18), ui.dp(18));
        historySearch.setCompoundDrawables(sd, null, null, null);
        historySearch.setCompoundDrawablePadding(ui.dp(8));
        historySearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderHistory();
            }
        });
        LinearLayout.LayoutParams slp = Ui.fillW();
        slp.setMargins(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(8));
        col.addView(historySearch, slp);
        ScrollView sv = new ScrollView(a);
        historyList = ui.scrollColumn(sv, 14, 4);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        layer.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return layer;
    }

    /** Opens the chat archive over the conversation. */
    public void openHistory() {
        if (historyLayer == null) view();
        historyLayer.setVisibility(View.VISIBLE);
        historySearch.setText("");
        e.listChats(new Engine.Callback<List<ConversationStore.Entry>>() {
            @Override
            public void done(List<ConversationStore.Entry> entries, String error) {
                historyEntries = entries == null ? new ArrayList<ConversationStore.Entry>() : entries;
                renderHistory();
            }
        });
    }

    private void closeHistory() {
        if (historyLayer != null) historyLayer.setVisibility(View.GONE);
    }

    private void renderHistory() {
        historyList.removeAllViews();
        String q = historySearch.getText().toString().trim().toLowerCase(Locale.US);
        String current = e.conversation().id;
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        int shown = 0;
        for (final ConversationStore.Entry en : historyEntries) {
            if (q.length() > 0 && !en.title.toLowerCase(Locale.US).contains(q)) continue;
            shown++;
            LinearLayout card = ui.card();
            card.setPadding(ui.dp(14), ui.dp(12), ui.dp(10), ui.dp(12));
            LinearLayout row = ui.hbox();
            LinearLayout text = ui.vbox();
            TextView tt = ui.text(en.title, 15, en.id.equals(current) ? t.accent : t.ink, t.bodyMedium);
            tt.setSingleLine(true);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(tt);
            TextView st = ui.label(en.count + " messages · " + df.format(new Date(en.updated)));
            st.setPadding(0, ui.dp(5), 0, 0);
            text.addView(st);
            row.addView(text, Ui.weight(1));
            row.addView(ui.iconButton(IconDrawable.MENU, "Chat options", t.dim, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chatOptions(en);
                }
            }));
            card.addView(row, Ui.fillW());
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    e.openChat(en.id);
                    closeHistory();
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    chatOptions(en);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = Ui.fillW();
            lp.bottomMargin = ui.dp(8);
            historyList.addView(card, lp);
        }
        if (shown == 0) {
            TextView none = ui.dim(historyEntries.isEmpty() ? "No saved chats yet." : "No chats match.", 14);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, ui.dp(30), 0, 0);
            historyList.addView(none, Ui.fillW());
        }
    }

    private void chatOptions(final ConversationStore.Entry en) {
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        rows.add(new Ui.Row("Open", null, false, new Runnable() {
            @Override
            public void run() {
                e.openChat(en.id);
                closeHistory();
            }
        }, null));
        rows.add(new Ui.Row("Rename", null, false, new Runnable() {
            @Override
            public void run() {
                ui.prompt("Rename chat", "Title", en.title, InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, new Ui.TextResult() {
                    @Override
                    public void onText(String text) {
                        if (text.trim().length() == 0) return;
                        e.renameChat(en.id, text.trim());
                        historySearch.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                openHistory();
                            }
                        }, 150);
                    }
                });
            }
        }, null));
        rows.add(new Ui.Row("Delete", null, false, new Runnable() {
            @Override
            public void run() {
                ui.confirm("Delete this chat?", en.title, "Delete", new Runnable() {
                    @Override
                    public void run() {
                        e.deleteChat(en.id);
                        historyEntries.remove(en);
                        renderHistory();
                    }
                });
            }
        }, null));
        ui.pick(Fmt.ellipsize(en.title, 40), rows, null, null);
    }
}
