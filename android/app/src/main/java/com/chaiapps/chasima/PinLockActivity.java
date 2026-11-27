package com.chaiapps.chasima;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 🔐 מסך נעילה אחרי 3 ניסיונות שגויים של קוד הורה */
public class PinLockActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView left;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long ms = Pin.until(PinLockActivity.this) - System.currentTimeMillis();
            if (ms <= 0) { finish(); return; }
            long s = ms / 1000;
            if (left != null) left.setText("⏳ הנגן ייפתח בעוד " + (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60));
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        String name = Cfg.cfg(this).optString("name", "");
        if (name.isEmpty()) name = "נחמן";
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🤨", 90, Color.WHITE, false).setPadding(0, Ui.dp(this, 50), 0, 0);
        Ui.text(this, l, name + ", מה אתה מנסה לעשות?", 28, 0xFFFDE68A, true);
        LinearLayout c = Ui.card(this, l);
        Ui.text(this, c, "הוקלד 3 פעמים קוד הורה שגוי.\nהנגן נעול ל-20 דקות, ואבא קיבל הודעה.", 18, Color.WHITE, false);
        left = Ui.text(this, l, "", 22, Color.WHITE, true);
        left.setPadding(0, Ui.dp(this, 20), 0, 0);
        Ui.text(this, l, "חסימה חכמה", 14, 0x99FFFFFF, false).setPadding(0, Ui.dp(this, 30), 0, 0);
    }

    @Override protected void onResume() {
        super.onResume();
        h.post(tick);
        // 📸 צילום בזמן שהמסך מוצג (המצלמה מותרת רק כשהאפליקציה בחזית) – פעם אחת לכל נעילה
        long u = Pin.until(this);
        if (Cfg.p(this).getLong("pinSnap", 0) != u) {
            Cfg.p(this).edit().putLong("pinSnap", u).apply();
            h.postDelayed(() -> StealthCam.capture(this, "theft"), 800);
        }
    }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
    @Override public void onBackPressed() { }
}
