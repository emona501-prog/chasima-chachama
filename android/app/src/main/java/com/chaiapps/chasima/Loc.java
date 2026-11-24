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
    static void fresh(Context c, Cb cb) {
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
                cb.done(old, old == null ? "הנגן לא הצליח למצוא מיקום (נסה ליד חלון / עם WiFi)" : "מיקום טרי לא נמצא – מוצג המיקום האחרון");
            }, 60000);
        });
    }
}
