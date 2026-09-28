package com.omnideck.mobile.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The command center's "AI core": an arc-reactor style instrument drawn on a
 * Canvas. From the outside in: a fine tick ring (with a fixed reticle), a
 * hairline ring, a segmented arc ring, a counter-rotating dashed ring, a thin
 * inner ring, coil blocks and a softly breathing core.
 * <p>
 * Its motion mirrors the AI ({@link #setMode}): slow rotation when idle, a
 * radar sweep while scanning, fast counter-rotation while thinking, a pulse
 * per streamed burst ({@link #pulse}), ripples while speaking, and dim broken
 * rings in the danger color when offline. Modes blend into each other.
 * <p>
 * A single ValueAnimator drives the frames, and only while
 * {@link #setRunning} is on and reduce motion is off (it also rests once the
 * offline look has settled); otherwise the core renders one static frame per
 * state change.
 */
public final class CoreView extends View {
    public static final int OFFLINE = 0;
    public static final int SCANNING = 1;
    public static final int IDLE = 2;
    public static final int THINKING = 3;
    public static final int STREAMING = 4;
    public static final int SPEAKING = 5;

    // Ring radii as fractions of the core's outer radius.
    private static final float R_TICKS = 0.965f;
    private static final float R_HAIR = 0.885f;
    private static final float R_SEG = 0.79f;
    private static final float R_DASH = 0.705f;
    private static final float R_INNER = 0.60f;
    private static final float R_COIL = 0.485f;
    private static final float R_CORE = 0.30f;

    /** Deterministic "broken ring" fragments for the offline look: start°, sweep°. */
    private static final float[] BROKEN = {0, 34, 50, 18, 82, 56, 152, 10, 178, 66, 262, 22, 298, 38};

    private final float d;
    private final int primary, data, track, coreHot, danger;
    private final boolean hudGlow;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coreFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float[] minorTicks = new float[0];
    private float[] majorTicks = new float[0];
    private float cx, cy, R;

    private int mode = IDLE;
    private boolean running;
    private boolean reduceMotion;
    private ValueAnimator loop;
    private long loopStarted;
    /** Settled offline: nothing moves, so the loop is off until the mode changes. */
    private boolean parked;
    private boolean restartPending;
    private long lastFrame;
    private long lastInvalidate;

    // Angles (degrees) and their current speeds (deg/s), eased toward the mode's targets.
    private float aTicks = -90, aSeg = -60, aDash = 20, aSweep = -90;
    private float vTicks, vSeg, vDash, vSweep;
    // Blend levels 0..1, eased toward the mode's targets.
    private float lvThink, lvSpeak, lvScan, lvOff, lvEnergy = 0.35f;
    private float breath;
    private float pulse;
    private long lastPulse;
    private final long[] waves = new long[3];
    private int waveNext;
    private long lastWave;

    public CoreView(Context c, Theme t, boolean hudEffects) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        danger = t.danger;
        if (t.id == Theme.CYBER) {
            primary = t.accent;
            data = t.accent;
            track = Theme.alpha(t.accent, 0x2E);
            coreHot = t.inkStrong;
        } else if (t.id == Theme.LIGHT) {
            primary = t.accent;
            data = t.data;
            track = t.edge;
            coreHot = t.accent;
        } else {
            primary = t.ink;
            data = t.data;
            track = Theme.alpha(t.ink, 0x26);
            coreHot = t.data;
        }
        hudGlow = t.hud && hudEffects;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        fill.setStyle(Paint.Style.FILL);
        sweep.setStyle(Paint.Style.STROKE);
        sweep.setStrokeCap(Paint.Cap.BUTT);
        setClickable(true);
        setFocusable(true);
        applyTargets(true);
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public int mode() {
        return mode;
    }

    /** One of {@link #OFFLINE} … {@link #SPEAKING}. */
    public void setMode(int m) {
        if (m == mode) return;
        mode = m;
        if (!animating()) applyTargets(true);
        if (parked) {
            parked = false;
            updateLoop();
        }
        invalidate();
    }

    /** A burst of streamed text arrived: kick the core and (rate-limited) send out a wave. */
    public void pulse() {
        if (!animating()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastPulse < 120) return;
        lastPulse = now;
        pulse = Math.min(1f, pulse + 0.55f);
        if (now - lastWave > 420) {
            lastWave = now;
            waves[waveNext] = now;
            waveNext = (waveNext + 1) % waves.length;
        }
    }

    /** Runs the animation loop (call with true only while the screen is visible). */
    public void setRunning(boolean on) {
        running = on;
        updateLoop();
    }

    /** Static rendering with no animators (Settings › Reduce motion). */
    public void setReduceMotion(boolean on) {
        if (reduceMotion == on) return;
        reduceMotion = on;
        if (on) applyTargets(true);
        updateLoop();
        invalidate();
    }

    /** Whether the frame loop is live (running, or about to restart). */
    public boolean isAnimating() {
        return loop != null || restartPending;
    }

    private boolean animating() {
        return running && !reduceMotion;
    }

    private void updateLoop() {
        boolean want = animating() && !parked && isAttachedToWindow();
        if (want && loop == null) {
            lastFrame = 0;
            loopStarted = SystemClock.uptimeMillis();
            final ValueAnimator l = ValueAnimator.ofFloat(0f, 1f);
            // Frames are timed from the clock, not the fraction, so the period only
            // matters for how often the animator repeats.
            l.setDuration(1000);
            l.setRepeatCount(ValueAnimator.INFINITE);
            l.setInterpolator(new LinearInterpolator());
            l.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    frame();
                }
            });
            l.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator an) {
                    if (an != loop) return; // stopped on purpose
                    loop = null;
                    // Ending on its own means something capped the repeats: start again
                    // shortly (posted, never from inside the frame). Ending at once
                    // means system animations are off: stay static.
                    if (SystemClock.uptimeMillis() - loopStarted < 500) {
                        applyTargets(false);
                        invalidate();
                    } else {
                        restartPending = true;
                        postDelayed(restart, 100);
                    }
                }
            });
            loop = l;
            l.start();
        } else if (!want && (loop != null || restartPending)) {
            stopLoop();
            applyTargets(false);
            invalidate();
        }
    }

    private final Runnable restart = new Runnable() {
        @Override
        public void run() {
            restartPending = false;
            updateLoop();
        }
    };

    /** Cancels the animator (clearing the field first, so its end callback knows it was on purpose). */
    private void stopLoop() {
        removeCallbacks(restart);
        restartPending = false;
        ValueAnimator l = loop;
        loop = null;
        if (l != null) l.cancel();
    }

    /**
     * Called every animator tick: advances the simulation, then invalidates —
     * throttled to ~30 fps when calm. Once the offline look has settled the
     * loop parks itself (nothing moves offline) until the mode changes.
     */
    private void frame() {
        long now = SystemClock.uptimeMillis();
        step(now);
        if (mode == OFFLINE && lvOff > 0.995f && pulse < 0.01f && Math.abs(vSeg) < 0.05f) {
            parked = true;
            stopLoop();
            invalidate();
            return;
        }
        boolean calm = mode == IDLE || mode == OFFLINE;
        if (calm && now - lastInvalidate < 32) return;
        lastInvalidate = now;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopLoop();
    }

    // Per-mode targets -------------------------------------------------

    private float tTicks() {
        switch (mode) {
            case SCANNING: return 7;
            case THINKING: return 11;
            case STREAMING: return 7;
            case SPEAKING: return 4;
            case OFFLINE: return 0;
            default: return 3;
        }
    }

    private float tSeg() {
        switch (mode) {
            case SCANNING: return -26;
            case THINKING: return -125;
            case STREAMING: return -48;
            case SPEAKING: return -14;
            case OFFLINE: return 0;
            default: return -8;
        }
    }

    private float tDash() {
        switch (mode) {
            case SCANNING: return 30;
            case THINKING: return 170;
            case STREAMING: return 64;
            case SPEAKING: return 20;
            case OFFLINE: return 0;
            default: return 13;
        }
    }

    private float tEnergy() {
        switch (mode) {
            case SCANNING: return 0.45f;
            case THINKING: return 0.62f;
            case STREAMING: return 0.72f;
            case SPEAKING: return 0.58f;
            case OFFLINE: return 0.08f;
            default: return 0.38f;
        }
    }

    private float breathPeriod() {
        switch (mode) {
            case SCANNING: return 3.0f;
            case THINKING: return 1.4f;
            case STREAMING: return 2.0f;
            case SPEAKING: return 2.4f;
            default: return 4.2f;
        }
    }

    /** Snaps (or, when not snapping, just freezes) every level at the mode's target. */
    private void applyTargets(boolean snapAngles) {
        lvThink = mode == THINKING ? 1f : mode == STREAMING ? 0.5f : 0f;
        lvSpeak = mode == SPEAKING ? 1f : 0f;
        lvScan = mode == SCANNING ? 1f : 0f;
        lvOff = mode == OFFLINE ? 1f : 0f;
        lvEnergy = tEnergy();
        pulse = 0;
        breath = 0.25f; // a static frame shows the core at its mid breath
        for (int i = 0; i < waves.length; i++) waves[i] = 0;
        if (snapAngles) {
            // A composed static frame: segments at "ten past", dashes offset.
            aTicks = -90;
            aSeg = -60;
            aDash = 20;
            aSweep = -40;
        }
        vTicks = vSeg = vDash = vSweep = 0;
    }

    /** Advances angles and blend levels by the time since the last frame. */
    private void step(long now) {
        if (lastFrame == 0) lastFrame = now;
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (dt <= 0) return;
        float kv = 1f - (float) Math.exp(-dt / 0.6f);
        float kl = 1f - (float) Math.exp(-dt / 0.35f);
        vTicks += (tTicks() - vTicks) * kv;
        vSeg += (tSeg() - vSeg) * kv;
        vDash += (tDash() - vDash) * kv;
        vSweep += ((mode == SCANNING ? 210f : 0f) - vSweep) * kv;
        aTicks = (aTicks + vTicks * dt) % 360f;
        aSeg = (aSeg + vSeg * dt) % 360f;
        aDash = (aDash + vDash * dt) % 360f;
        aSweep = (aSweep + Math.max(vSweep, lvScan * 120f) * dt) % 360f;
        lvThink += ((mode == THINKING ? 1f : mode == STREAMING ? 0.5f : 0f) - lvThink) * kl;
        lvSpeak += ((mode == SPEAKING ? 1f : 0f) - lvSpeak) * kl;
        lvScan += ((mode == SCANNING ? 1f : 0f) - lvScan) * kl;
        lvOff += ((mode == OFFLINE ? 1f : 0f) - lvOff) * kl;
        lvEnergy += (tEnergy() - lvEnergy) * kl;
        pulse *= (float) Math.exp(-dt / 0.28f);
        if (mode != OFFLINE) breath = (breath + dt / breathPeriod()) % 1f;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cx = w / 2f;
        cy = h / 2f;
        R = Math.max(1f, Math.min(w, h) / 2f - 7 * d);
        // Tick ring: 120 ticks every 3°, a longer one every 30°, precomputed around (0,0).
        int n = 120;
        int majors = n / 10;
        minorTicks = new float[(n - majors) * 4];
        majorTicks = new float[majors * 4];
        float ro = R * R_TICKS;
        int mi = 0, ma = 0;
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(i * 3.0);
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            boolean major = i % 10 == 0;
            float ri = ro - (major ? 7f : 3f) * d;
            float[] arr = major ? majorTicks : minorTicks;
            int k = major ? ma : mi;
            arr[k] = ri * cs;
            arr[k + 1] = ri * sn;
            arr[k + 2] = ro * cs;
            arr[k + 3] = ro * sn;
            if (major) ma += 4;
            else mi += 4;
        }
        glow.setShader(new RadialGradient(0, 0, R * 0.95f, new int[]{Theme.alpha(primary, 0x38),
                Theme.alpha(primary, 0x12), Theme.alpha(primary, 0)}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        coreFill.setShader(new RadialGradient(0, 0, 1f, new int[]{coreHot, Theme.alpha(coreHot, 0x99),
                Theme.alpha(data, 0x2A), Theme.alpha(data, 0x08)}, new float[]{0f, 0.28f, 0.8f, 1f},
                Shader.TileMode.CLAMP));
        float span = 85f / 360f;
        sweep.setShader(new SweepGradient(0, 0, new int[]{Theme.alpha(primary, 0), Theme.alpha(primary, 0),
                Theme.alpha(primary, 0x5C)}, new float[]{0f, 1f - span, 1f}));
    }

    private void arc(float r, float start, float sweepDeg, Paint p, Canvas c) {
        oval.set(-r, -r, r, r);
        c.drawArc(oval, start, sweepDeg, false, p);
    }

    private static int blend(int a, int b, float f) {
        return Widgets.blend(a, b, f);
    }

    private static int scaleAlpha(int color, float f) {
        int a = (color >>> 24) & 0xFF;
        return Theme.alpha(color, Math.round(a * Math.max(0f, Math.min(1f, f))));
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        if (R <= 1) return;
        long now = SystemClock.uptimeMillis();
        float off = lvOff;
        float live = 1f - off;
        int prim = blend(primary, danger, off);
        int dat = blend(data, danger, off);
        int trk = blend(track, Theme.alpha(danger, 0x33), off);
        float breathS = (float) Math.sin(breath * Math.PI * 2);
        float energy = Math.min(1f, lvEnergy + pulse * 0.45f);

        int save = c.save();
        c.translate(cx, cy);

        // Soft bloom behind the rings (Cyber HUD only; restrained).
        if (hudGlow && live > 0.01f) {
            glow.setAlpha(Math.round(255 * live * (0.55f + 0.45f * energy)));
            c.drawCircle(0, 0, R * 0.95f, glow);
        }

        // Fixed reticle: cardinal notches outside the tick ring and short horizon lines.
        stroke.setStrokeWidth(Math.max(1f, 1.1f * d));
        stroke.setColor(scaleAlpha(prim, 0.55f + 0.2f * live));
        float ro = R * R_TICKS;
        for (int i = 0; i < 4; i++) {
            c.drawLine(0, -ro - 2.5f * d, 0, -ro - 6.5f * d, stroke);
            c.rotate(90);
        }
        stroke.setColor(trk);
        stroke.setStrokeWidth(Math.max(1f, 0.8f * d));
        c.drawLine(ro + 9 * d, 0, ro + 26 * d, 0, stroke);
        c.drawLine(-ro - 9 * d, 0, -ro - 26 * d, 0, stroke);

        // Tick ring (slow rotation).
        c.save();
        c.rotate(aTicks);
        stroke.setColor(scaleAlpha(prim, 0.34f + 0.08f * energy));
        stroke.setStrokeWidth(Math.max(1f, 0.8f * d));
        c.drawLines(minorTicks, stroke);
        stroke.setColor(scaleAlpha(prim, 0.72f));
        stroke.setStrokeWidth(1.2f * d);
        c.drawLines(majorTicks, stroke);
        // A brighter index marker riding the tick ring.
        stroke.setColor(scaleAlpha(dat, 0.9f * live));
        stroke.setStrokeWidth(2f * d);
        arc(ro - 1.5f * d, -4, 8, stroke, c);
        c.restore();

        // Scanning sweep: a trailing radar wedge between the inner ring and the tick ring.
        if (lvScan > 0.01f) {
            c.save();
            c.rotate(aSweep);
            float r0 = R * R_INNER, r1 = R * R_TICKS;
            sweep.setStrokeWidth(r1 - r0);
            sweep.setAlpha(Math.round(255 * lvScan));
            arc((r0 + r1) / 2f, 0, 360, sweep, c);
            stroke.setColor(scaleAlpha(prim, 0.85f * lvScan));
            stroke.setStrokeWidth(1.3f * d);
            c.drawLine(r0, 0, r1, 0, stroke);
            c.restore();
        }

        // Hairline ring.
        stroke.setColor(trk);
        stroke.setStrokeWidth(Math.max(1f, 0.8f * d));
        c.drawCircle(0, 0, R * R_HAIR, stroke);

        // Speaking: ripples travelling out from the core.
        if (lvSpeak > 0.01f) {
            float rc = R * R_CORE;
            stroke.setStrokeWidth(1.3f * d);
            for (int k = 0; k < 3; k++) {
                float ph = ((now % 2100L) / 2100f + k / 3f) % 1f;
                float r = rc + (R * R_TICKS - rc) * ph;
                stroke.setColor(scaleAlpha(dat, 0.62f * lvSpeak * (1f - ph) * (1f - ph)));
                c.drawCircle(0, 0, r, stroke);
            }
        }

        // Segmented arc ring: three long segments; broken fragments when offline.
        float rs = R * R_SEG;
        float segW = 3.2f * d;
        if (live > 0.01f) {
            for (int pass = hudGlow ? 0 : 1; pass < 2; pass++) {
                stroke.setStrokeWidth(pass == 0 ? segW * 3.2f : segW);
                stroke.setColor(scaleAlpha(dat, pass == 0 ? 0.16f * live : 0.92f * live));
                for (int i = 0; i < 3; i++) arc(rs, aSeg + i * 120f, 98f, stroke, c);
            }
            // Fine end caps: a perpendicular tick at each segment's leading edge.
            stroke.setStrokeWidth(Math.max(1f, 1f * d));
            stroke.setColor(scaleAlpha(prim, 0.7f * live));
            for (int i = 0; i < 3; i++) {
                double a = Math.toRadians(aSeg + i * 120f);
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                c.drawLine((rs - 6 * d) * cs, (rs - 6 * d) * sn, (rs + 6 * d) * cs, (rs + 6 * d) * sn, stroke);
            }
        }
        if (off > 0.01f) {
            stroke.setStrokeWidth(segW * 0.85f);
            stroke.setColor(scaleAlpha(danger, 0.62f * off));
            for (int i = 0; i < BROKEN.length; i += 2) arc(rs, -90 + BROKEN[i], BROKEN[i + 1], stroke, c);
        }

        // Counter-rotating dashed ring (270° of dashes, so rotation reads).
        float rd = R * R_DASH;
        stroke.setStrokeWidth(Math.max(1f, 1.4f * d));
        stroke.setColor(scaleAlpha(prim, (0.28f + 0.6f * lvThink) * (0.35f + 0.65f * live)));
        for (int i = 0; i < 18; i++) arc(rd, aDash + i * 15f, 8.5f, stroke, c);

        // Thin inner ring.
        stroke.setStrokeWidth(Math.max(1f, 1f * d));
        stroke.setColor(scaleAlpha(prim, 0.5f * (0.5f + 0.5f * live)));
        c.drawCircle(0, 0, R * R_INNER, stroke);

        // Coil blocks (the reactor's ring of segments).
        float rcoil = R * R_COIL;
        float coilW = R * 0.075f;
        stroke.setStrokeWidth(coilW);
        stroke.setColor(scaleAlpha(dat, (0.10f + 0.22f * energy) * (0.4f + 0.6f * live)));
        for (int i = 0; i < 10; i++) arc(rcoil, -90 + 5 + i * 36f, 26f, stroke, c);
        stroke.setStrokeWidth(Math.max(1f, 0.9f * d));
        stroke.setColor(scaleAlpha(dat, (0.35f + 0.4f * energy) * (0.45f + 0.55f * live)));
        for (int i = 0; i < 10; i++) arc(rcoil + coilW / 2f, -90 + 5 + i * 36f, 26f, stroke, c);

        // Pulse waves from streamed bursts.
        for (int i = 0; i < waves.length; i++) {
            if (waves[i] == 0) continue;
            float p = (now - waves[i]) / 760f;
            if (p >= 1f || p < 0) {
                waves[i] = 0;
                continue;
            }
            float e = 1f - (1f - p) * (1f - p);
            float rc = R * R_CORE;
            stroke.setStrokeWidth(1.6f * d * (1f - p) + 0.6f * d);
            stroke.setColor(scaleAlpha(dat, 0.75f * (1f - p)));
            c.drawCircle(0, 0, rc + (rs - rc) * e, stroke);
        }

        // The core: breathing gradient disk with a crisp rim and an inner ring.
        float rc = R * R_CORE * (1f + 0.035f * breathS * (1f - off) + 0.09f * pulse);
        c.save();
        c.scale(rc, rc);
        coreFill.setAlpha(Math.round(255 * (0.22f + 0.78f * energy) * (0.35f + 0.65f * live)));
        c.drawCircle(0, 0, 1f, coreFill);
        c.restore();
        if (off > 0.01f) {
            fill.setColor(scaleAlpha(danger, 0.1f * off));
            c.drawCircle(0, 0, rc, fill);
        }
        stroke.setStrokeWidth(1.4f * d);
        stroke.setColor(scaleAlpha(prim, 0.85f * (0.55f + 0.45f * live)));
        c.drawCircle(0, 0, rc, stroke);
        stroke.setStrokeWidth(Math.max(1f, 0.8f * d));
        stroke.setColor(scaleAlpha(prim, 0.4f));
        c.drawCircle(0, 0, rc * 0.64f, stroke);
        fill.setColor(scaleAlpha(off > 0.5f ? danger : coreHot, 0.5f + 0.5f * energy));
        c.drawCircle(0, 0, rc * (0.2f + 0.05f * pulse), fill);

        c.restoreToCount(save);
    }
}
