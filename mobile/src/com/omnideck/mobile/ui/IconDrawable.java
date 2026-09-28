package com.omnideck.mobile.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Line icons drawn in code on a 24-unit grid — the same stroke style as the
 * web app's inline SVG icons (feather-like: round caps, ~1.8u strokes) — so
 * they're crisp at any size and identical on every phone.
 */
public final class IconDrawable extends Drawable {
    public static final int SEND = 1;
    public static final int STOP = 2;
    public static final int MENU = 3;
    public static final int LOGO = 4;
    public static final int CHEVRON = 5;
    public static final int DOWN = 6;
    public static final int NAV_COMMAND = 10;
    public static final int NAV_COMMS = 11;
    public static final int NAV_MODELS = 12;
    public static final int NAV_PC = 13;
    public static final int SETTINGS = 14;
    public static final int MIC = 15;
    public static final int IMAGE = 16;
    public static final int SPEAKER = 17;
    public static final int SPEAKER_OFF = 18;
    public static final int HISTORY = 19;
    public static final int PLUS = 20;
    public static final int SEARCH = 21;
    public static final int SCAN = 22;
    public static final int BOLT = 23;
    public static final int POWER = 24;
    public static final int DOWNLOAD = 25;
    public static final int TRASH = 26;
    public static final int CAMERA = 27;
    public static final int CLIPBOARD = 28;
    public static final int APPS = 29;
    public static final int CHECK = 30;
    public static final int CLOSE = 31;
    public static final int COPY = 32;
    public static final int SHARE = 33;
    public static final int INFO = 34;
    public static final int ACTIVITY = 35;
    public static final int TERMINAL = 36;
    public static final int LINK = 37;
    public static final int DOC = 38;
    public static final int CPU = 39;
    public static final int EDIT = 40;
    public static final int BACK = 41;
    public static final int REFRESH = 42;
    public static final int PLAY = 43;
    public static final int WIFI = 44;
    public static final int BRAIN = 45;
    public static final int STAR = 46;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final int intrinsic;
    private float stroke = 1.8f;

    public IconDrawable(int kind, int color, int color2, int intrinsicPx) {
        this.kind = kind;
        this.intrinsic = intrinsicPx;
        paint.setColor(color);
        paint2.setColor(color2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Stroke width in grid units (default 1.8 of 24). */
    public IconDrawable stroke(float units) {
        stroke = units;
        invalidateSelf();
        return this;
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

    // --- 24-unit drawing helpers (canvas already scaled) ---
    private Canvas c;

    private void strokeStyle() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
    }

    private void fillStyle() {
        paint.setStyle(Paint.Style.FILL);
    }

    private void line(float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, paint);
    }

    private void circle(float cx, float cy, float r) {
        c.drawCircle(cx, cy, r, paint);
    }

    private void rrect(float l, float t, float r, float b, float rad) {
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, rad, rad, paint);
    }

    private void poly(boolean close, float... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        if (close) path.close();
        c.drawPath(path, paint);
    }

    private void arc(float cx, float cy, float r, float start, float sweep) {
        rf.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rf, start, sweep, false, paint);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        if (s <= 0) return;
        c = canvas;
        int save = canvas.save();
        canvas.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
        canvas.scale(s / 24f, s / 24f);
        strokeStyle();
        switch (kind) {
            case SEND:
                fillStyle();
                poly(true, 4.5f, 4.8f, 20.5f, 12f, 4.5f, 19.2f, 7.2f, 12f);
                break;
            case STOP:
                fillStyle();
                rrect(7f, 7f, 17f, 17f, 1.8f);
                break;
            case MENU:
                fillStyle();
                circle(12, 5.5f, 1.8f);
                circle(12, 12, 1.8f);
                circle(12, 18.5f, 1.8f);
                break;
            case CHEVRON:
                poly(false, 6.5f, 9.5f, 12, 15, 17.5f, 9.5f);
                break;
            case DOWN:
                line(12, 5, 12, 19);
                poly(false, 6, 13, 12, 19, 18, 13);
                break;
            case BACK:
                line(19, 12, 5, 12);
                poly(false, 11, 6, 5, 12, 11, 18);
                break;
            case NAV_COMMAND: // reactor: hexagon + core
                poly(true, 12, 2.5f, 20.2f, 7.25f, 20.2f, 16.75f, 12, 21.5f, 3.8f, 16.75f, 3.8f, 7.25f);
                circle(12, 12, 3.4f);
                break;
            case NAV_COMMS: // message-square with lines
                poly(true, 21, 15, 21, 5, 3, 5, 3, 15, 8, 15, 8, 19.5f, 12.5f, 15);
                line(7.5f, 9, 16.5f, 9);
                line(7.5f, 12, 13, 12);
                break;
            case NAV_MODELS: // layers
                poly(true, 12, 2.8f, 21.5f, 7.6f, 12, 12.4f, 2.5f, 7.6f);
                poly(false, 2.5f, 12, 12, 16.8f, 21.5f, 12);
                poly(false, 2.5f, 16.4f, 12, 21.2f, 21.5f, 16.4f);
                break;
            case NAV_PC: // monitor
                rrect(2.5f, 3.5f, 21.5f, 16, 1.6f);
                line(8.5f, 20.5f, 15.5f, 20.5f);
                line(12, 16, 12, 20.5f);
                break;
            case SETTINGS: // sliders
                line(4, 21, 4, 14);
                line(4, 10, 4, 3);
                line(12, 21, 12, 12);
                line(12, 8, 12, 3);
                line(20, 21, 20, 16);
                line(20, 12, 20, 3);
                line(1.5f, 14, 6.5f, 14);
                line(9.5f, 8, 14.5f, 8);
                line(17.5f, 16, 22.5f, 16);
                break;
            case MIC:
                rrect(9, 2.5f, 15, 14.5f, 3f);
                path.reset();
                rf.set(5.5f, 5.5f, 18.5f, 17.5f);
                path.addArc(rf, 0, 180);
                c.drawPath(path, paint);
                line(12, 17.5f, 12, 21.5f);
                line(8.5f, 21.5f, 15.5f, 21.5f);
                break;
            case IMAGE:
                rrect(3, 3, 21, 21, 2.2f);
                circle(8.5f, 8.5f, 1.6f);
                poly(false, 21, 15, 16, 10, 5, 21);
                break;
            case SPEAKER:
            case SPEAKER_OFF:
                poly(true, 11, 5, 6, 9, 2.5f, 9, 2.5f, 15, 6, 15, 11, 19);
                if (kind == SPEAKER) {
                    arc(12.5f, 12, 3.8f, -50, 100);
                    arc(12.5f, 12, 7.6f, -50, 100);
                } else {
                    line(22, 9, 16, 15);
                    line(16, 9, 22, 15);
                }
                break;
            case HISTORY: // clock with rewind arrow
                arc(12, 12, 9, -150, 300);
                poly(false, 3.3f, 4.8f, 3.3f, 8.6f, 7.1f, 8.6f);
                poly(false, 12, 7, 12, 12, 15.5f, 14);
                break;
            case PLUS:
                line(12, 5, 12, 19);
                line(5, 12, 19, 12);
                break;
            case SEARCH:
                circle(10.5f, 10.5f, 6.5f);
                line(21, 21, 15.3f, 15.3f);
                break;
            case SCAN: // radar sweep
                circle(12, 12, 9);
                circle(12, 12, 4.5f);
                line(12, 12, 18.4f, 5.6f);
                fillStyle();
                circle(12, 12, 1.3f);
                break;
            case WIFI:
                arc(12, 19, 15, -135, 90);
                arc(12, 19, 10, -135, 90);
                arc(12, 19, 5, -135, 90);
                fillStyle();
                circle(12, 19, 1.4f);
                break;
            case BOLT:
                poly(true, 13, 2, 3.5f, 13.5f, 12, 13.5f, 11, 22, 20.5f, 10.5f, 12, 10.5f);
                break;
            case POWER:
                arc(12, 13, 8, -60, 300);
                line(12, 2.5f, 12, 12);
                break;
            case DOWNLOAD:
                poly(false, 3, 15, 3, 20, 21, 20, 21, 15);
                poly(false, 7, 10, 12, 15, 17, 10);
                line(12, 15, 12, 3);
                break;
            case TRASH:
                line(3, 6, 21, 6);
                poly(false, 19, 6, 18, 20.5f, 6, 20.5f, 5, 6);
                poly(false, 8.5f, 6, 8.5f, 3.5f, 15.5f, 3.5f, 15.5f, 6);
                line(10, 10.5f, 10, 16.5f);
                line(14, 10.5f, 14, 16.5f);
                break;
            case CAMERA:
                poly(true, 2.5f, 8, 2.5f, 19, 21.5f, 19, 21.5f, 8, 17, 8, 15.2f, 5, 8.8f, 5, 7, 8);
                circle(12, 13, 3.6f);
                break;
            case CLIPBOARD:
                poly(false, 16, 4.5f, 18.5f, 4.5f, 18.5f, 21, 5.5f, 21, 5.5f, 4.5f, 8, 4.5f);
                rrect(8.5f, 2.5f, 15.5f, 6.5f, 1f);
                break;
            case APPS:
                rrect(3.5f, 3.5f, 10, 10, 1.5f);
                rrect(14, 3.5f, 20.5f, 10, 1.5f);
                rrect(3.5f, 14, 10, 20.5f, 1.5f);
                rrect(14, 14, 20.5f, 20.5f, 1.5f);
                break;
            case CHECK:
                poly(false, 4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 7);
                break;
            case CLOSE:
                line(6, 6, 18, 18);
                line(18, 6, 6, 18);
                break;
            case COPY:
                rrect(9, 9, 21, 21, 2);
                poly(false, 5, 15, 3.5f, 15, 3.5f, 3.5f, 15, 3.5f, 15, 5);
                break;
            case SHARE:
                circle(18, 5, 2.6f);
                circle(6, 12, 2.6f);
                circle(18, 19, 2.6f);
                line(8.3f, 13.3f, 15.7f, 17.7f);
                line(15.7f, 6.3f, 8.3f, 10.7f);
                break;
            case INFO:
                circle(12, 12, 9.5f);
                line(12, 16.5f, 12, 11);
                fillStyle();
                circle(12, 7.6f, 1.2f);
                break;
            case ACTIVITY:
                poly(false, 2.5f, 12, 6.5f, 12, 9.5f, 20, 14.5f, 4, 17.5f, 12, 21.5f, 12);
                break;
            case TERMINAL:
                poly(false, 4.5f, 17, 10, 11.5f, 4.5f, 6);
                line(12, 18, 19.5f, 18);
                break;
            case LINK:
                path.reset();
                rrect(2.5f, 8.5f, 12.5f, 15.5f, 3.5f);
                rrect(11.5f, 8.5f, 21.5f, 15.5f, 3.5f);
                break;
            case DOC:
                poly(true, 14, 2.5f, 5.5f, 2.5f, 5.5f, 21.5f, 18.5f, 21.5f, 18.5f, 7);
                poly(false, 14, 2.5f, 14, 7, 18.5f, 7);
                line(8.5f, 12, 15.5f, 12);
                line(8.5f, 16, 15.5f, 16);
                break;
            case CPU:
                rrect(5, 5, 19, 19, 2);
                rrect(9, 9, 15, 15, 0.5f);
                for (int i = 0; i < 2; i++) {
                    float p = i == 0 ? 9.5f : 14.5f;
                    line(p, 2, p, 5);
                    line(p, 19, p, 22);
                    line(2, p, 5, p);
                    line(19, p, 22, p);
                }
                break;
            case EDIT:
                poly(true, 16.5f, 3.5f, 20.5f, 7.5f, 8, 20, 3.5f, 20.5f, 4, 16);
                break;
            case REFRESH:
                arc(12, 12, 8.5f, -30, 290);
                poly(false, 20.5f, 3.5f, 20.5f, 8.6f, 15.4f, 8.6f);
                break;
            case PLAY:
                fillStyle();
                poly(true, 7, 4.5f, 19.5f, 12, 7, 19.5f);
                break;
            case BRAIN: // neural node graph
                circle(5.5f, 6, 2.2f);
                circle(18.5f, 6, 2.2f);
                circle(12, 12.5f, 2.6f);
                circle(5.5f, 19, 2.2f);
                circle(18.5f, 19, 2.2f);
                line(7.3f, 7.3f, 10.1f, 10.7f);
                line(16.7f, 7.3f, 13.9f, 10.7f);
                line(7.3f, 17.7f, 10.1f, 14.3f);
                line(16.7f, 17.7f, 13.9f, 14.3f);
                break;
            case STAR:
                poly(true, 12, 2.5f, 14.9f, 8.6f, 21.5f, 9.4f, 16.6f, 13.9f, 17.9f, 20.5f, 12, 17.2f, 6.1f, 20.5f,
                        7.4f, 13.9f, 2.5f, 9.4f, 9.1f, 8.6f);
                break;
            case LOGO:
            default: {
                // Segmented ring + core, like the launcher icon.
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeWidth(3.1f);
                for (int start = -35; start < 325; start += 90) arc(12, 12, 8.6f, start, 70);
                paint2.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 12, 2.9f, paint2);
                paint.setStrokeCap(Paint.Cap.ROUND);
                break;
            }
        }
        canvas.restoreToCount(save);
        c = null;
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
