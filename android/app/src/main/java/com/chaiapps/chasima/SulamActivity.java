package com.chaiapps.chasima;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.Locale;

/** "הסולם שלי" – סדר יום, ניקוד, סולם וקופה. המסך עצמו הוא דף HTML מתוך assets/sulam. */
public class SulamActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsOk = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF1A1046);
        getWindow().setNavigationBarColor(0xFF140C3C);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#1A1046"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Sulam");
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/sulam/index.html");

        tts = new TextToSpeech(getApplicationContext(), st -> {
            if (st != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(new Locale("he", "IL"));
            if (r < 0) r = tts.setLanguage(new Locale("iw", "IL"));
            ttsOk = r >= 0;
            tts.setSpeechRate(1.0f);
            tts.setPitch(1.05f);
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.onBack&&window.onBack())?1:0", v -> {
            if (!"1".equals(v)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) {}
        if (web != null) web.destroy();
        super.onDestroy();
    }

    class Bridge {
        @JavascriptInterface
        public String id() { return Cfg.deviceId(SulamActivity.this); }

        @JavascriptInterface
        public String kidName() { return Cfg.cfg(SulamActivity.this).optString("name", ""); }

        @JavascriptInterface
        public boolean pinOk(String p) {
            String pin = Cfg.cfg(SulamActivity.this).optString("pin", "");
            return !pin.isEmpty() && pin.equals(p);
        }

        @JavascriptInterface
        public boolean hasVoice() { return ttsOk; }

        @JavascriptInterface
        public void say(String txt) {
            if (!ttsOk || txt == null) return;
            tts.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "s" + System.currentTimeMillis());
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(Math.min(ms, 1000), VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(Math.min(ms, 1000));
            } catch (Exception ignored) {}
        }

        /** הודעה לאבא בצ'אט הקיים (אבא מקבל התראה). */
        @JavascriptInterface
        public void chat(String txt) {
            if (txt == null || txt.trim().isEmpty()) return;
            new Thread(() -> ChatActivity.sendMsg(getApplicationContext(), txt)).start();
        }

        /** בקשה לשרת. מותר רק בתוך d/<הנגן הזה>/s/ . התשובה חוזרת ל-window.__scb */
        @JavascriptInterface
        public void http(final String cbId, final String method, final String path, final String body) {
            new Thread(() -> {
                int ok = 1; String res;
                try {
                    String mine = "d/" + Cfg.deviceId(getApplicationContext()) + "/s/";
                    if (path == null || !path.startsWith(mine) || path.contains("..")) throw new Exception("נתיב לא מורשה");
                    res = Fb.raw(method, path, body == null || body.isEmpty() ? null : body);
                } catch (Exception e) { ok = 0; res = String.valueOf(e.getMessage()); }
                final String js = "window.__scb&&__scb(" + JSONObject.quote(cbId) + "," + ok + "," + JSONObject.quote(res == null ? "" : res) + ")";
                runOnUiThread(() -> { try { web.evaluateJavascript(js, null); } catch (Exception ignored) {} });
            }).start();
        }
    }
}
