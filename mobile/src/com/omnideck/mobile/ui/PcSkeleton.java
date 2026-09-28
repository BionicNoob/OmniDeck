package com.omnideck.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.LinearInterpolator;

/**
 * Placeholder for a PC card while the link is being checked: the shape of
 * the Vitals card (gauge, trace, a 2×2 tile grid) or the Controls card
 * (volume slider, presets, screen well) in soft bars, drawn inside a card
 * background (set by the caller, cap band included). A slow shimmer sweeps
 * across while it's wanted and actually on screen ({@link Widgets.Animated}).
 */
public final class PcSkeleton extends Widgets.Animated {
    public static final int VITALS = 0;
    public static final int CONTROLS = 1;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint well = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private final int kind;
    private final int sheenColor;
    private final float capInset;
    private boolean shimmer;
    private float phase = -1;

    /**
     * {@code capInsetDp}: where the cap title starts (Cyber leads it with a
     * status dot). Colors: soft bars, fainter wells, and the shimmer's peak.
     */
    public PcSkeleton(Context c, int kind, int barColor, int wellColor, int sheenColor, float capInsetDp) {
        super(c);
        this.kind = kind;
        this.sheenColor = sheenColor;
        d = c.getResources().getDisplayMetrics().density;
        capInset = capInsetDp * d;
        bar.setColor(barColor);
        well.setColor(wellColor);
        ring.setColor(barColor);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(5 * d);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Whether the shimmer should run (the view also has to be on screen). */
    public void setShimmer(boolean on) {
        if (shimmer == on) return;
        shimmer = on;
        syncLoop();
    }

    @Override
    protected boolean wantsLoop() {
        return shimmer;
    }

    @Override
    protected ValueAnimator makeLoop() {
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(1600);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator v) {
                phase = (Float) v.getAnimatedValue();
                invalidate();
            }
        });
        return a;
    }

    @Override
    protected void onLoopStopped() {
        phase = -1;
    }

    @Override
    protected void onMeasure(int w, int h) {
        float height = kind == VITALS ? 392 : 318;
        setMeasuredDimension(resolveSize((int) (320 * d), w), resolveSize((int) (height * d), h));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int clear = sheenColor & 0x00FFFFFF;
        sheen.setShader(new LinearGradient(0, 0, w * 0.35f, 0, new int[]{clear, sheenColor, clear},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    private void pill(Canvas c, float l, float t, float w, float h) {
        r.set(l, t, l + w, t + h);
        c.drawRoundRect(r, Math.min(h / 2, 4 * d), Math.min(h / 2, 4 * d), bar);
    }

    private void box(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        c.drawRoundRect(r, 8 * d, 8 * d, well);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), pad = 14 * d, right = w - pad, inner = right - pad;
        // Cap title, centered in the 34dp band the card background draws.
        pill(c, pad + capInset, 13 * d, kind == VITALS ? 58 * d : 72 * d, 8 * d);
        float y = 46 * d;
        if (kind == VITALS) {
            pill(c, pad, y, inner * 0.42f, 9 * d);                       // OS line
            y += 21 * d;
            box(c, pad, y, right, y + 112 * d);                           // CPU well
            float cx = pad + 56 * d, cy = y + 56 * d;
            r.set(cx - 38 * d, cy - 38 * d, cx + 38 * d, cy + 38 * d);
            c.drawArc(r, 150, 240, false, ring);                          // gauge arc
            float tx = pad + 116 * d, tw = right - 12 * d - tx;
            pill(c, tx, y + 14 * d, tw * 0.55f, 8 * d);                   // "Processor load"
            pill(c, tx, y + 44 * d, tw, 3 * d);                           // trace lines
            pill(c, tx, y + 60 * d, tw, 3 * d);
            pill(c, tx, y + 76 * d, tw, 3 * d);
            pill(c, tx, y + 92 * d, tw * 0.6f, 7 * d);                    // avg / peak
            y += 124 * d;
            float tile = (inner - 10 * d) / 2f;
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    float l = pad + col * (tile + 10 * d);
                    box(c, l, y, l + tile, y + 94 * d);
                    pill(c, l + 12 * d, y + 12 * d, tile * 0.42f, 8 * d);      // label
                    pill(c, l + 12 * d, y + 32 * d, tile * 0.34f, 18 * d);     // value
                    pill(c, l + 12 * d, y + 62 * d, tile - 24 * d, 4 * d);     // meter
                    pill(c, l + 12 * d, y + 76 * d, tile * 0.55f, 7 * d);      // detail
                }
                y += 104 * d;
            }
        } else {
            pill(c, pad, y + 4 * d, 16 * d, 16 * d);                      // section icon
            pill(c, pad + 26 * d, y + 8 * d, inner * 0.24f, 8 * d);       // "Volume"
            pill(c, right - 36 * d, y + 4 * d, 36 * d, 16 * d);           // level
            y += 40 * d;
            pill(c, pad + 12 * d, y, inner - 24 * d, 4 * d);              // slider track
            y += 22 * d;
            box(c, pad, y, right, y + 36 * d);                            // presets
            y += 52 * d;
            pill(c, pad, y, inner, Math.max(1, d));                       // section rule
            y += 17 * d;
            pill(c, pad, y + 4 * d, 16 * d, 16 * d);
            pill(c, pad + 26 * d, y + 8 * d, inner * 0.2f, 8 * d);       // "Screen"
            pill(c, right - 84 * d, y, 84 * d, 24 * d);                   // capture
            y += 36 * d;
            box(c, pad, y, right, y + 96 * d);                            // viewport
        }
        if (phase >= 0) {
            float span = w * 1.35f;
            int save = c.save();
            c.translate(-w * 0.35f + span * phase, 0);
            c.drawRect(0, 0, w * 0.35f, getHeight(), sheen);
            c.restoreToCount(save);
        }
    }
}
