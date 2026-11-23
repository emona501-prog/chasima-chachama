package com.chaiapps.chasima;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;

/** רשימת האפליקציות שבנגן + אילו רכיבי מערכת אסור לחסום. */
final class Apps {
    private Apps() {}

    static Set<String> launchable(Context c) {
        Set<String> s = new HashSet<>();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, 0)) {
            s.add(r.activityInfo.packageName);
        }
        s.remove(c.getPackageName());
        return s;
    }

    static Set<String> exempt(Context c) {
        Set<String> s = new HashSet<>();
        s.add(c.getPackageName());
        s.add("android");
        s.add("com.android.systemui");
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(home, 0)) {
            s.add(r.activityInfo.packageName);
        }
        try {
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo m : imm.getEnabledInputMethodList()) s.add(m.getPackageName());
        } catch (Exception ignored) {}
        // מסך הבית של ההגדרות (FallbackHome) רשום כ"מסך בית" – אבל את ההגדרות כן חוסמים
        s.remove(Rules.SETTINGS);
        return s;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    static String icon(Context c, String pkg) {
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            int size = 72;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(cv);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** מעלה את רשימת האפליקציות להורה, ומסמן אפליקציות חדשות כבקשה. */
    static void report(Context c) {
        try {
            String id = Cfg.deviceId(c);
            Set<String> pkgs = launchable(c);
            JSONObject all = new JSONObject();
            for (String p : pkgs) {
                JSONObject a = new JSONObject();
                a.put("p", p);
                a.put("n", label(c, p));
                a.put("i", icon(c, p));
                all.put(Cfg.key(p), a);
            }
            Fb.put("d/" + id + "/apps", all.toString());

            Set<String> known = Cfg.p(c).getStringSet("known", null);
            if (known != null) {
                JSONObject rules = Cfg.cfg(c).optJSONObject("rules");
                for (String p : pkgs) {
                    if (known.contains(p)) continue;
                    if (rules != null && rules.has(Cfg.key(p))) continue;
                    JSONObject r = new JSONObject();
                    r.put("p", p);
                    r.put("n", label(c, p));
                    r.put("nw", true);
                    r.put("t", new JSONObject().put(".sv", "timestamp"));
                    Fb.put("d/" + id + "/req/" + Cfg.key(p), r.toString());
                }
            }
            Cfg.p(c).edit().putStringSet("known", new HashSet<>(pkgs)).apply();
        } catch (Exception ignored) {}
    }
}
