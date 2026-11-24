package com.chaiapps.chasima;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** טיימר לילד: לכמה זמן נעול / פתוח, ומתי זה ישתנה. */
final class Timer {
    private Timer() {}

    /** מצב טיימר: כותרת + רגע הסיום (ms). */
    static final class State {
        final String title; final long end; final boolean locked;
        State(String title, long end, boolean locked) { this.title = title; this.end = end; this.locked = locked; }
    }

    private static long cacheAt = 0;
    private static long cacheEnd = 0;
    private static boolean cacheIn = false;

    /** @param full true = גם מצבים רחוקים (למסך החסימה). false = רק מה ששווה להציג בבועה הקטנה. */
    static State get(Context c, boolean full) {
        JSONObject cfg = Cfg.cfg(c);
        long now = System.currentTimeMillis();
        String mode = Rules.mode(cfg, now);
        long until = cfg.optLong("until", 0);

        if (mode.equals("off")) return null;
        if (mode.equals("lock")) return until > now ? new State("🔒 נעול – ייפתח בעוד", until, true) : null;
        if (mode.equals("free")) return until > now ? new State("🎉 זמן חופשי – נגמר בעוד", until, false) : null;

        // מכסה יומית
        State best = null;
        JSONObject lim = cfg.optJSONObject("limit");
        if (lim != null && lim.optBoolean("on", false)) {
            long leftSec = (lim.optInt("min", 120) + Rules.bonusToday(cfg)) * 60L - Cfg.usedSec(c);
            if (leftSec > 0 && (full || leftSec <= 30 * 60))
                best = new State("⏳ זמן מסך שנשאר היום", now + leftSec * 1000, false);
        }

        // שעות שימוש: מתי החלון נסגר / נפתח
        JSONObject sched = cfg.optJSONObject("sched");
        if (sched != null && sched.optBoolean("on", false)) {
            if (now - cacheAt > 20000) {
                cacheAt = now;
                cacheIn = Rules.inWindow(sched, now);
                cacheEnd = 0;
                long t = now - (now % 60000) + 60000;
                for (int i = 0; i < 24 * 60 + 1; i++, t += 60000) {
                    if (Rules.inWindow(sched, t) != cacheIn) { cacheEnd = t; break; }
                }
            }
            if (cacheEnd > now) {
                if (cacheIn) {
                    if ((full || cacheEnd - now <= 30 * 60000) && (best == null || cacheEnd < best.end))
                        best = new State("⏳ שעות המסך נגמרות בעוד", cacheEnd, false);
                } else if (full) {
                    best = new State("🔓 שעות המסך מתחילות בעוד", cacheEnd, true);
                }
            }
        }
        return best;
    }

    static String left(long end) {
        long s = Math.max(0, (end - System.currentTimeMillis()) / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    static String clock(long end) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(end));
    }

    static String line(State s) {
        return s.title + " " + left(s.end) + "  (בשעה " + clock(s.end) + ")";
    }
}
