package com.omnideck.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;

import java.util.Locale;

/** Small custom-drawn instruments for the command center. */
public final class Widgets {
    private Widgets() {}

    /**
     * The one "no data yet" look shared by every metric instrument: a dashed
     * hairline with a small micro-caps note ("NO SAMPLES") centred on it.
     */
    static final class EmptyMark {
        private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private boolean styled;
        String label = "No samples";

        EmptyMark(Context c, int lineColor) {
            d = c.getResources().getDisplayMetrics().density;
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(Math.max(1f, d));
            dash.setColor(lineColor);
            dash.setPathEffect(new DashPathEffect(new float[]{4 * d, 4 * d}, 0));
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(8.5f * d);
            text.setColor(lineColor);
        }

        /** Fonts and ink come from the activity's theme (looked up once, on first draw). */
        private void style(View v) {
            if (styled) return;
            styled = true;
            Theme t = Theme.from(v.getContext());
            if (t == null) return;
            text.setTypeface(t.labelFace);
            text.setColor(t.faint);
            text.setLetterSpacing(t.hud ? 0.16f : 0.06f);
            text.setTextSize((t.hud ? 8 : 9) * d);
            dash.setColor(t.hud ? t.edge : t.isDark ? t.hair : t.edge);
        }

        String shown() {
            return label.toUpperCase(Locale.US);
        }

        /** A dashed line across [x0, x1] at y, broken around the label centred at cx. */
        void drawLine(View v, Canvas c, float x0, float x1, float y, float cx) {
            style(v);
            String s = shown();
            float half = text.measureText(s) / 2f + 7 * d;
            if (cx - half > x0) c.drawLine(x0, y, cx - half, y, dash);
            if (cx + half < x1) c.drawLine(cx + half, y, x1, y, dash);
            drawText(c, cx, y);
        }

        /** The label alone, vertically centred on {@code y}. */
        void drawText(Canvas c, float cx, float y) {
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(shown(), cx, y - (fm.ascent + fm.descent) / 2f, text);
        }

        Paint dashPaint(View v) {
            style(v);
            return dash;
        }
    }

    /** A line chart of recent samples with a soft gradient fill underneath. */
    public static final class Sparkline extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint base = new Paint();
        private final Path p = new Path();
        private final Path fillPath = new Path();
        private final EmptyMark empty;
        private double[] data = new double[0];
        private int color;
        private double floor = 0;

        public Sparkline(Context c, int color, int baseline) {
            super(c);
            this.color = color;
            float d = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.6f * d);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(color);
            dot.setColor(color);
            base.setColor(baseline);
            base.setStrokeWidth(Math.max(1f, d * 0.6f));
            empty = new EmptyMark(c, baseline);
            setContentDescription(empty.label);
        }

        public void setColor(int c) {
            color = c;
            line.setColor(c);
            dot.setColor(c);
            area.setShader(null);
            invalidate();
        }

        /** Values below this are treated as the chart floor (default 0). */
        public void setFloor(double f) {
            floor = f;
        }

        /** The note shown while there is no data (default "No samples"). */
        public void setEmptyLabel(String s) {
            empty.label = s == null ? "" : s;
            if (data.length == 0) setContentDescription(empty.label);
            invalidate();
        }

        public void setData(double[] values) {
            data = values == null ? new double[0] : values;
            setContentDescription(data.length == 0 ? empty.label : null);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            area.setShader(null);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (data.length == 0) {
                empty.drawLine(this, c, 0, w, h / 2f, w / 2f);
                return;
            }
            float pad = line.getStrokeWidth() * 2;
            c.drawLine(0, h - base.getStrokeWidth() / 2, w, h - base.getStrokeWidth() / 2, base);
            double max = floor, min = floor;
            for (double v : data) {
                if (v > max) max = v;
                if (v < min) min = v;
            }
            if (max - min < 1e-9) max = min + 1;
            int n = data.length;
            float step = n > 1 ? (w - pad * 2) / (n - 1) : 0;
            p.reset();
            fillPath.reset();
            float lx = pad, ly = h / 2f;
            for (int i = 0; i < n; i++) {
                float x = n > 1 ? pad + i * step : w / 2f;
                float y = (float) (h - pad - (data[i] - min) / (max - min) * (h - pad * 2));
                if (i == 0) {
                    p.moveTo(x, y);
                    fillPath.moveTo(x, h);
                    fillPath.lineTo(x, y);
                } else {
                    p.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
                lx = x;
                ly = y;
            }
            fillPath.lineTo(lx, h);
            fillPath.close();
            if (area.getShader() == null) {
                area.setShader(new LinearGradient(0, 0, 0, h, (color & 0x00FFFFFF) | 0x40000000,
                        color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            }
            if (n > 1) {
                c.drawPath(fillPath, area);
                c.drawPath(p, line);
            }
            c.drawCircle(lx, ly, line.getStrokeWidth() * 1.6f, dot);
        }
    }

    /** A 240° arc gauge (0..1) — context fill, CPU, RAM. */
    public static final class Gauge extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final EmptyMark empty;
        private float fraction = -1;
        private float shown;
        private ValueAnimator anim;

        public Gauge(Context c, int trackColor, int valueColor) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(5 * d);
            track.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(trackColor);
            value.setStyle(Paint.Style.STROKE);
            value.setStrokeWidth(5 * d);
            value.setStrokeCap(Paint.Cap.ROUND);
            value.setColor(valueColor);
            ticks.setColor(trackColor);
            ticks.setStrokeWidth(Math.max(1, d));
            empty = new EmptyMark(c, trackColor);
            setContentDescription(empty.label);
        }

        public void setValueColor(int c) {
            value.setColor(c);
            invalidate();
        }

        /** The note shown while there is no data (default "No samples"). */
        public void setEmptyLabel(String s) {
            empty.label = s == null ? "" : s;
            if (fraction < 0) setContentDescription(empty.label);
            invalidate();
        }

        /** 0..1, or negative for "no data". */
        public void setFraction(float f, boolean animate) {
            float target = f < 0 ? -1 : Math.max(0, Math.min(1, f));
            if (target == fraction) return;
            fraction = target;
            setContentDescription(target < 0 ? empty.label : null);
            if (anim != null) anim.cancel();
            float to = Math.max(0, target);
            if (!animate) {
                shown = to;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(shown, to);
            anim.setDuration(450);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    shown = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float sw = track.getStrokeWidth();
            float size = Math.min(getWidth(), getHeight()) - sw * 2;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            oval.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            if (fraction < 0) {
                // No data: the track as a dashed hairline, the note in the gauge's open bottom.
                c.drawArc(oval, 150, 240, false, empty.dashPaint(this));
                empty.drawText(c, cx, cy + size * 0.40f);
                return;
            }
            c.drawArc(oval, 150, 240, false, track);
            if (shown > 0.002f) c.drawArc(oval, 150, 240 * shown, false, value);
        }
    }

    /**
     * Base for instruments with a looping animation: the loop only runs while
     * the view is really on screen (attached, its window visible, every
     * ancestor shown), so a hidden tab or a backgrounded app costs no frames.
     */
    abstract static class Animated extends View {
        private ValueAnimator loop;

        Animated(Context c) {
            super(c);
        }

        /** Whether the loop is wanted right now (the subclass's own state). */
        abstract boolean wantsLoop();

        /** A fresh loop animator (INFINITE repeat), not started. */
        abstract ValueAnimator makeLoop();

        /** Called when the loop stops (reset the frame to a static look). */
        void onLoopStopped() {}

        final void syncLoop() {
            boolean run = wantsLoop() && isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
            if (run && loop == null) {
                loop = makeLoop();
                loop.start();
            } else if (!run && loop != null) {
                loop.cancel();
                loop = null;
                onLoopStopped();
                invalidate();
            }
        }

        /** True while the loop animator runs (tests and previews). */
        public final boolean isAnimating() {
            return loop != null;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            syncLoop();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            syncLoop();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            syncLoop();
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            syncLoop();
        }
    }

    /** A thin horizontal meter (VRAM, RAM, download progress). */
    public static final class Meter extends Animated {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rr = new RectF();
        private float fraction;
        private boolean indeterminate;
        private float phase;

        public Meter(Context c, int trackColor, int barColor) {
            super(c);
            track.setColor(trackColor);
            bar.setColor(barColor);
        }

        public void setBarColor(int c) {
            bar.setColor(c);
            invalidate();
        }

        public void setFraction(float f) {
            fraction = Math.max(0, Math.min(1, f));
            setIndeterminate(false);
            invalidate();
        }

        public void setIndeterminate(boolean on) {
            if (indeterminate == on) return;
            indeterminate = on;
            syncLoop();
            invalidate();
        }

        @Override
        boolean wantsLoop() {
            return indeterminate;
        }

        @Override
        ValueAnimator makeLoop() {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(1200);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
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
            float h = getHeight(), w = getWidth(), rad = h / 2f;
            rr.set(0, 0, w, h);
            c.drawRoundRect(rr, rad, rad, track);
            if (indeterminate) {
                float seg = w * 0.3f;
                float x = -seg + (w + seg) * phase;
                rr.set(Math.max(0, x), 0, Math.min(w, x + seg), h);
                if (rr.width() > 0) c.drawRoundRect(rr, rad, rad, bar);
            } else if (fraction > 0) {
                rr.set(0, 0, Math.max(h, w * fraction), h);
                c.drawRoundRect(rr, rad, rad, bar);
            }
        }
    }

    /**
     * An on/off switch drawn in the theme's colors. The track, edge, knob
     * color and knob size each blend between their off and on values as it
     * slides, so one class draws Cyber's glowing hub switch, Light's
     * outlined switch and Dark's monochrome one (see {@link Ui#toggle}).
     */
    public static final class Toggle extends View {
        public interface OnChange {
            void changed(boolean on);
        }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rr = new RectF();
        private final float d;
        private boolean on;
        private float pos;
        private OnChange listener;
        private int trackOff, trackOn, knobOff, knobOn, edgeOff, edgeOn, glowColor;
        private float knobOffSize = 0.72f, knobOnSize = 0.72f;
        private ValueAnimator slide;

        public Toggle(Context c, int onColor, int offColor, int knobColor, int edgeColor) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            trackOn = onColor;
            trackOff = offColor;
            knobOff = knobOn = knobColor;
            edgeOff = edgeOn = edgeColor;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(d);
            setClickable(true);
            setFocusable(true);
            setContentDescription("Toggle");
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setChecked(!on, true);
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                    if (listener != null) listener.changed(on);
                }
            });
        }

        public void setOnChange(OnChange l) {
            listener = l;
        }

        /** Different knob colors off/on (Dark's monochrome switch: light knob off, dark knob on). */
        public void setKnobColors(int off, int onColor) {
            knobOff = off;
            knobOn = onColor;
            invalidate();
        }

        /** Edge color off/on (0 = no edge); e.g. Light's outlined off state, Cyber's cyan rim when on. */
        public void setEdgeColors(int off, int onColor) {
            edgeOff = off;
            edgeOn = onColor;
            invalidate();
        }

        public void setEdgeWidth(float px) {
            edge.setStrokeWidth(px);
            invalidate();
        }

        /** Knob diameter as a fraction of the track height, off and on. */
        public void setKnobSizes(float off, float onSize) {
            knobOffSize = off;
            knobOnSize = onSize;
            invalidate();
        }

        /** A soft halo around the knob when on (Cyber); 0 = none. */
        public void setGlow(int color) {
            glowColor = color;
            invalidate();
        }

        public boolean isChecked() {
            return on;
        }

        public void setChecked(boolean checked, boolean animate) {
            on = checked;
            if (slide != null) slide.cancel();
            if (!animate) {
                pos = on ? 1 : 0;
                invalidate();
                return;
            }
            slide = ValueAnimator.ofFloat(pos, on ? 1 : 0);
            slide.setDuration(140);
            slide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    pos = (Float) va.getAnimatedValue();
                    invalidate();
                }
            });
            slide.start();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(on);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (slide != null) slide.end();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(resolveSize((int) (44 * d), w), resolveSize((int) (26 * d), h));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            // The web hub switch: a 38×21 track with a 15px knob.
            float th = Math.min(h * 0.82f, (w - 2) * 21f / 38f);
            float tw = th * 38f / 21f;
            float left = (w - tw) / 2f, top = (h - th) / 2f, rad = th / 2f;
            rr.set(left, top, left + tw, top + th);
            track.setColor(blend(trackOff, trackOn, pos));
            c.drawRoundRect(rr, rad, rad, track);
            int ec = blend(edgeOff, edgeOn, pos);
            if ((ec >>> 24) != 0 && edge.getStrokeWidth() > 0) {
                edge.setColor(ec);
                float in = edge.getStrokeWidth() / 2f;
                rr.inset(in, in);
                c.drawRoundRect(rr, rad - in, rad - in, edge);
                rr.inset(-in, -in);
            }
            float kr = th * (knobOffSize + (knobOnSize - knobOffSize) * pos) / 2f;
            float kx = left + rad + (tw - rad * 2) * pos;
            if (glowColor != 0 && pos > 0) {
                glow.setColor(glowColor);
                glow.setAlpha((int) (((glowColor >>> 24) & 0xFF) * pos));
                c.drawCircle(kx, h / 2f, kr + 2.5f * d, glow);
            }
            knob.setColor(blend(knobOff, knobOn, pos));
            c.drawCircle(kx, h / 2f, kr, knob);
        }
    }

    /**
     * A status dot. Pulsing, it either sends out a halo (default) or breathes
     * its opacity 1 → .25 like the web app's hudPulse ({@link #setFade}). The
     * pulse only animates while the dot is actually on screen.
     */
    public static final class StatusDot extends Animated {
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float t = 1;
        private boolean pulsing;
        private boolean fade;
        private float coreFraction = 0.5f;

        public StatusDot(Context c) {
            super(c);
        }

        public void setColor(int color) {
            core.setColor(color);
            halo.setColor(color);
            invalidate();
        }

        /** Breathe (opacity 1 → .25 over 2.6 s) instead of sending out a halo. */
        public void setFade(boolean f) {
            if (fade == f) return;
            fade = f;
            if (pulsing) {
                pulsing = false;
                syncLoop();
                pulsing = true;
                syncLoop();
            }
        }

        /** Core diameter as a fraction of the view (default .5, leaving room for the halo). */
        public void setCoreFraction(float f) {
            coreFraction = f;
            invalidate();
        }

        public boolean isPulsing() {
            return pulsing;
        }

        public void setPulsing(boolean p) {
            if (pulsing == p) return;
            pulsing = p;
            syncLoop();
            invalidate();
        }

        @Override
        boolean wantsLoop() {
            return pulsing;
        }

        @Override
        ValueAnimator makeLoop() {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(fade ? 2600 : 1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    t = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            return anim;
        }

        @Override
        void onLoopStopped() {
            t = 1;
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) * coreFraction;
            int a = 255;
            if (pulsing && fade && isAnimating()) {
                double wave = 0.5 + 0.5 * Math.cos(2 * Math.PI * t);
                a = (int) (255 * (0.25 + 0.75 * wave));
            } else if (pulsing && !fade) {
                halo.setAlpha((int) (110 * (1 - t)));
                c.drawCircle(cx, cy, r + (Math.min(cx, cy) - r) * t, halo);
            }
            core.setAlpha(a);
            c.drawCircle(cx, cy, r, core);
        }
    }

    /** The HUD's short dashed "data rail" drawn after a cap title (4dp dash, 7dp gap). */
    public static final class Rail extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Rail(Context c, int color) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, d));
            p.setPathEffect(new DashPathEffect(new float[]{4 * d, 7 * d}, 0));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas c) {
            float y = getHeight() / 2f;
            c.drawLine(0, y, getWidth(), y, p);
        }
    }

    /**
     * The cyber HUD's travelling scan line — a faint horizontal band that
     * sweeps top to bottom every few seconds. Draws nothing when stopped.
     */
    public static final class ScanLine extends View {
        private final Paint band = new Paint();
        private ValueAnimator anim;
        private float y = -1;
        private final int color;

        public ScanLine(Context c, int color) {
            super(c);
            this.color = color;
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;
        }

        public void start() {
            if (anim != null) return;
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(7000);
            anim.setStartDelay(600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    y = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        public void stop() {
            if (anim != null) anim.cancel();
            anim = null;
            y = -1;
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (y < 0) return;
            float h = getHeight();
            float bandH = 90 * getResources().getDisplayMetrics().density;
            if (band.getShader() == null) {
                // Built once; each frame only moves it (no per-frame allocation).
                int rgb = color & 0x00FFFFFF;
                band.setShader(new LinearGradient(0, 0, 0, bandH, new int[]{rgb, color, rgb},
                        new float[]{0f, 0.85f, 1f}, Shader.TileMode.CLAMP));
            }
            float top = -bandH + (h + bandH) * y;
            // Fade in/out at the ends like hudScan's 12% / 88% keyframes.
            float a = y < 0.12f ? y / 0.12f : y > 0.88f ? (1 - y) / 0.12f : 1f;
            band.setAlpha((int) (255 * a));
            int save = c.save();
            c.translate(0, top);
            c.drawRect(0, 0, getWidth(), bandH, band);
            c.restoreToCount(save);
        }
    }

    /** Linear blend of two ARGB colors. */
    static int blend(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * f) << 24) | ((int) (ar + (br - ar) * f) << 16)
                | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }
}
