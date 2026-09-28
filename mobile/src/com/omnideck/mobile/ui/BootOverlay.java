package com.omnideck.mobile.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import java.util.Locale;

/**
 * The cold-start "systems check": the emblem's rings draw in, the name and a
 * status line appear over a thin progress rail (like the web app's home
 * transition loader), then it fades away. About a second; tap to skip.
 */
public final class BootOverlay extends View {
    private static final long RUN_MS = 950;
    private static final long FADE_MS = 220;
    private static final String[] STEPS = {"Initializing", "Link", "Models", "Telemetry", "Voice", "Ready"};

    private final Theme t;
    private final float d;
    private final Backdrop backdrop;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint status = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private ValueAnimator anim;
    private float p;
    private boolean finishing;

    public BootOverlay(Context c, Theme t) {
        super(c);
        this.t = t;
        d = c.getResources().getDisplayMetrics().density;
        backdrop = new Backdrop(t, t.hud ? 22 * d : 0);
        setBackground(backdrop);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.BUTT);
        ring.setColor(t.hud ? t.accent : t.data);
        core.setColor(t.hud ? 0xFFBAE6FD : t.data);
        glow.setColor(Theme.alpha(t.accent, 0x40));
        title.setTypeface(t.display);
        title.setColor(t.inkStrong);
        title.setTextAlign(Paint.Align.CENTER);
        title.setTextSize(t.hud ? 17 * d : 20 * d);
        title.setLetterSpacing(t.hud ? 0.22f : 0.02f);
        status.setTypeface(t.mono);
        status.setColor(t.dim);
        status.setTextAlign(Paint.Align.CENTER);
        status.setTextSize(11 * d);
        status.setLetterSpacing(0.12f);
        rail.setStrokeCap(Paint.Cap.ROUND);
        rail.setStrokeWidth(2 * d);
        setClickable(true);
        setContentDescription("Starting up, tap to skip");
    }

    /** Adds the overlay on top of {@code parent} and runs it; it removes itself when done. */
    public void play(ViewGroup parent) {
        parent.addView(this, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(RUN_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                p = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                finish();
            }
        });
        anim.start();
    }

    /** Freezes the sequence at {@code progress} (0..1) — for previews and tests. */
    public void setProgress(float progress) {
        p = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }

    /** Fades out and detaches. Safe to call more than once. */
    public void finish() {
        if (finishing) return;
        finishing = true;
        if (anim != null) anim.cancel();
        p = 1f;
        invalidate();
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(new Runnable() {
            @Override
            public void run() {
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) parent.removeView(BootOverlay.this);
            }
        }).start();
    }

    private static float seg(float p, float from, float to) {
        return Math.max(0f, Math.min(1f, (p - from) / (to - from)));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * 0.42f;
        float r = Math.min(w, h) * 0.17f;
        int full = t.hud ? 0xFF : 0xE6;

        // Outer hairline ring sweeps in.
        ring.setStrokeWidth(Math.max(1f, d));
        ring.setAlpha(full * 140 / 255);
        rf.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rf, -90, 360 * seg(p, 0f, 0.42f), false, ring);

        // Fine ticks fade in.
        float ticks = seg(p, 0.15f, 0.5f);
        if (ticks > 0) {
            ring.setAlpha((int) (full * 0.45f * ticks));
            for (int k = 0; k < 48; k++) {
                double a = Math.toRadians(k * 7.5);
                float r1 = r * (k % 6 == 0 ? 0.86f : 0.91f);
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                c.drawLine(cx + r1 * cos, cy + r1 * sin, cx + r * 0.95f * cos, cy + r * 0.95f * sin, ring);
            }
        }

        // Four segments, one after another.
        ring.setStrokeWidth(r * 0.12f);
        float sr = r * 0.70f;
        rf.set(cx - sr, cy - sr, cx + sr, cy + sr);
        for (int i = 0; i < 4; i++) {
            float s = seg(p, 0.18f + i * 0.08f, 0.38f + i * 0.08f);
            if (s <= 0) continue;
            ring.setAlpha((int) (full * s));
            c.drawArc(rf, -38 + i * 90, 76 * s, false, ring);
        }

        // Inner ring + core.
        float inner = seg(p, 0.45f, 0.65f);
        if (inner > 0) {
            ring.setStrokeWidth(Math.max(1f, r * 0.035f));
            ring.setAlpha((int) (full * 0.8f * inner));
            c.drawCircle(cx, cy, r * 0.46f, ring);
            if (t.hud) {
                glow.setAlpha((int) (0x40 * inner));
                c.drawCircle(cx, cy, r * 0.34f, glow);
            }
            core.setAlpha((int) (255 * inner));
            c.drawCircle(cx, cy, r * 0.22f * (0.6f + 0.4f * inner), core);
        }

        // Name and status line.
        float text = seg(p, 0.3f, 0.6f);
        title.setAlpha((int) (255 * text));
        c.drawText("OMNI-DECK", cx, cy + r + 44 * d, title);
        int step = Math.min(STEPS.length - 1, (int) (p * STEPS.length));
        String line = STEPS[step];
        status.setAlpha((int) (255 * text));
        c.drawText(t.hud ? line.toUpperCase(Locale.US) + (step < STEPS.length - 1 ? " …" : "")
                : line + (step < STEPS.length - 1 ? "…" : ""), cx, cy + r + 70 * d, status);

        // Progress rail.
        float rw = Math.min(w * 0.46f, 180 * d);
        float ry = cy + r + 88 * d;
        rail.setColor(t.hud ? t.hair : t.hairSoft);
        c.drawLine(cx - rw / 2, ry, cx + rw / 2, ry, rail);
        rail.setColor(t.hud ? t.accent : t.data);
        c.drawLine(cx - rw / 2, ry, cx - rw / 2 + rw * p, ry, rail);
    }
}
