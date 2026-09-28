package com.omnideck.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Instrument-grade controls for the Settings screen, drawn from the active
 * theme's tokens so they read the same in Cyber, Light and Dark:
 * a fader-style {@link Slider}, a {@link Segmented} control, a numeric
 * {@link Stepper}, miniature {@link ThemePreview}s and a few extra
 * {@link Glyph} icons (minus, eye) in the app's line-icon style.
 */
public final class SettingsWidgets {
    private SettingsWidgets() {}

    /** Solid version of a (possibly translucent) color over a base. */
    static int opaque(int c, int base) {
        int al = (c >>> 24) & 0xFF;
        if (al == 0xFF) return c;
        int r = (((c >> 16) & 0xFF) * al + ((base >> 16) & 0xFF) * (255 - al)) / 255;
        int g = (((c >> 8) & 0xFF) * al + ((base >> 8) & 0xFF) * (255 - al)) / 255;
        int b = ((c & 0xFF) * al + (base & 0xFF) * (255 - al)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------
    // Slider
    // ------------------------------------------------------------------

    /**
     * A SeekBar over a float range with a hairline track, tick marks, an
     * optional "default" notch and a knob in the theme's style (a reticle in
     * Cyber, a solid knob in Light/Dark). It can show an "unset" state (the
     * model's own default is used): the fill disappears and the knob goes
     * hollow until the user moves it. Keeps SeekBar's accessibility.
     */
    public static final class Slider extends SeekBar {
        /** {@code done} is true when the user lets go (or a key/accessibility step lands). */
        public interface OnValue {
            void changed(float value, boolean done);
        }

        private final Theme t;
        private final float min, step, density;
        private final int ticks;
        private float marker = Float.NaN;
        private boolean unset;
        private boolean binding;
        private boolean tracking;
        private OnValue listener;

        public Slider(Context c, Theme t, float min, float max, float step, int ticks) {
            super(c);
            this.t = t;
            this.min = min;
            this.step = step;
            this.ticks = ticks;
            this.density = c.getResources().getDisplayMetrics().density;
            setMax(Math.round((max - min) / step));
            setBackground(null);
            setSplitTrack(false);
            setProgressDrawable(new Track());
            setThumb(new Knob());
            int pad = Math.round(12 * density);
            setPadding(pad, 0, pad, 0);
            setMinimumHeight(Math.round(40 * density));
            setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (binding) return;
                    unset = false;
                    invalidate();
                    if (listener != null) listener.changed(value(), !tracking);
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                    tracking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    tracking = false;
                    // A tap on the knob's own spot moves nothing but still sets the value.
                    unset = false;
                    invalidate();
                    if (listener != null) listener.changed(value(), true);
                }
            });
        }

        public void setOnValue(OnValue l) {
            listener = l;
        }

        /** A small notch under the track (e.g. where the model's default sits). */
        public void setMarker(float v) {
            marker = v;
            invalidate();
        }

        public float value() {
            return Math.round((min + getProgress() * step) * 1000f) / 1000f;
        }

        /** Sets the knob without calling the listener; {@code unsetState} draws it hollow. */
        public void bind(float v, boolean unsetState) {
            binding = true;
            setProgress(Math.max(0, Math.min(getMax(), Math.round((v - min) / step))));
            binding = false;
            unset = unsetState;
            invalidate();
        }

        public boolean isUnset() {
            return unset;
        }

        private float fraction() {
            return getMax() > 0 ? getProgress() / (float) getMax() : 0f;
        }

        /** The track: rest line, filled part, ticks and the default notch. */
        private final class Track extends Drawable {
            private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();

            Track() {
                rest.setColor(t.hud ? Theme.alpha(t.accent, 0x2E) : t.isDark ? 0x2EFFFFFF : 0x24171717);
                fill.setColor(t.accent);
                tick.setStrokeWidth(Math.max(1f, density));
            }

            @Override
            protected boolean onLevelChange(int level) {
                invalidateSelf();
                return true;
            }

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                float h = (t.hud ? 2f : 3f) * density;
                float cy = b.exactCenterY();
                r.set(b.left, cy - h / 2, b.right, cy + h / 2);
                c.drawRoundRect(r, h / 2, h / 2, rest);
                // Ticks below the track, like a fader's scale.
                if (ticks > 1) {
                    tick.setColor(t.hud ? Theme.alpha(t.accent, 0x40) : t.isDark ? 0x33FFFFFF : 0x2E171717);
                    float y0 = cy + 7 * density, y1 = y0 + 4 * density;
                    for (int i = 0; i <= ticks; i++) {
                        float x = b.left + (b.width() - 1) * (i / (float) ticks);
                        c.drawLine(x, y0, x, (i == 0 || i == ticks || i * 2 == ticks) ? y1 + 2 * density : y1, tick);
                    }
                }
                if (!Float.isNaN(marker) && getMax() > 0) {
                    float f = (marker - min) / (step * getMax());
                    float x = b.left + b.width() * Math.max(0, Math.min(1, f));
                    tick.setColor(t.hud ? t.engaged : t.dim);
                    c.drawLine(x, cy - 8 * density, x, cy - 4 * density, tick);
                }
                if (!unset) {
                    r.set(b.left, cy - h / 2, b.left + b.width() * fraction(), cy + h / 2);
                    if (r.width() > 0) c.drawRoundRect(r, h / 2, h / 2, fill);
                }
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        }

        /** The knob: a reticle in Cyber, a solid knob in Light / Dark, hollow while unset. */
        private final class Knob extends Drawable {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final int size = Math.round(22 * density);

            @Override
            public int getIntrinsicWidth() {
                return size;
            }

            @Override
            public int getIntrinsicHeight() {
                return size;
            }

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                float cx = b.exactCenterX(), cy = b.exactCenterY();
                float rOuter = 8.5f * density;
                int base = opaque(t.surface, t.bg);
                if (unset) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(base);
                    c.drawCircle(cx, cy, rOuter, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.4f * density);
                    p.setColor(t.faint);
                    c.drawCircle(cx, cy, rOuter - 0.7f * density, p);
                    return;
                }
                if (t.hud) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(opaque(t.surface2, t.bg));
                    c.drawCircle(cx, cy, rOuter, p);
                    p.setColor(Theme.alpha(t.accent, 0x33));
                    c.drawCircle(cx, cy, rOuter - 1.5f * density, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * density);
                    p.setColor(t.accent);
                    c.drawCircle(cx, cy, rOuter - 0.75f * density, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy, 2.4f * density, p);
                } else {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(t.isDark ? t.bg : 0x1F000000);
                    c.drawCircle(cx, cy + (t.isDark ? 0 : 0.6f * density), rOuter + 0.8f * density, p);
                    p.setColor(t.isDark ? t.accent : 0xFFFFFFFF);
                    c.drawCircle(cx, cy, rOuter, p);
                    p.setColor(t.isDark ? t.onAccent : t.accent);
                    c.drawCircle(cx, cy, 3f * density, p);
                }
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        }
    }

    // ------------------------------------------------------------------
    // Segmented control
    // ------------------------------------------------------------------

    /**
     * A row of mutually exclusive options in a recessed slot. The active
     * option is an accent-edged tint in Cyber (the web's "quiet control
     * strip") and a solid accent pill in Light / Dark. Each option's content
     * description is "{group}: {label}".
     */
    public static final class Segmented extends LinearLayout {
        public interface OnSelect {
            void selected(int index);
        }

        private final Ui ui;
        private final Theme t;
        private final TextView[] cells;
        private int selected = -1;
        private OnSelect listener;

        public Segmented(final Ui ui, String group, String[] labels) {
            super(ui.c);
            this.ui = ui;
            this.t = ui.t;
            setOrientation(HORIZONTAL);
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            setBackground(ui.rounded(t.input, t.hud ? t.hair : t.edge, t.hud ? 8 : 9));
            cells = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView v = ui.text(t.hud ? labels[i].toUpperCase(Locale.US) : labels[i], t.hud ? 10 : 13,
                        t.dim, t.hud ? t.labelFace : t.bodySemi);
                if (t.hud) v.setLetterSpacing(0.1f);
                v.setGravity(Gravity.CENTER);
                v.setSingleLine(true);
                v.setEllipsize(TextUtils.TruncateAt.END);
                v.setMinHeight(ui.dp(32));
                v.setPadding(ui.dp(4), 0, ui.dp(4), 0);
                v.setContentDescription(group + ": " + labels[i]);
                v.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        ui.tick(view);
                        if (idx == selected) return;
                        setSelectedIndex(idx);
                        if (listener != null) listener.selected(idx);
                    }
                });
                cells[i] = v;
                addView(v, new LayoutParams(0, ui.dp(32), 1));
            }
        }

        public void setOnSelect(OnSelect l) {
            listener = l;
        }

        public int selectedIndex() {
            return selected;
        }

        /** Highlights {@code index} (or none with -1) without calling the listener. */
        public void setSelectedIndex(int index) {
            selected = index;
            for (int i = 0; i < cells.length; i++) {
                boolean on = i == index;
                TextView v = cells[i];
                v.setSelected(on);
                if (on) {
                    if (t.hud) {
                        v.setBackground(ui.rounded(Theme.alpha(t.accent, 0x2E), Theme.alpha(t.accent, 0xB3), 6));
                        v.setTextColor(t.accent);
                    } else {
                        v.setBackground(ui.rounded(t.accent, 0, 7));
                        v.setTextColor(t.onAccent);
                    }
                } else {
                    v.setBackground(null);
                    v.setTextColor(t.dim);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Stepper
    // ------------------------------------------------------------------

    /**
     * [−] value [+] in a recessed slot, over either a linear range or a fixed
     * ladder of values. Tapping the value asks for an exact number.
     */
    public static final class Stepper extends LinearLayout {
        public interface Format {
            String format(int value);
        }

        public interface OnStep {
            void stepped(int value);
        }

        private final TextView readout;
        private final ImageView minus, plus;
        private final Theme t;
        private int[] ladder;
        private int min, max, step = 1;
        private int value;
        private Format format;
        private OnStep listener;

        public Stepper(final Ui ui, String name, final Runnable onEdit) {
            super(ui.c);
            this.t = ui.t;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(ui.rounded(t.input, t.hud ? t.hair : t.edge, 8));
            minus = cellButton(ui, Glyph.MINUS, "Decrease " + name, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    move(-1);
                }
            });
            addView(minus, new LayoutParams(ui.dp(34), ui.dp(34)));
            addView(rule(ui), new LayoutParams(Math.max(1, ui.dp(1)), ui.dp(18)));
            readout = ui.text("", 13.5f, t.ink, t.mono);
            readout.setGravity(Gravity.CENTER);
            readout.setSingleLine(true);
            readout.setMinWidth(ui.dp(64));
            readout.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            readout.setContentDescription("Edit " + name);
            readout.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    if (onEdit != null) onEdit.run();
                }
            });
            addView(readout, new LayoutParams(LayoutParams.WRAP_CONTENT, ui.dp(34)));
            addView(rule(ui), new LayoutParams(Math.max(1, ui.dp(1)), ui.dp(18)));
            plus = cellButton(ui, Glyph.PLUS, "Increase " + name, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.tick(v);
                    move(1);
                }
            });
            addView(plus, new LayoutParams(ui.dp(34), ui.dp(34)));
        }

        private View rule(Ui ui) {
            View v = new View(ui.c);
            v.setBackgroundColor(t.hud ? t.hair : t.edge);
            return v;
        }

        private ImageView cellButton(Ui ui, int glyph, String desc, OnClickListener l) {
            ImageView v = new ImageView(ui.c);
            v.setImageDrawable(new Glyph(glyph, t.hud ? t.accent : t.ink, ui.dp(16)));
            v.setScaleType(ImageView.ScaleType.CENTER);
            v.setContentDescription(desc);
            TypedValue tv = new TypedValue();
            ui.c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            if (tv.resourceId != 0) v.setBackground(ui.c.getDrawable(tv.resourceId));
            v.setOnClickListener(l);
            return v;
        }

        public Stepper range(int min, int max, int step) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.ladder = null;
            return this;
        }

        public Stepper ladder(int[] values) {
            this.ladder = values;
            this.min = values[0];
            this.max = values[values.length - 1];
            return this;
        }

        public Stepper format(Format f) {
            format = f;
            return this;
        }

        public void setOnStep(OnStep l) {
            listener = l;
        }

        public int value() {
            return value;
        }

        /** Shows {@code v} without calling the listener. */
        public void bind(int v) {
            value = v;
            readout.setText(format != null ? format.format(v) : String.valueOf(v));
            minus.setAlpha(v <= min ? 0.35f : 1f);
            plus.setAlpha(v >= max ? 0.35f : 1f);
        }

        private void move(int dir) {
            int next = value;
            if (ladder != null) {
                if (dir > 0) {
                    for (int l : ladder) {
                        if (l > value) {
                            next = l;
                            break;
                        }
                    }
                } else {
                    for (int i = ladder.length - 1; i >= 0; i--) {
                        if (ladder[i] < value) {
                            next = ladder[i];
                            break;
                        }
                    }
                }
            } else {
                next = Math.max(min, Math.min(max, value + dir * step));
            }
            if (next == value) return;
            bind(next);
            if (listener != null) listener.stepped(next);
        }
    }

    // ------------------------------------------------------------------
    // Theme preview
    // ------------------------------------------------------------------

    /**
     * A miniature of the app in one theme — backdrop, top bar, a card with
     * its cap and a sample of the display face, a meter, a chat bubble and
     * the command bar — drawn from that theme's own tokens. With two themes
     * it shows each on one half (the "System" choice). Selected: an accent
     * ring and a check badge in the current theme's accent.
     */
    public static final class ThemePreview extends View {
        private final Theme cur;
        private final Theme left, right;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        /** The miniature's screen area (kept apart from {@link #r}, which the drawing reuses). */
        private final RectF frame = new RectF();
        private final Path clip = new Path();
        private final float d;
        private boolean chosen;
        private Backdrop backLeft, backRight;
        private final IconDrawable check;

        /** {@code current} is the app's active theme (ring/badge colors). */
        public ThemePreview(Context c, Theme current, Theme left, Theme right) {
            super(c);
            this.cur = current;
            this.left = left;
            this.right = right;
            this.d = c.getResources().getDisplayMetrics().density;
            check = new IconDrawable(IconDrawable.CHECK, current.onAccent, current.onAccent, Math.round(10 * d));
            check.stroke(3f);
        }

        public void setChosen(boolean on) {
            chosen = on;
            setSelected(on);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            backLeft = new Backdrop(left, left.hud ? 5 * d : 0);
            backRight = right == null ? null : new Backdrop(right, right.hud ? 5 * d : 0);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float inset = 3 * d;
            float rad = 9 * d;
            frame.set(inset, inset, w - inset, h - inset);
            int save = c.save();
            clip.reset();
            clip.addRoundRect(frame, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            if (right == null) {
                mini(c, left, backLeft, frame);
            } else {
                int s2 = c.save();
                c.clipRect(frame.left, frame.top, frame.centerX(), frame.bottom);
                mini(c, left, backLeft, frame);
                c.restoreToCount(s2);
                s2 = c.save();
                c.clipRect(frame.centerX(), frame.top, frame.right, frame.bottom);
                mini(c, right, backRight, frame);
                c.restoreToCount(s2);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x66808890);
                c.drawRect(frame.centerX() - d * 0.5f, frame.top, frame.centerX() + d * 0.5f, frame.bottom, p);
            }
            c.restoreToCount(save);

            // Frame: accent ring when chosen, else a hairline.
            p.setStyle(Paint.Style.STROKE);
            if (chosen) {
                p.setStrokeWidth(2f * d);
                p.setColor(cur.accent);
                r.set(d, d, w - d, h - d);
                c.drawRoundRect(r, rad + 2 * d, rad + 2 * d, p);
            } else {
                p.setStrokeWidth(Math.max(1f, d));
                p.setColor(cur.hud ? cur.edge : cur.isDark ? cur.edgeStrong : cur.edge);
                r.set(inset, inset, w - inset, h - inset);
                c.drawRoundRect(r, rad, rad, p);
            }
            if (chosen) {
                float br = 8 * d;
                float cx = w - inset - br - 3 * d, cy = inset + br + 3 * d;
                p.setStyle(Paint.Style.FILL);
                p.setColor(cur.accent);
                c.drawCircle(cx, cy, br, p);
                int s = Math.round(10 * d);
                check.setBounds(Math.round(cx - s / 2f), Math.round(cy - s / 2f), Math.round(cx + s / 2f),
                        Math.round(cy + s / 2f));
                check.draw(c);
            }
        }

        /** Draws one theme's miniature app into {@code b}. */
        private void mini(Canvas c, Theme t, Backdrop back, RectF b) {
            float w = b.width(), h = b.height();
            float x0 = b.left, y0 = b.top;
            if (back != null) {
                back.setBounds(Math.round(b.left), Math.round(b.top), Math.round(b.right), Math.round(b.bottom));
                back.draw(c);
            }
            p.setStyle(Paint.Style.FILL);
            // Top bar + its rule.
            float barH = h * 0.13f;
            p.setColor(opaque(t.topbar, t.bg));
            c.drawRect(x0, y0, x0 + w, y0 + barH, p);
            p.setColor(t.hud ? Theme.alpha(t.accent, 0x99) : t.isDark ? opaque(t.hair, t.topbar) : t.edge);
            c.drawRect(x0, y0 + barH - Math.max(1, d * 0.8f), x0 + w, y0 + barH, p);
            float cy = y0 + barH / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f * d);
            p.setColor(t.accent);
            c.drawCircle(x0 + w * 0.13f, cy, barH * 0.22f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.hud ? t.inkStrong : t.accent2);
            c.drawCircle(x0 + w * 0.13f, cy, barH * 0.08f, p);
            p.setColor(t.inkStrong);
            bar(c, x0 + w * 0.25f, cy - barH * 0.07f, w * 0.3f, barH * 0.14f);
            p.setColor(Theme.alpha(t.ok, 0x33));
            r.set(x0 + w * 0.66f, cy - barH * 0.2f, x0 + w * 0.9f, cy + barH * 0.2f);
            c.drawRoundRect(r, barH * 0.2f, barH * 0.2f, p);
            p.setColor(t.ok);
            c.drawCircle(x0 + w * 0.71f, cy, barH * 0.07f, p);

            // A card with a cap band, a sample of the display face and a meter.
            float cl = x0 + w * 0.08f, cr = x0 + w * 0.92f, ct = y0 + h * 0.19f, cb = y0 + h * 0.55f;
            float rad = 4 * d;
            r.set(cl, ct, cr, cb);
            p.setColor(t.surface);
            c.drawRoundRect(r, rad, rad, p);
            float capH = h * 0.075f;
            int s = c.save();
            c.clipRect(cl, ct, cr, ct + capH);
            p.setColor(t.cap);
            c.drawRoundRect(r, rad, rad, p);
            c.restoreToCount(s);
            p.setColor(t.hud ? Theme.alpha(t.accent, 0x40) : t.edge);
            c.drawRect(cl, ct + capH - Math.max(1, d * 0.6f), cr, ct + capH, p);
            if (t.hud) {
                p.setColor(t.accent);
                c.drawRect(cl + w * 0.06f, ct + capH * 0.3f, cl + w * 0.06f + 1.5f * d, ct + capH * 0.7f, p);
            }
            p.setColor(t.label);
            bar(c, cl + w * (t.hud ? 0.11f : 0.06f), ct + capH / 2f - capH * 0.1f, w * 0.34f, capH * 0.2f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, d * 0.8f));
            p.setColor(t.edge);
            c.drawRoundRect(r, rad, rad, p);
            if (t.hud) {
                p.setColor(t.bracketColor);
                p.setStrokeWidth(1.1f * d);
                float L = 4 * d, in = 2 * d;
                c.drawLine(cl + in, ct + in, cl + in + L, ct + in, p);
                c.drawLine(cl + in, ct + in, cl + in, ct + in + L, p);
                c.drawLine(cr - in, cb - in, cr - in - L, cb - in, p);
                c.drawLine(cr - in, cb - in, cr - in, cb - in - L, p);
            }
            p.setStyle(Paint.Style.FILL);
            // Sample text in the theme's display face.
            text.setTypeface(t.display != null ? t.display : Typeface.DEFAULT_BOLD);
            text.setColor(t.inkStrong);
            text.setTextSize(h * 0.11f);
            float by = ct + capH + (cb - ct - capH) * 0.52f;
            c.drawText("Aa", cl + w * 0.07f, by, text);
            float tx = cl + w * 0.07f + text.measureText("Aa") + w * 0.06f;
            p.setColor(Theme.alpha(t.ink, 0xB3));
            bar(c, tx, by - h * 0.07f, cr - tx - w * 0.06f, h * 0.022f);
            p.setColor(Theme.alpha(t.dim, 0x99));
            bar(c, tx, by - h * 0.025f, (cr - tx - w * 0.06f) * 0.7f, h * 0.022f);
            float my = cb - (cb - ct) * 0.17f;
            p.setColor(t.hud ? Theme.alpha(t.accent, 0x2E) : t.isDark ? 0x2EFFFFFF : 0x1F171717);
            bar(c, cl + w * 0.07f, my, (cr - cl) - w * 0.14f, h * 0.022f);
            p.setColor(t.data);
            bar(c, cl + w * 0.07f, my, ((cr - cl) - w * 0.14f) * 0.62f, h * 0.022f);

            // Chat: the user bubble and an AI reply.
            r.set(x0 + w * 0.42f, y0 + h * 0.6f, x0 + w * 0.92f, y0 + h * 0.69f);
            p.setColor(t.userFill);
            c.drawRoundRect(r, 3.5f * d, 3.5f * d, p);
            p.setColor(Theme.alpha(t.userText, 0xB3));
            bar(c, r.left + w * 0.06f, r.centerY() - h * 0.011f, r.width() * 0.62f, h * 0.022f);
            float ax = x0 + w * (t.hud ? 0.12f : 0.08f);
            if (t.hud) {
                p.setColor(t.aiBar);
                c.drawRect(x0 + w * 0.08f, y0 + h * 0.735f, x0 + w * 0.08f + d, y0 + h * 0.83f, p);
            }
            p.setColor(Theme.alpha(t.aiText, 0x99));
            bar(c, ax, y0 + h * 0.745f, w * 0.7f, h * 0.022f);
            bar(c, ax, y0 + h * 0.79f, w * 0.52f, h * 0.022f);

            // Command bar.
            float navT = y0 + h * 0.88f;
            p.setColor(opaque(t.nav, t.bg));
            c.drawRect(x0, navT, x0 + w, y0 + h, p);
            p.setColor(t.hud ? t.hair : t.isDark ? opaque(t.hair, t.nav) : t.edge);
            c.drawRect(x0, navT, x0 + w, navT + Math.max(1, d * 0.6f), p);
            for (int i = 0; i < 4; i++) {
                float nx = x0 + w * (0.125f + i * 0.25f);
                p.setColor(i == 1 ? t.accent : Theme.alpha(t.dim, 0x99));
                c.drawCircle(nx, navT + (y0 + h - navT) * 0.5f, h * 0.018f, p);
            }
        }

        private void bar(Canvas c, float x, float y, float w, float h) {
            r.set(x, y, x + Math.max(0, w), y + h);
            c.drawRoundRect(r, h / 2, h / 2, p);
        }
    }

    // ------------------------------------------------------------------
    // Extra icons
    // ------------------------------------------------------------------

    /** Line glyphs the shared icon set lacks, in the same 24-unit stroke style. */
    public static final class Glyph extends Drawable {
        public static final int MINUS = 1;
        public static final int PLUS = 2;
        public static final int EYE = 3;
        public static final int EYE_OFF = 4;

        private final int kind;
        private final int size;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        public Glyph(int kind, int color, int sizePx) {
            this.kind = kind;
            this.size = sizePx;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(1.8f);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            int save = c.save();
            float s = Math.min(b.width(), b.height()) / 24f;
            c.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s);
            c.scale(s, s);
            switch (kind) {
                case MINUS:
                    c.drawLine(6, 12, 18, 12, p);
                    break;
                case PLUS:
                    c.drawLine(6, 12, 18, 12, p);
                    c.drawLine(12, 6, 12, 18, p);
                    break;
                default:
                    path.reset();
                    path.moveTo(2, 12);
                    path.quadTo(12, 2.5f, 22, 12);
                    path.quadTo(12, 21.5f, 2, 12);
                    path.close();
                    c.drawPath(path, p);
                    c.drawCircle(12, 12, 3, p);
                    if (kind == EYE_OFF) c.drawLine(4, 4, 20, 20, p);
                    break;
            }
            c.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
