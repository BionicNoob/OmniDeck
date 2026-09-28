package com.omnideck.mobile.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * A wrapping row for chips (the PC tab's tool list and recent apps): children
 * are laid out left to right and continue on the next line when a row is full.
 */
public final class PcFlow extends ViewGroup {
    private final int hGap;
    private final int vGap;

    public PcFlow(Context c, int hGapPx, int vGapPx) {
        super(c);
        hGap = hGapPx;
        vGap = vGapPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        boolean bounded = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED;
        int x = 0, y = 0, rowH = 0, widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            ch.measure(MeasureSpec.makeMeasureSpec(bounded ? maxW : 0, bounded ? MeasureSpec.AT_MOST
                    : MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
            if (bounded && x > 0 && x + w > maxW) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            x += w + hGap;
            rowH = Math.max(rowH, h);
            widest = Math.max(widest, x - hGap);
        }
        int h = y + rowH + getPaddingTop() + getPaddingBottom();
        int w = bounded && MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec)
                : widest + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(w, resolveSize(h, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
            if (x > 0 && x + w > maxW) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            int left = getPaddingLeft() + x, top = getPaddingTop() + y;
            ch.layout(left, top, left + w, top + h);
            x += w + hGap;
            rowH = Math.max(rowH, h);
        }
    }
}
