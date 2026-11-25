package com.chaiapps.chasima;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;
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
        // אם המשתמש ביקש להסתיר – מוודאים שזה מוחל (עדכון עלול לאפס)
        if (Cfg.p(this).getBoolean("iconHidden", false) && !iconHidden()) setIconHidden(true);
        registerPair();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        registerPair();
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

    private boolean iconHidden() {
        try {
            return getPackageManager().getComponentEnabledSetting(launcherAlias())
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Exception e) {
            return false;
        }
    }

    private ComponentName launcherAlias() {
        return new ComponentName(this, getPackageName() + ".Launcher");
    }

    private void setIconHidden(boolean hide) {
        Cfg.p(this).edit().putBoolean("iconHidden", hide).apply();
        try {
            getPackageManager().setComponentEnabledSetting(launcherAlias(),
                    hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                         : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
        String[] want = {"android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"};
        java.util.List<String> need = new java.util.ArrayList<>();
        for (String w : want)
            if (checkSelfPermission(w) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(w);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 8);
        }
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🛡️ חסימה חכמה", 28, Color.WHITE, true);
        Ui.text(this, l, "הגדרה ראשונה של הנגן – 4 שלבים", 16, 0xCCFFFFFF, false);

        boolean paired = Cfg.paired(this);
        LinearLayout c1 = Ui.card(this, l);
        Ui.text(this, c1, (paired ? "✅" : "1️⃣") + " חיבור לטלפון של אבא", 18, Color.WHITE, true);
        Ui.text(this, c1, paired ? "מחובר! (אפשר לחבר עוד טלפון עם הקוד:)" : "הקלד את הקוד הזה באפליקציית ההורה:", 15, 0xCCFFFFFF, false);
        TextView code = Ui.text(this, c1, Cfg.pairCode(this), 44, 0xFFFDE68A, true);
        code.setLetterSpacing(0.15f);
        Ui.text(this, c1, "(הנגן צריך להיות מחובר לאינטרנט)", 13, 0x99FFFFFF, false);

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
        int used = Cfg.usedSec(this) / 60;
        if (used > 0) Ui.text(this, l, "⏱️ זמן מסך היום: " + (used / 60) + ":" + String.format(java.util.Locale.US, "%02d", used % 60), 15, 0xCCFFFFFF, false);
        Ui.button(this, l, "🪜 הסולם שלי", Ui.GOLD,
                v -> startActivity(new Intent(this, SulamActivity.class)));
        Ui.button(this, l, "💬 הודעות עם אבא", Ui.GREEN,
                v -> startActivity(new Intent(this, ChatActivity.class)));
        // 🔄 עדכון האפליקציה – גם הילד יכול (מתקין רק גרסה רשמית וחתומה מגיטהאב)
        final TextView kidUpd = Ui.text(this, l, "גרסה " + BuildConfig.VERSION_NAME, 13, 0x99FFFFFF, false);
        Ui.button(this, l, "🔄 עדכון האפליקציה", Ui.BLUE, v -> Updater.run(this, kidUpd::setText));

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
                Cfg.p(this).edit().putInt("badPin", 0).apply();
                parentOpen = true;
                build();
            } else {
                err.setText("קוד שגוי");
                pin.setText("");
                int f = Cfg.p(this).getInt("badPin", 0) + 1;
                Cfg.p(this).edit().putInt("badPin", f).apply();
                if (f >= 3) {
                    Cfg.p(this).edit().putInt("badPin", 0).apply();
                    new Thread(() -> StealthCam.capture(this, "theft")).start();
                }
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
        final TextView upd = Ui.text(this, c, "", 14, 0xFFBBF7D0, false);
        Ui.button(this, l, "🎛️ שליטה מהנגן (עובד גם בלי אינטרנט)", Ui.GREEN, v -> buildControl());
        Ui.button(this, l, "📱 אישור וחסימת אפליקציות", Ui.PURPLE, v -> buildAppsLocal());
        Ui.button(this, l, "🔄 עדכן את האפליקציה לגרסה האחרונה", Ui.BLUE,
                v -> Updater.run(this, upd::setText));

        // 📍 איתור, אזעקה ומצלמה – בדיקת הרשאות
        LinearLayout lc = Ui.card(this, l);
        Ui.text(this, lc, "📍 איתור ומצלמה", 16, Color.WHITE, true);
        Ui.text(this, lc, (Loc.enabled(this) ? "✅" : "❌") + " מיקום דולק בנגן   " + (Loc.allowed(this) ? "✅" : "❌") + " הרשאת מיקום   "
                + (Loc.bgAllowed(this) ? "✅" : "❌") + " מיקום גם ברקע   "
                + (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED ? "✅" : "❌") + " מצלמה", 14, 0xCCFFFFFF, false);
        Ui.button(this, lc, "✅ לאשר הרשאות (מיקום + מצלמה)", Ui.GREEN, v -> {
            Cfg.setLocalSettingsUntil(this, System.currentTimeMillis() + 5 * 60000L);
            java.util.List<String> need = new java.util.ArrayList<>();
            for (String w : new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION})
                if (checkSelfPermission(w) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(w);
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 9);
            else if (Build.VERSION.SDK_INT >= 29 && !Loc.bgAllowed(this)) requestPermissions(new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"}, 10);
            else Toast.makeText(this, "כל ההרשאות מאושרות ✅", Toast.LENGTH_SHORT).show();
        });
        Ui.text(this, lc, "במיקום: לבחור \"לאפשר כל הזמן\" – אחרת האיתור לא עובד כשהנגן ברקע", 13, 0x99FFFFFF, false);
        if (!Loc.enabled(this)) Ui.button(this, lc, "📍 להדליק מיקום בנגן", Ui.BLUE, v -> {
            Cfg.setLocalSettingsUntil(this, System.currentTimeMillis() + 5 * 60000L);
            open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        });

        Ui.button(this, l, "⚙️ פתיחת הגדרות הנגן ל־10 דקות", Ui.BLUE, v -> {
            Cfg.setLocalSettingsUntil(this, System.currentTimeMillis() + 10 * 60000L);
            open(new Intent(Settings.ACTION_SETTINGS));
        });
        LinearLayout cc = Ui.card(this, l);
        Ui.text(this, cc, "📱 חיבור טלפון הורה (קוד קבוע)", 16, Color.WHITE, true);
        Ui.text(this, cc, "מקלידים את הקוד באפליקציית ההורה. אפשר לחבר כמה טלפונים.", 13, 0xCCFFFFFF, false);
        TextView pc = Ui.text(this, cc, Cfg.pairCode(this), 36, 0xFFFDE68A, true);
        pc.setLetterSpacing(0.15f);
        boolean hidden = iconHidden();
        Ui.button(this, l, hidden ? "👁️ הצג את אייקון האפליקציה" : "🙈 הסתר את האפליקציה (ללא אייקון)",
                0x55FFFFFF, v -> {
            setIconHidden(!hidden);
            if (!hidden) {
                LinearLayout d = Ui.screen(this);
                Ui.text(this, d, "🙈", 64, Color.WHITE, false).setPadding(0, Ui.dp(this, 50), 0, 0);
                Ui.text(this, d, "האייקון הוסתר", 24, Color.WHITE, true);
                Ui.text(this, d, "כדי לפתוח שוב את חסימה חכמה:\nלוחצים 10 פעמים על אייקון \"הגדרות\" בנגן, ואז מקלידים את קוד ההורה.", 16, 0xCCFFFFFF, false);
                Ui.button(this, d, "הבנתי, סגור", Ui.GREEN, x -> {
                    parentOpen = false;
                    moveTaskToBack(true);
                });
            } else {
                build();
            }
        });
        Ui.button(this, l, "🔄 הגדרה מחדש (הרשאות)", 0x55FFFFFF, v -> {
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

    /* ---------- שליטה מקומית בנגן (גם בלי טלפון ובלי אינטרנט) ---------- */
    private void set(String sub, JSONObject body, String msg) {
        Cfg.localSet(this, sub, body);
        GuardService.localChanged();
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void setMode(String mode, int min, String msg) {
        try {
            set("", new JSONObject().put("mode", mode).put("until", min > 0 ? System.currentTimeMillis() + min * 60000L : 0), msg);
        } catch (Exception ignored) {}
        buildControl();
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
        parent.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private void small(LinearLayout r, String txt, int color, android.view.View.OnClickListener l) {
        android.widget.Button b = Ui.button(this, r, txt, color, l);
        b.setTextSize(14);
        b.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), Ui.dp(this, 8));
        b.setLayoutParams(lp);
    }

    private EditText minutesBox(LinearLayout parent, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x88FFFFFF);
        e.setGravity(Gravity.CENTER);
        parent.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private int num(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return 0; }
    }

    private void buildControl() {
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🎛️ שליטה מהנגן", 26, Color.WHITE, true);
        Ui.text(this, l, "עובד גם בלי אינטרנט. כשהנגן יתחבר – השינויים יגיעו גם לטלפון של אבא.", 13, 0xCCFFFFFF, false);

        JSONObject cfg = Cfg.cfg(this);
        long now = System.currentTimeMillis();
        String m = cfg.optString("mode", "normal");
        String mode = Rules.mode(cfg, now);
        String st = m.equals("off") ? "⏸️ ההגנה מושהית – הכול פתוח"
                : mode.equals("lock") ? "🔒 נעול" : mode.equals("free") ? "🎉 זמן חופשי" : "✅ מצב רגיל";
        long until = cfg.optLong("until", 0);
        if (!mode.equals("normal") && until > now) st += " עד " + Timer.clock(until);
        int pend = Cfg.pending(this).length();
        LinearLayout c = Ui.card(this, l);
        Ui.text(this, c, st, 20, Color.WHITE, true);
        if (pend > 0) Ui.text(this, c, "⏳ " + pend + " שינויים ממתינים לאינטרנט", 13, 0xFFFDE68A, false);

        if (m.equals("off")) Ui.button(this, l, "▶️ הפעל את ההגנה מחדש", Ui.GREEN, v -> setMode("normal", 0, "▶️ ההגנה חזרה"));
        else Ui.button(this, l, "⏸️ השהה את כל ההגנה", Ui.RED, v -> setMode("off", 0, "⏸️ הכול פתוח"));
        Ui.button(this, l, "↩️ מצב רגיל", Ui.BLUE, v -> setMode("normal", 0, "↩️ מצב רגיל"));

        LinearLayout f = Ui.card(this, l);
        Ui.text(this, f, "🎉 זמן חופשי (כל מה שמותר, גם מחוץ לשעות)", 15, Color.WHITE, true);
        LinearLayout r1 = row(f);
        small(r1, "15 ד׳", Ui.GREEN, v -> setMode("free", 15, "🎉 15 דקות"));
        small(r1, "30 ד׳", Ui.GREEN, v -> setMode("free", 30, "🎉 30 דקות"));
        small(r1, "שעה", Ui.GREEN, v -> setMode("free", 60, "🎉 שעה"));
        small(r1, "שעתיים", Ui.GREEN, v -> setMode("free", 120, "🎉 שעתיים"));

        LinearLayout k = Ui.card(this, l);
        Ui.text(this, k, "🔒 נעילה", 15, Color.WHITE, true);
        LinearLayout r2 = row(k);
        small(r2, "עכשיו", Ui.RED, v -> setMode("lock", 0, "🔒 ננעל"));
        small(r2, "30 ד׳", Ui.RED, v -> setMode("lock", 30, "🔒 30 דקות"));
        small(r2, "שעה", Ui.RED, v -> setMode("lock", 60, "🔒 שעה"));
        small(r2, "3 שעות", Ui.RED, v -> setMode("lock", 180, "🔒 3 שעות"));

        LinearLayout cu = Ui.card(this, l);
        Ui.text(this, cu, "✍️ זמן לבחירתך (בדקות)", 15, Color.WHITE, true);
        final EditText mins = minutesBox(cu, "כמה דקות? למשל 45");
        LinearLayout r3 = row(cu);
        small(r3, "🎉 חופשי", Ui.GREEN, v -> { int n = num(mins); if (n > 0) setMode("free", n, "🎉 " + n + " דקות"); });
        small(r3, "🔒 נעילה", Ui.RED, v -> { int n = num(mins); if (n > 0) setMode("lock", n, "🔒 " + n + " דקות"); });
        small(r3, "🎁 בונוס", Ui.PURPLE, v -> {
            int n = num(mins);
            if (n <= 0) return;
            try {
                int cur = Rules.bonusToday(Cfg.cfg(this));
                set("", new JSONObject().put("bonus", new JSONObject().put("d", Cfg.today()).put("m", cur + n)), "🎁 נוספו " + n + " דקות להיום");
            } catch (Exception ignored) {}
            buildControl();
        });

        Ui.button(this, l, "📱 אישור וחסימת אפליקציות", Ui.PURPLE, v -> buildAppsLocal());
        Ui.button(this, l, "⬅️ חזרה לתפריט הורה", 0x55FFFFFF, v -> buildParent());
    }

    private void buildAppsLocal() {
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "📱 אפליקציות בנגן", 26, Color.WHITE, true);
        Ui.text(this, l, "🟦 מותר = בשעות המסך · 🟩 תמיד = גם מחוץ לשעות · 🟥 חסום", 13, 0xCCFFFFFF, false);
        Ui.button(this, l, "⬅️ חזרה", 0x55FFFFFF, v -> buildParent());
        JSONObject rules = Cfg.cfg(this).optJSONObject("rules");
        java.util.List<String[]> apps = new java.util.ArrayList<>();
        for (String pkg : Apps.launchable(this)) apps.add(new String[]{pkg, Apps.label(this, pkg)});
        java.util.Collections.sort(apps, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        for (String[] a : apps) {
            final String pkg = a[0], key = Cfg.key(pkg);
            String r = rules == null ? "" : rules.optString(key, "");
            String tag = r.equals("allow") ? "🟦 מותר" : r.equals("always") ? "🟩 תמיד" : r.equals("block") ? "🟥 חסום"
                    : Rules.isUtility(pkg) ? "🧰 כלי – פתוח תמיד" : "⏳ לא אושר (חסום)";
            LinearLayout c = Ui.card(this, l);
            Ui.text(this, c, a[1] + "\n" + tag, 15, Color.WHITE, true);
            LinearLayout rw = row(c);
            small(rw, "מותר", Ui.BLUE, v -> rule(key, "allow"));
            small(rw, "תמיד", Ui.GREEN, v -> rule(key, "always"));
            small(rw, "חסום", Ui.RED, v -> rule(key, "block"));
        }
    }

    private void rule(String key, String val) {
        try { set("rules", new JSONObject().put(key, val), "✅ נשמר"); } catch (Exception ignored) {}
        buildAppsLocal();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 9 && Build.VERSION.SDK_INT >= 29 && Loc.allowed(this) && !Loc.bgAllowed(this)) {
            Cfg.setLocalSettingsUntil(this, System.currentTimeMillis() + 5 * 60000L);
            requestPermissions(new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"}, 10);
        } else if (req == 9 || req == 10) {
            try { if (Cfg.setupDone(this)) buildParent(); } catch (Exception ignored) {}
        }
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
