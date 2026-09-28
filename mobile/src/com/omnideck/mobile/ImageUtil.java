package com.omnideck.mobile;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Turns a picked image into the downscaled base64 JPEG Ollama's vision models take. */
final class ImageUtil {
    static final int MAX_SIDE = 1024;

    private ImageUtil() {}

    /** Result of encoding one image. */
    static final class Encoded {
        final String base64;
        final Bitmap preview;

        Encoded(String base64, Bitmap preview) {
            this.base64 = base64;
            this.preview = preview;
        }
    }

    /** Blocking: call off the main thread. Returns null if the image can't be read. */
    static Encoded encode(ContentResolver cr, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = cr.openInputStream(uri);
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                in.close();
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = cr.openInputStream(uri);
            if (in == null) return null;
            Bitmap bmp;
            try {
                bmp = BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (bmp == null) return null;
            bmp = rotate(bmp, orientation(cr, uri));
            int w = bmp.getWidth(), h = bmp.getHeight();
            float scale = Math.min(1f, MAX_SIDE / (float) Math.max(w, h));
            if (scale < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
                if (s != bmp) bmp.recycle();
                bmp = s;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return new Encoded(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), bmp);
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static int orientation(ContentResolver cr, Uri uri) {
        // ExifInterface(InputStream) is API 24; on older phones fall back to file paths.
        if ("file".equals(uri.getScheme())) {
            try {
                return new ExifInterface(uri.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException e) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
        }
        return ExifInterface.ORIENTATION_NORMAL;
    }

    private static Bitmap rotate(Bitmap b, int exif) {
        int deg = exif == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : exif == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : exif == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    /** Decodes a stored base64 image, downsampled to fit {@code maxSidePx}. */
    static Bitmap decode(String base64, int maxSidePx) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSidePx) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
        } catch (RuntimeException e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
