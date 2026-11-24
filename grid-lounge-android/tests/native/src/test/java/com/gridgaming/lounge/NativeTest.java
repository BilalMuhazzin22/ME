package com.gridgaming.lounge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowWebView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Runs the app's real Android code (Store, receivers, service, activity, JS bridge) on Android 7, 9 and 14. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34})
public class NativeTest {
    private Application app;
    private AlarmManager am;
    private NotificationManager nm;
    private long now;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        am = (AlarmManager) app.getSystemService(android.content.Context.ALARM_SERVICE);
        nm = (NotificationManager) app.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 31) ShadowAlarmManager.setCanScheduleExactAlarms(true);
        Store.prefs(app).edit().clear().commit();
        now = System.currentTimeMillis();
    }

    // ---------- helpers ----------
    private static Object call(Object o, String getter, String field) throws Exception {
        try { Method m = o.getClass().getMethod(getter); m.setAccessible(true); return m.invoke(o); }
        catch (NoSuchMethodException e) { Field f = o.getClass().getField(field); return f.get(o); }
    }
    private List<?> alarms() { return shadowOf(am).getScheduledAlarms(); }
    private long trigger(Object a) throws Exception { return ((Number) call(a, "getTriggerAtMs", "triggerAtTime")).longValue(); }
    private PendingIntent op(Object a) throws Exception { return (PendingIntent) call(a, "getOperation", "operation"); }
    private Object clockInfo(Object a) throws Exception { return call(a, "getAlarmClockInfo", "alarmClockInfo"); }
    private String session(int i, String name, long endsAt, boolean paused) throws Exception {
        return new JSONObject().put("i", i).put("name", name).put("endsAt", endsAt).put("paused", paused).toString();
    }
    private String list(String... s) { return "[" + String.join(",", s) + "]"; }
    private Intent alarmIntentFor(String kind) throws Exception {
        for (Object a : alarms()) {
            Intent it = shadowOf(op(a)).getSavedIntent();
            if (kind.equals(it.getStringExtra("kind"))) return it;
        }
        return null;
    }
    private void idle() { shadowOf(Looper.getMainLooper()).idle(); }

    // ---------- storage ----------
    @Test public void stateFileRoundTripAndOverwrite() throws Exception {
        assertEquals("", Store.readState(app));
        Store.writeState(app, "{\"a\":1,\"name\":\"₹ ठीक\"}");
        assertEquals("{\"a\":1,\"name\":\"₹ ठीक\"}", Store.readState(app));
        Store.writeState(app, "{\"b\":2}");
        assertEquals("{\"b\":2}", Store.readState(app));
        assertFalse("temp file cleaned up", new File(app.getFilesDir(), Store.STATE_FILE + ".tmp").exists());
    }

    // ---------- alarms ----------
    @Test public void runningSessionsGetWarningAndEndAlarms() throws Exception {
        long e1 = now + 30 * 60000L, e3 = now + 3 * 60000L;
        Store.syncSessions(app, list(session(1, "PS Screen 1", e1, false), session(3, "PS Screen 3", e3, false)));
        List<Long> times = new ArrayList<>();
        for (Object a : alarms()) times.add(trigger(a));
        assertEquals("warn+end for PS1, end only for PS3 (5-min warning already passed)", 3, times.size());
        assertTrue(times.contains(e1 - 5 * 60000L));
        assertTrue(times.contains(e1));
        assertTrue(times.contains(e3));
        for (Object a : alarms()) assertNotNull("uses exact alarm-clock alarms", clockInfo(a));
        Intent svc = shadowOf(app).getNextStartedService();
        assertNotNull("background service started", svc);
        assertEquals(SessionService.class.getName(), svc.getComponent().getClassName());
    }

    @Test public void pausedSessionHasNoAlarms() throws Exception {
        Store.syncSessions(app, list(session(2, "PS Screen 2", now + 40 * 60000L, true)));
        assertEquals(0, alarms().size());
    }

    @Test public void finishingAllSessionsCancelsAlarmsAndStopsService() throws Exception {
        Store.syncSessions(app, list(session(1, "PS Screen 1", now + 30 * 60000L, false)));
        assertEquals(2, alarms().size());
        Store.syncSessions(app, "[]");
        assertEquals(0, alarms().size());
        Intent stopped = shadowOf(app).getNextStoppedService();
        assertNotNull("service stopped", stopped);
        assertEquals(SessionService.class.getName(), stopped.getComponent().getClassName());
    }

    @Test public void extendingReschedulesToTheNewEndTime() throws Exception {
        long e = now + 20 * 60000L;
        Store.syncSessions(app, list(session(4, "PS Screen 4", e, false)));
        Store.syncSessions(app, list(session(4, "PS Screen 4", e + 30 * 60000L, false)));
        List<Long> times = new ArrayList<>();
        for (Object a : alarms()) times.add(trigger(a));
        assertEquals(2, times.size());
        assertTrue(times.contains(e + 30 * 60000L));
        assertFalse("old end alarm removed", times.contains(e));
    }

    @Test public void fallsBackWhenExactAlarmsNotAllowed() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return; // exact alarms are always allowed before Android 12
        ShadowAlarmManager.setCanScheduleExactAlarms(false);
        Store.syncSessions(app, list(session(1, "PS Screen 1", now + 30 * 60000L, false)));
        assertEquals("still scheduled (inexact, allowed while idle)", 2, alarms().size());
        for (Object a : alarms()) assertNull(clockInfo(a));
    }

    // ---------- alarm notifications ----------
    @Test public void timeUpAlarmPostsInsistentAlarmNotification() throws Exception {
        long e = now + 30 * 60000L;
        Store.syncSessions(app, list(session(1, "PS Screen 1", e, false)));
        new AlarmReceiver().onReceive(app, alarmIntentFor("end"));
        Notification n = shadowOf(nm).getNotification(101);
        assertNotNull("time-up notification posted", n);
        assertEquals("TIME UP — PS Screen 1", n.extras.getString(Notification.EXTRA_TITLE));
        assertTrue("keeps ringing until dismissed", (n.flags & Notification.FLAG_INSISTENT) != 0);
        assertEquals(Notification.CATEGORY_ALARM, n.category);
        assertNotNull("tapping opens the app", n.contentIntent);
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(Store.CH_ALARM, n.getChannelId());
            NotificationChannel ch = nm.getNotificationChannel(Store.CH_ALARM);
            assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.getImportance());
            assertEquals("plays as an alarm sound", AudioAttributes.USAGE_ALARM, ch.getAudioAttributes().getUsage());
            assertTrue(ch.shouldVibrate());
        } else {
            assertNotNull("sound set on older Android", n.sound);
        }
    }

    @Test public void fiveMinuteWarningNotification() throws Exception {
        Store.syncSessions(app, list(session(2, "PS Screen 2", now + 30 * 60000L, false)));
        new AlarmReceiver().onReceive(app, alarmIntentFor("warn"));
        Notification n = shadowOf(nm).getNotification(202);
        assertNotNull(n);
        assertEquals("PS Screen 2 ends in 5 minutes", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("warning does not ring endlessly", 0, n.flags & Notification.FLAG_INSISTENT);
    }

    @Test public void staleAlarmAfterExtensionIsIgnored() throws Exception {
        long e = now + 30 * 60000L;
        Store.syncSessions(app, list(session(1, "PS Screen 1", e, false)));
        Intent old = new Intent(alarmIntentFor("end")); // copy: Android updates the stored intent in place
        Store.syncSessions(app, list(session(1, "PS Screen 1", e + 30 * 60000L, false)));
        new AlarmReceiver().onReceive(app, old);
        assertNull("no false alarm for the old end time", shadowOf(nm).getNotification(101));
    }

    @Test public void alarmForPausedOrFinishedSessionIsIgnored() throws Exception {
        long e = now + 30 * 60000L;
        Store.syncSessions(app, list(session(1, "PS Screen 1", e, false)));
        Intent end = alarmIntentFor("end");
        Store.syncSessions(app, list(session(1, "PS Screen 1", e, true)));
        new AlarmReceiver().onReceive(app, end);
        assertNull("paused → silent", shadowOf(nm).getNotification(101));
        Store.syncSessions(app, "[]");
        new AlarmReceiver().onReceive(app, end);
        assertNull("finished → silent", shadowOf(nm).getNotification(101));
    }

    @Test public void extendingClearsARingingTimeUpNotification() throws Exception {
        long e = now + 30 * 60000L;
        Store.syncSessions(app, list(session(1, "PS Screen 1", e, false)));
        new AlarmReceiver().onReceive(app, alarmIntentFor("end"));
        assertNotNull(shadowOf(nm).getNotification(101));
        Store.syncSessions(app, list(session(1, "PS Screen 1", now + 60 * 60000L, false)));
        assertNull("ringing stops when the session is extended", shadowOf(nm).getNotification(101));
    }

    // ---------- background service ----------
    @Test public void foregroundServiceShowsAllStations() throws Exception {
        Store.prefs(app).edit().putString("sessions", list(
                session(0, "Steering Simulator", now + 40 * 60000L, false),
                session(1, "PS Screen 1", now + 10 * 60000L, false),
                session(2, "PS Screen 2", now - 60000L, false),
                session(3, "PS Screen 3", now + 50 * 60000L, true))).commit();
        SessionService svc = Robolectric.buildService(SessionService.class).create().startCommand(0, 1).get();
        Notification n = shadowOf(svc).getLastForegroundNotification();
        assertNotNull("runs as a foreground service", n);
        assertEquals("4 stations running", n.extras.getString(Notification.EXTRA_TITLE));
        String text = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(text, text.contains("Next: PS Screen 1"));
        assertTrue(text, text.contains("1 time up"));
        String big = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        assertTrue(big, big.contains("PS Screen 2 — TIME UP"));
        assertTrue(big, big.contains("PS Screen 3 — paused"));
        assertTrue("countdown chronometer", n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue((n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertFalse(shadowOf(svc).isStoppedBySelf());
    }

    @Test public void serviceStopsItselfWhenNothingIsRunning() {
        SessionService svc = Robolectric.buildService(SessionService.class).create().startCommand(0, 1).get();
        assertTrue(shadowOf(svc).isStoppedBySelf());
    }

    @Test public void rebootRestoresAlarms() throws Exception {
        Store.prefs(app).edit().putString("sessions", list(session(5, "PS Screen 5", now + 45 * 60000L, false))).commit();
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals(2, alarms().size());
        assertNotNull(shadowOf(app).getNextStartedService());
    }

    // ---------- manifest ----------
    @Test public void manifestDeclaresRotationBackgroundAndPermissions() throws Exception {
        PackageManager pm = app.getPackageManager();
        ActivityInfo ai = pm.getActivityInfo(new ComponentName(app, MainActivity.class), 0);
        assertEquals("follows phone auto-rotate by default", ActivityInfo.SCREEN_ORIENTATION_FULL_USER, ai.screenOrientation);
        assertTrue("rotation does not restart the app", (ai.configChanges & ActivityInfo.CONFIG_ORIENTATION) != 0 && (ai.configChanges & ActivityInfo.CONFIG_SCREEN_SIZE) != 0);
        ServiceInfo si = pm.getServiceInfo(new ComponentName(app, SessionService.class), 0);
        if (Build.VERSION.SDK_INT >= 34) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, si.getForegroundServiceType());
        String[] perms = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
        List<String> p = java.util.Arrays.asList(perms);
        for (String need : new String[]{"android.permission.POST_NOTIFICATIONS", "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.USE_EXACT_ALARM", "android.permission.WAKE_LOCK", "android.permission.VIBRATE"})
            assertTrue("missing " + need, p.contains(need));
    }

    // ---------- activity + JavaScript bridge ----------
    private MainActivity activity;
    private WebView web;
    private Object bridge;
    private Object js(String method, Object... args) throws Exception {
        for (Method m : bridge.getClass().getDeclaredMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(bridge, args); }
        }
        throw new AssertionError("bridge method missing: " + method);
    }
    private void launch() {
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        web = (WebView) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        bridge = shadowOf(web).getJavascriptInterface("AndroidBridge");
    }

    @Test public void appOpensTheLoungeScreenWithStorageAndScriptsOn() {
        launch();
        assertEquals("file:///android_asset/index.html", shadowOf(web).getLastLoadedUrl());
        assertTrue(web.getSettings().getJavaScriptEnabled());
        assertTrue("localStorage enabled", web.getSettings().getDomStorageEnabled());
        assertFalse("alarm sound can play without a tap", web.getSettings().getMediaPlaybackRequiresUserGesture());
        assertNotNull("bridge registered", bridge);
        assertTrue("screen kept on by default", (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
    }

    @Test public void bridgeSavesAndLoadsData() throws Exception {
        launch();
        assertEquals(true, js("saveState", "{\"records\":[1,2,3]}"));
        assertEquals("{\"records\":[1,2,3]}", js("loadState"));
        assertEquals("{\"records\":[1,2,3]}", Store.readState(app));
    }

    @Test public void bridgeReportsSettings() throws Exception {
        launch();
        JSONObject s = new JSONObject((String) js("getSettings"));
        for (String k : new String[]{"orientation", "keepOn", "notifications", "batteryUnrestricted", "exactAlarms", "backupLocation"}) assertTrue(k, s.has(k));
        assertEquals("auto", s.getString("orientation"));
    }

    @Test public void rotationOptionsWork() throws Exception {
        launch();
        String[][] cases = {{"always", "" + ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR}, {"portrait", "" + ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT},
                {"landscape", "" + ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE}, {"auto", "" + ActivityInfo.SCREEN_ORIENTATION_FULL_USER}};
        for (String[] c : cases) {
            js("setOrientation", c[0]); idle();
            assertEquals(c[0], Integer.parseInt(c[1]), activity.getRequestedOrientation());
            assertEquals(c[0], new JSONObject((String) js("getSettings")).getString("orientation"));
        }
        js("setOrientation", "landscape"); idle();
        MainActivity relaunched = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals("rotation choice remembered after restart", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, relaunched.getRequestedOrientation());
    }

    @Test public void keepScreenOnToggle() throws Exception {
        launch();
        js("setKeepScreenOn", false); idle();
        assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        js("setKeepScreenOn", true); idle();
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
    }

    @Test public void bridgeSchedulesAlarms() throws Exception {
        launch();
        js("syncSessions", list(session(1, "PS Screen 1", now + 30 * 60000L, false)));
        assertEquals(2, alarms().size());
    }

    @Test public void whatsAppCallAndShareOpenOtherApps() throws Exception {
        launch();
        ShadowActivity sa = shadowOf(activity);
        js("openUrl", "https://wa.me/919876543210?text=Hi"); idle();
        Intent wa = sa.getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, wa.getAction());
        assertEquals("https://wa.me/919876543210?text=Hi", wa.getDataString());
        js("openUrl", "tel:9876543210"); idle();
        assertEquals("tel:9876543210", sa.getNextStartedActivity().getDataString());
        js("shareText", "Invoice #1001 Total ₹261"); idle();
        Intent chooser = sa.getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals("Invoice #1001 Total ₹261", inner.getStringExtra(Intent.EXTRA_TEXT));
    }

    @Test public void backupAndCsvSaveToDownloads() throws Exception {
        if (Build.VERSION.SDK_INT < 29) shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        else {
            FakeMediaStore.inserted.clear();
            FakeMediaStore.dir = Files.createTempDirectory("media").toFile();
            android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
            info.authority = "media";
            Robolectric.buildContentProvider(FakeMediaStore.class).create(info);
        }
        launch();
        String r1 = (String) js("exportBackup", "{\"data\":1}", "backup-test.json");
        assertEquals("Saved to Download/GridGamingLounge/backup-test.json", r1);
        String r2 = (String) js("saveFile", "report.csv", "text/csv", "Type,Total\r\nBill,100");
        assertEquals("Saved to Download/GridGamingLounge/report.csv", r2);
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(2, FakeMediaStore.inserted.size());
            android.content.ContentValues csv = FakeMediaStore.inserted.get(1);
            assertEquals("report.csv", csv.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME));
            assertEquals("text/csv", csv.getAsString(android.provider.MediaStore.MediaColumns.MIME_TYPE));
            assertEquals("Download/GridGamingLounge", csv.getAsString(android.provider.MediaStore.MediaColumns.RELATIVE_PATH));
            assertEquals("application/json", FakeMediaStore.inserted.get(0).getAsString(android.provider.MediaStore.MediaColumns.MIME_TYPE));
            assertEquals("Type,Total\r\nBill,100", new String(Files.readAllBytes(new File(FakeMediaStore.dir, "2.bin").toPath()), "UTF-8"));
        }
        if (Build.VERSION.SDK_INT < 29) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GridGamingLounge");
            assertEquals("Type,Total\r\nBill,100", new String(Files.readAllBytes(new File(dir, "report.csv").toPath()), "UTF-8"));
            assertEquals("{\"data\":1}", new String(Files.readAllBytes(new File(dir, "backup-test.json").toPath()), "UTF-8"));
        }
        js("saveState", "{\"x\":1}");
        String auto = (String) js("backupNow");
        assertTrue(auto, auto.startsWith("Auto-backup updated"));
        assertTrue(new JSONObject((String) js("getSettings")).getString("lastAutoBackup").length() > 0);
    }

    @Test public void backButtonAsksTheScreenFirst() {
        launch();
        activity.onBackPressed();
        String script = shadowOf(web).getLastEvaluatedJavascript();
        assertNotNull(script);
        assertTrue(script, script.contains("handleBack"));
    }

    @Test public void restoreBackupFilePickerReturnsTheFile() {
        launch();
        WebChromeClient chrome = shadowOf(web).getWebChromeClient();
        final Uri[][] got = {null};
        boolean handled = chrome.onShowFileChooser(web, new ValueCallback<Uri[]>() { public void onReceiveValue(Uri[] v) { got[0] = v; } }, null);
        assertTrue(handled);
        Intent pick = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.getAction());
        Uri file = Uri.parse("content://com.android.providers.downloads.documents/document/42");
        shadowOf(activity).receiveResult(pick, Activity.RESULT_OK, new Intent().setData(file));
        assertNotNull("file handed to the screen", got[0]);
        assertEquals(file, got[0][0]);
    }

    @Test public void confirmDialogsWork() {
        launch();
        WebChromeClient chrome = shadowOf(web).getWebChromeClient();
        final Boolean[] answer = {null};
        JsResult result = new JsResult(new JsResult.ResultReceiver() { public void onJsResultComplete(JsResult r) { answer[0] = r.getResult(); } });
        assertTrue(chrome.onJsConfirm(web, "file:///android_asset/index.html", "Delete this entry?", result));
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(d);
        assertEquals("Delete this entry?", shadowOf(d).getMessage().toString());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        assertEquals(Boolean.TRUE, answer[0]);
    }

    @Test public void printDoesNotCrash() throws Exception {
        launch();
        js("printHtml", "<html><body>Receipt</body></html>", "Receipt-1001"); idle();
    }
}
