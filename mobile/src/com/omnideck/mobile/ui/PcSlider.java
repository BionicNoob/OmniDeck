package com.omnideck.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A 0–100 instrument slider for the PC volume: a thin track with a data-colored
 * fill, a tick scale every 10% underneath and a ringed thumb. Drawn in the
 * theme's colors, so it matches Cyber, Light and Dark. Reports live movement
 * while dragging and a commit when the finger lifts (or on a key /
 * accessibility step), so the PC is only told once per gesture.
 */
public final class PcSlider extends View {
    /** Receives slider changes. */
    public interface Listener {
        /** The value moved (live, while dragging). */
        void onMove(int value);

        /** The user settled on a value: apply it. */
        void onCommit(int value);
    }

    private static final int MAX = 100;
    private static final int STEP = 5;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rr = new RectF();
    private final float d;
    private int value = -1;
    private boolean dragging;
    private Listener listener;

    public PcSlider(Context c, Theme t) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        track.setColor(Theme.alpha(t.data, t.isDark ? 0x33 : 0x2B));
        fill.setColor(t.data);
        tick.setColor(Theme.alpha(t.hud ? t.label : t.faint, 0x8C));
        tick.setStrokeWidth(Math.max(1f, d * 0.8f));
        thumb.setColor(t.hud ? t.bg : t.surface);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * d);
        ring.setColor(t.data);
        halo.setColor(Theme.alpha(t.data, 0x2E));
        setFocusable(true);
        setClickable(true);
        setContentDescription("PC volume");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Current value (0..100), or -1 while unknown. */
    public int value() {
        return value;
    }

    public boolean isDragging() {
        return dragging;
    }

    /** Sets the value from outside (ignored mid-drag so the thumb doesn't jump under the finger). */
    public void setValue(int v) {
        if (dragging) return;
        value = v < 0 ? -1 : Math.min(MAX, v);
        invalidate();
    }

    private float pad() {
        return 13 * d;
    }

    private float xFor(int v) {
        return pad() + (getWidth() - pad() * 2) * v / (float) MAX;
    }

    private void moveTo(float x) {
        float span = Math.max(1, getWidth() - pad() * 2);
        int v = Math.round(Math.max(0, Math.min(1, (x - pad()) / span)) * MAX);
        if (v != value) {
            value = v;
            invalidate();
            if (listener != null) listener.onMove(v);
        }
    }

    private void step(int delta) {
        int v = Math.max(0, Math.min(MAX, Math.max(0, value) + delta));
        value = v;
        invalidate();
        if (listener != null) {
            listener.onMove(v);
            listener.onCommit(v);
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize((int) (200 * d), w), resolveSize((int) (44 * d), h));
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isEnabled()) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                setPressed(true);
                moveTo(ev.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) moveTo(ev.getX());
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) moveTo(ev.getX());
                finishDrag();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishDrag();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private void finishDrag() {
        boolean was = dragging;
        dragging = false;
        setPressed(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
        if (was && value >= 0 && listener != null) listener.onCommit(value);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (isEnabled() && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            step(-STEP);
            return true;
        }
        if (isEnabled() && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            step(STEP);
            return true;
        }
        return super.onKeyDown(keyCode, ev);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                0, MAX, Math.max(0, value)));
        if (isEnabled()) {
            if (value < MAX) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (value > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (isEnabled() && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            step(STEP);
            return true;
        }
        if (isEnabled() && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            step(-STEP);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), p = pad();
        float cy = h * 0.40f, th = 4 * d;
        rr.set(p, cy - th / 2, w - p, cy + th / 2);
        c.drawRoundRect(rr, th / 2, th / 2, track);
        // Tick scale: every 10%, taller at 0 / 50 / 100.
        float ty = cy + 11 * d;
        for (int i = 0; i <= 10; i++) {
            float x = p + (w - p * 2) * i / 10f;
            float len = (i % 5 == 0 ? 5 : 3) * d;
            c.drawLine(x, ty, x, ty + len, tick);
        }
        if (value < 0) return;
        float x = xFor(value);
        rr.set(p, cy - th / 2, Math.max(p + th, x), cy + th / 2);
        c.drawRoundRect(rr, th / 2, th / 2, fill);
        if (dragging) c.drawCircle(x, cy, 13 * d, halo);
        float r = 7 * d;
        c.drawCircle(x, cy, r, thumb);
        c.drawCircle(x, cy, r - ring.getStrokeWidth() / 2, ring);
        if (!isEnabled()) return;
        c.drawCircle(x, cy, 2 * d, fill);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
    }

    /** The x coordinate (in this view) where the thumb sits for value {@code v}. */
    public float positionOf(int v) {
        return xFor(Math.max(0, Math.min(MAX, v)));
    }
}
