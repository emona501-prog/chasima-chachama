package com.chaiapps.chasima;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Set;

/** כאן מוחלט אם אפליקציה מותרת עכשיו או חסומה (ולמה). */
final class Rules {
    private Rules() {}

    static final String SETTINGS = "com.android.settings";

    static boolean isInstaller(String pkg) {
        return pkg.equals("com.android.packageinstaller")
                || pkg.equals("com.google.android.packageinstaller");
    }

    /**
     * @return null אם מותר, אחרת הסיבה לחסימה (טקסט לילד).
     */
    static String check(Context c, String pkg, Set<String> launchable, Set<String> exempt) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (exempt.contains(pkg)) return null;
        // עד שההורה מסיים את ההתקנה בנגן – לא חוסמים כלום
        if (!Cfg.setupDone(c)) return null;

        long now = System.currentTimeMillis();
        JSONObject cfg = Cfg.cfg(c);

        // הגדרות המכשיר והתקנת/הסרת אפליקציות – רק באישור הורה
        if (pkg.equals(SETTINGS) || isInstaller(pkg)) {
            if (Cfg.localSettingsUntil(c) > now) return null;
            if (cfg.optLong("settingsUntil", 0) > now) return null;
            return pkg.equals(SETTINGS) ? "ההגדרות נעולות" : "התקנה והסרה של אפליקציות נעולות";
        }

        // רכיבי מערכת שאין להם אייקון (חייגן, מסכי הרשאות וכו') – לא נוגעים
        if (!launchable.contains(pkg)) return null;

        String rule = cfg.optJSONObject("rules") != null
                ? cfg.optJSONObject("rules").optString(Cfg.key(pkg), "")
                : "";

        String mode = cfg.optString("mode", "normal");
        long until = cfg.optLong("until", 0);
        if (!mode.equals("normal") && until > 0 && until < now) mode = "normal";

        if (rule.equals("always")) return null;

        if (mode.equals("lock")) return "הנגן נעול כרגע על ידי אבא";
        if (rule.isEmpty()) return "האפליקציה עוד לא אושרה";
        if (rule.equals("block")) return "האפליקציה חסומה";

        // rule == allow
        if (mode.equals("free")) return null;
        JSONObject sched = cfg.optJSONObject("sched");
        if (sched != null && sched.optBoolean("on", false) && !inWindow(sched, now)) {
            return "עכשיו לא שעות השימוש בנגן";
        }
        return null;
    }

    static boolean inWindow(JSONObject sched, long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int dow = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0 = ראשון
        int min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (matches(windows(sched, dow), min, false)) return true;
        // חלון שהתחיל אתמול ועובר את חצות
        return matches(windows(sched, (dow + 6) % 7), min, true);
    }

    private static JSONArray windows(JSONObject sched, int dow) {
        JSONObject days = sched.optJSONObject("days");
        if (days == null) {
            JSONArray arr = sched.optJSONArray("days");
            if (arr == null) return new JSONArray();
            Object o = arr.opt(dow);
            return toArray(o);
        }
        return toArray(days.opt(String.valueOf(dow)));
    }

    private static JSONArray toArray(Object o) {
        if (o instanceof JSONArray) return (JSONArray) o;
        JSONArray a = new JSONArray();
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) a.put(j.opt(it.next()));
        }
        return a;
    }

    private static boolean matches(JSONArray ws, int min, boolean fromYesterday) {
        for (int i = 0; i < ws.length(); i++) {
            JSONObject w = ws.optJSONObject(i);
            if (w == null) continue;
            int f = toMin(w.optString("f", "")), t = toMin(w.optString("t", ""));
            if (f < 0 || t < 0) continue;
            if (f <= t) {
                if (!fromYesterday && min >= f && min < t) return true;
            } else { // עובר חצות
                if (!fromYesterday && min >= f) return true;
                if (fromYesterday && min < t) return true;
            }
        }
        return false;
    }

    private static int toMin(String s) {
        try {
            String[] p = s.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
