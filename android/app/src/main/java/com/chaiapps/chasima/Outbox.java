package com.chaiapps.chasima;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * 📮 תיבת יציאה: תמונות ומיקומים של אירועי אבטחה נשמרים בנגן,
 * ונשלחים לאבא מיד – או ברגע שהנגן מתחבר לאינטרנט (ואז גם עם המיקום הנוכחי).
 */
final class Outbox {
    private Outbox() {}
    private static volatile boolean busy = false;
    private static long lastTry = 0;

    static File dir(Context c) { File d = new File(c.getFilesDir(), "outbox"); d.mkdirs(); return d; }

    static boolean pending(Context c) { String[] f = dir(c).list(); return f != null && f.length > 0; }

    private static void write(File f, byte[] b) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b); }
        tmp.renameTo(f);
    }

    static void photo(Context c, byte[] jpg, String reason) {
        try {
            long t = System.currentTimeMillis();
            write(new File(dir(c), t + "_" + reason + ".jpg"), jpg);
        } catch (Exception ignored) {}
        flush(c, true);
    }

    /** אירוע אבטחה (3 קודים שגויים): שומרים את המיקום של עכשיו, וברגע שיש אינטרנט שולחים גם את המיקום העדכני */
    static void event(Context c, String what) {
        final Context a = c.getApplicationContext();
        final long t = System.currentTimeMillis();
        try { write(new File(dir(a), t + ".evt"), new JSONObject().put("w", what).put("t", t).toString().getBytes("UTF-8")); } catch (Exception ignored) {}
        Loc.fresh(a, (lc, err) -> new Thread(() -> {
            try {
                File f = new File(dir(a), t + ".evt");
                if (lc != null && f.exists()) write(f, new JSONObject().put("w", what).put("t", t).put("loc", lc).toString().getBytes("UTF-8"));
            } catch (Exception ignored) {}
            flush(a, true);
        }).start());
    }

    static void flush(Context c, boolean force) {
        final Context a = c.getApplicationContext();
        long now = System.currentTimeMillis();
        if (busy || (!force && now - lastTry < 30000)) return;
        if (!pending(a)) return;
        lastTry = now;
        busy = true;
        new Thread(() -> {
            try { run(a); } finally { busy = false; }
        }).start();
    }

    private static void run(Context c) {
        String base = "d/" + Cfg.deviceId(c);
        File[] fs = dir(c).listFiles((d, n) -> !n.endsWith(".tmp"));
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            try {
                String n = f.getName();
                byte[] b = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int o = 0, r; while (o < b.length && (r = in.read(b, o, b.length - o)) > 0) o += r; }
                long t = Long.parseLong(n.substring(0, n.indexOf(n.contains("_") ? '_' : '.')));
                boolean late = System.currentTimeMillis() - t > 120000;
                if (n.endsWith(".jpg")) {
                    String reason = n.substring(n.indexOf('_') + 1, n.length() - 4);
                    JSONObject p = new JSONObject();
                    p.put("img", Base64.encodeToString(b, Base64.NO_WRAP));
                    p.put("r", reason);
                    p.put("t", t);
                    if (late) p.put("late", true);
                    Fb.put(base + "/photo", p.toString());
                    try { Fb.patch(base + "/st", "{\"camMsg\":\"\"}"); } catch (Exception ignored) {}
                } else if (n.endsWith(".evt")) {
                    JSONObject e = new JSONObject(new String(b, "UTF-8"));
                    JSONObject then = e.optJSONObject("loc");
                    // המיקום העדכני – עכשיו כשהנגן מחובר
                    final JSONObject[] cur = {null};
                    final Object lock = new Object();
                    Loc.fresh(c, (lc, err) -> { synchronized (lock) { cur[0] = lc; lock.notifyAll(); } });
                    synchronized (lock) { if (cur[0] == null) lock.wait(70000); }
                    JSONObject send = cur[0] != null ? cur[0] : then;
                    if (send != null) Fb.patch(base + "/loc", send.toString());
                    String msg = "🔐 3 ניסיונות שגויים של קוד הורה" + (late ? " (קרה כשהנגן היה בלי אינטרנט, " + new java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.US).format(new java.util.Date(t)) + ")" : "")
                            + (cur[0] != null ? " – נשלח המיקום הנוכחי" : "");
                    JSONObject st = new JSONObject().put("alert", msg).put("pinLock", t);
                    if (then != null) st.put("locThen", then);
                    Fb.patch(base + "/st", st.toString());
                }
                f.delete();
            } catch (Exception ex) {
                return; // אין אינטרנט – ננסה שוב בעוד חצי דקה
            }
        }
    }
}
