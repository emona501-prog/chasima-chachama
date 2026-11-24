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
        setContentView(web);
        web.loadUrl(BuildConfig.SITE + tabHash(getIntent()));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        askBattery();
        if (!prefs(this).getString("id", "").isEmpty()) AlertService.start(this);
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

        @JavascriptInterface
        public String getDevice() {
            return prefs(MainActivity.this).getString("id", "");
        }

        @JavascriptInterface
        public boolean isApp() {
            return true;
        }
    }
}
