package com.chaiapps.chasima;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.security.SecureRandom;

/** הגדרות שנשמרות בנגן: מזהה המכשיר, קוד החיבור וההגדרות שההורה קבע. */
final class Cfg {
    private Cfg() {}

    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("chasima", Context.MODE_PRIVATE);
    }

    static synchronized String deviceId(Context c) {
        SharedPreferences sp = p(c);
        String id = sp.getString("id", null);
        if (id == null) {
            String abc = "abcdefghijkmnpqrstuvwxyz23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 24; i++) b.append(abc.charAt(r.nextInt(abc.length())));
            id = b.toString();
            sp.edit().putString("id", id).apply();
        }
        return id;
    }

    static synchronized String pairCode(Context c) {
        SharedPreferences sp = p(c);
        String code = sp.getString("code", null);
        if (code == null) {
            code = String.valueOf(100000 + new SecureRandom().nextInt(900000));
            sp.edit().putString("code", code).apply();
        }
        return code;
    }

    static JSONObject cfg(Context c) {
        try {
            return new JSONObject(p(c).getString("cfg", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void saveCfg(Context c, String json) {
        if (json == null || json.equals("null")) json = "{}";
        // שינויים שאבא עשה בנגן עצמו ועוד לא הגיעו לשרת – נשארים בתוקף
        try {
            org.json.JSONArray pend = pending(c);
            if (pend.length() > 0) {
                JSONObject o = new JSONObject(json);
                applyPending(o, pend);
                json = o.toString();
            }
        } catch (Exception ignored) {}
        p(c).edit().putString("cfg", json).apply();
    }

    /* ---------- שינויים מקומיים (עובד גם בלי אינטרנט) ---------- */
    static synchronized org.json.JSONArray pending(Context c) {
        try { return new org.json.JSONArray(p(c).getString("pend", "[]")); }
        catch (Exception e) { return new org.json.JSONArray(); }
    }

    private static void applyPending(JSONObject cfg, org.json.JSONArray pend) throws Exception {
        for (int i = 0; i < pend.length(); i++) {
            JSONObject e = pend.getJSONObject(i);
            String sub = e.optString("s", "");
            JSONObject b = e.getJSONObject("b");
            JSONObject target = cfg;
            if (!sub.isEmpty()) {
                target = cfg.optJSONObject(sub);
                if (target == null) { target = new JSONObject(); cfg.put(sub, target); }
            }
            java.util.Iterator<String> it = b.keys();
            while (it.hasNext()) { String k = it.next(); target.put(k, b.get(k)); }
        }
    }

    /** שינוי הגדרה מהנגן עצמו: חל מיד, ונשלח לשרת כשיש אינטרנט. sub = "" (שורש) או "rules". */
    static synchronized void localSet(Context c, String sub, JSONObject body) {
        try {
            org.json.JSONArray pend = pending(c);
            pend.put(new JSONObject().put("s", sub).put("b", body));
            p(c).edit().putString("pend", pend.toString()).apply();
            JSONObject cur = cfg(c);
            applyPending(cur, new org.json.JSONArray().put(new JSONObject().put("s", sub).put("b", body)));
            p(c).edit().putString("cfg", cur.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** שולח לשרת את השינויים המקומיים שממתינים. נקרא מחוט רקע. */
    static void flushPending(Context c) {
        org.json.JSONArray pend = pending(c);
        if (pend.length() == 0) return;
        int done = 0;
        for (int i = 0; i < pend.length(); i++) {
            try {
                JSONObject e = pend.getJSONObject(i);
                String sub = e.optString("s", "");
                Fb.patch("d/" + deviceId(c) + "/cfg" + (sub.isEmpty() ? "" : "/" + sub), e.getJSONObject("b").toString());
                done++;
            } catch (Exception ex) { break; }
        }
        if (done == 0) return;
        synchronized (Cfg.class) {
            org.json.JSONArray now = pending(c), rest = new org.json.JSONArray();
            for (int i = done; i < now.length(); i++) rest.put(now.opt(i));
            p(c).edit().putString("pend", rest.toString()).apply();
        }
    }

    static boolean paired(Context c) {
        return cfg(c).optString("pin", "").length() > 0;
    }

    static boolean setupDone(Context c) {
        return p(c).getBoolean("setupDone", false);
    }

    static void setSetupDone(Context c, boolean v) {
        p(c).edit().putBoolean("setupDone", v).apply();
    }

    /** זמן (במילישניות) שעד אליו מותר להיכנס להגדרות בנגן אחרי הקלדת קוד ההורה. */
    static long localSettingsUntil(Context c) {
        return p(c).getLong("setUntil", 0);
    }

    static void setLocalSettingsUntil(Context c, long t) {
        p(c).edit().putLong("setUntil", t).apply();
    }

    static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    /** כמה שניות מסך נוצלו היום (רק אפליקציות "מותר"). */
    static int usedSec(Context c) {
        SharedPreferences sp = p(c);
        return today().equals(sp.getString("useDay", "")) ? sp.getInt("useSec", 0) : 0;
    }

    static void addUsed(Context c, int sec) {
        int cur = usedSec(c);
        p(c).edit().putString("useDay", today()).putInt("useSec", cur + sec).apply();
    }

    /** במסד הנתונים אסור נקודה במפתח – מחליפים בפסיק. */
    static String key(String pkg) {
        return pkg.replace('.', ',');
    }
}
