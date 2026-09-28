package com.omnideck.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Small custom-drawn instruments for the command center. */
public final class Widgets {
    private Widgets() {}

    /** A line chart of recent samples with a soft gradient fill underneath. */
    public static final class Sparkline extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint base = new Paint();
        private final Path p = new Path();
        private final Path fillPath = new Path();
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

        public void setData(double[] values) {
            data = values == null ? new double[0] : values;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            area.setShader(null);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float pad = line.getStrokeWidth() * 2;
            c.drawLine(0, h - base.getStrokeWidth() / 2, w, h - base.getStrokeWidth() / 2, base);
            if (data.length == 0) return;
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
        }

        public void setValueColor(int c) {
            value.setColor(c);
            invalidate();
        }

        /** 0..1, or negative for "no data". */
        public void setFraction(float f, boolean animate) {
            float target = f < 0 ? -1 : Math.max(0, Math.min(1, f));
            if (target == fraction) return;
            fraction = target;
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
            c.drawArc(oval, 150, 240, false, track);
            if (fraction >= 0 && shown > 0.002f) c.drawArc(oval, 150, 240 * shown, false, value);
        }
    }

    /** A thin horizontal meter (VRAM, RAM, download progress). */
    public static final class Meter extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rr = new RectF();
        private float fraction;
        private boolean indeterminate;
        private float phase;
        private ValueAnimator anim;

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
            if (on) {
                anim = ValueAnimator.ofFloat(0, 1);
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
                anim.start();
            } else if (anim != null) {
                anim.cancel();
                anim = null;
            }
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
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

    /** An on/off switch drawn in the theme's colors. */
    public static final class Toggle extends View {
        public interface OnChange {
            void changed(boolean on);
        }

        private final Paint trackOn = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackOff = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rr = new RectF();
        private boolean on;
        private float pos;
        private OnChange listener;
        private int knobOff, knobOn;

        public Toggle(Context c, int onColor, int offColor, int knobColor, int edgeColor) {
            super(c);
            trackOn.setColor(onColor);
            trackOff.setColor(offColor);
            knob.setColor(knobColor);
            knobOff = knobOn = knobColor;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(c.getResources().getDisplayMetrics().density);
            edge.setColor(edgeColor);
            setClickable(true);
            setFocusable(true);
            setContentDescription("Toggle");
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setChecked(!on, true);
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

        public boolean isChecked() {
            return on;
        }

        public void setChecked(boolean checked, boolean animate) {
            on = checked;
            if (!animate) {
                pos = on ? 1 : 0;
                invalidate();
                return;
            }
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1 : 0);
            a.setDuration(140);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    pos = (Float) va.getAnimatedValue();
                    invalidate();
                }
            });
            a.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            float d = getResources().getDisplayMetrics().density;
            setMeasuredDimension(resolveSize((int) (40 * d), w), resolveSize((int) (24 * d), h));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float th = h * 0.72f, top = (h - th) / 2f, rad = th / 2f;
            rr.set(1, top, w - 1, top + th);
            c.drawRoundRect(rr, rad, rad, trackOff);
            trackOn.setAlpha((int) (255 * pos));
            c.drawRoundRect(rr, rad, rad, trackOn);
            c.drawRoundRect(rr, rad, rad, edge);
            float kr = th / 2f - 3 * getResources().getDisplayMetrics().density;
            float kx = rr.left + rad + (rr.width() - rad * 2) * pos;
            if (knobOff != knobOn) knob.setColor(blend(knobOff, knobOn, pos));
            c.drawCircle(kx, h / 2f, kr, knob);
        }
    }

    /** A status dot that pulses while "live" (like the web app's hudPulse). */
    public static final class StatusDot extends View {
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;
        private float t = 1;
        private boolean pulsing;

        public StatusDot(Context c) {
            super(c);
        }

        public void setColor(int color) {
            core.setColor(color);
            halo.setColor(color);
            invalidate();
        }

        public void setPulsing(boolean p) {
            if (pulsing == p) return;
            pulsing = p;
            if (p) {
                anim = ValueAnimator.ofFloat(0, 1);
                anim.setDuration(1600);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        t = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                anim.start();
            } else {
                if (anim != null) anim.cancel();
                anim = null;
                t = 1;
                invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) * 0.5f;
            if (pulsing) {
                halo.setAlpha((int) (110 * (1 - t)));
                c.drawCircle(cx, cy, r + (Math.min(cx, cy) - r) * t, halo);
            }
            core.setAlpha(255);
            c.drawCircle(cx, cy, r, core);
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
