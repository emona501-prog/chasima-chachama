package com.chaiapps.chasima.parent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** אחרי הפעלה מחדש של הטלפון – ההתראות חוזרות לפעול. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!MainActivity.prefs(c).getString("id", "").isEmpty()) {
            try { AlertService.start(c); } catch (Exception ignored) {}
        }
    }
}
