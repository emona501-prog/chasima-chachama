package com.chaiapps.chasima;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.UserManager;

/**
 * 🛡️ מיגון מקסימלי – רק אם חסימה חכמה הוגדרה "בעלת המכשיר" (פעם אחת, מחשב + ADB).
 * אז אנדרואיד עצמו חוסם: מחיקה מכל מקום (גם ממנהל קבצים), מצב בטוח, איפוס יצרן ו-USB debugging.
 */
final class Owner {
    private Owner() {}

    static boolean is(Context c) {
        try {
            DevicePolicyManager d = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return d.isDeviceOwnerApp(c.getPackageName());
        } catch (Exception e) { return false; }
    }

    static void lock(Context c) {
        if (!is(c)) return;
        try {
            DevicePolicyManager d = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName a = AdminReceiver.name(c);
            d.setUninstallBlocked(a, c.getPackageName(), true);
            d.addUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT);
            d.addUserRestriction(a, UserManager.DISALLOW_FACTORY_RESET);
            d.addUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES);
            d.addUserRestriction(a, UserManager.DISALLOW_ADD_USER);
        } catch (Exception ignored) {}
    }

    /** כשאבא מסיר את ההגנה – משחררים הכל */
    static void release(Context c) {
        if (!is(c)) return;
        try {
            DevicePolicyManager d = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName a = AdminReceiver.name(c);
            d.setUninstallBlocked(a, c.getPackageName(), false);
            d.clearUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT);
            d.clearUserRestriction(a, UserManager.DISALLOW_FACTORY_RESET);
            d.clearUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES);
            d.clearUserRestriction(a, UserManager.DISALLOW_ADD_USER);
            d.clearDeviceOwnerApp(c.getPackageName());
        } catch (Exception ignored) {}
    }
}
