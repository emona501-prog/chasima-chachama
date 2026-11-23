package com.chaiapps.chasima;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** עדכון עצמי: מוריד את הגרסה האחרונה מגיטהאב ומתקין – בלי דפדפן. */
final class Updater {
    private Updater() {}

    static final String URL_APK =
            "https://github.com/emona501-prog/chasima-chachama/releases/latest/download/chasima-chachama.apk";

    interface Progress {
        void say(String msg);
    }

    static final String API_LATEST =
            "https://api.github.com/repos/emona501-prog/chasima-chachama/releases/latest";

    private static void tell(Progress p, String msg) {
        if (p != null) new Handler(Looper.getMainLooper()).post(() -> p.say(msg));
    }

    /** מספר ה-build האחרון בגיטהאב, או -1 אם נכשל. */
    private static int latestBuild() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(API_LATEST).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() >= 400) { c.disconnect(); return -1; }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            }
            c.disconnect();
            String tag = new org.json.JSONObject(bo.toString("UTF-8")).optString("tag_name", "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("-(\\d+)$").matcher(tag);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** בודק אם יש גרסה חדשה; אם כן – מוריד ומתקין. silent=true: לא מודיע כשאין עדכון. */
    static void checkAndUpdate(final android.content.Context ctx, final Progress p, final boolean silent) {
        new Thread(() -> {
            if (!silent) tell(p, "בודק עדכון…");
            int latest = latestBuild();
            if (latest < 0) { if (!silent) tell(p, "❌ אין חיבור לבדיקת עדכון"); return; }
            if (latest <= BuildConfig.VERSION_CODE) { if (!silent) tell(p, "אתה כבר בגרסה האחרונה ✅"); return; }
            run(ctx, p);
        }).start();
    }

    static void run(final android.content.Context a, final Progress p) {
        // פותחים את ההתקנה לכמה דקות כדי שהשומר לא יחסום את המתקין
        Cfg.setLocalSettingsUntil(a, System.currentTimeMillis() + 5 * 60000L);
        new Thread(() -> {
            final Handler main = new Handler(Looper.getMainLooper());
            try {
                main.post(() -> { if (p != null) p.say("מוריד עדכון…"); });
                File out = new File(a.getExternalCacheDir(), "update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(URL_APK).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n, got = 0, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100L / total);
                            if (pct != lastPct && pct % 10 == 0) {
                                lastPct = pct;
                                final int fp = pct;
                                main.post(() -> { if (p != null) p.say("מוריד… " + fp + "%"); });
                            }
                        }
                    }
                }
                c.disconnect();
                main.post(() -> {
                    if (p != null) p.say("מתקין…");
                    install(a, out);
                });
            } catch (Exception e) {
                main.post(() -> { if (p != null) p.say("❌ ההורדה נכשלה. בדוק אינטרנט ונסה שוב."); });
            }
        }).start();
    }

    private static void install(android.content.Context a, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fp", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            // חלק מהמכשירים דורשים אישור "התקנה ממקורות לא ידועים"
        }
    }
}
