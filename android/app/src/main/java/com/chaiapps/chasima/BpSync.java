package com.chaiapps.chasima;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 🪜 מקבל מ"ברסלב PRO" את היעדים ומה סומן היום, ושומר בשביל "הסולם שלי". */
public class BpSync extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String pkg = i.getStringExtra("pkg");
        String d = i.getStringExtra("d");
        if (pkg == null || !pkg.startsWith("com.chaiproductions.breslovpro") || d == null || d.length() > 400000) return;
        Cfg.p(c).edit().putString("bp", d).putString("bpPkg", pkg).putLong("bpAt", System.currentTimeMillis()).apply();
        SulamActivity.pushBp(d);
    }
}
