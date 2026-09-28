package com.omnideck.mobile.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;

import com.omnideck.mobile.core.BridgeClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Background work for the PC tab that the Engine API doesn't cover: opening
 * an app by id with a result callback, and screenshot handling (decoding,
 * re-encoding for the AI, saving to the phone's gallery). Jobs run on one
 * worker thread; results arrive on the main thread.
 */
public final class PcLink {
    /** Exactly one of value / error is non-null. Called on the main thread. */
    public interface Result<T> {
        void done(T value, String error);
    }

    /** Where a saved screenshot went: a gallery URI (Android 10+) and a human-readable place. */
    public static final class Saved {
        public final Uri uri;
        public final String where;

        Saved(Uri uri, String where) {
            this.uri = uri;
            this.where = where;
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread th = new Thread(r, "omni-pc");
            th.setDaemon(true);
            return th;
        }
    });

    private final Handler main = new Handler(Looper.getMainLooper());

    private <T> void run(final Callable<T> job, final Result<T> cb) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                T v = null;
                String err = null;
                try {
                    v = job.call();
                } catch (BridgeClient.BridgeException e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = "Not enough memory for that image.";
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final T fv = v;
                final String fe = err != null ? err : v == null ? "No result." : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(fe == null ? fv : null, fe);
                    }
                });
            }
        });
    }

    /** POST /launch {app_id} — opens exactly this app on the PC. */
    public void launch(final String host, final int port, final String token, final String appId,
                       Result<JSONObject> cb) {
        run(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws Exception {
                if (host == null || host.length() == 0) {
                    throw new IOException("Connect to your PC first — the bridge runs on the same machine as "
                            + "the AI.");
                }
                return new BridgeClient(host, port, token).launchId(appId);
            }
        }, cb);
    }

    /** A decoded screenshot: a display-sized bitmap plus the original pixel size and format. */
    public static final class Decoded {
        public final Bitmap bitmap;
        public final int width;
        public final int height;
        public final String format;

        Decoded(Bitmap bitmap, int width, int height, String format) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }

    /** Decodes base64 image data, downsampled so its longer side is at most {@code maxSide}. */
    public void decode(final String b64, final int maxSide, Result<Decoded> cb) {
        run(new Callable<Decoded>() {
            @Override
            public Decoded call() throws Exception {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                Bitmap b = decodeNow(bytes, o, maxSide);
                if (b == null) throw new IOException("The bridge sent an image this phone can't read.");
                String fmt = o.outMimeType != null && o.outMimeType.contains("jpeg") ? "JPEG"
                        : o.outMimeType != null && o.outMimeType.contains("webp") ? "WEBP" : "PNG";
                return new Decoded(b, o.outWidth, o.outHeight, fmt);
            }
        }, cb);
    }

    /** Re-encodes a screenshot as a JPEG (longer side ≤ maxSide) for a vision model. */
    public void jpeg(final String b64, final int maxSide, Result<String> cb) {
        run(new Callable<String>() {
            @Override
            public String call() throws Exception {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                Bitmap b = decodeNow(bytes, o, maxSide);
                if (b == null) throw new IOException("Couldn't read the screenshot.");
                float scale = Math.min(1f, maxSide / (float) Math.max(b.getWidth(), b.getHeight()));
                if (scale < 1f) {
                    b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)),
                            Math.max(1, Math.round(b.getHeight() * scale)), true);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 85, out);
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
        }, cb);
    }

    /**
     * Saves the original image bytes: to Pictures/OmniDeck in the gallery on
     * Android 10+ (no permission needed), else to the app's own Pictures folder.
     */
    public void save(final Context c, final String b64, Result<Saved> cb) {
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        run(new Callable<Saved>() {
            @Override
            public Saved call() throws Exception {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                boolean jpg = bytes.length > 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
                String name = "PC-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                        + (jpg ? ".jpg" : ".png");
                String mime = jpg ? "image/jpeg" : "image/png";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = app.getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put("relative_path", Environment.DIRECTORY_PICTURES + "/OmniDeck"); // API 29 column
                    v.put("is_pending", 1);
                    Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IOException("The phone's gallery didn't accept the image.");
                    OutputStream os = cr.openOutputStream(uri);
                    if (os == null) throw new IOException("The phone's gallery didn't accept the image.");
                    try {
                        os.write(bytes);
                    } finally {
                        os.close();
                    }
                    ContentValues done = new ContentValues();
                    done.put("is_pending", 0);
                    cr.update(uri, done, null, null);
                    return new Saved(uri, "Pictures/OmniDeck/" + name);
                }
                File dir = app.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) dir = new File(app.getFilesDir(), "pictures");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Can't create " + dir);
                File f = new File(dir, name);
                FileOutputStream os = new FileOutputStream(f);
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
                return new Saved(null, f.getAbsolutePath());
            }
        }, cb);
    }

    /** Decodes {@code bytes} (bounds already in {@code o}) with a power-of-two sample fitting {@code maxSide}. */
    static Bitmap decodeNow(byte[] bytes, BitmapFactory.Options o, int maxSide) {
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
    }
}
