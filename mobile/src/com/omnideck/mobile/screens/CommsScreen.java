package com.omnideck.mobile.screens;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
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
import com.omnideck.mobile.core.Markdown;
import com.omnideck.mobile.core.ModelInfo;
import com.omnideck.mobile.core.ReplyError;
import com.omnideck.mobile.core.ToolApproval;
import com.omnideck.mobile.core.ToolCall;
import com.omnideck.mobile.ui.Sheet;
import com.omnideck.mobile.ui.BubbleLayout;
import com.omnideck.mobile.ui.ChatScrollView;
import com.omnideck.mobile.ui.IconDrawable;
import com.omnideck.mobile.ui.MarkdownRenderer;
import com.omnideck.mobile.ui.Panel;
import com.omnideck.mobile.ui.Theme;
import com.omnideck.mobile.ui.Ui;
import com.omnideck.mobile.ui.Widgets;

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
 * voice turns (a spoken question gets a spoken answer; hands-free listens
 * again), read-aloud, image attachments for vision models (picked, shared
 * from other apps, or a PC screenshot), code copy, and the chat archive.
 */
public final class CommsScreen extends Screen {
    static final int INITIAL_RENDER = 80;
    /** Up to this many images per message. */
    static final int MAX_IMAGES = 4;
    /** Context fill (of num_ctx) at which Comms warns and offers /compact. */
    static final double CONTEXT_WARN = 0.8;
    /** Hands-free: how long to wait for the spoken reply to start before listening anyway. */
    static final long SPEECH_START_WAIT_MS = 6000;
    static final long RELISTEN_POLL_MS = 350;
    /** A message typed while offline is sent when the link returns within this long. */
    static final long OFFLINE_SEND_MS = 10 * 60 * 1000;

    private MarkdownRenderer md;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Holder> holders = new HashMap<String, Holder>();
    private final Set<String> expandedThoughts = new HashSet<String>();
    private final List<String> pendingImages = new ArrayList<String>();
    private final List<Bitmap> pendingPreviews = new ArrayList<Bitmap>();
    private int renderedFrom;
    private boolean suppressSuggest;

    // Voice turns
    /** The next submit came from the speech recognizer. */
    private boolean voiceArmed;
    /** The reply to a voice turn: spoken when it finishes. */
    private String voiceReplyId;
    private boolean relistenPending;
    private boolean sawSpeech;
    private long relistenDeadline;
    private boolean speakingNow;
    // Sent while offline: goes out when the link is back.
    private boolean waitingForLink;
    private boolean waitingVoice;
    private long waitingSince;

    private TextView chatTitle;
    private TextView chatSub;
    private ImageView speakerBtn;
    private ImageView handsFreeBtn;
    private ChatScrollView scroll;
    private LinearLayout list;
    private LinearLayout emptyState;
    private TextView emptySub;
    private View jumpBtn;
    private LinearLayout ctxWarn;
    private TextView ctxWarnText;
    private LinearLayout linkWait;
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
        /** Replies that used PC tools: the action log (null for other messages). */
        final ToolLog toolLog;
        final Dots dots;
        final TextView body;
        final LinearLayout images;
        final TextView footer;
        /** A failed reply: the reason in plain words, the raw error, Retry and a fix for the kind of failure. */
        final LinearLayout retryRow;
        final TextView retryReason;
        final TextView retryDetail;
        final LinearLayout retryActions;
        final View retryBtn;
        /** The fix for this kind of failure (Pull the model, pick a vision model, Settings); null when none. */
        View fixBtn;
        String shownFix = "";
        /** A PC screenshot: "Ask about this" attaches it to the composer. */
        final View askRow;
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

            if (m.isAssistant()) {
                toolLog = new ToolLog(a, ui, t, e.settings.reduceMotion());
                toolLog.setVisibility(View.GONE);
                LinearLayout.LayoutParams glp = Ui.fillW();
                glp.topMargin = ui.dp(8);
                glp.bottomMargin = ui.dp(2);
                bubble.addView(toolLog, glp);
            } else {
                toolLog = null;
            }

            dots = new Dots(a, m.isAssistant() ? t.accent : t.dim, e.settings.reduceMotion());
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

            if (m.isAssistant()) {
                // Reason above, the buttons below: a weighted side-by-side row would clip the text
                // inside the wrap-content bubble.
                retryRow = ui.vbox();
                retryRow.setVisibility(View.GONE);
                retryReason = ui.text("", 14, t.ink, t.body);
                retryReason.setLineSpacing(0, 1.25f);
                retryRow.addView(retryReason, Ui.wrap());
                retryDetail = ui.text("", 11.5f, t.dim, t.mono);
                retryDetail.setLineSpacing(0, 1.15f);
                retryDetail.setMaxLines(3);
                retryDetail.setEllipsize(TextUtils.TruncateAt.END);
                retryDetail.setPadding(0, ui.dp(5), 0, 0);
                retryRow.addView(retryDetail, Ui.wrap());
                retryActions = ui.hbox();
                retryBtn = ui.button("Retry", IconDrawable.REFRESH, Ui.SECONDARY, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        e.regenerate();
                    }
                });
                retryBtn.setContentDescription("Retry this reply");
                retryActions.addView(retryBtn, Ui.wrap());
                LinearLayout.LayoutParams blp = Ui.wrap();
                blp.topMargin = ui.dp(10);
                retryRow.addView(retryActions, blp);
                LinearLayout.LayoutParams rrl = Ui.wrap();
                rrl.topMargin = ui.dp(10);
                bubble.addView(retryRow, rrl);
            } else {
                retryRow = null;
                retryReason = null;
                retryDetail = null;
                retryActions = null;
                retryBtn = null;
            }
            if (m.isNotice()) {
                askRow = ui.actionChip("Ask about this", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askAbout(m);
                    }
                });
                askRow.setContentDescription("Ask OMNI about this image");
                askRow.setVisibility(View.GONE);
                LinearLayout.LayoutParams alp = Ui.wrap();
                alp.topMargin = ui.dp(10);
                bubble.addView(askRow, alp);
            } else {
                askRow = null;
            }

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

    /**
     * Three pulsing dots while the AI hasn't produced text yet. They animate
     * only while on screen, and hold still with Reduce motion.
     */
    private static final class Dots extends Widgets.Animated {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean still;
        private float phase = 0.3f;

        Dots(Context c, int color, boolean still) {
            super(c);
            this.still = still;
            p.setColor(color);
        }

        @Override
        protected boolean wantsLoop() {
            return !still;
        }

        @Override
        protected ValueAnimator makeLoop() {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(1100);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    phase = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            return anim;
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
        // 14dp gutters, like the cards and the top bar's icons.
        list.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12));
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

        ctxWarn = buildContextWarning();
        ctxWarn.setVisibility(View.GONE);
        column.addView(ctxWarn, Ui.fillW());
        linkWait = buildLinkWait();
        linkWait.setVisibility(View.GONE);
        column.addView(linkWait, Ui.fillW());

        suggestScroll = new ScrollView(a);
        suggestScroll.setBackgroundColor(Theme.flatten(t.surface2, t.bg));
        suggestBox = ui.vbox();
        suggestBox.setPadding(0, ui.dp(4), 0, ui.dp(4));
        suggestScroll.addView(suggestBox);
        suggestScroll.setVisibility(View.GONE);
        column.addView(suggestScroll, Ui.fillW());

        attachStrip = ui.hbox();
        attachStrip.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), 0);
        attachScroll = new HorizontalScrollView(a);
        attachScroll.setHorizontalScrollBarEnabled(false);
        // Part of the composer: same surface, so the thumbnails don't float over the chat.
        attachScroll.setBackgroundColor(t.hud ? Theme.alpha(t.surface2, 0xE6) : t.surface);
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
        // The draft outlives the process too (Android may kill the app in the background).
        String draft = e.draft.length() > 0 ? e.draft : savedDraft();
        if (draft.length() > 0) {
            input.setText(draft);
            input.setSelection(input.getText().length());
        }
        return root;
    }

    private android.content.SharedPreferences shellPrefs() {
        return a.getSharedPreferences("omnideck-shell", Context.MODE_PRIVATE);
    }

    private String savedDraft() {
        String d = shellPrefs().getString("draft", "");
        return d == null ? "" : d;
    }

    /** Keeps the composer's text in the Engine (and on disk, for a cold start). */
    private void keepDraft() {
        if (input == null) return;
        e.draft = input.getText().toString();
        shellPrefs().edit().putString("draft", e.draft).apply();
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
        // Titles start where the top bar's do (after its logo slot).
        titles.setPadding(ui.dp(7), 0, ui.dp(4), 0);
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
        handsFreeBtn = ui.iconButton(IconDrawable.HEADSET, "Hands-free conversation", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setHandsFree(!e.settings.handsFree());
            }
        });
        h.addView(handsFreeBtn);
        speakerBtn = ui.iconButton(IconDrawable.SPEAKER_OFF, "Read replies aloud", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (speakingNow) {
                    // While the phone talks, this is its Stop control.
                    a.stopSpeaking();
                    return;
                }
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
        big.setImageDrawable(new IconDrawable(IconDrawable.LOGO, t.accent, t.logoCore, ui.dp(56)));
        big.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
        // {label, command ("" = send the label as a message)}; commands are identifiers → mono.
        String[][] prompts = {{"What can you do?", ""}, {"/help", "/help"}, {"/models", "/models"},
                {"Speak", "/voice"}};
        for (int i = 0; i < prompts.length; i++) {
            final String label = prompts[i][0];
            final String cmd = prompts[i][1];
            TextView c = ui.actionChip(label, label.startsWith("/"), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
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

    /** "Context 86% full" with a one-tap Compact, above the composer. */
    private LinearLayout buildContextWarning() {
        LinearLayout bar = ui.hbox();
        bar.setPadding(ui.dp(14), ui.dp(8), ui.dp(6), ui.dp(8));
        bar.setBackgroundColor(Theme.flatten(Theme.alpha(t.warn, t.isDark ? 0x1F : 0x14), t.hud ? t.surface2 : t.surface));
        ImageView icon = new ImageView(a);
        icon.setImageDrawable(new IconDrawable(IconDrawable.WARN, t.warn, t.warn, ui.dp(18)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(icon, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        ctxWarnText = ui.text("", 13, t.ink, t.body);
        ctxWarnText.setLineSpacing(0, 1.15f);
        ctxWarnText.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        bar.addView(ctxWarnText, Ui.weight(1));
        TextView compact = ui.button("Compact", 0, Ui.SECONDARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideContextWarning();
                a.commander().run("/compact");
            }
        });
        compact.setContentDescription("Compact the chat");
        bar.addView(compact);
        bar.addView(ui.iconButton(IconDrawable.CLOSE, "Dismiss context warning", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideContextWarning();
            }
        }));
        return bar;
    }

    /** "Waiting for your AI" with Cancel, above the composer, while an offline message waits. */
    private LinearLayout buildLinkWait() {
        LinearLayout bar = ui.hbox();
        // 10 + the ghost button's 4dp = the 14dp gutter.
        bar.setPadding(ui.dp(14), ui.dp(8), ui.dp(10), ui.dp(8));
        bar.setBackgroundColor(Theme.flatten(t.accentSoft, t.hud ? t.surface2 : t.surface));
        ImageView icon = new ImageView(a);
        icon.setImageDrawable(new IconDrawable(IconDrawable.WIFI, t.id == Theme.DARK ? t.data : t.accent,
                t.id == Theme.DARK ? t.data : t.accent, ui.dp(18)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(icon, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        TextView text = ui.text("Waiting for your AI — this sends when the link is back.", 13, t.ink, t.body);
        text.setLineSpacing(0, 1.15f);
        text.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        bar.addView(text, Ui.weight(1));
        TextView cancel = ui.button("Cancel", 0, Ui.GHOST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setWaitingForLink(false, false);
            }
        });
        cancel.setContentDescription("Don't send when the link is back");
        bar.addView(cancel);
        return bar;
    }

    private void setWaitingForLink(boolean on, boolean voice) {
        waitingForLink = on;
        waitingVoice = on && voice;
        waitingSince = on ? SystemClock.uptimeMillis() : 0;
        if (linkWait != null) linkWait.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** True while a message typed offline waits for the link (tests). */
    public boolean waitingForLink() {
        return waitingForLink;
    }

    private View buildComposer() {
        LinearLayout wrap = ui.vbox();
        View line = ui.divider();
        if (t.hud) line.setBackgroundColor(t.edge);
        wrap.addView(line);
        LinearLayout composer = ui.hbox();
        composer.setGravity(Gravity.BOTTOM);
        composer.setBackgroundColor(t.hud ? Theme.alpha(t.surface2, 0xE6) : t.surface);
        // Both ends sit on the 14dp grid: the attach glyph (centred in its 40dp slot) and the send button.
        composer.setPadding(ui.dp(4), ui.dp(8), ui.dp(14), ui.dp(10));

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
                // Emptied while waiting for the link: nothing left to send.
                if (waitingForLink && s.toString().trim().length() == 0 && pendingImages.isEmpty()) {
                    setWaitingForLink(false, false);
                }
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
                if (e.isWorking()) {
                    // A reply (and its PC actions), or a compact / benchmark.
                    e.stop();
                } else if (input.getText().toString().trim().length() == 0 && pendingImages.isEmpty()) {
                    voice();
                } else {
                    submit();
                }
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46));
        slp.leftMargin = ui.dp(10);
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

    /** The meta line over a message; model tags stay in mono, in their own case. */
    CharSequence headerText(ChatMessage m) {
        String time = ui.clock(m.time, false);
        if (m.isUser()) return (t.hud ? "OPERATOR // YOU" : "You") + " · " + time;
        if (m.isSystem()) return t.hud ? "CONTEXT // SUMMARY" : "Earlier conversation (summary)";
        if (m.isNotice()) {
            String k = "ok".equals(m.tone) ? "OK" : "error".equals(m.tone) ? "Error" : "warn".equals(m.tone) ? "Notice" : "Info";
            return (t.hud ? "SYSTEM // " + k.toUpperCase(Locale.US) : k) + " · " + time;
        }
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (t.hud) s.append("OMNI // ");
        if (m.model.length() > 0) s.append(ui.mono(m.model));
        else s.append(t.hud ? "AI" : "OMNI");
        s.append(" · ").append(time);
        if (m.streaming) {
            long secs = (System.currentTimeMillis() - m.startedAt) / 1000;
            String tools = m.activeToolState();
            if (ToolCall.ASKING.equals(tools)) {
                s.append(" · awaiting approval");
            } else if (tools != null) {
                s.append(" · acting on PC");
            } else if (!m.tools.isEmpty() && m.content.length() == 0) {
                // The PC's answers are in: the model is reading them.
                s.append(" · processing results");
            } else if (m.content.length() == 0) {
                s.append(m.thinking.length() > 0 ? " · thinking " + secs + "s"
                        : secs >= 2 ? " · loading model " + secs + "s" : " · connecting");
            } else {
                s.append(" · streaming");
            }
        }
        return s;
    }

    /** A failed reply's error, in plain words (the raw error stays in the footer). */
    static String failureReason(String error) {
        String e = error == null ? "" : error.toLowerCase(Locale.US);
        if (e.contains("not found") && (e.contains("model") || e.contains("pull"))) {
            return "The PC doesn't have this model any more — pick another one in Models.";
        }
        if (e.contains("memory") || e.contains("oom") || e.contains("cuda")) {
            return "The PC ran out of memory for this model. Try a smaller model, or unload others.";
        }
        if (e.contains("context") && (e.contains("length") || e.contains("exceed") || e.contains("too long"))) {
            return "This chat no longer fits the model's context. Compact it, or start a new chat.";
        }
        if (e.contains("connect") || e.contains("reset") || e.contains("refused") || e.contains("timed out")
                || e.contains("timeout") || e.contains("broken pipe") || e.contains("closed") || e.contains("eof")
                || e.contains("unexpected end") || e.contains("socket") || e.contains("network")) {
            return "The connection to your PC dropped before the reply finished.";
        }
        return "Your AI couldn't finish this reply.";
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

        if (h.toolLog != null) {
            if (m.tools.isEmpty()) {
                if (h.toolLog.getVisibility() != View.GONE) h.toolLog.setVisibility(View.GONE);
            } else {
                h.toolLog.bind(m, e.pcName());
                if (h.toolLog.getVisibility() != View.VISIBLE) h.toolLog.setVisibility(View.VISIBLE);
            }
        }

        // The typing dots while the AI hasn't written anything yet (not while a PC tool runs or asks:
        // the action log shows that).
        boolean waiting = m.streaming && m.content.length() == 0 && m.thinking.length() == 0
                && m.activeToolState() == null;
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

        // A PC screenshot a tool returned shows in the reply, like one taken with /shot.
        List<String> toolImages = new ArrayList<String>();
        for (ToolCall c : m.tools) {
            if (c.image.length() > 0) toolImages.add(c.image);
        }
        int imgCount = m.images.size() + (m.image.length() > 0 ? 1 : 0) + toolImages.size();
        if (imgCount != h.shownImages) {
            h.shownImages = imgCount;
            h.images.removeAllViews();
            List<String> all = new ArrayList<String>(m.images);
            if (m.image.length() > 0) all.add(m.image);
            all.addAll(toolImages);
            for (final String b64 : all) {
                boolean single = all.size() == 1 && (m.image.length() > 0 || !toolImages.isEmpty());
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
        if (h.askRow != null) h.askRow.setVisibility(m.image.length() > 0 ? View.VISIBLE : View.GONE);

        String foot = m.stats;
        if (foot.length() > 0 && !m.streaming) {
            // Cyber's stats line is micro-caps, but units stay lower-case: "40.0 tok/s · 2.7s", never "TOK/S · 2.7S".
            h.footer.setText(t.hud ? t.labelUnits(foot) : foot);
            h.footer.setTextColor(m.error ? t.danger : m.stopped ? t.warn : t.faint);
            h.footer.setVisibility(View.VISIBLE);
        } else {
            h.footer.setVisibility(View.GONE);
        }

        if (h.retryRow != null) {
            boolean failed = m.error && !m.streaming;
            if (failed) {
                // The footer ("stats") of a failed reply is "plain words · raw error".
                String[] parts = splitFailure(m.stats);
                h.retryReason.setText(parts[0]);
                h.retryDetail.setText(parts[1]);
                h.retryDetail.setVisibility(parts[1].length() > 0 ? View.VISIBLE : View.GONE);
                // Retry answers the last question again, so it's offered on the latest reply only.
                boolean latest = m == e.conversation().lastOfRole(ChatMessage.ASSISTANT)
                        && !e.isWorking();
                h.retryBtn.setVisibility(latest ? View.VISIBLE : View.GONE);
                bindFix(h, latest);
                h.retryActions.setVisibility(latest ? View.VISIBLE : View.GONE);
                // The words and the raw error show above; the footer would repeat them.
                h.footer.setVisibility(View.GONE);
            }
            h.retryRow.setVisibility(failed ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * A failed reply's footer is "plain words · raw error" ({@code ReplyError.stats()});
     * older chats saved only the raw error, which is explained here instead.
     */
    static String[] splitFailure(String stats) {
        String s = stats == null ? "" : stats.trim();
        int dot = s.indexOf(" · ");
        if (dot > 0) return new String[]{s.substring(0, dot).trim(), s.substring(dot + 3).trim()};
        String plain = failureReason(s);
        return new String[]{plain, plain.equals(s) ? "" : s};
    }

    /** The one-tap fix for this kind of failure, next to Retry. */
    private void bindFix(final Holder h, boolean latest) {
        final ChatMessage m = h.m;
        String kind = latest ? m.errorKind : "";
        if (kind.equals(h.shownFix)) return;
        h.shownFix = kind;
        if (h.fixBtn != null) h.retryActions.removeView(h.fixBtn);
        h.fixBtn = null;
        View b = null;
        if (ReplyError.MODEL_MISSING.equals(kind) && m.model.length() > 0) {
            b = ui.button("/pull " + m.model, IconDrawable.DOWNLOAD, Ui.SECONDARY, true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.commander().run("/pull " + m.model);
                }
            });
            b.setContentDescription("Download " + m.model + " onto the PC");
        } else if (ReplyError.NO_VISION.equals(kind)) {
            b = ui.button("Vision model", IconDrawable.IMAGE, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickVisionModel();
                }
            });
            b.setContentDescription("Switch to a model that can see images");
        } else if (ReplyError.OUT_OF_MEMORY.equals(kind) || ReplyError.UNAUTHORIZED.equals(kind)) {
            final boolean memory = ReplyError.OUT_OF_MEMORY.equals(kind);
            b = ui.button("Settings", IconDrawable.SETTINGS, Ui.SECONDARY, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.openSettings();
                    ui.toast(memory ? "Lower Context size in Performance, or pick a smaller model."
                            : "Check the API key in Connection.");
                }
            });
            b.setContentDescription(memory ? "Open Settings to lower the context size" : "Open Settings to check the API key");
        }
        if (b != null) {
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.leftMargin = ui.dp(8);
            h.retryActions.addView(b, lp);
            h.fixBtn = b;
        }
    }

    /** Installed models known to see images; one tap switches (the retry then keeps the images). */
    private void pickVisionModel() {
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        String cur = e.currentModel();
        for (final ModelInfo mi : e.models()) {
            if (!Boolean.TRUE.equals(e.supportsVision(mi.name))) continue;
            rows.add(new Ui.Row(ui.mono(mi.name), (e.isLoaded(mi.name) ? "loaded · " : "") + mi.describe(),
                    mi.name.equals(cur), new Runnable() {
                        @Override
                        public void run() {
                            e.setModel(mi.name);
                            ui.toast("Model: " + mi.name + " — tap Retry to send the image again.");
                        }
                    }, null).icon(IconDrawable.IMAGE));
        }
        if (rows.isEmpty()) {
            // Not known yet (or none installed): the model bay shows each model's abilities.
            ui.toast("No model that can see images is known yet — pick or download one (llava, gemma3, qwen2.5vl…).");
            a.select(MainActivity.TAB_MODELS, true);
            return;
        }
        ui.pick("Vision", "Switch to a model that can see images", rows, null, null);
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
            SpannableStringBuilder sb = new SpannableStringBuilder("Linked to ");
            sb.append(ui.mono(e.server() != null ? e.server().label() : "your PC"));
            if (model.length() > 0) sb.append(" · ").append(ui.mono(model));
            sb.append(".\nType, tap the mic to speak, or / for commands.");
            emptySub.setText(sb);
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
        // The model tag is an identifier: mono, its own case. Plain words stay micro-caps.
        SpannableStringBuilder sub = new SpannableStringBuilder();
        if (model.length() > 0) sub.append(ui.mono(model));
        else sub.append(t.label("No model"));
        sub.append(t.label(" · " + modeLabel + (e.settings.incognito() ? " · incognito" : "")));
        if (model.length() > 0 && e.toolsReady(model)) {
            // OMNI can act on the PC in this chat: the "engaged" ink, like switched-on modes.
            sub.append(t.label(" · "));
            int at = sub.length();
            sub.append(t.label("PC tools"));
            sub.setSpan(new android.text.style.ForegroundColorSpan(t.engagedInk), at, sub.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        chatSub.setText(sub);
        if (speakingNow) {
            speakerBtn.setImageDrawable(new IconDrawable(IconDrawable.STOP_CIRCLE, t.accent, t.accent, ui.dp(20)));
            speakerBtn.setContentDescription("Stop speaking");
        } else {
            // Switched-on modes use the "engaged" ink, like the Command tab's engaged tiles.
            boolean on = e.settings.readAloud();
            speakerBtn.setImageDrawable(new IconDrawable(on ? IconDrawable.SPEAKER : IconDrawable.SPEAKER_OFF,
                    on ? t.engagedInk : t.dim, 0, ui.dp(20)));
            speakerBtn.setContentDescription("Read replies aloud");
            speakerBtn.setSelected(on);
        }
        boolean hf = e.settings.handsFree();
        handsFreeBtn.setImageDrawable(new IconDrawable(IconDrawable.HEADSET, hf ? t.engagedInk : t.dim,
                hf ? t.engagedInk : t.dim, ui.dp(20)));
        handsFreeBtn.setSelected(hf);
        Boolean vision = e.supportsVision(model);
        attachBtn.setImageDrawable(new IconDrawable(IconDrawable.IMAGE,
                Boolean.TRUE.equals(vision) ? t.accent : t.faint, 0, ui.dp(20)));
    }

    // ------------------------------------------------------------------
    // Context window warning
    // ------------------------------------------------------------------

    private void showContextWarning(double fill) {
        if (ctxWarn == null) return;
        int pct = (int) Math.round(fill * 100);
        if (pct >= 100) {
            ctxWarnText.setText("Context full — the start of this chat no longer reaches the model.");
            ctxWarn.setContentDescription("Context full");
        } else {
            ctxWarnText.setText("Context " + pct + "% full — the start of this chat will soon drop out.");
            ctxWarn.setContentDescription("Context " + pct + " percent full");
        }
        ctxWarn.setVisibility(View.VISIBLE);
    }

    private void hideContextWarning() {
        if (ctxWarn != null) ctxWarn.setVisibility(View.GONE);
    }

    /** True while the "context nearly full" row shows (tests). */
    public boolean contextWarningShown() {
        return ctxWarn != null && ctxWarn.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------
    // Engine events
    // ------------------------------------------------------------------

    @Override
    public void onStateChanged() {
        updateEmptyState();
        updateHeader();
        // The link is back (and the model list with it): send what waited.
        if (waitingForLink && e.state() == Engine.State.ONLINE && !e.models().isEmpty()) {
            handler.removeCallbacks(sendWaiting);
            handler.post(sendWaiting);
        }
    }

    private final Runnable sendWaiting = new Runnable() {
        @Override
        public void run() {
            if (!waitingForLink || e.state() != Engine.State.ONLINE || e.isWorking()) return;
            boolean fresh = SystemClock.uptimeMillis() - waitingSince < OFFLINE_SEND_MS;
            boolean voice = waitingVoice;
            setWaitingForLink(false, false);
            if (!fresh) return;
            if (input.getText().toString().trim().length() == 0 && pendingImages.isEmpty()) return;
            voiceArmed = voice;
            submit();
        }
    };

    @Override
    public void onConversationReplaced() {
        hideContextWarning();
        voiceReplyId = null;
        renderAll();
    }

    @Override
    public void onMessageAdded(ChatMessage m) {
        if (holders.containsKey(m.id)) return;
        addHolder(m, -1);
        updateEmptyState();
        updateHeader();
        if (m.isUser() || m.isNotice()) scroll.stickToBottom(false);
        if (m.isAssistant()) refreshFailed();
    }

    /** Failed replies offer Retry only while they're the latest and nothing runs: re-check them. */
    private void refreshFailed() {
        for (Holder h : holders.values()) {
            if (h.m.error) bind(h);
        }
    }

    @Override
    public void onMessageChanged(ChatMessage m) {
        Holder h = holders.get(m.id);
        if (h != null) bind(h);
        if (!m.isAssistant() || m.streaming) return;
        if (m.id.equals(voiceReplyId)) {
            voiceReplyId = null;
            if (!m.error && !m.stopped && m.content.trim().length() > 0) {
                // A spoken question gets a spoken answer, even with read-aloud off
                // (with it on, the reply was already read as it streamed).
                if (!e.settings.readAloud()) e.speakNow(m.content);
                if (e.settings.handsFree()) armRelisten();
            }
        }
        if (!m.error && !m.stopped && m == e.conversation().lastOfRole(ChatMessage.ASSISTANT)) {
            // The Engine's estimate for this chat and model (a telemetry sample may belong to
            // another chat, and a cached prompt makes Ollama count only its new part).
            double fill = e.contextFill();
            if (fill >= CONTEXT_WARN) showContextWarning(fill);
            else hideContextWarning();
        }
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
        refreshFailed();
    }

    @Override
    public void onSpeechChanged(boolean speaking) {
        speakingNow = speaking;
        if (chatTitle != null) updateHeader();
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
        // Speech events only reach built pages: catch up (the top bar hides its Stop here).
        speakingNow = a.isSpeaking();
        updateHeader();
        scroll.removeCallbacks(ticker);
        scroll.post(ticker);
    }

    @Override
    protected void onHide() {
        scroll.removeCallbacks(ticker);
        keepDraft();
    }

    @Override
    public void onActivityStop() {
        keepDraft();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
        boolean busy = e.isWorking();
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

    /**
     * Sends recognized speech. The reply to a voice turn is spoken back even
     * when read-aloud is off, and in hands-free mode the mic opens again
     * once it has been said.
     */
    public void submitVoice(String text) {
        voiceArmed = true;
        submitText(text);
    }

    /** Opens the speech recognizer for a voice turn (the mic button, the "Talk" shortcut). */
    public void talk() {
        if (input == null) view();
        voice();
    }

    /** Puts the cursor in the composer (e.g. after "New chat" from the launcher). */
    public void focusComposer() {
        if (input == null) view();
        input.requestFocus();
    }

    private void submit() {
        boolean voiceTurn = voiceArmed;
        voiceArmed = false;
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
            setWaitingForLink(false, false);
            clearInput();
            clearAttachments();
            scroll.stickToBottom(false);
            if (voiceTurn) {
                ChatMessage reply = e.streamingMessage();
                voiceReplyId = reply != null ? reply.id : null;
            }
            a.onUserSent();
        } else if (e.state() != Engine.State.ONLINE) {
            // Offline: the text stays in the composer and goes out when the link is back.
            setWaitingForLink(true, voiceTurn);
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
                submitVoice(text);
            }
        });
    }

    // ------------------------------------------------------------------
    // Hands-free conversation
    // ------------------------------------------------------------------

    private void setHandsFree(boolean on) {
        e.settings.setHandsFree(on);
        updateHeader();
        if (!on) {
            relistenPending = false;
            handler.removeCallbacks(relisten);
            ui.toast("Hands-free off");
            return;
        }
        ui.toast("Hands-free on: after each spoken reply, OMNI listens again");
        if (!e.isWorking()) voice();
    }

    /** After a voice turn's spoken reply ends (or never starts), listen again. */
    private void armRelisten() {
        relistenPending = true;
        sawSpeech = false;
        relistenDeadline = SystemClock.uptimeMillis() + SPEECH_START_WAIT_MS;
        handler.removeCallbacks(relisten);
        handler.postDelayed(relisten, RELISTEN_POLL_MS);
    }

    /** True while hands-free waits for the spoken reply to end (tests). */
    public boolean relistenPending() {
        return relistenPending;
    }

    private final Runnable relisten = new Runnable() {
        @Override
        public void run() {
            if (!relistenPending) return;
            if (!e.settings.handsFree() || !isAppVisible()) {
                relistenPending = false;
                return;
            }
            if (e.speaking()) {
                sawSpeech = true;
                handler.postDelayed(this, RELISTEN_POLL_MS);
                return;
            }
            if (!sawSpeech && SystemClock.uptimeMillis() < relistenDeadline) {
                // The voice may still be starting up.
                handler.postDelayed(this, RELISTEN_POLL_MS);
                return;
            }
            relistenPending = false;
            if (!e.isWorking()) voice();
        }
    };

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    private void attachImage() {
        String model = e.currentModel();
        Boolean vision = e.supportsVision(model);
        if (Boolean.FALSE.equals(vision)) {
            ui.toast((model.length() > 0 ? model : "This model") + " can't see images. Switch to a vision model "
                    + "(llava, gemma3, qwen2.5vl…) to attach photos.");
            return;
        }
        if (pendingImages.size() >= MAX_IMAGES) {
            ui.toast("Up to 4 images per message.");
            return;
        }
        a.pickImage(new MainActivity.ImageResult() {
            @Override
            public void onImage(String base64, Bitmap preview) {
                addAttachment(base64, preview);
            }
        });
    }

    /**
     * Adds an encoded image (base64 JPEG/PNG) to the next message — a
     * picked photo, one shared from another app, or a PC screenshot.
     * {@code preview} may be null (it is decoded from the image).
     */
    public void addAttachment(String base64, Bitmap preview) {
        if (input == null) view();
        if (base64 == null || base64.length() == 0) return;
        if (pendingImages.size() >= MAX_IMAGES) {
            ui.toast("Up to 4 images per message.");
            return;
        }
        pendingImages.add(base64);
        pendingPreviews.add(preview != null ? preview : decode(base64, ui.dp(116)));
        renderAttachments();
        updateSendButton();
        String model = e.currentModel();
        if (Boolean.FALSE.equals(e.supportsVision(model))) {
            ui.toast(model + " can't see images — switch to a vision model (llava, gemma3, qwen2.5vl…) to send it.");
        }
    }

    /** The images waiting in the composer (tests). */
    public List<String> pendingImages() {
        return new ArrayList<String>(pendingImages);
    }

    /** "Ask about this" on a PC screenshot: attach it (downscaled) and suggest a question. */
    private void askAbout(ChatMessage m) {
        if (m.image.length() == 0) return;
        if (pendingImages.size() >= MAX_IMAGES) {
            ui.toast("Up to 4 images per message.");
            return;
        }
        a.downscaleImage(m.image, 1280, new MainActivity.ImageResult() {
            @Override
            public void onImage(String base64, Bitmap preview) {
                addAttachment(base64, preview);
                if (input.getText().toString().trim().length() == 0) {
                    input.setText("What's on my PC screen?");
                    input.selectAll();
                }
                input.requestFocus();
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
            iv.setContentDescription("Attached image " + (i + 1));
            f.addView(iv, new FrameLayout.LayoutParams(ui.dp(58), ui.dp(58)));
            ImageView x = new ImageView(a);
            // A dark puck with a light ×, readable on any photo.
            x.setImageDrawable(new IconDrawable(IconDrawable.CLOSE, t.isDark ? t.inkStrong : t.surface, 0, ui.dp(12)));
            x.setScaleType(ImageView.ScaleType.CENTER);
            x.setBackground(ui.rounded(Theme.alpha(t.isDark ? t.bg : t.ink, 0xCC), 0, 10));
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
        TextView name = ui.text(clickable ? c.name : c.usage, 13.5f, t.id == Theme.DARK ? t.data : t.accent, t.mono);
        row.addView(name);
        TextView desc = ui.dim(c.desc, 12);
        desc.setSingleLine(true);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setPadding(0, ui.dp(2), 0, 0);
        row.addView(desc);
        if (clickable) {
            row.setBackground(ui.pressableRow(0));
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
            rows.add(new Ui.Row(ui.mono(m.name), (e.isLoaded(m.name) ? "loaded · " : "") + m.describe(),
                    m.name.equals(cur), new Runnable() {
                        @Override
                        public void run() {
                            e.setModel(m.name);
                            ui.toast("Model: " + m.name);
                        }
                    }, null));
        }
        String mode = e.mode();
        ui.pick("Model", "Switch model", rows, "Mode: " + (Settings.MODE_DEEP.equals(mode) ? "Deep"
                : Settings.MODE_FAST.equals(mode) ? "Fast" : "Auto"), new Runnable() {
            @Override
            public void run() {
                a.commander().cycleMode();
            }
        });
    }

    /** The first line of a message as plain text (no Markdown), shortened: the actions sheet's title. */
    private static String excerpt(ChatMessage m) {
        String s = m.content.trim();
        int nl = s.indexOf('\n');
        if (nl > 0) s = s.substring(0, nl);
        if (!m.isUser()) s = Markdown.parse(s).text.trim();
        if (s.length() == 0) return m.images.isEmpty() && m.image.length() == 0 ? "Empty message" : "Image";
        return "“" + Fmt.ellipsize(s, 56) + "”";
    }

    private void showMessageActions(final ChatMessage m) {
        List<Ui.Row> rows = new ArrayList<Ui.Row>();
        if (m.content.length() > 0) {
            rows.add(new Ui.Row("Copy text", null, false, new Runnable() {
                @Override
                public void run() {
                    a.copy("message", m.content);
                }
            }, null).icon(IconDrawable.COPY));
        }
        if (m.thinking.length() > 0) {
            rows.add(new Ui.Row("Copy reasoning", null, false, new Runnable() {
                @Override
                public void run() {
                    a.copy("reasoning", m.thinking);
                }
            }, null).icon(IconDrawable.BRAIN));
        }
        if (m.isAssistant() && m.content.length() > 0) {
            rows.add(new Ui.Row("Read aloud", null, false, new Runnable() {
                @Override
                public void run() {
                    e.speakNow(m.content);
                }
            }, null).icon(IconDrawable.SPEAKER));
        }
        if (m.content.length() > 0) {
            rows.add(new Ui.Row("Share", null, false, new Runnable() {
                @Override
                public void run() {
                    a.share("OMNI-DECK", m.content);
                }
            }, null).icon(IconDrawable.SHARE));
        }
        if (m.isNotice() && m.image.length() > 0) {
            rows.add(new Ui.Row("Ask about this", "Attach it to your next message", false, new Runnable() {
                @Override
                public void run() {
                    askAbout(m);
                }
            }, null).icon(IconDrawable.IMAGE));
        }
        if (m.isAssistant() && !e.isWorking() && m == e.conversation().lastOfRole(ChatMessage.ASSISTANT)) {
            rows.add(new Ui.Row("Regenerate", "Answer the last question again", false, new Runnable() {
                @Override
                public void run() {
                    e.regenerate();
                }
            }, null).icon(IconDrawable.REFRESH));
        }
        if (m.isUser() && !e.isWorking()) {
            rows.add(new Ui.Row("Edit & resend", "Removes this message and everything after it", false,
                    new Runnable() {
                        @Override
                        public void run() {
                            editAndResend(m);
                        }
                    }, null).icon(IconDrawable.EDIT));
        }
        rows.add(new Ui.Row("Delete", null, false, new Runnable() {
            @Override
            public void run() {
                e.deleteMessage(m);
            }
        }, null).icon(IconDrawable.TRASH).danger());
        ui.pick(m.isUser() ? "Your message" : m.isAssistant() ? "OMNI's reply" : "Notice", excerpt(m), rows,
                null, null);
    }

    /** Puts a sent message back in the composer (with its images) and drops it and everything after. */
    private void editAndResend(ChatMessage m) {
        List<String> images = new ArrayList<String>(m.images);
        String text = e.editFrom(m);
        clearAttachments();
        for (String b64 : images) {
            if (pendingImages.size() >= MAX_IMAGES) break;
            pendingImages.add(b64);
            pendingPreviews.add(decode(b64, ui.dp(116)));
        }
        renderAttachments();
        input.setText(text);
        input.setSelection(input.getText().length());
        input.requestFocus();
        updateSendButton();
    }

    // ------------------------------------------------------------------
    // PC actions: the approval sheet
    // ------------------------------------------------------------------

    private Sheet approvalSheet;
    private ToolApproval approvalShown;

    /**
     * Asks before the AI changes something on the PC: "OMNI wants to set
     * volume to 40% on ATLAS-PC", what exactly runs (tool id and arguments in
     * mono, the PC), Deny / Allow, and "Allow for this chat" for tools that
     * aren't destructive. Closing the sheet without answering denies. Returns
     * false when it can't be shown (the activity is going away).
     */
    public boolean showToolApproval(final ToolApproval req) {
        if (!req.isPending()) return true;
        if (approvalShown == req && approvalSheet != null && approvalSheet.isShowing()) return true;
        if (!ui.canShowDialogs()) return false;
        if (approvalSheet != null && approvalSheet.isShowing()) approvalSheet.dismiss();
        final Sheet s = ui.sheet(req.destructive ? "Destructive PC action" : "PC action · approval", req.sentence());
        s.eyebrowColor(req.destructive ? t.danger : t.warn);

        LinearLayout spec = ui.vbox();
        spec.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(10));
        spec.setBackground(ui.rounded(t.input, t.hud ? t.edge : t.hair, 8));
        spec.addView(specRow("Tool", req.tool), Ui.fillW());
        if (req.detail.length() > 0 && !"none".equals(req.detail)) {
            boolean app = com.omnideck.mobile.core.ToolKit.OPEN_APP.equals(req.tool);
            String key = !app ? "Arguments" : req.detail.indexOf('\\') >= 0 || req.detail.indexOf('/') >= 0 ? "Path" : "App id";
            spec.addView(specRow(key, req.detail), Ui.fillW());
        }
        String where = req.where.length() > 0 && !req.where.startsWith(req.pc + ":") && req.pc.length() > 0
                ? req.pc + " · " + req.where : req.where.length() > 0 ? req.where : req.pc;
        if (where.length() > 0) spec.addView(specRow("PC", where), Ui.fillW());
        s.body.addView(spec, Ui.fillW());

        Widgets.Toggle always = null;
        if (req.destructive) {
            LinearLayout warn = ui.hbox();
            warn.setGravity(Gravity.TOP);
            ImageView icon = new ImageView(a);
            icon.setImageDrawable(new IconDrawable(IconDrawable.WARN, t.danger, t.danger, ui.dp(16)));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            warn.addView(icon, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
            TextView tx = ui.text("This can shut down, restart, sign out or delete things on the PC, so OMNI asks "
                    + "every time.", 13.5f, t.danger, t.body);
            tx.setLineSpacing(0, 1.25f);
            tx.setPadding(ui.dp(10), 0, 0, 0);
            warn.addView(tx, Ui.weight(1));
            LinearLayout.LayoutParams wl = Ui.fillW();
            wl.topMargin = ui.dp(14);
            s.body.addView(warn, wl);
        } else {
            always = ui.toggle(false, null);
            always.setContentDescription("Allow for this chat");
            String generic = com.omnideck.mobile.core.ToolKit.label(null, req.tool, null);
            LinearLayout row = ui.settingRow("Allow for this chat", "Don't ask again for “" + generic
                    + "” in this conversation.", always);
            LinearLayout.LayoutParams rl = Ui.fillW();
            rl.topMargin = ui.dp(4);
            s.body.addView(row, rl);
        }

        final Widgets.Toggle forChat = always;
        s.negative("Deny", new Runnable() {
            @Override
            public void run() {
                req.deny();
            }
        });
        s.positive("Allow", req.destructive ? Ui.DANGER : Ui.PRIMARY, new Runnable() {
            @Override
            public void run() {
                if (forChat != null && forChat.isChecked()) req.allowForChat();
                else req.allow();
            }
        });
        s.onDismiss(new Runnable() {
            @Override
            public void run() {
                if (approvalSheet == s) {
                    approvalSheet = null;
                    approvalShown = null;
                }
                // Closed without an answer (back, a tap outside) means no — unless the activity itself
                // is going away: the recreated one asks again.
                if (req.isPending() && !a.isFinishing() && !a.isDestroyed()) req.deny();
            }
        });
        req.setOnSettled(new Runnable() {
            @Override
            public void run() {
                s.dismiss();
            }
        });
        approvalSheet = s;
        approvalShown = req;
        s.show();
        ui.tick(a.getWindow().getDecorView());
        // A spoken conversation: the question is spoken too.
        ChatMessage reply = e.streamingMessage();
        if (reply != null && reply.id.equals(voiceReplyId)) e.speakNow(req.sentence() + ". Allow?");
        return true;
    }

    /** One line of the approval sheet's spec block: micro-caps key, mono value. */
    private View specRow(String key, String value) {
        LinearLayout r = ui.hbox();
        r.setGravity(Gravity.TOP);
        r.setPadding(0, ui.dp(5), 0, 0);
        TextView k = ui.label(key);
        k.setPadding(0, ui.dp(2), ui.dp(8), 0);
        r.addView(k, new LinearLayout.LayoutParams(ui.dp(82), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = ui.text(value, 13, t.ink, t.mono);
        v.setLineSpacing(0, 1.15f);
        v.setMaxLines(8);
        v.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(v, Ui.weight(1));
        return r;
    }

    /** The approval sheet on screen, if any (tests). */
    public android.app.AlertDialog approvalDialog() {
        return approvalSheet != null && approvalSheet.isShowing() ? approvalSheet.dialog : null;
    }

    // ------------------------------------------------------------------
    // History (the archive)
    // ------------------------------------------------------------------

    private FrameLayout buildHistoryLayer() {
        FrameLayout layer = new FrameLayout(a);
        layer.setBackgroundColor(t.hud ? Theme.flatten(t.surface2, t.bg) : t.bg);
        layer.setClickable(true);
        LinearLayout col = ui.vbox();
        LinearLayout head = ui.hbox();
        head.setPadding(ui.dp(4), ui.dp(4), ui.dp(14), ui.dp(4));
        head.addView(ui.iconButton(IconDrawable.BACK, "Close history", t.dim, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeHistory();
            }
        }));
        TextView title = ui.title(t.hud ? "Archive" : "Chats", t.hud ? 14 : 18);
        title.setPadding(ui.dp(7), 0, 0, 0);
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
        java.text.DateFormat day = android.text.format.DateFormat.getMediumDateFormat(a);
        int shown = 0;
        for (final ConversationStore.Entry en : historyEntries) {
            if (q.length() > 0 && !en.title.toLowerCase(Locale.US).contains(q)) continue;
            shown++;
            LinearLayout card = ui.card();
            card.setPadding(ui.dp(14), ui.dp(12), ui.dp(4), ui.dp(12));
            LinearLayout row = ui.hbox();
            LinearLayout text = ui.vbox();
            TextView tt = ui.text(en.title, 15, en.id.equals(current) ? (t.id == Theme.DARK ? t.data : t.accent)
                    : t.ink, t.bodyMedium);
            tt.setSingleLine(true);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(tt);
            // The model the chat was last used with (reopening it switches back), in its own case.
            SpannableStringBuilder meta = new SpannableStringBuilder();
            if (en.model.length() > 0) meta.append(en.model).append(" · ");
            meta.append(en.count + " messages · " + day.format(new Date(en.updated)) + " · "
                    + ui.clock(en.updated, false));
            TextView st = ui.readout("", 11.5f, t.dim);
            st.setText(meta);
            st.setEllipsize(TextUtils.TruncateAt.END);
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
        }, null).icon(IconDrawable.NAV_COMMS));
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
        }, null).icon(IconDrawable.EDIT));
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
        }, null).icon(IconDrawable.TRASH).danger());
        ui.pick("Chat", Fmt.ellipsize(en.title, 40), rows, null, null);
    }
}
