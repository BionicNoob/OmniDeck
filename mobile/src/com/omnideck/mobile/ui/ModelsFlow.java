package com.omnideck.mobile.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * A row of chips that wraps onto new lines when it runs out of width
 * (capability chips, pull suggestions). Children are vertically centred
 * within each line.
 */
public final class ModelsFlow extends ViewGroup {
    private final int hGap;
    private final int vGap;

    public ModelsFlow(Context c, int hGapPx, int vGapPx) {
        super(c);
        this.hGap = hGapPx;
        this.vGap = vGapPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(widthSpec);
        int maxW = mode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int childSpec = MeasureSpec.makeMeasureSpec(Math.max(0, maxW == Integer.MAX_VALUE ? 0 : maxW),
                maxW == Integer.MAX_VALUE ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST);
        int x = 0, lineH = 0, totalH = 0, widest = 0;
        boolean any = false;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            if (x > 0 && x + hGap + w > maxW) {
                totalH += lineH + vGap;
                x = 0;
                lineH = 0;
            }
            x += (x > 0 ? hGap : 0) + w;
            lineH = Math.max(lineH, h);
            widest = Math.max(widest, x);
            any = true;
        }
        if (any) totalH += lineH;
        int w = mode == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec)
                : Math.min(widest + getPaddingLeft() + getPaddingRight(),
                mode == MeasureSpec.AT_MOST ? MeasureSpec.getSize(widthSpec) : Integer.MAX_VALUE);
        setMeasuredDimension(w, resolveSize(totalH + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = getPaddingTop();
        int lineStart = 0;
        int lineH = 0;
        int n = getChildCount();
        // Two passes per line: find the line's height, then centre each child in it.
        for (int i = 0; i < n; i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int w = v.getMeasuredWidth();
            if (x > 0 && x + hGap + w > maxW) {
                placeLine(lineStart, i, y, lineH);
                y += lineH + vGap;
                x = 0;
                lineH = 0;
                lineStart = i;
            }
            x += (x > 0 ? hGap : 0) + w;
            lineH = Math.max(lineH, v.getMeasuredHeight());
        }
        placeLine(lineStart, n, y, lineH);
    }

    private void placeLine(int from, int to, int y, int lineH) {
        int x = getPaddingLeft();
        boolean first = true;
        for (int i = from; i < to; i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            if (!first) x += hGap;
            first = false;
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            int top = y + (lineH - h) / 2;
            v.layout(x, top, x + w, top + h);
            x += w;
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }
}
