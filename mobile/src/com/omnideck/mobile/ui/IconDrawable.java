package com.omnideck.mobile.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small vector icons drawn in code, so they look identical on every phone. */
public final class IconDrawable extends Drawable {
    public static final int SEND = 1;
    public static final int STOP = 2;
    public static final int MENU = 3;
    public static final int LOGO = 4;
    public static final int CHEVRON = 5;
    public static final int DOWN = 6;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int intrinsic;

    public IconDrawable(int kind, int color, int color2, int intrinsicPx) {
        this.kind = kind;
        this.intrinsic = intrinsicPx;
        paint.setColor(color);
        paint2.setColor(color2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setColors(int color, int color2) {
        paint.setColor(color);
        paint2.setColor(color2);
        invalidateSelf();
    }

    @Override
    public int getIntrinsicWidth() {
        return intrinsic;
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsic;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float x0 = b.left + (b.width() - s) / 2f, y0 = b.top + (b.height() - s) / 2f;
        path.reset();
        switch (kind) {
            case SEND: {
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(x0 + s * 0.22f, y0 + s * 0.2f);
                path.lineTo(x0 + s * 0.84f, y0 + s * 0.5f);
                path.lineTo(x0 + s * 0.22f, y0 + s * 0.8f);
                path.lineTo(x0 + s * 0.33f, y0 + s * 0.5f);
                path.close();
                c.drawPath(path, paint);
                break;
            }
            case STOP: {
                paint.setStyle(Paint.Style.FILL);
                float r = s * 0.06f;
                c.drawRoundRect(new RectF(x0 + s * 0.3f, y0 + s * 0.3f, x0 + s * 0.7f, y0 + s * 0.7f), r, r, paint);
                break;
            }
            case MENU: {
                paint.setStyle(Paint.Style.FILL);
                float r = s * 0.075f;
                c.drawCircle(x0 + s * 0.5f, y0 + s * 0.26f, r, paint);
                c.drawCircle(x0 + s * 0.5f, y0 + s * 0.5f, r, paint);
                c.drawCircle(x0 + s * 0.5f, y0 + s * 0.74f, r, paint);
                break;
            }
            case CHEVRON:
            case DOWN: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * (kind == DOWN ? 0.11f : 0.12f));
                float top = kind == DOWN ? 0.36f : 0.38f;
                path.moveTo(x0 + s * 0.28f, y0 + s * top);
                path.lineTo(x0 + s * 0.5f, y0 + s * (top + 0.24f));
                path.lineTo(x0 + s * 0.72f, y0 + s * top);
                c.drawPath(path, paint);
                break;
            }
            case LOGO:
            default: {
                // Segmented ring + core, like the launcher icon.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                float w = s * 0.13f;
                paint.setStrokeWidth(w);
                float r = s * 0.36f;
                RectF oval = new RectF(x0 + s / 2 - r, y0 + s / 2 - r, x0 + s / 2 + r, y0 + s / 2 + r);
                for (int start = -35; start < 325; start += 90) c.drawArc(oval, start, 70, false, paint);
                paint2.setStyle(Paint.Style.FILL);
                c.drawCircle(x0 + s / 2, y0 + s / 2, s * 0.12f, paint2);
                paint.setStrokeCap(Paint.Cap.ROUND);
                break;
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        paint2.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        paint2.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
