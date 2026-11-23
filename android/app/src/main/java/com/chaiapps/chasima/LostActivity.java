package com.chaiapps.chasima;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;

import org.json.JSONObject;

/** מסך "אבוד/נעול" מלא – מופיע כשההורה נועל את הנגן מרחוק. */
public class LostActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            JSONObject lost = Cfg.cfg(LostActivity.this).optJSONObject("lost");
            if (lost == null || !lost.optBoolean("on", false)) {
                finish();
                return;
            }
            render(lost.optString("msg", ""));
            h.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        render(Cfg.cfg(this).optJSONObject("lost") != null
                ? Cfg.cfg(this).optJSONObject("lost").optString("msg", "") : "");
    }

    private String shown = null;

    private void render(String msg) {
        if (msg.equals(shown)) return;
        shown = msg;
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🔒", 80, Color.WHITE, false).setPadding(0, Ui.dp(this, 60), 0, 0);
        Ui.text(this, l, "הנגן נעול", 30, Color.WHITE, true);
        if (!msg.isEmpty()) {
            LinearLayout c = Ui.card(this, l);
            Ui.text(this, c, msg, 20, 0xFFFDE68A, true);
        }
        Ui.text(this, l, "חסימה חכמה", 14, 0x99FFFFFF, false).setPadding(0, Ui.dp(this, 30), 0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(watch);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(watch);
        // אם עדיין נעול – חוזרים למסך הנעילה
        JSONObject lost = Cfg.cfg(this).optJSONObject("lost");
        if (lost != null && lost.optBoolean("on", false)) {
            startActivity(new Intent(this, LostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // אין יציאה
    }
}
