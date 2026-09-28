package com.omnideck.mobile;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;

import com.omnideck.mobile.core.Fmt;
import com.omnideck.mobile.ui.IconDrawable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * System notifications for what finishes while OmniDeck is in the
 * background: a reply, a model download, a timer. Only posted when the user
 * allows them — the in-app switch (Settings.notifications) and, on Android
 * 13+, the POST_NOTIFICATIONS permission. Tapping one opens the app on the
 * matching tab ({@link #EXTRA_TAB}). Everything newer than API 23 is reached
 * by reflection.
 */
final class Notifier {
    static final String PERMISSION = "android.permission.POST_NOTIFICATIONS";
    /** Launch-intent extra: the MainActivity.TAB_* to show. */
    static final String EXTRA_TAB = "com.omnideck.mobile.TAB";
    static final String CHANNEL_REPLIES = "omni.replies";
    static final String CHANNEL_TIMERS = "omni.timers";
    static final int ID_REPLY = 1001;
    static final int ID_DOWNLOAD = 1002;
    private static final int ID_TIMER = 2000;
    /** NotificationManager.IMPORTANCE_DEFAULT / IMPORTANCE_HIGH (API 24). */
    private static final int IMPORTANCE_DEFAULT = 3;
    private static final int IMPORTANCE_HIGH = 4;

    private final Context app;
    private final Settings settings;
    private boolean channelsMade;

    Notifier(Context app, Settings settings) {
        this.app = app.getApplicationContext();
        this.settings = settings;
    }

    /** Why nothing can be posted right now, as advice for the user; "" when notifications work. */
    String blockedReason() {
        if (!settings.notifications()) return "Notifications are off in OmniDeck's settings.";
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return "Allow notifications for OmniDeck in Android settings.";
        }
        NotificationManager nm = manager();
        if (nm == null) return "This phone has no notification service.";
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Object on = NotificationManager.class.getMethod("areNotificationsEnabled").invoke(nm);
                if (Boolean.FALSE.equals(on)) return "Notifications for OmniDeck are turned off in Android settings.";
            } catch (ReflectiveOperationException ignored) {
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    boolean canPost() {
        return blockedReason().length() == 0;
    }

    /** A reply finished ({@code error} null) or failed while the app was in the background. */
    void reply(String model, String text, String error, boolean incognito) {
        boolean failed = error != null;
        String title = (failed ? "Reply failed" : "Reply ready") + (model.length() > 0 ? " · " + model : "");
        String body = failed ? error : incognito ? "Open OmniDeck to read it." : text;
        post(ID_REPLY, CHANNEL_REPLIES, IconDrawable.NAV_COMMS, title, body.length() > 0 ? body : "(empty reply)",
                Notification.CATEGORY_MESSAGE, MainActivity.TAB_COMMS, failed ? "Reply failed" : "Reply ready");
    }

    /** A model download finished or failed while the app was in the background. */
    void download(String model, boolean ok, String detail) {
        post(ID_DOWNLOAD, CHANNEL_REPLIES, IconDrawable.DOWNLOAD, ok ? "Model downloaded" : "Download failed",
                ok ? model + " is ready on the PC." : model + ": " + detail, Notification.CATEGORY_PROGRESS,
                MainActivity.TAB_MODELS, ok ? "Model downloaded" : "Download failed");
    }

    /** A /timer went off while the app was in the background (each timer gets its own notification). */
    void timer(long id, String message) {
        post(ID_TIMER + (int) (id % 100000), CHANNEL_TIMERS, IconDrawable.HISTORY, "Time's up", message,
                Notification.CATEGORY_ALARM, MainActivity.TAB_COMMS, "Time's up");
    }

    /** Takes back the reply / download notifications (the user is looking at the app now). */
    void clearFinished() {
        NotificationManager nm = manager();
        if (nm == null) return;
        try {
            nm.cancel(ID_REPLY);
            nm.cancel(ID_DOWNLOAD);
        } catch (RuntimeException ignored) {
        }
    }

    private void post(int id, String channel, int icon, String title, String text, String category, int tab,
                      String publicTitle) {
        if (!canPost()) return;
        NotificationManager nm = manager();
        if (nm == null) return;
        ensureChannels(nm);
        boolean alarm = CHANNEL_TIMERS.equals(channel);
        String shown = Fmt.ellipsize(text, 1000);
        try {
            Notification.Builder b = builder(channel, icon)
                    .setContentTitle(title)
                    .setContentText(shown)
                    .setStyle(new Notification.BigTextStyle().bigText(shown))
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setCategory(category)
                    .setContentIntent(open(tab))
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    // The lock screen shows only what happened, not the text.
                    .setPublicVersion(builder(channel, icon).setContentTitle(publicTitle)
                            .setContentText("OmniDeck").build());
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(alarm ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
                b.setDefaults(Notification.DEFAULT_SOUND);
            }
            nm.notify(id, b.build());
        } catch (RuntimeException ignored) {
            // A broken notification service must never take the app down.
        }
    }

    private Notification.Builder builder(String channel, int icon) {
        Notification.Builder b = new Notification.Builder(app);
        Icon small = smallIcon(icon);
        if (small != null) b.setSmallIcon(small);
        else b.setSmallIcon(android.R.drawable.stat_notify_chat);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, channel);
            } catch (ReflectiveOperationException ignored) {
            } catch (RuntimeException ignored) {
            }
        }
        return b;
    }

    /** The app's own line icon as a status-bar glyph. */
    private Icon smallIcon(int kind) {
        try {
            return Icon.createWithBitmap(glyph(app, kind));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * An IconDrawable glyph at status-bar size (24dp), drawn opaque on
     * transparent: only its alpha counts, the system tints it.
     */
    static Bitmap glyph(Context c, int kind) {
        int px = Math.max(24, Math.round(24 * c.getResources().getDisplayMetrics().density));
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        IconDrawable d = new IconDrawable(kind, Color.WHITE, Color.WHITE, px);
        d.stroke(2.2f);
        d.setBounds(0, 0, px, px);
        d.draw(new Canvas(bmp));
        return bmp;
    }

    /** Opens (or brings back) the app on {@code tab}. */
    private PendingIntent open(int tab) {
        Intent i = new Intent(app, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TAB, tab);
        // FLAG_IMMUTABLE exists from API 23 (minSdk) and is required from Android 12.
        return PendingIntent.getActivity(app, 100 + tab, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Android 8+ files notifications under channels the user can tune; NotificationChannel is API 26. */
    private void ensureChannels(NotificationManager nm) {
        if (channelsMade || Build.VERSION.SDK_INT < 26) return;
        try {
            Class<?> cls = Class.forName("android.app.NotificationChannel");
            Constructor<?> make = cls.getConstructor(String.class, CharSequence.class, int.class);
            Method describe = cls.getMethod("setDescription", String.class);
            Method create = NotificationManager.class.getMethod("createNotificationChannel", cls);
            Object replies = make.newInstance(CHANNEL_REPLIES, "Replies and downloads", IMPORTANCE_DEFAULT);
            describe.invoke(replies, "When a reply or a model download finishes while OmniDeck is in the background.");
            create.invoke(nm, replies);
            Object timers = make.newInstance(CHANNEL_TIMERS, "Timers", IMPORTANCE_HIGH);
            describe.invoke(timers, "When a timer set with /timer goes off.");
            create.invoke(nm, timers);
            channelsMade = true;
        } catch (ReflectiveOperationException ignored) {
        } catch (RuntimeException ignored) {
        }
    }

    private NotificationManager manager() {
        return (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
