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
 * Placeholder for a model card while the list loads: the card's shape in
 * soft bars (name, spec line, chips, two controls) with an optional slow
 * shimmer sweeping across (off when motion is reduced). The shimmer is a
 * {@link Widgets.Animated} loop, so it only runs while the view is really
 * on screen.
 */
public final class ModelsSkeleton extends Widgets.Animated {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private final int sheenColor;
    private final float nameFraction;
    private boolean animating;
    private float phase = -1;

    /** {@code variant} (0..2) varies the bar lengths so a stack doesn't look cloned. */
    public ModelsSkeleton(Context c, int barColor, int sheenColor, int variant) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        bar.setColor(barColor);
        this.sheenColor = sheenColor;
        nameFraction = new float[]{0.46f, 0.58f, 0.38f}[Math.abs(variant) % 3];
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Starts or stops the shimmer. */
    public void setAnimating(boolean on) {
        if (animating == on) return;
        animating = on;
        syncLoop();
        invalidate();
    }

    /** Whether the shimmer is wanted (it runs while this is true and the view is on screen). */
    public boolean isShimmering() {
        return animating;
    }

    @Override
    protected boolean wantsLoop() {
        return animating;
    }

    @Override
    protected ValueAnimator makeLoop() {
        ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
        anim.setDuration(1600);
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
    protected void onLoopStopped() {
        phase = -1;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize((int) (300 * d), w), resolveSize((int) (128 * d), h));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int clear = sheenColor & 0x00FFFFFF;
        sheen.setShader(new LinearGradient(0, 0, w * 0.35f, 0, new int[]{clear, sheenColor, clear},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    private void pill(Canvas c, float l, float t, float w, float h, float rad) {
        r.set(l, t, l + w, t + h);
        c.drawRoundRect(r, rad, rad, bar);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float y = 2 * d;
        pill(c, 0, y, w * nameFraction, 15 * d, 4 * d);                  // name
        y += 25 * d;
        pill(c, 0, y, w * 0.72f, 9 * d, 3 * d);                           // spec line
        y += 22 * d;
        pill(c, 0, y, 58 * d, 18 * d, 4 * d);                             // chips
        pill(c, 64 * d, y, 72 * d, 18 * d, 4 * d);
        pill(c, 142 * d, y, 48 * d, 18 * d, 4 * d);
        y += 34 * d;
        float bw = (w - 8 * d) / 2f;
        pill(c, 0, y, bw, 34 * d, 8 * d);                                 // controls
        pill(c, bw + 8 * d, y, bw, 34 * d, 8 * d);
        if (phase >= 0) {
            float span = w * 1.35f;
            int save = c.save();
            c.translate(-w * 0.35f + span * phase, 0);
            c.drawRect(0, 0, w * 0.35f, getHeight(), sheen);
            c.restoreToCount(save);
        }
    }
}
