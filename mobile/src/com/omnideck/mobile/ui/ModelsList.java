package com.omnideck.mobile.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The model bay's column of cards. {@link #setOrder} re-sorts the cards by
 * moving them inside the column rather than removing and re-adding them, so
 * a card never leaves the window while it moves: its pulse and progress
 * animations keep running, and TalkBack focus stays on the card it was on.
 * (The order changes whenever a model is loaded, unloaded or made active.)
 */
public final class ModelsList extends LinearLayout {
    public ModelsList(Context c) {
        super(c);
        setOrientation(VERTICAL);
    }

    /**
     * Shows exactly {@code order}, top to bottom: views not in it are removed,
     * new ones are added, and the rest are moved into place.
     */
    public void setOrder(List<View> order) {
        Set<View> wanted = new HashSet<View>(order);
        for (int i = getChildCount() - 1; i >= 0; i--) {
            if (!wanted.contains(getChildAt(i))) removeViewAt(i);
        }
        boolean moved = false;
        for (int i = 0; i < order.size(); i++) {
            View v = order.get(i);
            if (i < getChildCount() && getChildAt(i) == v) continue;
            if (v.getParent() == this) {
                // Positions above i already hold order[0..i-1], so v sits further down: lift it out
                // and drop it in at i without detaching it from the window.
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                detachViewFromParent(v);
                attachViewToParent(v, i, lp);
                moved = true;
            } else {
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                addView(v, i);
            }
        }
        if (moved) {
            requestLayout();
            invalidate();
        }
    }
}
