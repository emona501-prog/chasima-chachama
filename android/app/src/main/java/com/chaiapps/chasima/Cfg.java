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
        p(c).edit().putString("cfg", json).apply();
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

    /** במסד הנתונים אסור נקודה במפתח – מחליפים בפסיק. */
    static String key(String pkg) {
        return pkg.replace('.', ',');
    }
}
