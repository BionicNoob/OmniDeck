package com.omnideck.mobile.ui;

import android.content.Context;
import android.widget.LinearLayout;

/** A vertical LinearLayout that never grows wider than a fraction of what it's offered. */
public final class BubbleLayout extends LinearLayout {
    private final float fraction;

    public BubbleLayout(Context c, float fraction) {
        super(c);
        this.fraction = fraction;
        setOrientation(VERTICAL);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = MeasureSpec.getSize(widthMeasureSpec);
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        if (mode != MeasureSpec.UNSPECIFIED && size > 0) {
            int max = (int) (size * fraction);
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(max, mode == MeasureSpec.EXACTLY
                    ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
