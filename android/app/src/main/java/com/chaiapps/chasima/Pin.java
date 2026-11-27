package com.chaiapps.chasima;

import android.content.Context;
import android.content.Intent;

/** 🔐 קוד הורה: 3 טעויות → הנגן ננעל ל-20 דקות, צילום, והודעה לאבא. */
final class Pin {
    private Pin() {}
    static final long LOCK_MS = 20 * 60000L;

    static boolean locked(Context c) { return Cfg.p(c).getLong("pinLockUntil", 0) > System.currentTimeMillis(); }
    static long until(Context c) { return Cfg.p(c).getLong("pinLockUntil", 0); }

    /** בודק קוד. מחזיר true אם נכון. בזמן נעילה – תמיד false. */
    static boolean check(Context c, String p) {
        if (locked(c)) return false;
        String want = Cfg.cfg(c).optString("pin", "");
        if (!want.isEmpty() && want.equals(p == null ? "" : p.trim())) {
            Cfg.p(c).edit().putInt("badPin", 0).apply();
            return true;
        }
        int f = Cfg.p(c).getInt("badPin", 0) + 1;
        Cfg.p(c).edit().putInt("badPin", f).apply();
        if (f >= 3) lock(c);
        return false;
    }

    static void lock(Context c) {
        final Context a = c.getApplicationContext();
        long u = System.currentTimeMillis() + LOCK_MS;
        Cfg.p(a).edit().putInt("badPin", 0).putLong("pinLockUntil", u).apply();
        Outbox.event(a, "pin");
        Trail.start(a, "pin");
        show(a);
    }

    static void show(Context c) {
        try {
            c.startActivity(new Intent(c, PinLockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception ignored) {}
    }
}
