package com.omnideck.mobile.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Backgrounds in OMNI-DECK's two shape languages: chamfered (cut) corners
 * like the web app's clip-path polygons in the cyber theme, or rounded
 * corners like its modern theme. Optional hairline/dashed border and a
 * left accent bar (the AI bubble's red edge).
 */
public final class ShapeDrawable2 extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path strokePath = new Path();
    private final float[] corners; // tl, tr, br, bl
    private final boolean cut;
    private final float strokeWidth;
    private final float barWidth;

    private ShapeDrawable2(boolean cut, float[] corners, int fillColor, int strokeColor, float strokeWidth,
                           float dash, int barColor, float barWidth) {
        this.cut = cut;
        this.corners = corners;
        this.strokeWidth = strokeWidth;
        this.barWidth = barWidth;
        fill.setColor(fillColor);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(strokeColor);
        stroke.setStrokeWidth(strokeWidth);
        if (dash > 0) stroke.setPathEffect(new DashPathEffect(new float[]{dash, dash * 0.7f}, 0));
        bar.setColor(barColor);
    }

    /** Chamfered corners (cut sizes in px: tl, tr, br, bl). */
    public static ShapeDrawable2 chamfer(float tl, float tr, float br, float bl, int fillColor, int strokeColor,
                                         float strokeWidth) {
        return new ShapeDrawable2(true, new float[]{tl, tr, br, bl}, fillColor, strokeColor, strokeWidth, 0, 0, 0);
    }

    /** Rounded corners (radii in px: tl, tr, br, bl). */
    public static ShapeDrawable2 round(float tl, float tr, float br, float bl, int fillColor, int strokeColor,
                                       float strokeWidth) {
        return new ShapeDrawable2(false, new float[]{tl, tr, br, bl}, fillColor, strokeColor, strokeWidth, 0, 0, 0);
    }

    public ShapeDrawable2 dashed(float dashPx) {
        return new ShapeDrawable2(cut, corners, fill.getColor(), stroke.getColor(), strokeWidth, dashPx,
                bar.getColor(), barWidth);
    }

    public ShapeDrawable2 withBar(int color, float widthPx) {
        return new ShapeDrawable2(cut, corners, fill.getColor(), stroke.getColor(), strokeWidth, 0, color, widthPx);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        build(path, new RectF(b));
        float h = strokeWidth / 2f;
        build(strokePath, new RectF(b.left + h, b.top + h, b.right - h, b.bottom - h));
    }

    private void build(Path p, RectF r) {
        p.reset();
        float max = Math.min(r.width(), r.height()) / 2f;
        float tl = Math.min(corners[0], max), tr = Math.min(corners[1], max);
        float br = Math.min(corners[2], max), bl = Math.min(corners[3], max);
        if (cut) {
            p.moveTo(r.left + tl, r.top);
            p.lineTo(r.right - tr, r.top);
            p.lineTo(r.right, r.top + tr);
            p.lineTo(r.right, r.bottom - br);
            p.lineTo(r.right - br, r.bottom);
            p.lineTo(r.left + bl, r.bottom);
            p.lineTo(r.left, r.bottom - bl);
            p.lineTo(r.left, r.top + tl);
            p.close();
        } else {
            p.addRoundRect(r, new float[]{tl, tl, tr, tr, br, br, bl, bl}, Path.Direction.CW);
        }
    }

    @Override
    public void draw(Canvas c) {
        if (fill.getAlpha() > 0) c.drawPath(path, fill);
        if (barWidth > 0 && bar.getAlpha() > 0) {
            Rect b = getBounds();
            int save = c.save();
            c.clipPath(path);
            c.drawRect(b.left, b.top, b.left + barWidth, b.bottom, bar);
            c.restoreToCount(save);
        }
        if (strokeWidth > 0 && stroke.getAlpha() > 0) c.drawPath(strokePath, stroke);
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        if (!cut) {
            outline.setRoundRect(b, Math.max(Math.max(corners[0], corners[1]), Math.max(corners[2], corners[3])));
        } else if (path.isConvex()) {
            outline.setConvexPath(path);
        } else {
            outline.setRect(b);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        stroke.setColorFilter(cf);
        bar.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
