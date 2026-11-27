package com.chaiapps.chasima.parent;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** אפליקציית ההורה: מציגה את האתר של חסימה חכמה ומפעילה התראות קופצות. */
public class MainActivity extends Activity {

    private WebView web;

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("parent", Context.MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0F1B3D);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0F1B3D"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.clearCache(true);
        web.addJavascriptInterface(new Bridge(), "ChasimaApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u.toString().startsWith(BuildConfig.SITE)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String url) {
                v.loadData("<html dir=rtl><body style='background:#0f1b3d;color:#fff;font-family:sans-serif;text-align:center;padding-top:40%'>"
                        + "<h2>אין חיבור לאינטרנט</h2><p>בדוק את החיבור ונסה שוב</p>"
                        + "<button style='padding:14px 30px;font-size:18px;border-radius:14px;border:0;background:#22c55e;color:#fff' "
                        + "onclick='location.href=\"" + BuildConfig.SITE + "\"'>נסה שוב</button></body></html>", "text/html; charset=utf-8", "UTF-8");
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams fp) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "בחר קובץ"), 77);
                } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(BuildConfig.SITE + tabHash(getIntent()));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        askBattery();
        if (!prefs(this).getString("id", "").isEmpty()) AlertService.start(this);
    }

    private android.webkit.ValueCallback<Uri[]> fileCb;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 77 && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCb.onReceiveValue(r);
            fileCb = null;
        }
    }

    private String tabHash(Intent i) {
        String t = i == null ? null : i.getStringExtra("tab");
        return t == null ? "" : "#" + t;
    }

    private void askBattery() {
        SharedPreferences p = prefs(this);
        if (p.getBoolean("askedBattery", false)) return;
        p.edit().putBoolean("askedBattery", true).apply();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String t = i.getStringExtra("tab");
        if (t != null) web.evaluateJavascript("try{tab('" + t.replace("'", "") + "')}catch(e){}", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private long pausedAt = 0;

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // חוזרים לאפליקציה אחרי זמן – טוענים מחדש כדי שהחיבור יהיה טרי
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 60000 && web != null) web.reload();
    }

    /** גשר בין האתר לאפליקציה: האתר מודיע לאיזה נגן התחברנו. */
    class Bridge {
        @JavascriptInterface
        public void setDevice(String id) {
            if (id == null || id.isEmpty()) return;
            prefs(MainActivity.this).edit().putString("id", id).apply();
            AlertService.start(MainActivity.this);
        }

        @JavascriptInterface
        public void clearDevice() {
            prefs(MainActivity.this).edit().remove("id").apply();
            stopService(new Intent(MainActivity.this, AlertService.class));
        }

        /** בקשה לשרת דרך האפליקציה עצמה (עוקף מגבלות של דפדפן). התשובה חוזרת ל-window.__fbcb. */
        @JavascriptInterface
        public void http(final String cbId, final String method, final String path, final String body) {
            new Thread(() -> {
                int ok = 1; String res;
                try { res = Fb.raw(method, path, body == null || body.isEmpty() ? null : body); }
                catch (Exception e) { ok = 0; res = String.valueOf(e.getMessage()); }
                final String js = "window.__fbcb&&__fbcb(" + org.json.JSONObject.quote(cbId) + "," + ok + "," + org.json.JSONObject.quote(res == null ? "" : res) + ")";
                runOnUiThread(() -> { try { web.evaluateJavascript(js, null); } catch (Exception ignored) {} });
            }).start();
        }

        @JavascriptInterface
        public boolean getNetAlerts() { return prefs(MainActivity.this).getBoolean("netAlerts", true); }

        @JavascriptInterface
        public void setNetAlerts(boolean on) { prefs(MainActivity.this).edit().putBoolean("netAlerts", on).putString("netState", "").apply(); }

        @JavascriptInterface
        public int appVersion() { return BuildConfig.VERSION_CODE; }

        /** עדכון האפליקציה מתוכה: הורדה והתקנה בלי דפדפן. התקדמות חוזרת ל-window.__updMsg */
        @JavascriptInterface
        public void selfUpdate(boolean onlyIfNew) {
            Updater.Progress pr = msg -> {
                String js = "window.__updMsg&&__updMsg(" + org.json.JSONObject.quote(msg) + ")";
                try { web.evaluateJavascript(js, null); } catch (Exception ignored) {}
            };
            if (onlyIfNew) Updater.checkAndUpdate(MainActivity.this, pr, false);
            else Updater.run(MainActivity.this, pr);
        }

        @JavascriptInterface
        public void checkUpdate() {
            new Thread(() -> {
                int latest = Updater.latestBuild();
                String js = "window.__updAvail&&__updAvail(" + latest + "," + BuildConfig.VERSION_CODE + ")";
                runOnUiThread(() -> { try { web.evaluateJavascript(js, null); } catch (Exception ignored) {} });
            }).start();
        }

        @JavascriptInterface
        public String getDevice() {
            return prefs(MainActivity.this).getString("id", "");
        }

        /** שמירת קובץ מהצ'אט לתיקיית ההורדות */
        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            try {
                byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                String safe = name == null || name.isEmpty() ? "file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    Uri u = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) return "";
                    try (java.io.OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data); }
                } else {
                    java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, safe))) { o.write(data); }
                }
                return safe;
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public boolean isApp() {
            return true;
        }
    }
}
