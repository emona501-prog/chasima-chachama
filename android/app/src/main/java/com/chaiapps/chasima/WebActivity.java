package com.chaiapps.chasima;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** דפדפן בטוח: נפתח רק אתרים שאבא אישר (קבוע או לזמן מוגבל). הילד יכול לבקש אתר חדש. */
public class WebActivity extends Activity {

    private WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable guard = new Runnable() {
        @Override
        public void run() {
            // אם ההרשאה פגה באמצע הגלישה – חוזרים למסך הבקשות
            if (web != null && web.getUrl() != null && !ok(web.getUrl())) showMenu();
            else h.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String open = getIntent() != null ? getIntent().getStringExtra("open") : null;
        if (open != null && ok(open)) openSite(open);
        else showMenu();
    }

    // ---------- רשימת אתרים מותרים כרגע ----------
    private List<String> allowedHosts() {
        List<String> out = new ArrayList<>();
        JSONObject cfg = Cfg.cfg(this);
        JSONArray a = cfg.optJSONArray("sites");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String hst = host(a.optString(i, ""));
            if (!hst.isEmpty()) out.add(hst);
        }
        JSONObject g = cfg.optJSONObject("grants");
        if (g != null) {
            long now = System.currentTimeMillis();
            Iterator<String> it = g.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (g.optLong(k, 0) > now) out.add(k.replace(',', '.'));
            }
        }
        return out;
    }

    private long grantLeft(String host) {
        JSONObject g = Cfg.cfg(this).optJSONObject("grants");
        if (g == null) return 0;
        long exp = g.optLong(host.replace('.', ','), 0);
        return Math.max(0, exp - System.currentTimeMillis());
    }

    private String host(String url) {
        try {
            String u = url.trim();
            if (!u.startsWith("http")) u = "https://" + u;
            String hst = Uri.parse(u).getHost();
            return hst == null ? "" : hst.toLowerCase(Locale.US).replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "";
        }
    }

    private boolean ok(String url) {
        String hst = host(url);
        if (hst.isEmpty()) return false;
        for (String a : allowedHosts())
            if (!a.isEmpty() && (hst.equals(a) || hst.endsWith("." + a))) return true;
        return false;
    }

    // ---------- תצוגת הדפדפן ----------
    private void openSite(String url) {
        h.removeCallbacks(guard);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (ok(r.getUrl().toString())) return false;
                showMenu();
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (r.isForMainFrame() && !ok(r.getUrl().toString()))
                    return new WebResourceResponse("text/html", "utf-8",
                            new ByteArrayInputStream("<h3 dir=rtl>האתר חסום</h3>".getBytes()));
                return null;
            }
        });
        setContentView(web);
        web.loadUrl(url.startsWith("http") ? url : "https://" + url);
        h.postDelayed(guard, 20000);
    }

    // ---------- מסך בקשות ----------
    private void showMenu() {
        h.removeCallbacks(guard);
        web = null;
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🌐 דפדפן בטוח", 24, Color.WHITE, true);

        List<String> hosts = allowedHosts();
        if (hosts.isEmpty()) {
            Ui.text(this, l, "אין כרגע אתרים פתוחים.\nאפשר לבקש אתר מאבא 👇", 16, 0xCCFFFFFF, false);
        } else {
            Ui.text(this, l, "אתרים פתוחים עכשיו:", 15, 0xCCFFFFFF, false);
            for (final String hst : hosts) {
                long left = grantLeft(hst);
                String label = "🔓 " + hst + (left > 0 ? "  (" + (left / 60000 + 1) + " ד׳)" : "");
                Ui.button(this, l, label, Ui.GREEN, v -> openSite("https://" + hst));
            }
        }

        LinearLayout c = Ui.card(this, l);
        Ui.text(this, c, "בקשת אתר חדש מאבא", 16, Color.WHITE, true);
        final EditText url = new EditText(this);
        url.setHint("כתובת האתר, למשל kipa.co.il");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        style(url);
        c.addView(url);
        final EditText why = new EditText(this);
        why.setHint("למה? (לא חובה)");
        style(why);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = Ui.dp(this, 8);
        c.addView(why, wp);
        final android.widget.TextView st = Ui.text(this, c, "", 14, 0xFFBBF7D0, false);
        Ui.button(this, c, "📩 שלח בקשה לאבא", Ui.BLUE, v -> {
            final String hst = host(url.getText().toString());
            if (hst.isEmpty()) {
                st.setText("כתובת לא תקינה");
                return;
            }
            st.setText("שולח...");
            new Thread(() -> {
                boolean okk = request(hst, url.getText().toString().trim(), why.getText().toString().trim());
                runOnUiThread(() -> st.setText(okk ? "✅ הבקשה נשלחה לאבא" : "❌ אין אינטרנט"));
            }).start();
        });

        Ui.button(this, l, "🏠 יציאה", 0x55FFFFFF, v -> finish());
    }

    private void style(EditText e) {
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x99FFFFFF);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(Ui.dp(this, 12));
        e.setBackground(g);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
    }

    private boolean request(String hst, String url, String why) {
        try {
            JSONObject r = new JSONObject();
            r.put("host", hst);
            r.put("url", url);
            if (!why.isEmpty()) r.put("msg", why);
            r.put("t", new JSONObject().put(".sv", "timestamp"));
            Fb.put("d/" + Cfg.deviceId(this) + "/sitereq/" + hst.replace('.', ','), r.toString());
            if (!why.isEmpty()) ChatActivity.sendMsg(this, "🌐 ביקשתי להיכנס ל-" + hst + ": " + why);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) showMenu();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else if (web != null) showMenu();
        else super.onBackPressed();
    }
}
