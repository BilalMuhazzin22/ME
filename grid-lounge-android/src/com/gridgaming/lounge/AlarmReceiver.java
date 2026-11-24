package com.gridgaming.lounge;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fires the 5-minute warning and time-up alarms, even when the app is closed or the phone is asleep. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        int station = intent.getIntExtra("station", -1);
        String kind = intent.getStringExtra("kind");
        long endsAt = intent.getLongExtra("endsAt", 0);
        if (station < 0) return;

        // Ignore alarms for sessions that were finished or extended since scheduling.
        JSONArray arr = Store.sessions(c);
        String name = null;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject s = arr.optJSONObject(k);
            if (s != null && s.optInt("i", -1) == station && s.optLong("endsAt", 0) == endsAt) {
                name = s.optString("name", "Station " + (station + 1));
            }
        }
        if (name == null) return;

        Store.ensureChannels(c);
        boolean end = "end".equals(kind);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, Store.CH_ALARM)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setColor(end ? 0xFFEF4444 : 0xFFF59E0B)
                .setContentTitle(end ? "TIME UP — " + name : name + " ends in 5 minutes")
                .setContentText(end ? "Session time expired. Finish or extend the session." : "Session is ending soon.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setWhen(endsAt)
                .setShowWhen(true)
                .setContentIntent(Store.openAppIntent(c, 300 + station));
        if (Build.VERSION.SDK_INT < 26) {
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    .setVibrate(new long[]{0, 600, 300, 600, 300, 600});
        }
        Notification n = b.build();
        if (end) n.flags |= Notification.FLAG_INSISTENT; // keep ringing until tapped or dismissed
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            nm.notify((end ? 100 : 200) + station, n);
            if (end) nm.cancel(200 + station);
        } catch (SecurityException ignored) {
            // notifications permission denied
        }
        SessionService.refresh(c, true);
    }
}
