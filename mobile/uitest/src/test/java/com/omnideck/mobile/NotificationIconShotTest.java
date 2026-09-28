package com.omnideck.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;

import com.omnideck.mobile.ui.IconDrawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The status-bar glyphs of OmniDeck's notifications (reply, download, timer),
 * tinted the way the system tints them on a dark and a light shade →
 * build/screens/notification-icons.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NotificationIconShotTest {
    private static final int[] KINDS = {IconDrawable.NAV_COMMS, IconDrawable.DOWNLOAD, IconDrawable.HISTORY};

    @Test
    public void glyphsAreCleanAlphaMasks() throws Exception {
        Bitmap first = Notifier.glyph(RuntimeEnvironment.getApplication(), KINDS[0]);
        int px = first.getWidth();
        assertEquals("24dp at xxhdpi", 72, px);
        int zoom = 3;
        int cell = px * zoom + 24;
        Bitmap sheet = Bitmap.createBitmap(cell * KINDS.length * 2, cell, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        Paint bg = new Paint();
        int[] shades = {0xFF202124, 0xFFF1F3F4};
        int[] tints = {0xFFE8EAED, 0xFF3C4043};
        for (int s = 0; s < 2; s++) {
            bg.setColor(shades[s]);
            c.drawRect(s * cell * KINDS.length, 0, (s + 1) * cell * KINDS.length, cell, bg);
            for (int k = 0; k < KINDS.length; k++) {
                Bitmap g = Notifier.glyph(RuntimeEnvironment.getApplication(), KINDS[k]);
                int opaque = 0;
                for (int y = 0; y < px; y++) {
                    for (int x = 0; x < px; x++) {
                        if ((g.getPixel(x, y) >>> 24) > 0x80) opaque++;
                    }
                }
                assertTrue("glyph " + KINDS[k] + " draws something", opaque > px * px / 40);
                assertEquals("corners stay transparent", 0, g.getPixel(0, 0) >>> 24);
                Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
                tint.setColorFilter(new PorterDuffColorFilter(tints[s], PorterDuff.Mode.SRC_IN));
                int left = (s * KINDS.length + k) * cell + 12;
                c.drawBitmap(g, new Rect(0, 0, px, px), new Rect(left, 12, left + px * zoom, 12 + px * zoom), tint);
            }
        }
        File dir = new File("build/screens");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "notification-icons.png"))) {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
