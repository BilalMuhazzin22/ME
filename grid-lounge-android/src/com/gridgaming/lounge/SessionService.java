package com.gridgaming.lounge;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/** Foreground service that keeps the app alive while any station has a running session. */
public class SessionService extends Service {
    private static final int ID = 1;

    static void refresh(Context c, boolean anyActive) {
        Intent i = new Intent(c, SessionService.class);
        try {
            if (anyActive) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } else {
                c.stopService(i);
            }
        } catch (Exception ignored) {
            // Background start not allowed right now; alarms still fire via AlarmManager.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        JSONArray arr = Store.sessions(this);
        Notification n = build(arr);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (arr.length() == 0) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private Notification build(JSONArray arr) {
        Store.ensureChannels(this);
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        String nextName = null;
        int expired = 0;
        StringBuilder lines = new StringBuilder();
        DateFormat tf = DateFormat.getTimeInstance(DateFormat.SHORT);
        for (int k = 0; k < arr.length(); k++) {
            JSONObject s = arr.optJSONObject(k);
            if (s == null) continue;
            long e = s.optLong("endsAt", 0);
            String name = s.optString("name", "Station");
            if (lines.length() > 0) lines.append('\n');
            if (e <= now) {
                expired++;
                lines.append(name).append(" — TIME UP");
            } else {
                lines.append(name).append(" — ends ").append(tf.format(new Date(e)));
                if (e < next) { next = e; nextName = name; }
            }
        }
        int count = arr.length();
        String title = count + (count == 1 ? " station running" : " stations running");
        String text;
        if (nextName != null) {
            text = "Next: " + nextName + " ends " + tf.format(new Date(next));
            if (expired > 0) text = expired + " time up · " + text;
        } else {
            text = expired > 0 ? expired + " station(s) time up — finish or extend" : "Tap to open";
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, Store.CH_RUNNING)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFF22C55E)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(lines.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(Store.openAppIntent(this, 1));
        if (nextName != null) {
            b.setWhen(next).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
