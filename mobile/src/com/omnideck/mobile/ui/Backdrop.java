package com.omnideck.mobile.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The app's backdrop, after the web app's home screen: a radial gradient
 * centred a little above the middle (#0a1622 → #050a12 → #030509 in Cyber),
 * plus in Cyber the 22dp hairline grid and a soft cyan bloom off the top.
 */
public final class Backdrop extends Drawable {
    private final Paint base = new Paint();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final int center, mid, edge, bloomColor;
    private final float step;

    public Backdrop(Theme t, float gridStepPx) {
        if (t.id == Theme.CYBER) {
            center = 0xFF0A1622;
            mid = 0xFF050A12;
            edge = 0xFF030509;
        } else if (t.id == Theme.LIGHT) {
            center = 0xFFFFFFFF;
            mid = 0xFFF5F5F5;
            edge = 0xFFEDEDED;
        } else {
            center = 0xFF161A1F;
            mid = 0xFF12161B;
            edge = 0xFF0F1216;
        }
        base.setColor(edge);
        // The gradients span only a few color steps per channel (Dark: 7–9);
        // dithering keeps them from drawing as concentric bands.
        glow.setDither(true);
        bloom.setDither(true);
        step = gridStepPx;
        grid.setColor(t.gridColor);
        grid.setStrokeWidth(1f);
        bloomColor = t.hud ? t.bloomColor : 0;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        if (b.width() <= 0 || b.height() <= 0) return;
        float cx = b.exactCenterX();
        float cy = b.top + b.height() * 0.38f;
        float r = (float) Math.hypot(Math.max(cx - b.left, b.right - cx), Math.max(cy - b.top, b.bottom - cy));
        glow.setShader(new RadialGradient(cx, cy, r, new int[]{center, mid, edge}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        if (bloomColor != 0) {
            float br = b.width() * 0.9f;
            bloom.setShader(new RadialGradient(cx, b.top - br * 0.25f, br,
                    new int[]{bloomColor, bloomColor & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        c.drawRect(b, base);
        c.drawRect(b, glow);
        if (bloomColor != 0) c.drawRect(b, bloom);
        if (step > 0 && grid.getAlpha() > 0) {
            for (float x = b.left + step; x < b.right; x += step) c.drawLine(x, b.top, x, b.bottom, grid);
            for (float y = b.top + step; y < b.bottom; y += step) c.drawLine(b.left, y, b.right, y, grid);
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
