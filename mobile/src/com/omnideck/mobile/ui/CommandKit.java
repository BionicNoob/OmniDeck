package com.omnideck.mobile.ui;

import android.content.Context;
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
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Small views and drawables used only by the COMMAND tab
 * ({@code screens/CommandScreen}): pieces the shared kit doesn't have.
 */
public final class CommandKit {
    private CommandKit() {}

    /** An IconDrawable kind (&gt; 0) or a {@link Glyph} kind (&lt; 0), in one color. */
    public static Drawable icon(int kind, int color, int sizePx) {
        return kind < 0 ? new Glyph(kind, color, sizePx) : new IconDrawable(kind, color, color, sizePx);
    }

    // ------------------------------------------------------------------
    // Tile grid: one label size for every tile
    // ------------------------------------------------------------------

    /**
     * The quick-action grid: a column of equal-width tile rows whose labels
     * all share ONE text size, the largest at which every label fits its
     * cell. No label shrinks on its own, so the grid reads as one panel at
     * every phone width. Labels in wider cells ({@link #addFollower}) just
     * take the shared size.
     */
    public static final class TileGrid extends LinearLayout {
        private final List<TextView> labels = new ArrayList<TextView>();
        private final List<TextView> followers = new ArrayList<TextView>();
        private final Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int cols;
        private final float gapPx, insetPx, maxPx, minPx;
        private float tracking, narrowTracking, narrowBelowPx;
        private float shared = -1;

        /**
         * @param gapPx   space between two cells in a row
         * @param insetPx a cell's horizontal padding, both sides together
         */
        public TileGrid(Context c, int cols, float gapPx, float insetPx, float maxPx, float minPx) {
            super(c);
            setOrientation(VERTICAL);
            this.cols = cols;
            this.gapPx = gapPx;
            this.insetPx = insetPx;
            this.maxPx = maxPx;
            this.minPx = minPx;
        }

        /** Letter spacing for the labels, tightened to {@code narrow} when a cell is under {@code belowPx} wide. */
        public void setTracking(float normal, float narrow, float belowPx) {
            tracking = normal;
            narrowTracking = narrow;
            narrowBelowPx = belowPx;
        }

        /** A label in a one-column cell: it takes part in choosing the size. */
        public void addLabel(TextView tv) {
            labels.add(tv);
        }

        /** A label in a wider cell: it takes the shared size. */
        public void addFollower(TextView tv) {
            followers.add(tv);
        }

        /** The size every label got in the last measure pass (px; -1 before the first). */
        public float sharedSizePx() {
            return shared;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && width > 0) {
                float cell = (width - getPaddingLeft() - getPaddingRight() - gapPx * (cols - 1)) / cols;
                float avail = cell - insetPx;
                float track = cell < narrowBelowPx ? narrowTracking : tracking;
                float size = maxPx;
                for (TextView tv : labels) {
                    if (tv.getVisibility() == GONE) continue;
                    probe.set(tv.getPaint());
                    probe.setLetterSpacing(track);
                    size = fit(tv.getText().toString(), avail, size);
                }
                shared = size;
                for (TextView tv : labels) apply(tv, size, track);
                for (TextView tv : followers) apply(tv, size, track);
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private float fit(String s, float avail, float start) {
            float size = start;
            probe.setTextSize(size);
            while (size > minPx && probe.measureText(s) > avail) {
                size = Math.max(minPx, size - 0.25f);
                probe.setTextSize(size);
            }
            return size;
        }

        private static void apply(TextView tv, float size, float track) {
            if (Math.abs(tv.getTextSize() - size) > 0.01f) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            if (Math.abs(tv.getLetterSpacing() - track) > 0.001f) tv.setLetterSpacing(track);
        }
    }

    // ------------------------------------------------------------------
    // Rail card
    // ------------------------------------------------------------------

    /**
     * A card background with a colored rail down its left edge, clipped to
     * the card's rounded corners: the card keeps its quiet edge and the rail
     * alone carries the state color (an annunciator, not an alarm frame).
     */
    public static final class RailDrawable extends Drawable {
        private final Drawable base;
        private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rf = new RectF();
        private final float width, radius, insetV;

        /** {@code insetV}: how far the rail stays clear of the top and bottom edges. */
        public RailDrawable(Drawable base, int color, float widthPx, float radiusPx, float insetV) {
            this.base = base;
            this.width = widthPx;
            this.radius = radiusPx;
            this.insetV = insetV;
            rail.setColor(color);
        }

        public int railColor() {
            return rail.getColor();
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            base.setBounds(b);
            rf.set(b);
            clip.reset();
            clip.addRoundRect(rf, radius, radius, Path.Direction.CW);
        }

        @Override
        public void draw(Canvas c) {
            base.draw(c);
            Rect b = getBounds();
            int save = c.save();
            c.clipPath(clip);
            c.drawRect(b.left, b.top + insetV, b.left + width, b.bottom - insetV, rail);
            c.restoreToCount(save);
        }

        @Override
        public void getOutline(Outline outline) {
            base.getOutline(outline);
        }

        @Override
        public void setAlpha(int alpha) {
            base.setAlpha(alpha);
            rail.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            base.setColorFilter(cf);
            rail.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------
    // Dashed track
    // ------------------------------------------------------------------

    /**
     * A dashed hairline across the view: the track of a meter whose scale is
     * unknown (e.g. free disk space without the disk's size), so it never
     * reads as an empty bar at 0%.
     */
    public static final class DashLine extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public DashLine(Context c, int color) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, d));
            p.setColor(color);
            p.setPathEffect(new DashPathEffect(new float[]{4 * d, 4 * d}, 0));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas c) {
            float y = getHeight() / 2f;
            c.drawLine(0, y, getWidth(), y, p);
        }
    }

    // ------------------------------------------------------------------
    // Glyphs
    // ------------------------------------------------------------------

    /** Stroked glyphs in {@link IconDrawable}'s 24-unit style, for the ones the kit doesn't have. */
    public static final class Glyph extends Drawable {
        /** A padlock (Lock PC). */
        public static final int LOCK = -1;
        /** Eject: a triangle over a bar (unload a model from memory). */
        public static final int EJECT = -2;

        private final int kind;
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();

        public Glyph(int kind, int color, int sizePx) {
            this.kind = kind;
            this.size = sizePx;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        public int kind() {
            return kind;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0) return;
            int save = c.save();
            c.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
            c.scale(s / 24f, s / 24f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            if (kind == LOCK) {
                rf.set(4.5f, 10.5f, 19.5f, 21f);
                c.drawRoundRect(rf, 2.2f, 2.2f, paint);
                path.reset();
                path.moveTo(8f, 10.5f);
                path.lineTo(8f, 7.5f);
                rf.set(8f, 3.5f, 16f, 11.5f);
                path.arcTo(rf, 180, 180, false);
                path.lineTo(16f, 10.5f);
                c.drawPath(path, paint);
                c.drawLine(12f, 14.6f, 12f, 17f, paint);
            } else if (kind == EJECT) {
                path.reset();
                path.moveTo(12f, 4.5f);
                path.lineTo(19.5f, 13.5f);
                path.lineTo(4.5f, 13.5f);
                path.close();
                c.drawPath(path, paint);
                c.drawLine(4.5f, 18.5f, 19.5f, 18.5f, paint);
            }
            c.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
