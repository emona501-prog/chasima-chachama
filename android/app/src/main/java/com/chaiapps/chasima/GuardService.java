package com.chaiapps.chasima;

import android.accessibilityservice.AccessibilityService;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Vibrator;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** השומר: רץ כל הזמן ברקע, מקבל פקודות מההורה וחוסם אפליקציות. */
public class GuardService extends AccessibilityService {

    static volatile GuardService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private ExecutorService io;
    private volatile Set<String> launchable = new HashSet<>();
    private volatile Set<String> exempt = new HashSet<>();
    private volatile String current = "";
    private volatile String currentCls = "";
    private long lastAcct = 0;
    private String acctPkg = "";
    private long lastBlockAt = 0;
    private String lastBlockPkg = "";
    private int settingsTaps = 0;
    private long settingsTapsAt = 0;
    private long lastBeat = 0;
    private volatile Ringtone alarmRt;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            main.postDelayed(() -> {
                refreshLists();
                io.execute(() -> Apps.report(GuardService.this));
            }, 1500);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stop.get()) return;
            account();
            check(current);
            // גיבוי: מושכים את ההגדרות וההודעות מההורה כל 6 שניות, גם אם ההאזנה החיה נקטעה
            io.execute(() -> pullCfg("d/" + Cfg.deviceId(GuardService.this) + "/cfg"));
            io.execute(GuardService.this::pollChat);
            maybeAutoUpdate();
            if (System.currentTimeMillis() - lastBeat > 45000) beat();
            main.postDelayed(this, 6000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        stop.set(false);
        io = Executors.newCachedThreadPool();
        refreshLists();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(pkgReceiver, f);
        }

        io.execute(this::syncLoop);
        io.execute(this::chatLoop);
        io.execute(() -> Apps.report(this));
        io.execute(() -> pullCfg("d/" + Cfg.deviceId(this) + "/cfg"));
        main.postDelayed(tick, 3000);
        beat();
    }

    private void refreshLists() {
        launchable = Apps.launchable(this);
        exempt = Apps.exempt(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (exempt.contains(pkg) && !pkg.equals(getPackageName())) {
            // מקלדת/שורת מצב לא נחשבות "אפליקציה פתוחה", חוץ ממסך הבית
            if (!isHome(pkg)) return;
        }
        boolean changed = !pkg.equals(current);
        account();
        current = pkg;
        currentCls = e.getClassName() == null ? "" : e.getClassName().toString();
        // גילוי האפליקציה המוסתרת: 10 לחיצות רצופות על ההגדרות
        if (changed && pkg.equals(Rules.SETTINGS)) {
            long now = System.currentTimeMillis();
            if (now - settingsTapsAt > 20000) settingsTaps = 0;
            settingsTapsAt = now;
            if (++settingsTaps >= 10) {
                settingsTaps = 0;
                performGlobalAction(GLOBAL_ACTION_HOME);
                main.postDelayed(() -> {
                    try {
                        startActivity(new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    } catch (Exception ignored) {}
                }, 150);
                return;
            }
        }
        check(pkg);
        if (changed && System.currentTimeMillis() - lastBeat > 5000) beat();
    }

    private boolean isHome(String pkg) {
        return !pkg.equals("com.android.systemui") && !pkg.equals("android") && launchableHome(pkg);
    }

    private boolean launchableHome(String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        home.setPackage(pkg);
        return !getPackageManager().queryIntentActivities(home, 0).isEmpty();
    }

    void recheck() {
        main.post(() -> check(current));
    }

    /** ספירת זמן מסך: כמה שניות האפליקציה הנוכחית הייתה פתוחה (רק כשהמסך דולק). */
    private void account() {
        long now = System.currentTimeMillis();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean on = pm != null && pm.isInteractive();
        if (lastAcct > 0 && on && Rules.counts(this, acctPkg, launchable)) {
            long sec = (now - lastAcct) / 1000;
            if (sec > 0 && sec <= 120) Cfg.addUsed(this, (int) sec);
        }
        lastAcct = now;
        acctPkg = on ? current : "";
    }

    private void check(String pkg) {
        if (Cfg.setupDone(this)) {
            JSONObject lost = Cfg.cfg(this).optJSONObject("lost");
            if (lost != null && lost.optBoolean("on", false)) {
                showLost();
                return;
            }
        }
        String reason = Rules.check(this, pkg, pkg.equals(current) ? currentCls : "", launchable, exempt);
        if (reason == null) return;
        long now = System.currentTimeMillis();
        if (pkg.equals(lastBlockPkg) && now - lastBlockAt < 1200) return;
        lastBlockPkg = pkg;
        lastBlockAt = now;
        final String bn = Apps.label(this, pkg), br = reason;
        io.execute(() -> {
            try {
                JSONObject bl = new JSONObject().put("n", bn).put("why", br)
                        .put("t", new JSONObject().put(".sv", "timestamp"));
                Fb.patch("d/" + Cfg.deviceId(this) + "/st", new JSONObject().put("blk", bl).toString());
            } catch (Exception ignored) {}
        });
        performGlobalAction(GLOBAL_ACTION_HOME);
        Intent i = new Intent(this, BlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("pkg", pkg)
                .putExtra("name", Apps.label(this, pkg))
                .putExtra("reason", reason);
        main.postDelayed(() -> startActivity(i), 150);
    }

    private long lastLostAt = 0;

    private void showLost() {
        long now = System.currentTimeMillis();
        if (now - lastLostAt < 1500) return;
        lastLostAt = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
        main.postDelayed(() -> startActivity(new Intent(this, LostActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)), 120);
    }

    /** פקודה חדשה מההורה? נשמר בזיכרון קבוע, כך שלא תלוי בשעון של הנגן ולא חוזר אחרי הפעלה מחדש. */
    private boolean fresh(String name, long v) {
        if (v <= 0) return false;
        android.content.SharedPreferences sp = Cfg.p(this);
        String k = "cmd_" + name;
        if (v <= sp.getLong(k, 0)) return false;
        sp.edit().putLong(k, v).apply();
        return true;
    }

    /** בהפעלה הראשונה של הגרסה: מסמנים פקודות ישנות כ"טופלו" כדי שלא יקפצו שוב. */
    private void initCmds() {
        android.content.SharedPreferences sp = Cfg.p(this);
        if (sp.getBoolean("cmdInit", false)) return;
        JSONObject cfg = Cfg.cfg(this);
        JSONObject b = cfg.optJSONObject("banner");
        sp.edit().putLong("cmd_alarm", cfg.optLong("alarm", 0))
                .putLong("cmd_photo", cfg.optLong("photoReq", 0))
                .putLong("cmd_banner", b == null ? 0 : b.optLong("t", 0))
                .putLong("cmd_upd", cfg.optLong("updateReq", 0))
                .putLong("cmd_loc", cfg.optLong("locateReq", 0))
                .putBoolean("cmdInit", true).apply();
    }

    /** פקודות מיידיות מההורה: אזעקה, צילום, איתור, הודעה, עדכון. */
    private void handleCmds() {
        initCmds();
        JSONObject cfg = Cfg.cfg(this);
        long a = cfg.optLong("alarm", 0);
        if (fresh("alarm", a)) startAlarm();
        else if (a == 0 && alarmRt != null && alarmRt.isPlaying()) stopAlarm();
        if (fresh("photo", cfg.optLong("photoReq", 0))) io.execute(() -> StealthCam.capture(this, "parent"));
        JSONObject banner = cfg.optJSONObject("banner");
        if (banner != null && fresh("banner", banner.optLong("t", 0))) {
            final String txt = banner.optString("txt", "");
            main.post(() -> {
                try {
                    startActivity(new Intent(this, BannerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra("txt", txt));
                } catch (Exception ignored) {}
            });
        }
        if (fresh("upd", cfg.optLong("updateReq", 0))) {
            main.post(() -> Updater.run(this, msg -> io.execute(() -> {
                try { Fb.patch("d/" + Cfg.deviceId(this) + "/st", "{\"upd\":" + JSONObject.quote(msg) + "}"); }
                catch (Exception ignored) {}
            })));
        }
        if (fresh("loc", cfg.optLong("locateReq", 0))) {
            io.execute(() -> { try { Fb.patch("d/" + Cfg.deviceId(this) + "/st", "{\"locMsg\":\"⏳ מחפש מיקום…\"}"); } catch (Exception ignored) {} });
            Loc.fresh(this, (lc, err) -> io.execute(() -> {
                try {
                    if (lc != null) Fb.patch("d/" + Cfg.deviceId(this) + "/loc", lc.toString());
                    Fb.patch("d/" + Cfg.deviceId(this) + "/st", new JSONObject().put("locMsg", err).toString());
                } catch (Exception ignored) {}
            }));
        }
    }

    private void startAlarm() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            if (alarmRt != null && alarmRt.isPlaying()) return;
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            alarmRt = RingtoneManager.getRingtone(this, u);
            if (alarmRt != null) {
                alarmRt.setStreamType(AudioManager.STREAM_ALARM);
                alarmRt.play();
            }
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(new long[]{0, 600, 400}, 0);
            main.postDelayed(this::stopAlarm, 30000);
        } catch (Exception ignored) {}
    }

    private void stopAlarm() {
        try { if (alarmRt != null) alarmRt.stop(); } catch (Exception ignored) {}
        try {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.cancel();
        } catch (Exception ignored) {}
    }

    /** האזנה חיה להגדרות שההורה משנה. */
    private void syncLoop() {
        String path = "d/" + Cfg.deviceId(this) + "/cfg";
        long wait = 3000;
        while (!stop.get()) {
            try {
                Fb.stream(path, (event, data) -> {
                    if (event.equals("put") || event.equals("patch")) {
                        pullCfg(path);
                    }
                }, stop);
                wait = 3000;
            } catch (Exception ex) {
                try { Thread.sleep(wait); } catch (InterruptedException ie) { return; }
                wait = Math.min(wait * 2, 60000);
            }
        }
    }

    /** האזנה להודעות מאבא – מציג התראה בנגן. */
    private void chatLoop() {
        String url = Fb.url("d/" + Cfg.deviceId(this) + "/chat") + Fb.lastQ(1);
        long wait = 3000;
        while (!stop.get()) {
            try {
                Fb.streamUrl(url, (event, data) -> {
                    if (!event.equals("put") && !event.equals("patch")) return;
                    try {
                        JSONObject ev = new JSONObject(data);
                        String path = ev.optString("path", "/");
                        Object d = ev.opt("data");
                        if (path.equals("/") && d instanceof JSONObject) {
                            JSONObject all = (JSONObject) d;
                            java.util.Iterator<String> it = all.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                gotChat(k, all.optJSONObject(k));
                            }
                        } else if (d instanceof JSONObject) {
                            gotChat(path.replace("/", ""), (JSONObject) d);
                        }
                    } catch (Exception ignored) {}
                }, stop);
                wait = 3000;
            } catch (Exception ex) {
                try { Thread.sleep(wait); } catch (InterruptedException ie) { return; }
                wait = Math.min(wait * 2, 60000);
            }
        }
    }

    /** גיבוי להתראות צ'אט: מושך את ההודעה האחרונה ומתריע אם היא מאבא. */
    private void pollChat() {
        try {
            String json = Fb.last("d/" + Cfg.deviceId(this) + "/chat", 1);
            if (json == null || json.equals("null")) return;
            JSONObject all = new JSONObject(json);
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                gotChat(k, all.optJSONObject(k));
            }
        } catch (Exception ignored) {}
    }

    /** בדיקת עדכון אוטומטית כל 6 שעות. */
    private void maybeAutoUpdate() {
        long now = System.currentTimeMillis();
        long last = Cfg.p(this).getLong("updCheck", 0);
        if (now - last < 6 * 3600000L) return;
        Cfg.p(this).edit().putLong("updCheck", now).apply();
        Updater.checkAndUpdate(this, msg -> io.execute(() -> {
            try { Fb.patch("d/" + Cfg.deviceId(this) + "/st", "{\"upd\":" + JSONObject.quote(msg) + "}"); }
            catch (Exception ignored) {}
        }), true);
    }

    private void gotChat(String key, JSONObject m) {
        if (m == null || key.isEmpty()) return;
        String last = Cfg.p(this).getString("chatSeen", "");
        if (key.compareTo(last) <= 0) return;
        Cfg.p(this).edit().putString("chatSeen", key).apply();
        if (last.isEmpty()) return; // הפעם הראשונה – רק מסמנים
        if (!"p".equals(m.optString("from"))) return;
        Notify.chat(this, m.optString("txt", ""));
        // פותחים את מסך ההודעות מיד, כדי שהילד יראה גם אם ההתראות כבויות
        main.post(() -> {
            try {
                startActivity(new Intent(this, ChatActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (Exception ignored) {}
        });
    }

    private void pullCfg(String path) {
        try {
            String json = Fb.get(path);
            String old = Cfg.p(this).getString("cfg", "");
            Cfg.saveCfg(this, json);
            Cfg.p(this).edit().putLong("cfgAt", System.currentTimeMillis()).apply();
            if (!old.equals(json)) recheck();
            handleCmds();
        } catch (Exception ex) {
            Cfg.p(this).edit().putString("cfgErr", String.valueOf(ex.getMessage())).apply();
        }
    }

    /** דיווח להורה: הנגן מחובר, מה פתוח עכשיו, סוללה. */
    private void beat() {
        lastBeat = System.currentTimeMillis();
        final String pkg = current;
        io.execute(() -> {
            try {
                JSONObject st = new JSONObject();
                st.put("seen", new JSONObject().put(".sv", "timestamp"));
                st.put("app", pkg);
                st.put("an", pkg.isEmpty() ? "" : Apps.label(this, pkg));
                st.put("guard", true);
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                st.put("admin", dpm.isAdminActive(AdminReceiver.name(this)));
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                st.put("bat", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                st.put("v", BuildConfig.VERSION_NAME);
                st.put("used", Cfg.usedSec(this) / 60);
                st.put("cfgAt", Cfg.p(this).getLong("cfgAt", 0));
                JSONObject rl = Cfg.cfg(this).optJSONObject("rules");
                st.put("rules", rl == null ? 0 : rl.length());
                st.put("err", Cfg.p(this).getString("cfgErr", ""));
                st.put("day", Cfg.today());
                st.put("now", System.currentTimeMillis());
                st.put("loc", Loc.allowed(this));
                JSONObject lc = Loc.last(this);
                if (lc != null) Fb.patch("d/" + Cfg.deviceId(this) + "/loc", lc.toString());
                st.put("alert", "");
                Fb.patch("d/" + Cfg.deviceId(this) + "/st", st.toString());
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        stop.set(true);
        instance = null;
        stopAlarm();
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) {}
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                Fb.patch("d/" + Cfg.deviceId(c) + "/st", "{\"guard\":false,\"alert\":\"השומר כובה בנגן\"}");
            } catch (Exception ignored) {}
        }).start();
        if (io != null) io.shutdownNow();
        super.onDestroy();
    }
}
