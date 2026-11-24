package com.gridgaming.lounge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 41;
    private static final int REQ_PERMS = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoBackupTask = new Runnable() {
        @Override
        public void run() {
            final Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Store.autoBackup(app);
                }
            }).start();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Store.ensureChannels(this);
        applyDisplayPrefs();

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1020);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        web.setWebChromeClient(new Chrome());
        if (saved != null) web.restoreState(saved);
        else web.loadUrl("file:///android_asset/index.html");

        requestStartupPermissions();
    }

    // ---------- display: rotation & keep-awake ----------

    private void applyDisplayPrefs() {
        SharedPreferences p = Store.prefs(this);
        String mode = p.getString("orientation", "auto");
        int o;
        if ("always".equals(mode)) o = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        else if ("portrait".equals(mode)) o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        else if ("landscape".equals(mode)) o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        else o = ActivityInfo.SCREEN_ORIENTATION_FULL_USER; // follow the phone's auto-rotate setting
        setRequestedOrientation(o);
        if (p.getBoolean("keepOn", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void requestStartupPermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
        } else if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        callJs("window.onNativeSettingsChanged&&window.onNativeSettingsChanged()");
    }

    private void callJs(final String js) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(js, null);
            }
        });
    }

    // ---------- lifecycle ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        callJs("window.onNativeSettingsChanged&&window.onNativeSettingsChanged()");
    }

    @Override
    protected void onPause() {
        // Do NOT pause WebView timers: sessions must keep counting in the background.
        main.removeCallbacks(autoBackupTask);
        autoBackupTask.run();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        // Keep the app running in the background instead of closing it.
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------- file picker for "Restore backup file" ----------

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(r);
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
            try {
                startActivityForResult(i, REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public boolean onJsAlert(WebView v, String url, String msg, final JsResult result) {
            new AlertDialog.Builder(MainActivity.this, R.style.DialogTheme)
                    .setTitle("Grid Gaming Lounge")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result.confirm(); }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView v, String url, String msg, final JsResult result) {
            new AlertDialog.Builder(MainActivity.this, R.style.DialogTheme)
                    .setTitle("Please confirm")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result.confirm(); }
                    })
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result.cancel(); }
                    })
                    .show();
            return true;
        }
    }

    // ---------- JavaScript bridge ----------

    private class Bridge {
        @JavascriptInterface
        public String loadState() {
            return Store.readState(getApplicationContext());
        }

        @JavascriptInterface
        public boolean saveState(String json) {
            try {
                Store.writeState(getApplicationContext(), json);
            } catch (Exception e) {
                return false;
            }
            main.removeCallbacks(autoBackupTask);
            main.postDelayed(autoBackupTask, 15000);
            return true;
        }

        @JavascriptInterface
        public void syncSessions(String json) {
            Store.syncSessions(getApplicationContext(), json);
        }

        @JavascriptInterface
        public String exportBackup(String json, String filename) {
            try {
                Store.writeDownload(getApplicationContext(), filename, json, null);
                return "Saved to " + Store.backupLocation() + "/" + filename;
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String backupNow() {
            String w = Store.autoBackup(getApplicationContext());
            return w.isEmpty() ? "ERROR: " + Store.prefs(getApplicationContext()).getString("autoError", "no data yet")
                    : "Auto-backup updated in " + Store.backupLocation();
        }

        @JavascriptInterface
        public String getSettings() {
            Context c = getApplicationContext();
            SharedPreferences p = Store.prefs(c);
            JSONObject o = new JSONObject();
            try {
                o.put("orientation", p.getString("orientation", "auto"));
                o.put("keepOn", p.getBoolean("keepOn", true));
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                o.put("notifications", nm.areNotificationsEnabled());
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                o.put("batteryUnrestricted", pm.isIgnoringBatteryOptimizations(c.getPackageName()));
                o.put("exactAlarms", Store.canExact(c));
                long at = p.getLong("autoAt", 0);
                o.put("lastAutoBackup", at == 0 ? "" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(at)));
                o.put("autoBackupError", p.getString("autoError", ""));
                o.put("backupLocation", Store.backupLocation());
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void setOrientation(String mode) {
            Store.prefs(getApplicationContext()).edit().putString("orientation", mode).apply();
            main.post(new Runnable() { public void run() { applyDisplayPrefs(); } });
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean on) {
            Store.prefs(getApplicationContext()).edit().putBoolean("keepOn", on).apply();
            main.post(new Runnable() { public void run() { applyDisplayPrefs(); } });
        }

        @JavascriptInterface
        public void openBatterySettings() {
            main.post(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        openAppSettings();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            main.post(new Runnable() {
                public void run() {
                    if (Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            && shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
                        return;
                    }
                    try {
                        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                        startActivity(i);
                    } catch (Exception e) {
                        openAppSettings();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openAppInfo() {
            main.post(new Runnable() { public void run() { openAppSettings(); } });
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }
}
