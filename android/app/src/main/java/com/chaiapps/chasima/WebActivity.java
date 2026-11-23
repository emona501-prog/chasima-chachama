package com.chaiapps.chasima;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** דפדפן בטוח מובנה – נפתח רק אתרים שההורה אישר. */
public class WebActivity extends Activity {

    private WebView web;
    private List<String> allow = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        allow = sites();
        if (allow.isEmpty()) {
            showEmpty();
            return;
        }
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !ok(r.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (r.isForMainFrame() && !ok(r.getUrl().toString())) {
                    return new WebResourceResponse("text/html", "utf-8",
                            new ByteArrayInputStream("<h3 dir=rtl>האתר הזה חסום</h3>".getBytes()));
                }
                return null;
            }
        });
        setContentView(web);
        web.loadUrl(norm(allow.get(0)));
    }

    private List<String> sites() {
        List<String> out = new ArrayList<>();
        JSONArray a = Cfg.cfg(this).optJSONArray("sites");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private String norm(String s) {
        if (!s.startsWith("http")) s = "https://" + s;
        return s;
    }

    private String host(String url) {
        try {
            String h = Uri.parse(norm(url)).getHost();
            return h == null ? "" : h.toLowerCase(Locale.US).replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "";
        }
    }

    private boolean ok(String url) {
        String h = host(url);
        if (h.isEmpty()) return false;
        for (String s : allow) {
            String a = host(s);
            if (!a.isEmpty() && (h.equals(a) || h.endsWith("." + a))) return true;
        }
        return false;
    }

    private void showEmpty() {
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🌐", 56, Color.WHITE, false).setPadding(0, Ui.dp(this, 60), 0, 0);
        Ui.text(this, l, "הדפדפן הבטוח", 24, Color.WHITE, true);
        Ui.text(this, l, "אבא עדיין לא הוסיף אתרים מאושרים.\nאפשר לבקש ממנו להוסיף אתר.", 16, 0xCCFFFFFF, false);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
