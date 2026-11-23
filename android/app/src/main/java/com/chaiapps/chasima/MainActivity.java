package com.chaiapps.chasima;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** מסך ההתקנה בנגן (פעם אחת), ואחר כך מסך נעול שרק ההורה נכנס אליו עם קוד. */
public class MainActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean parentOpen = false;
    private boolean visible = false;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            new Thread(() -> {
                try {
                    String json = Fb.get("d/" + Cfg.deviceId(MainActivity.this) + "/cfg");
                    boolean before = Cfg.paired(MainActivity.this);
                    Cfg.saveCfg(MainActivity.this, json);
                    if (before != Cfg.paired(MainActivity.this)) runOnUiThread(MainActivity.this::build);
                } catch (Exception ignored) {}
            }).start();
            h.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        registerPair();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        build();
        h.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        h.removeCallbacks(poll);
    }

    private void registerPair() {
        new Thread(() -> {
            try {
                String id = Cfg.deviceId(this);
                Fb.put("pair/" + Cfg.pairCode(this), JSONObject.quote(id));
                JSONObject info = new JSONObject();
                info.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                info.put("android", Build.VERSION.RELEASE);
                Fb.patch("d/" + id + "/info", info.toString());
            } catch (Exception ignored) {}
        }).start();
    }

    private boolean guardOn() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private boolean adminOn() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(AdminReceiver.name(this));
    }

    private boolean batteryOk() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void build() {
        if (Cfg.setupDone(this) && !parentOpen) buildLocked();
        else if (Cfg.setupDone(this)) buildParent();
        else buildSetup();
    }

    // ---------- התקנה ----------
    private void buildSetup() {
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🛡️ חסימה חכמה", 28, Color.WHITE, true);
        Ui.text(this, l, "הגדרה ראשונה של הנגן – 4 שלבים", 16, 0xCCFFFFFF, false);

        boolean paired = Cfg.paired(this);
        LinearLayout c1 = Ui.card(this, l);
        Ui.text(this, c1, (paired ? "✅" : "1️⃣") + " חיבור לטלפון של אבא", 18, Color.WHITE, true);
        if (paired) {
            Ui.text(this, c1, "מחובר!", 16, 0xFFBBF7D0, false);
        } else {
            Ui.text(this, c1, "הקלד את הקוד הזה באפליקציית ההורה:", 15, 0xCCFFFFFF, false);
            TextView code = Ui.text(this, c1, Cfg.pairCode(this), 44, 0xFFFDE68A, true);
            code.setLetterSpacing(0.15f);
            Ui.text(this, c1, "(הנגן צריך להיות מחובר לאינטרנט)", 13, 0x99FFFFFF, false);
        }

        boolean g = guardOn();
        LinearLayout c2 = Ui.card(this, l);
        Ui.text(this, c2, (g ? "✅" : "2️⃣") + " הפעלת השומר", 18, Color.WHITE, true);
        if (!g) {
            Ui.text(this, c2, "במסך שייפתח: בוחרים \"חסימה חכמה – שומר\" ומדליקים", 15, 0xCCFFFFFF, false);
            Ui.button(this, c2, "פתיחת הגדרות נגישות", Ui.BLUE,
                    v -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            Ui.text(this, c2, "אם אי אפשר להדליק (אפור / \"הגדרה מוגבלת\"): לוחצים למטה, ואז ⋮ למעלה ← \"מתן הרשאה להגדרות מוגבלות\", וחוזרים לכפתור הכחול", 14, 0xFFFDE68A, false);
            Ui.button(this, c2, "פרטי האפליקציה", 0x55FFFFFF,
                    v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))));
        }

        boolean a = adminOn();
        LinearLayout c3 = Ui.card(this, l);
        Ui.text(this, c3, (a ? "✅" : "3️⃣") + " הגנה מפני מחיקה", 18, Color.WHITE, true);
        if (!a) {
            Ui.button(this, c3, "הפעלת מנהל מכשיר", Ui.BLUE, v -> {
                Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.name(this));
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "כדי שלא יהיה אפשר למחוק את חסימה חכמה מהנגן");
                open(i);
            });
        }

        boolean bt = batteryOk();
        LinearLayout c4 = Ui.card(this, l);
        Ui.text(this, c4, (bt ? "✅" : "4️⃣") + " פעולה רציפה ברקע", 18, Color.WHITE, true);
        if (!bt) {
            Ui.button(this, c4, "לאפשר פעולה ברקע", Ui.BLUE,
                    v -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()))));
        }

        if (paired && g && a) {
            Ui.button(this, l, "🔒 סיום – להפעיל את ההגנה", Ui.GREEN, v -> {
                Cfg.setSetupDone(this, true);
                Cfg.setLocalSettingsUntil(this, 0);
                parentOpen = false;
                if (GuardService.instance != null) GuardService.instance.recheck();
                build();
            });
        } else {
            Ui.text(this, l, "כשכל השלבים מסומנים ✅ יופיע כאן כפתור הסיום", 14, 0x99FFFFFF, false);
        }
    }

    // ---------- מסך נעול ----------
    private void buildLocked() {
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🛡️", 64, Color.WHITE, false).setPadding(0, Ui.dp(this, 30), 0, 0);
        Ui.text(this, l, "הנגן מוגן", 26, Color.WHITE, true);
        Ui.text(this, l, "ע\"י חסימה חכמה", 16, 0xCCFFFFFF, false);
        if (!guardOn()) Ui.text(this, l, "⚠️ השומר כבוי!", 18, 0xFFFCA5A5, true);

        LinearLayout c = Ui.card(this, l);
        Ui.text(this, c, "כניסת הורה", 16, Color.WHITE, true);
        final EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setGravity(Gravity.CENTER);
        pin.setTextColor(Color.WHITE);
        pin.setHintTextColor(0x88FFFFFF);
        pin.setHint("קוד הורה");
        pin.setTextSize(22);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x33FFFFFF);
        gd.setCornerRadius(Ui.dp(this, 12));
        pin.setBackground(gd);
        pin.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        c.addView(pin);
        final TextView err = Ui.text(this, c, "", 14, 0xFFFCA5A5, false);
        Ui.button(this, c, "כניסה", Ui.BLUE, v -> {
            String want = Cfg.cfg(this).optString("pin", "");
            if (!want.isEmpty() && want.equals(pin.getText().toString().trim())) {
                parentOpen = true;
                build();
            } else {
                err.setText("קוד שגוי");
                pin.setText("");
            }
        });
    }

    // ---------- תפריט הורה בנגן ----------
    private void buildParent() {
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "👨 תפריט הורה", 26, Color.WHITE, true);
        LinearLayout c = Ui.card(this, l);
        Ui.text(this, c, (guardOn() ? "✅" : "❌") + " השומר  ·  " + (adminOn() ? "✅" : "❌") + " הגנה ממחיקה", 16, Color.WHITE, false);
        Ui.text(this, c, "גרסה " + BuildConfig.VERSION_NAME, 13, 0x99FFFFFF, false);

        Ui.button(this, l, "⚙️ פתיחת הגדרות הנגן ל־10 דקות", Ui.BLUE, v -> {
            Cfg.setLocalSettingsUntil(this, System.currentTimeMillis() + 10 * 60000L);
            open(new Intent(Settings.ACTION_SETTINGS));
        });
        Ui.button(this, l, "🔄 הגדרה מחדש (חיבור / הרשאות)", 0x55FFFFFF, v -> {
            Cfg.setSetupDone(this, false);
            Cfg.setLocalSettingsUntil(this, System.currentTimeMillis() + 30 * 60000L);
            parentOpen = false;
            build();
        });
        Ui.button(this, l, "🗑️ הסרת ההגנה מהנגן", Ui.RED, v -> {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                dpm.removeActiveAdmin(AdminReceiver.name(this));
            } catch (Exception ignored) {}
            Cfg.setSetupDone(this, false);
            Cfg.setLocalSettingsUntil(this, System.currentTimeMillis() + 60 * 60000L);
            parentOpen = false;
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        Ui.button(this, l, "🔒 יציאה", Ui.GREEN, v -> {
            parentOpen = false;
            build();
        });
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (parentOpen && Cfg.localSettingsUntil(this) < System.currentTimeMillis()) parentOpen = false;
    }
}
