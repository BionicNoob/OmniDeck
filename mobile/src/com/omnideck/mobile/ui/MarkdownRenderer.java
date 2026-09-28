package com.omnideck.mobile.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Toast;

import com.omnideck.mobile.core.Markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns the core Markdown parser's style ranges into Android spans, themed. */
public final class MarkdownRenderer {
    private final Theme t;
    private final float density;

    public MarkdownRenderer(Theme t, float density) {
        this.t = t;
        this.density = density;
    }

    /** Result of a render: the text and whether it has tappable spans (links, copy buttons). */
    public static final class Rendered {
        public final CharSequence text;
        public final boolean hasLinks;

        Rendered(CharSequence text, boolean hasLinks) {
            this.text = text;
            this.hasLinks = hasLinks;
        }
    }

    public Rendered render(String markdown, boolean cursor) {
        Markdown.Result r = Markdown.parse(markdown);
        SpannableStringBuilder sb = new SpannableStringBuilder(r.text);
        boolean links = false;
        List<Markdown.Span> blocks = new ArrayList<Markdown.Span>();
        for (Markdown.Span s : r.spans) {
            int a = s.start, b = s.end;
            switch (s.type) {
                case Markdown.BOLD:
                    set(sb, new StyleSpan(Typeface.BOLD), a, b);
                    break;
                case Markdown.ITALIC:
                    set(sb, new StyleSpan(Typeface.ITALIC), a, b);
                    break;
                case Markdown.STRIKE:
                    set(sb, new StrikethroughSpan(), a, b);
                    break;
                case Markdown.CODE:
                    set(sb, new FontSpan(t.mono), a, b);
                    set(sb, new BackgroundColorSpan(t.inlineCodeBg), a, b);
                    set(sb, new ForegroundColorSpan(t.inlineCodeText), a, b);
                    break;
                case Markdown.CODE_BLOCK:
                    set(sb, new FontSpan(t.mono), a, b);
                    set(sb, new ForegroundColorSpan(t.codeText), a, b);
                    set(sb, new RelativeSizeSpan(0.9f), a, b);
                    set(sb, new BlockSpan(t.codeBg, (int) (10 * density)), a, b);
                    blocks.add(s);
                    break;
                case Markdown.H1:
                case Markdown.H2:
                case Markdown.H3: {
                    float size = s.type == Markdown.H1 ? 1.28f : s.type == Markdown.H2 ? 1.16f : 1.07f;
                    set(sb, new RelativeSizeSpan(size), a, b);
                    set(sb, new FontSpan(t.bodyBold), a, b);
                    if (t.hud && s.type != Markdown.H3) set(sb, new ForegroundColorSpan(t.accent), a, b);
                    break;
                }
                case Markdown.QUOTE:
                    set(sb, new QuoteSpan(t.accent), a, b);
                    set(sb, new ForegroundColorSpan(t.dim), a, b);
                    break;
                case Markdown.LINK:
                    set(sb, new SafeLinkSpan(s.url, t.link), a, b);
                    links = true;
                    break;
                case Markdown.TABLE:
                    set(sb, new FontSpan(t.mono), a, b);
                    set(sb, new RelativeSizeSpan(0.88f), a, b);
                    break;
                case Markdown.BULLET:
                    set(sb, new ForegroundColorSpan(t.accent), a, b);
                    break;
                case Markdown.RULE:
                    set(sb, new ForegroundColorSpan(t.faint), a, b);
                    break;
                default:
                    break;
            }
        }
        // Header line with the language and a COPY button above each code block.
        // Inserted last (from the end) so earlier offsets stay valid.
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Markdown.Span s = blocks.get(i);
            final String code = r.text.substring(s.start, s.end);
            String lang = s.url == null || s.url.length() == 0 ? "code" : s.url;
            String head = lang.toUpperCase(Locale.US) + "      COPY\n";
            sb.insert(s.start, head);
            int hs = s.start, he = s.start + head.length();
            set(sb, new FontSpan(t.hud ? t.labelFace : t.bodySemi), hs, he - 1);
            set(sb, new RelativeSizeSpan(0.72f), hs, he - 1);
            set(sb, new ForegroundColorSpan(t.codeHeader), hs, he - 1);
            set(sb, new BlockSpan(t.codeBg, (int) (10 * density)), hs, he);
            int cs = he - 1 - "COPY".length();
            set(sb, new CopySpan(code, t.accent), cs, he - 1);
            links = true;
        }
        if (cursor) {
            int st = sb.length();
            sb.append(st == 0 ? "▌" : " ▌");
            set(sb, new ForegroundColorSpan(t.accent), st, sb.length());
        }
        return new Rendered(sb, links);
    }

    private static void set(SpannableStringBuilder sb, Object span, int a, int b) {
        if (a < 0 || b > sb.length() || a >= b) return;
        sb.setSpan(span, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Sets a Typeface on a range (TypefaceSpan(Typeface) needs API 28). */
    public static final class FontSpan extends MetricAffectingSpan {
        private final Typeface face;

        public FontSpan(Typeface face) {
            this.face = face;
        }

        private void apply(TextPaint tp) {
            Typeface old = tp.getTypeface();
            int style = old == null ? 0 : old.getStyle();
            tp.setTypeface(style == 0 ? face : Typeface.create(face, style));
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            apply(tp);
        }

        @Override
        public void updateMeasureState(TextPaint tp) {
            apply(tp);
        }
    }

    /** Full-width tinted background + inner padding for code blocks. */
    static final class BlockSpan implements LineBackgroundSpan, LeadingMarginSpan {
        private final int color;
        private final int pad;

        BlockSpan(int color, int pad) {
            this.color = color;
            this.pad = pad;
        }

        @Override
        public void drawBackground(Canvas c, Paint paint, int left, int right, int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lnum) {
            int old = paint.getColor();
            paint.setColor(color);
            c.drawRect(left, top, right, bottom, paint);
            paint.setColor(old);
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return pad;
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean first,
                                      android.text.Layout layout) {
        }
    }

    /** "COPY" on a code block header. */
    static final class CopySpan extends ClickableSpan {
        private final String code;
        private final int color;

        CopySpan(String code, int color) {
            this.code = code;
            this.color = color;
        }

        @Override
        public void onClick(View widget) {
            Context c = widget.getContext();
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("code", code));
                Toast.makeText(c, "Code copied", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(color);
            ds.setUnderlineText(false);
            ds.setFakeBoldText(true);
        }
    }

    /** A link that never crashes when no browser can open it. */
    static final class SafeLinkSpan extends ClickableSpan {
        private final String url;
        private final int color;

        SafeLinkSpan(String url, int color) {
            this.url = url;
            this.color = color;
        }

        @Override
        public void onClick(View widget) {
            Context c = widget.getContext();
            String u = url.matches("(?i)^[a-z][a-z0-9+.-]*:.*") ? url : "https://" + url;
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(c, "No app can open " + u, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Toast.makeText(c, "Can't open " + u, Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(color);
            ds.setUnderlineText(true);
        }
    }
}
