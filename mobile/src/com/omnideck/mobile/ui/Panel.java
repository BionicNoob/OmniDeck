package com.omnideck.mobile.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Card / panel background in the active theme's material.
 * <ul>
 * <li>Cyber ("mainframe HUD"): navy glass fill, cyan hairline edge, a faint
 * 22dp hairline grid, a soft bloom from the top edge, corner brackets and an
 * optional header "cap" band.</li>
 * <li>Light / Dark: a flat rounded card with a hairline border (elevation for
 * the shadow is set on the view).</li>
 * </ul>
 */
public final class Panel extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracket = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capLine = new Paint();
    private final Paint hi = new Paint();
    private final Path shape = new Path();
    private final Path capPath = new Path();
    private final RectF r = new RectF();
    private final float[] radii; // tl, tr, br, bl
    private final float stroke;
    private final float gridStep;
    private final float bracketLen;
    private final float capHeight;
    private final int bloomColor;
    private final float bracketInset;
    private final boolean bracketsAll;

    private Panel(Builder b) {
        radii = b.radii;
        stroke = b.stroke;
        gridStep = b.gridStep;
        bracketLen = b.bracketLen;
        capHeight = b.capHeight;
        bloomColor = b.bloomColor;
        bracketInset = b.bracketInset;
        bracketsAll = b.bracketsAll;
        hi.setColor(b.highlight);
        hi.setStrokeWidth(Math.max(1f, b.stroke));
        fill.setColor(b.fill);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(b.stroke);
        edge.setColor(b.edge);
        if (b.dashOn > 0) edge.setPathEffect(new DashPathEffect(new float[]{b.dashOn, b.dashOff}, 0));
        grid.setColor(b.gridColor);
        grid.setStrokeWidth(Math.max(1f, b.stroke));
        bracket.setStyle(Paint.Style.STROKE);
        bracket.setStrokeWidth(b.bracketStroke);
        bracket.setStrokeCap(Paint.Cap.SQUARE);
        bracket.setColor(b.bracketColor);
        capPaint.setColor(b.capFill);
        capLine.setColor(b.capLine);
        capLine.setStrokeWidth(Math.max(1f, b.stroke));
    }

    public static final class Builder {
        int fill;
        int edge;
        float stroke;
        float[] radii = {0, 0, 0, 0};
        float gridStep;
        int gridColor;
        int bloomColor;
        float bracketLen;
        float bracketStroke = 2;
        int bracketColor;
        float capHeight;
        int capFill;
        int capLine;
        int highlight;
        float bracketInset = -1;
        boolean bracketsAll;
        float dashOn, dashOff;

        public Builder fill(int c) {
            fill = c;
            return this;
        }

        public Builder edge(int color, float widthPx) {
            edge = color;
            stroke = widthPx;
            return this;
        }

        public Builder radius(float px) {
            radii = new float[]{px, px, px, px};
            return this;
        }

        public Builder radii(float tl, float tr, float br, float bl) {
            radii = new float[]{tl, tr, br, bl};
            return this;
        }

        public Builder grid(float stepPx, int color) {
            gridStep = stepPx;
            gridColor = color;
            return this;
        }

        public Builder bloom(int color) {
            bloomColor = color;
            return this;
        }

        public Builder brackets(float lengthPx, float widthPx, int color) {
            bracketLen = lengthPx;
            bracketStroke = widthPx;
            bracketColor = color;
            return this;
        }

        /** Where the brackets sit, in from the edge (default: on the edge). */
        public Builder bracketInset(float px) {
            bracketInset = px;
            return this;
        }

        /** A dashed edge (the web app's system-message border). */
        public Builder dash(float onPx, float offPx) {
            dashOn = onPx;
            dashOff = offPx;
            return this;
        }

        /** Brackets on all four corners (default: top-left and bottom-right). */
        public Builder bracketsAll() {
            bracketsAll = true;
            return this;
        }

        /** A 1px light line along the inside of the top edge (glass sheen). */
        public Builder highlight(int color) {
            highlight = color;
            return this;
        }

        public Builder cap(float heightPx, int fill, int line) {
            capHeight = heightPx;
            capFill = fill;
            capLine = line;
            return this;
        }

        public Panel build() {
            return new Panel(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        float h = stroke / 2f;
        r.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
        shape.reset();
        shape.addRoundRect(r, new float[]{radii[0], radii[0], radii[1], radii[1], radii[2], radii[2], radii[3], radii[3]},
                Path.Direction.CW);
        if (capHeight > 0) {
            capPath.reset();
            RectF cr = new RectF(r.left, r.top, r.right, Math.min(r.bottom, r.top + capHeight));
            capPath.addRoundRect(cr, new float[]{radii[0], radii[0], radii[1], radii[1], 0, 0, 0, 0}, Path.Direction.CW);
        }
        if (bloomColor != 0 && b.width() > 0) {
            float rad = Math.max(b.width() * 0.75f, 1);
            bloom.setShader(new RadialGradient(b.exactCenterX(), b.top - rad * 0.35f, rad,
                    new int[]{bloomColor, bloomColor & 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (fill.getAlpha() > 0) c.drawPath(shape, fill);
        if (gridStep > 0 || bloomColor != 0 || capHeight > 0 || hi.getAlpha() > 0) {
            int save = c.save();
            c.clipPath(shape);
            if (capHeight > 0) {
                c.drawPath(capPath, capPaint);
                float y = r.top + capHeight;
                c.drawLine(r.left, y, r.right, y, capLine);
            }
            if (bloomColor != 0) c.drawRect(b, bloom);
            if (gridStep > 0) {
                for (float x = b.left + gridStep; x < b.right; x += gridStep) c.drawLine(x, b.top, x, b.bottom, grid);
                for (float y = b.top + gridStep; y < b.bottom; y += gridStep) c.drawLine(b.left, y, b.right, y, grid);
            }
            if (hi.getAlpha() > 0) {
                float y = r.top + stroke + hi.getStrokeWidth() / 2f;
                c.drawLine(r.left + radii[0] * 0.6f, y, r.right - radii[1] * 0.6f, y, hi);
            }
            c.restoreToCount(save);
        }
        if (stroke > 0 && edge.getAlpha() > 0) c.drawPath(shape, edge);
        if (bracketLen > 0) {
            float in = bracket.getStrokeWidth() / 2f + stroke + Math.max(0, bracketInset);
            float L = bracketLen;
            float l = b.left + in, t = b.top + in, rr = b.right - in, bb = b.bottom - in;
            c.drawLine(l, t, l + L, t, bracket);
            c.drawLine(l, t, l, t + L, bracket);
            c.drawLine(rr, bb, rr - L, bb, bracket);
            c.drawLine(rr, bb, rr, bb - L, bracket);
            if (bracketsAll) {
                c.drawLine(rr, t, rr - L, t, bracket);
                c.drawLine(rr, t, rr, t + L, bracket);
                c.drawLine(l, bb, l + L, bb, bracket);
                c.drawLine(l, bb, l, bb - L, bracket);
            }
        }
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        float rad = Math.max(Math.max(radii[0], radii[1]), Math.max(radii[2], radii[3]));
        outline.setRoundRect(b, rad);
        outline.setAlpha(fill.getAlpha() / 255f);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        edge.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
