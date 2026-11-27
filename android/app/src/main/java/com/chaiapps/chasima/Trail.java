package com.chaiapps.chasima;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 🛰️ מעקב מסלול: אחרי ניסיון פריצה (או כשאבא מבקש) – הנגן שומר מיקום כל 3 דקות, גם בלי אינטרנט.
 * ברגע שיש אינטרנט כל הנקודות נשלחות לאבא, והוא רואה את כל המסלול עד המיקום הנוכחי.
 */
final class Trail {
    private Trail() {}
    static final long EVERY = 3 * 60000L;
    static final long HOURS = 24 * 3600000L;
    private static volatile boolean busy = false, upBusy = false;
    private static long lastUp = 0;

    static boolean on(Context c) { return Cfg.p(c).getLong("trailUntil", 0) > System.currentTimeMillis(); }

    static void start(Context c, String why) {
        long now = System.currentTimeMillis();
        boolean was = on(c);
        Cfg.p(c).edit().putLong("trailUntil", now + HOURS).putString("trailWhy", why)
                .putLong("trailLast", was ? Cfg.p(c).getLong("trailLast", 0) : 0).apply();
        if (!was) mark(c, why, now);
    }

    static void stop(Context c) { Cfg.p(c).edit().putLong("trailUntil", 0).apply(); }

    private static File file(Context c) { return new File(c.getFilesDir(), "trail.jsonl"); }

    private static synchronized void append(Context c, JSONObject p) {
        try (FileOutputStream o = new FileOutputStream(file(c), true)) { o.write((p.toString() + "\n").getBytes("UTF-8")); }
        catch (Exception ignored) {}
    }

    /** נקודת "אירוע" (למה התחיל המעקב) */
    private static void mark(Context c, String why, long t) {
        try { append(c, new JSONObject().put("t", t).put("ev", why)); } catch (Exception ignored) {}
    }

    /** נקרא כל 6 שניות מהשומר */
    static void tick(Context c) {
        final Context a = c.getApplicationContext();
        long now = System.currentTimeMillis();
        if (on(a) && !busy && now - Cfg.p(a).getLong("trailLast", 0) >= EVERY) {
            busy = true;
            Cfg.p(a).edit().putLong("trailLast", now).apply();
            Loc.fresh(a, (lc, err) -> {
                try {
                    if (lc != null && !lc.optBoolean("approx", false)) {
                        JSONObject p = new JSONObject().put("t", System.currentTimeMillis())
                                .put("lat", lc.optDouble("lat")).put("lng", lc.optDouble("lng")).put("acc", lc.optLong("acc"));
                        if (lc.optBoolean("wifi", false)) p.put("w", 1);
                        append(a, p);
                    }
                } catch (Exception ignored) {}
                busy = false;
                upload(a, true);
            });
        }
        upload(a, false);
    }

    static void upload(Context c, boolean force) {
        long now = System.currentTimeMillis();
        if (upBusy || (!force && now - lastUp < 30000)) return;
        final File f = file(c);
        if (!f.exists() || f.length() == 0) return;
        lastUp = now;
        upBusy = true;
        new Thread(() -> {
            try {
                String all;
                synchronized (Trail.class) {
                    byte[] b = new byte[(int) f.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int o = 0, r; while (o < b.length && (r = in.read(b, o, b.length - o)) > 0) o += r; }
                    all = new String(b, "UTF-8");
                }
                JSONObject batch = new JSONObject();
                int n = 0;
                for (String line : all.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    try { JSONObject p = new JSONObject(line); batch.put("p" + p.optLong("t"), p); n++; } catch (Exception ignored) {}
                }
                if (n > 0) Fb.patch("d/" + Cfg.deviceId(c) + "/trail", batch.toString());
                // מוחקים רק את מה שנשלח (אם בינתיים נוספה נקודה – נשארת)
                synchronized (Trail.class) {
                    byte[] b = new byte[(int) f.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int o = 0, r; while (o < b.length && (r = in.read(b, o, b.length - o)) > 0) o += r; }
                    String cur = new String(b, "UTF-8");
                    String rest = cur.startsWith(all) ? cur.substring(all.length()) : "";
                    try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(rest.getBytes("UTF-8")); }
                }
                Fb.patch("d/" + Cfg.deviceId(c) + "/st", new JSONObject().put("trailOn", on(c))
                        .put("trailUntil", Cfg.p(c).getLong("trailUntil", 0)).toString());
            } catch (Exception ignored) {
                // אין אינטרנט – הנקודות נשמרות בנגן וננסה שוב
            } finally { upBusy = false; }
        }).start();
    }
}
