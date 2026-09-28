package com.omnideck.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The model bay's disk-allocation strip: one segment per installed model,
 * sized by its share of the disk, coloured by state (active / loaded /
 * stored), with hairline gaps between segments — like a storage map on an
 * instrument panel.
 */
public final class ModelsStorageBar extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF r = new RectF();
    private final float gap;
    private long[] sizes = new long[0];
    private int[] colors = new int[0];

    public ModelsStorageBar(Context c, int trackColor, float gapPx) {
        super(c);
        track.setColor(trackColor);
        this.gap = gapPx;
    }

    /** One entry per segment, in drawing order (left to right). */
    public void setSegments(long[] sizes, int[] colors) {
        this.sizes = sizes == null ? new long[0] : sizes;
        this.colors = colors == null ? new int[0] : colors;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension(resolveSize((int) (120 * d), w), resolveSize((int) (6 * d), h));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = h / 2f;
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, track);
        long total = 0;
        for (long s : sizes) total += Math.max(0, s);
        if (total <= 0) return;
        int save = c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        float x = 0;
        int n = Math.min(sizes.length, colors.length);
        for (int i = 0; i < n; i++) {
            float sw = (float) (w * (Math.max(0, sizes[i]) / (double) total));
            float left = x + (i > 0 ? gap / 2f : 0);
            float right = x + sw - (i < n - 1 ? gap / 2f : 0);
            // Keep tiny models visible as a sliver.
            if (right - left < h * 0.5f) right = left + h * 0.5f;
            seg.setColor(colors[i]);
            c.drawRect(left, 0, Math.min(w, right), h, seg);
            x += sw;
        }
        c.restoreToCount(save);
    }
}
