package com.omnideck.mobile.ui;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

/**
 * A ScrollView that stays pinned to the bottom while content grows (a reply
 * streaming in, the keyboard opening) — unless the user has scrolled up to
 * read, in which case it leaves them where they are.
 */
public final class ChatScrollView extends ScrollView {
    public interface StickListener {
        void onStickChanged(boolean stuck);
    }

    private final float slopPx;
    private boolean stick = true;
    private boolean programmatic;
    private StickListener stickListener;

    public ChatScrollView(Context c) {
        super(c);
        slopPx = 48 * c.getResources().getDisplayMetrics().density;
        setFillViewport(true);
        setVerticalScrollBarEnabled(true);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    public void setStickListener(StickListener l) {
        stickListener = l;
    }

    public boolean isStuck() {
        return stick;
    }

    /** Pins to the bottom (e.g. after the user sends a message). */
    public void stickToBottom(boolean smooth) {
        setStick(true);
        int target = maxScroll();
        if (smooth) smoothScrollTo(0, target);
        else jumpTo(target);
    }

    private int maxScroll() {
        if (getChildCount() == 0) return 0;
        View child = getChildAt(0);
        return Math.max(0, child.getHeight() + getPaddingTop() + getPaddingBottom() - getHeight());
    }

    private void jumpTo(int y) {
        programmatic = true;
        scrollTo(0, y);
        programmatic = false;
    }

    private void setStick(boolean s) {
        if (stick == s) return;
        stick = s;
        if (stickListener != null) stickListener.onStickChanged(s);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (stick) jumpTo(maxScroll());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (stick) jumpTo(maxScroll());
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (programmatic) return;
        setStick(maxScroll() - t <= slopPx);
    }
}
