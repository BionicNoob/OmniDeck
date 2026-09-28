package com.omnideck.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Rings a /timer when its alarm goes off — also when OmniDeck is in the
 * background or its process was closed: the alarm (AlarmManager) starts the
 * app just for this, and the Engine posts the notification. Not exported;
 * only the app's own alarms reach it.
 */
public final class TimerReceiver extends BroadcastReceiver {
    static final String ACTION = "com.omnideck.mobile.TIMER";
    static final String EXTRA_ID = "timer_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        long id = intent.getLongExtra(EXTRA_ID, -1);
        if (id > 0) Engine.get(context).timerAlarm(id);
    }
}
