package com.omnideck.mobile;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps the process in the "service" state while a reply, a summary, a
 * benchmark or a model download is in progress, so switching apps for a
 * minute doesn't freeze or kill it mid-stream. It does nothing itself: the
 * work runs in {@link Engine}. It is started only while the app is visible
 * (Android 8+ refuses background starts) and stopped as soon as the work ends.
 */
public final class WorkService extends Service {
    private static boolean running;

    /** Starts the service when work begins and stops it when there's none left. */
    static void sync(Context c, boolean needed, boolean visible) {
        if (needed == running || (needed && !visible)) return;
        Intent i = new Intent(c, WorkService.class);
        try {
            if (needed) c.startService(i);
            else c.stopService(i);
            running = needed;
        } catch (RuntimeException ignored) {
            // Not allowed right now (e.g. a background start on Android 8+): the work continues anyway.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
