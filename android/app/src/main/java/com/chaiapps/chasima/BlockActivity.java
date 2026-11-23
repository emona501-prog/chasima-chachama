package com.chaiapps.chasima;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.graphics.drawable.GradientDrawable;
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

    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            // אם אבא אישר בינתיים – סוגרים את מסך החסימה ופותחים את האפליקציה
            new Thread(() -> {
                try {
                    Cfg.saveCfg(BlockActivity.this, Fb.get("d/" + Cfg.deviceId(BlockActivity.this) + "/cfg"));
                } catch (Exception ignored) {}
                runOnUiThread(() -> {
                    if (pkg.isEmpty() || isFinishing()) return;
                    String r = Rules.check(BlockActivity.this, pkg, "", Apps.launchable(BlockActivity.this), Apps.exempt(BlockActivity.this));
                    if (r == null) {
                        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                        finish();
                        if (li != null) startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } else {
                        h.postDelayed(recheck, 4000);
                    }
                });
            }).start();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        h.postDelayed(recheck, 1500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(recheck);
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
            final EditText note = new EditText(this);
            note.setHint("✍️ הודעה לאבא (לא חובה) – למה אתה צריך?");
            note.setTextColor(Color.WHITE);
            note.setHintTextColor(0x99FFFFFF);
            GradientDrawable ng = new GradientDrawable();
            ng.setColor(0x33FFFFFF);
            ng.setCornerRadius(Ui.dp(this, 14));
            note.setBackground(ng);
            note.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.bottomMargin = Ui.dp(this, 10);
            l.addView(note, nlp);
            final TextView status = Ui.text(this, l, "", 16, 0xFFBBF7D0, true);
            final Button[] ask = new Button[1];
            ask[0] = Ui.button(this, l, "📩 בקשת אישור מאבא", Ui.GREEN, v -> {
                ask[0].setEnabled(false);
                status.setText("שולח...");
                new Thread(() -> {
                    boolean ok = send(note.getText().toString().trim());
                    runOnUiThread(() -> {
                        status.setText(ok ? "✅ הבקשה נשלחה! מחכים לתשובה" : "❌ אין אינטרנט, נסה שוב");
                        ask[0].setEnabled(!ok);
                    });
                }).start();
            });
        }
        if (Rules.isBrowser(this, pkg)) {
            Ui.button(this, l, "🌐 דפדפן בטוח / בקשת אתר", Ui.GREEN, v -> {
                startActivity(new Intent(this, WebActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            });
        }
        Ui.button(this, l, "💬 הודעות עם אבא", 0x55FFFFFF,
                v -> startActivity(new Intent(this, ChatActivity.class)));
        Ui.button(this, l, "🏠 חזרה למסך הבית", Ui.BLUE, v -> goHome());
        Ui.text(this, l, "חסימה חכמה · חי אפליקציות", 13, 0x99FFFFFF, false).setPadding(0, Ui.dp(this, 24), 0, 0);
    }

    private boolean send(String msg) {
        try {
            JSONObject r = new JSONObject();
            r.put("p", pkg);
            r.put("n", name);
            if (!msg.isEmpty()) r.put("msg", msg);
            r.put("t", new JSONObject().put(".sv", "timestamp"));
            Fb.put("d/" + Cfg.deviceId(this) + "/req/" + Cfg.key(pkg), r.toString());
            if (!msg.isEmpty()) ChatActivity.sendMsg(this, "📩 ביקשתי את " + name + ": " + msg);
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
