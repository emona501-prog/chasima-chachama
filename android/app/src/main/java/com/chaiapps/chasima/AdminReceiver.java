package com.chaiapps.chasima;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** "מנהל מכשיר" – מונע מחיקה של חסימה חכמה. */
public class AdminReceiver extends DeviceAdminReceiver {

    static ComponentName name(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        try { Trail.start(context.getApplicationContext(), "admin"); } catch (Exception ignored) {}
        return "ביטול ההרשאה יסיר את ההגנה מהנגן. רק אבא יכול לעשות את זה.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            try {
                Fb.patch("d/" + Cfg.deviceId(c) + "/st", "{\"admin\":false,\"alert\":\"הרשאת מנהל המכשיר בוטלה\"}");
            } catch (Exception ignored) {}
        }).start();
    }
}
