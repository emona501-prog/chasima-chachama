package com.chaiapps.chasima;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONObject;

/** מיקום הנגן (לאיתור אם אבד או נגנב). */
final class Loc {
    private Loc() {}

    static boolean allowed(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** האם מותר מיקום גם כשהאפליקציה ברקע ("לאפשר כל הזמן") */
    static boolean bgAllowed(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 29) return allowed(c);
        return c.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED;
    }

    static boolean enabled(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && (android.os.Build.VERSION.SDK_INT < 28 || lm.isLocationEnabled());
        } catch (Exception e) { return false; }
    }

    /** המיקום האחרון הידוע, או null. */
    static JSONObject last(Context c) {
        if (!allowed(c)) return null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : lm.getAllProviders()) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l == null) continue;
                    if (best == null || l.getTime() > best.getTime()) best = l;
                } catch (SecurityException ignored) {}
            }
            if (best == null) return null;
            JSONObject o = new JSONObject();
            o.put("lat", best.getLatitude());
            o.put("lng", best.getLongitude());
            o.put("acc", Math.round(best.getAccuracy()));
            o.put("t", best.getTime());
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    interface Cb { void done(JSONObject loc, String err); }

    private static JSONObject toJson(Location l) {
        try {
            JSONObject o = new JSONObject();
            o.put("lat", l.getLatitude());
            o.put("lng", l.getLongitude());
            o.put("acc", Math.round(l.getAccuracy()));
            o.put("t", System.currentTimeMillis());
            return o;
        } catch (Exception e) { return null; }
    }

    /** מבקש מיקום טרי מכל מקור זמין (GPS / רשת), עד 60 שניות. */
    /** מיקום משוער לפי האינטרנט (עיר/שכונה) – כשאין GPS. */
    static JSONObject byIp() {
        try {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("https://ipwho.is/?lang=he").openConnection();
            h.setConnectTimeout(10000); h.setReadTimeout(10000);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = h.getInputStream()) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); }
            JSONObject r = new JSONObject(bo.toString("UTF-8"));
            if (!r.optBoolean("success", false)) return null;
            JSONObject o = new JSONObject();
            o.put("lat", r.getDouble("latitude")); o.put("lng", r.getDouble("longitude"));
            o.put("acc", 5000); o.put("approx", true); o.put("city", r.optString("city", ""));
            o.put("t", System.currentTimeMillis());
            return o;
        } catch (Exception e) { return null; }
    }

    static void fresh(Context c, Cb cb0) {
        final Cb cb = (loc, err) -> {
            if (loc != null) { cb0.done(loc, err); return; }
            new Thread(() -> {
                JSONObject ip = byIp();
                if (ip != null) cb0.done(ip, "📶 מיקום משוער לפי האינטרנט" + (ip.optString("city").isEmpty() ? "" : " (" + ip.optString("city") + ")") + " · " + err);
                else cb0.done(loc, err);
            }).start();
        };
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> {
            if (!allowed(c)) { cb.done(null, "הרשאת מיקום כבויה בנגן"); return; }
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) { cb.done(null, "אין שירות מיקום בנגן"); return; }
            if (android.os.Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled()) {
                cb.done(last(c), "המיקום כבוי בנגן (צריך להדליק 'מיקום' בהגדרות הנגן)");
                return;
            }
            final boolean[] fin = {false};
            final java.util.List<android.location.LocationListener> ls = new java.util.ArrayList<>();
            Runnable stopAll = () -> { for (android.location.LocationListener x : ls) try { lm.removeUpdates(x); } catch (Exception ignored) {} };
            int used = 0;
            for (String p : lm.getProviders(true)) {
                if (p.equals(LocationManager.PASSIVE_PROVIDER)) continue;
                android.location.LocationListener l = new android.location.LocationListener() {
                    @Override public void onLocationChanged(Location loc) {
                        if (fin[0]) return;
                        fin[0] = true; stopAll.run();
                        cb.done(toJson(loc), "");
                    }
                    @Override public void onStatusChanged(String a, int b, android.os.Bundle e) {}
                    @Override public void onProviderEnabled(String a) {}
                    @Override public void onProviderDisabled(String a) {}
                };
                try { lm.requestLocationUpdates(p, 0, 0, l, android.os.Looper.getMainLooper()); ls.add(l); used++; }
                catch (Exception ignored) {}
            }
            if (used == 0) { cb.done(last(c), "אין מקור מיקום זמין בנגן (GPS/רשת)"); return; }
            h.postDelayed(() -> {
                if (fin[0]) return;
                fin[0] = true; stopAll.run();
                JSONObject old = last(c);
                String why = !bgAllowed(c) ? " · חסרה הרשאת 'מיקום – לאפשר כל הזמן' (תפריט הורה בנגן ← 📍)" : "";
                cb.done(old, (old == null ? "הנגן לא הצליח למצוא מיקום (נסה ליד חלון / עם WiFi)" : "מיקום טרי לא נמצא – מוצג המיקום האחרון") + why);
            }, 60000);
        });
    }
}
