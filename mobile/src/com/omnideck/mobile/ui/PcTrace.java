package com.omnideck.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * A CPU load trace on a fixed 0–100% scale (so 30% always looks like 30%),
 * with dashed quarter gridlines. Like a task manager, the newest sample sits
 * at the right edge and history scrolls left, up to {@code capacity} samples.
 */
public final class PcTrace extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint();
    private final Path path = new Path();
    private final Path fill = new Path();
    private final int capacity;
    private final float d;
    private double[] data = new double[0];
    private int color;

    public PcTrace(Context c, int color, int gridColor, int capacity) {
        super(c);
        this.capacity = Math.max(2, capacity);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1f, 0.7f * d));
        grid.setColor(gridColor);
        grid.setPathEffect(new DashPathEffect(new float[]{2 * d, 3 * d}, 0));
        base.setColor(gridColor);
        base.setStrokeWidth(Math.max(1f, 0.7f * d));
        setColor(color);
    }

    public void setColor(int c) {
        if (c == color && area.getShader() != null) return;
        color = c;
        line.setColor(c);
        dot.setColor(c);
        area.setShader(null);
        invalidate();
    }

    /** Samples in percent (0..100), oldest first. */
    public void setData(double[] values) {
        data = values == null ? new double[0] : values;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        area.setShader(null);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float pad = 3 * d;
        float top = pad, bottom = h - base.getStrokeWidth();
        for (int q = 1; q <= 3; q++) {
            float y = bottom - (bottom - top) * q / 4f;
            c.drawLine(0, y, w, y, grid);
        }
        c.drawLine(0, bottom, w, bottom, base);
        int n = Math.min(data.length, capacity);
        if (n == 0) return;
        float step = (w - pad * 2) / (capacity - 1);
        path.reset();
        fill.reset();
        float x = 0, y = 0, x0 = 0;
        for (int i = 0; i < n; i++) {
            double v = Math.max(0, Math.min(100, data[data.length - n + i]));
            x = w - pad - (n - 1 - i) * step;
            y = (float) (bottom - (bottom - top) * v / 100.0);
            if (i == 0) {
                x0 = x;
                path.moveTo(x, y);
                fill.moveTo(x, bottom);
                fill.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        if (n > 1) {
            fill.lineTo(x, bottom);
            fill.lineTo(x0, bottom);
            fill.close();
            if (area.getShader() == null) {
                area.setShader(new LinearGradient(0, top, 0, bottom, (color & 0x00FFFFFF) | 0x4D000000,
                        color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            }
            c.drawPath(fill, area);
            c.drawPath(path, line);
        }
        c.drawCircle(x, y, 2.6f * d, dot);
    }
}
