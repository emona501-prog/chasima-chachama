package com.chaiapps.chasima;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** המסך שהילד רואה כשאפליקציה חסומה. */
public class BlockActivity extends Activity {

    private String pkg = "", name = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
    }

    private void build(Intent in) {
        pkg = in.getStringExtra("pkg");
        name = in.getStringExtra("name");
        String reason = in.getStringExtra("reason");
        if (pkg == null) pkg = "";
        if (name == null) name = pkg;

        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🔒", 72, Color.WHITE, false).setPadding(0, Ui.dp(this, 30), 0, 0);
        Ui.text(this, l, name, 26, Color.WHITE, true);
        Ui.text(this, l, reason == null ? "האפליקציה חסומה" : reason, 19, 0xFFFDE68A, true);

        String msg = Cfg.cfg(this).optString("msg", "");
        if (!msg.isEmpty()) {
            LinearLayout c = Ui.card(this, l);
            Ui.text(this, c, "💬 " + msg, 17, Color.WHITE, false);
        }

        boolean canAsk = !Rules.SETTINGS.equals(pkg) && !Rules.isInstaller(pkg);
        if (canAsk) {
            final TextView status = Ui.text(this, l, "", 16, 0xFFBBF7D0, true);
            final Button[] ask = new Button[1];
            ask[0] = Ui.button(this, l, "📩 בקשת אישור מאבא", Ui.GREEN, v -> {
                ask[0].setEnabled(false);
                status.setText("שולח...");
                new Thread(() -> {
                    boolean ok = send();
                    runOnUiThread(() -> {
                        status.setText(ok ? "✅ הבקשה נשלחה! מחכים לתשובה" : "❌ אין אינטרנט, נסה שוב");
                        ask[0].setEnabled(!ok);
                    });
                }).start();
            });
        }
        Ui.button(this, l, "🏠 חזרה למסך הבית", Ui.BLUE, v -> goHome());
        Ui.text(this, l, "חסימה חכמה · חי אפליקציות", 13, 0x99FFFFFF, false).setPadding(0, Ui.dp(this, 24), 0, 0);
    }

    private boolean send() {
        try {
            JSONObject r = new JSONObject();
            r.put("p", pkg);
            r.put("n", name);
            r.put("t", new JSONObject().put(".sv", "timestamp"));
            Fb.put("d/" + Cfg.deviceId(this) + "/req/" + Cfg.key(pkg), r.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goHome();
    }
}
