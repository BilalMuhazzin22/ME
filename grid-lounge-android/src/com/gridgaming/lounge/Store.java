package com.gridgaming.lounge;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared native storage, alarm and backup helpers. */
final class Store {
    static final String PREFS = "grid_native";
    static final String STATE_FILE = "lounge-state.json";
    static final String CH_ALARM = "session_alarms_v1";
    static final String CH_RUNNING = "session_running_v1";
    static final String BACKUP_DIR = "GridGamingLounge";
    static final int STATIONS = 6;

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- state file (mirror of the web app's localStorage) ----------

    static synchronized void writeState(Context c, String json) throws Exception {
        File dir = c.getFilesDir();
        File tmp = new File(dir, STATE_FILE + ".tmp");
        File dst = new File(dir, STATE_FILE);
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(dst)) {
            throw new Exception("rename failed");
        }
    }

    static synchronized String readState(Context c) {
        File f = new File(c.getFilesDir(), STATE_FILE);
        if (!f.exists()) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(buf, 0, off, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- notification channels ----------

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            NotificationChannel a = new NotificationChannel(CH_ALARM, "Session alarms", NotificationManager.IMPORTANCE_HIGH);
            a.setDescription("5-minute warnings and time-up alarms for gaming stations");
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            a.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            a.enableVibration(true);
            a.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
            a.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            a.setBypassDnd(true);
            nm.createNotificationChannel(a);
        }
        if (nm.getNotificationChannel(CH_RUNNING) == null) {
            NotificationChannel r = new NotificationChannel(CH_RUNNING, "Running sessions", NotificationManager.IMPORTANCE_LOW);
            r.setDescription("Shows active stations while the app works in the background");
            r.setShowBadge(false);
            nm.createNotificationChannel(r);
        }
    }

    static PendingIntent openAppIntent(Context c, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- sessions & alarms ----------

    static JSONArray sessions(Context c) {
        try {
            return new JSONArray(prefs(c).getString("sessions", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static PendingIntent alarmIntent(Context c, int station, String kind, String name, long endsAt, boolean create) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction("com.gridgaming.lounge.ALARM_" + kind + "_" + station);
        i.putExtra("station", station);
        i.putExtra("kind", kind);
        i.putExtra("name", name);
        i.putExtra("endsAt", endsAt);
        int req = station * 2 + ("warn".equals(kind) ? 0 : 1);
        int flags = PendingIntent.FLAG_IMMUTABLE | (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am.canScheduleExactAlarms();
    }

    private static void setAlarm(Context c, long at, PendingIntent pi) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        try {
            if (canExact(c)) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, openAppIntent(c, 900)), pi);
                return;
            }
        } catch (SecurityException ignored) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** Replace all scheduled alarms with ones matching the given active sessions. */
    static void syncSessions(Context c, String json) {
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            arr = new JSONArray();
        }
        prefs(c).edit().putString("sessions", arr.toString()).apply();
        applyAlarms(c, arr);
        SessionService.refresh(c, arr.length() > 0);
    }

    static void applyAlarms(Context c, JSONArray arr) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean[] active = new boolean[STATIONS];
        long now = System.currentTimeMillis();
        for (int k = 0; k < arr.length(); k++) {
            JSONObject s = arr.optJSONObject(k);
            if (s == null) continue;
            int i = s.optInt("i", -1);
            if (i < 0 || i >= STATIONS) continue;
            active[i] = true;
            String name = s.optString("name", "Station " + (i + 1));
            long endsAt = s.optLong("endsAt", 0);
            PendingIntent warn = alarmIntent(c, i, "warn", name, endsAt, true);
            PendingIntent end = alarmIntent(c, i, "end", name, endsAt, true);
            am.cancel(warn);
            am.cancel(end);
            long warnAt = endsAt - 5 * 60000L;
            if (warnAt > now + 1000) setAlarm(c, warnAt, warn);
            if (endsAt > now + 1000) {
                setAlarm(c, endsAt, end);
                // Session was extended: clear a stale "time up" alarm notification.
                nm.cancel(100 + i);
                if (warnAt > now) nm.cancel(200 + i);
            }
        }
        for (int i = 0; i < STATIONS; i++) {
            if (active[i]) continue;
            PendingIntent w = alarmIntent(c, i, "warn", "", 0, false);
            PendingIntent e = alarmIntent(c, i, "end", "", 0, false);
            if (w != null) { am.cancel(w); w.cancel(); }
            if (e != null) { am.cancel(e); e.cancel(); }
            nm.cancel(100 + i);
            nm.cancel(200 + i);
        }
    }

    // ---------- backups to the public Downloads folder ----------

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static String backupLocation() {
        return "Download/" + BACKUP_DIR;
    }

    /** Writes text to Download/GridGamingLounge/<name>. If reuse is non-null, tries to overwrite that file first. Returns the file's uri/path. */
    static String writeDownload(Context c, String name, String text, String reuse) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            if (reuse != null && reuse.startsWith("content://")) {
                try {
                    OutputStream o = cr.openOutputStream(Uri.parse(reuse), "wt");
                    if (o != null) {
                        try { o.write(data); } finally { o.close(); }
                        return reuse;
                    }
                } catch (Exception ignored) {
                    // file deleted by user or not ours any more; create a new one
                }
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + BACKUP_DIR);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("Could not create file in Downloads");
            OutputStream o = cr.openOutputStream(uri, "wt");
            if (o == null) throw new Exception("Could not open file in Downloads");
            try { o.write(data); } finally { o.close(); }
            return uri.toString();
        }
        File dir;
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR);
        } else {
            dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR);
        }
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create backup folder");
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
        return f.getAbsolutePath();
    }

    /** Daily auto-backup: one file per day, overwritten with the latest data during that day. */
    static synchronized String autoBackup(Context c) {
        String state = readState(c);
        if (state.isEmpty()) return "";
        SharedPreferences p = prefs(c);
        String day = today();
        String reuse = day.equals(p.getString("autoDay", "")) ? p.getString("autoUri", null) : null;
        try {
            String wrapped = "{\"app\":\"Grid Gaming Lounge\",\"version\":1,\"autoBackup\":true,\"exportedAt\":\""
                    + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date())
                    + "\",\"data\":" + state + "}";
            String where = writeDownload(c, "Grid-Gaming-Lounge-auto-" + day + ".json", wrapped, reuse);
            p.edit().putString("autoDay", day).putString("autoUri", where)
                    .putLong("autoAt", System.currentTimeMillis()).remove("autoError").apply();
            return where;
        } catch (Exception e) {
            p.edit().putString("autoError", String.valueOf(e.getMessage())).apply();
            return "";
        }
    }
}
