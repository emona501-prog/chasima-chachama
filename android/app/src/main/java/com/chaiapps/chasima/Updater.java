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

    static void run(final android.content.Context a, final Progress p) {
        // פותחים את ההתקנה לכמה דקות כדי שהשומר לא יחסום את המתקין
        Cfg.setLocalSettingsUntil(a, System.currentTimeMillis() + 5 * 60000L);
        new Thread(() -> {
            final Handler main = new Handler(Looper.getMainLooper());
            try {
                main.post(() -> p.say("מוריד עדכון…"));
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
                                main.post(() -> p.say("מוריד… " + fp + "%"));
                            }
                        }
                    }
                }
                c.disconnect();
                main.post(() -> {
                    p.say("מתקין…");
                    install(a, out);
                });
            } catch (Exception e) {
                main.post(() -> p.say("❌ ההורדה נכשלה. בדוק אינטרנט ונסה שוב."));
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
