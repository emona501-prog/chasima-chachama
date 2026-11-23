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
}
