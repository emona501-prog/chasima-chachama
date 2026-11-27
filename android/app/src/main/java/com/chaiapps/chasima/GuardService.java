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

    /** אחרי שינוי מקומי בנגן: לבדוק מחדש מיד ולנסות לשלוח לשרת. */
    static void localChanged() {
        GuardService g = instance;
        if (g == null) return;
        g.main.post(g::recheck);
        try { g.io.execute(() -> Cfg.flushPending(g)); } catch (Exception ignored) {}
    }

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
            if (++sulN % 5 == 0) io.execute(GuardService.this::sulamTick);
            if (sulN % 300 == 1) io.execute(GuardService.this::updTick);   // כל ~30 דקות
            maybeAutoUpdate();
            Outbox.flush(GuardService.this, false);
            Trail.tick(GuardService.this);
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
        main.postDelayed(pillTick, 2000);
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
        // מסכי בלוטות' בהגדרות – מותרים (מזהים לפי שם המסך או הכותרת)
        if (pkg.equals(Rules.SETTINGS)) {
            StringBuilder t = new StringBuilder(currentCls);
            try { for (CharSequence cs : e.getText()) t.append(' ').append(cs); } catch (Exception ignored) {}
            if (e.getContentDescription() != null) t.append(' ').append(e.getContentDescription());
            // בלוטות': מסך בלוטות' פותח "חלון בלוטות'" של 4 דקות – כל מסכי ההתאמה/החיבור בתוכו מותרים,
            // חוץ מהמסך הראשי של ההגדרות
            long nowBt = System.currentTimeMillis();
            boolean home = Rules.isSettingsHome(currentCls, t.toString());
            if (Rules.isBtScreen(t.toString())) { btUntil = nowBt + 4 * 60000L; currentCls = currentCls + "|bt"; }
            else if (!home && nowBt < btUntil) { btUntil = nowBt + 4 * 60000L; currentCls = currentCls + "|bt"; }
            else if (home) btUntil = 0;
        }
        if (pkg.equals(Rules.SETTINGS)) {
            // בהגדרות: מחכים רגע שהמסך יטען, ובודקים לפי מה שכתוב בו אם זה מסך בלוטות'
            final String cls0 = currentCls;
            main.postDelayed(() -> {
                if (!Rules.SETTINGS.equals(current)) return;
                int v = btByContent();
                if (v > 0 && !currentCls.endsWith("|bt")) { btUntil = System.currentTimeMillis() + 4 * 60000L; currentCls = cls0 + "|bt"; }
                else if (v < 0 && currentCls.endsWith("|bt")) currentCls = cls0;
                if (guardedScreen()) { if (screenHas(SELF)) Trail.start(this, "guard"); block(Rules.SETTINGS, "המסך הזה מוגן – רק אבא יכול לפתוח אותו"); return; }
                check(Rules.SETTINGS);
            }, 350);
        } else if (Rules.isInstaller(pkg)) {
            // מחיקה דרך מנהל קבצים / חלון הסרה – אם זו חסימה חכמה, חוסמים מיד
            main.postDelayed(() -> {
                if (!Rules.isInstaller(current)) return;
                if (Cfg.localSettingsUntil(this) <= System.currentTimeMillis() && Cfg.setupDone(this) && screenHas(SELF)
                        && (currentCls.toLowerCase(java.util.Locale.US).contains("uninstall") || screenHas(UNINST)))
                    { Trail.start(this, "uninstall"); block(pkg, "אי אפשר למחוק את חסימה חכמה"); return; }
                check(pkg);
            }, 250);
        } else {
            check(pkg);
        }
        if (changed && System.currentTimeMillis() - lastBeat > 5000) beat();
    }

    private static final String[] SELF = {"חסימה חכמה", "com.chaiapps.chasima", "חסימה חכמה – שומר"};
    private static final String[] UNINST = {"להסיר", "הסרת ההתקנה", "הסרת התקנה", "למחוק", "uninstall"};
    private static final String[] GUARDED = {"חסימה חכמה", "com.chaiapps.chasima", "מנהל מכשיר", "מנהלי מכשיר",
            "אפליקציות לניהול המכשיר", "device admin", "נגישות", "accessibility", "איפוס", "reset", "אפשרויות מפתח",
            "developer options", "משתמשים", "users", "מצב בטוח", "safe mode"};

    /**
     * כשההגדרות פתוחות לילד (בקשה שאבא אישר מרחוק) – עדיין חוסמים מסכים שבהם אפשר לכבות/למחוק את ההגנה.
     * כשאבא פתח בעצמו בנגן עם הקוד – הכל פתוח.
     */
    private boolean guardedScreen() {
        if (!Cfg.setupDone(this)) return false;
        if (Cfg.localSettingsUntil(this) > System.currentTimeMillis()) return false;
        if (currentCls.endsWith("|bt")) return false;
        String c = currentCls.toLowerCase(java.util.Locale.US);
        if (c.contains("deviceadmin") || c.contains("accessibility") || c.contains("reset") || c.contains("development")) return true;
        return screenHas(GUARDED);
    }

    private boolean screenHas(String[] words) {
        android.view.accessibility.AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); } catch (Exception e) { return false; }
        if (root == null) return false;
        StringBuilder sb = new StringBuilder();
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int n = 0;
        while (!q.isEmpty() && n < 500) {
            android.view.accessibility.AccessibilityNodeInfo x = q.poll();
            n++;
            if (x == null) continue;
            if (x.getText() != null) sb.append(' ').append(x.getText());
            if (x.getContentDescription() != null) sb.append(' ').append(x.getContentDescription());
            for (int i = 0; i < x.getChildCount(); i++) { try { q.add(x.getChild(i)); } catch (Exception ignored) {} }
        }
        String t = sb.toString().toLowerCase(java.util.Locale.US);
        for (String w : words) if (t.contains(w.toLowerCase(java.util.Locale.US))) return true;
        return false;
    }

    /** 1 = מסך בלוטות' בטוח, -1 = מסך מסוכן (נגישות/אפליקציות/מנהל מכשיר...), 0 = לא ידוע. */
    private int btByContent() {
        android.view.accessibility.AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); } catch (Exception e) { return 0; }
        if (root == null) return 0;
        StringBuilder sb = new StringBuilder();
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int n = 0;
        while (!q.isEmpty() && n < 500) {
            android.view.accessibility.AccessibilityNodeInfo x = q.poll();
            n++;
            if (x == null) continue;
            if (x.getText() != null) sb.append(' ').append(x.getText());
            if (x.getContentDescription() != null) sb.append(' ').append(x.getContentDescription());
            for (int i = 0; i < x.getChildCount(); i++) { try { q.add(x.getChild(i)); } catch (Exception ignored) {} }
        }
        String t = sb.toString().toLowerCase(java.util.Locale.US);
        String[] danger = {"נגישות", "accessibility", "אפליקציות", "apps", "מנהל", "admin", "אבטחה", "security",
                "פרטיות", "privacy", "מערכת", "system", "איפוס", "reset", "משתמש", "user", "חסימה חכמה", "מפתח", "developer",
                "סוללה", "battery", "אחסון", "storage", "הרשאות", "permission", "חשבונות", "accounts", "google"};
        for (String d : danger) if (t.contains(d)) return -1;
        return Rules.isBtScreen(t) ? 1 : 0;
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

    private long lastPinLockAt = 0;

    private void check(String pkg) {
        if (Pin.locked(this) && !Rules.isPhone(pkg) && !currentCls.contains("PinLockActivity")) {
            long now = System.currentTimeMillis();
            if (now - lastPinLockAt > 1500) {
                lastPinLockAt = now;
                performGlobalAction(GLOBAL_ACTION_HOME);
                main.postDelayed(() -> Pin.show(this), 120);
            }
            return;
        }
        if (Cfg.setupDone(this)) {
            JSONObject lost = Cfg.cfg(this).optJSONObject("lost");
            if (lost != null && lost.optBoolean("on", false)) {
                if (!Trail.on(this)) Trail.start(this, "lost");
                showLost();
                return;
            }
        }
        String reason = Rules.check(this, pkg, pkg.equals(current) ? currentCls : "", launchable, exempt);
        if (reason == null) return;
        block(pkg, reason);
    }

    private void block(String pkg, String reason) {
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
        if (fresh("alarm", a)) main.post(this::startAlarm);
        else if (a == 0 && ((alarmMp != null) || (alarmRt != null && alarmRt.isPlaying()))) main.post(this::stopAlarm);
        if (fresh("trailGo", cfg.optLong("trailReq", 0))) Trail.start(this, "dad");
        if (fresh("trailStop", cfg.optLong("trailStop", 0))) { Trail.stop(this); Trail.upload(this, true); }
        if (fresh("photo", cfg.optLong("photoReq", 0))) main.post(() -> CamActivity.snap(this, "parent"));
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

    private android.media.MediaPlayer alarmMp;

    /** 🔊 אזעקה: צליל סירנה מתוך האפליקציה (לא תלוי בצלצולים של הנגן), בעוצמה מלאה, 60 שניות. */
    private void startAlarm() {
        String err = "";
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                for (int st : new int[]{AudioManager.STREAM_ALARM, AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING})
                    try { am.setStreamVolume(st, am.getStreamMaxVolume(st), 0); } catch (Exception ignored) {}
            }
            if (alarmMp != null && alarmMp.isPlaying()) return;
            alarmMp = new android.media.MediaPlayer();
            alarmMp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            java.io.File cust = new java.io.File(getFilesDir(), "alarm_custom");
            JSONObject as = Cfg.cfg(this).optJSONObject("alarmSnd");
            if (as != null && "custom".equals(as.optString("k")) && cust.exists() && cust.length() > 0) {
                alarmMp.setDataSource(cust.getAbsolutePath());   // 🎙️ ההקלטה של אבא
            } else {
                android.content.res.AssetFileDescriptor fd = getResources().openRawResourceFd(R.raw.siren);
                alarmMp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            }
            alarmMp.setLooping(true);
            alarmMp.setVolume(1f, 1f);
            alarmMp.prepare();
            alarmMp.start();
        } catch (Exception e) {
            err = String.valueOf(e.getMessage());
            // גיבוי: צליל של הנגן
            try {
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                alarmRt = RingtoneManager.getRingtone(this, u);
                if (alarmRt != null) { alarmRt.setStreamType(AudioManager.STREAM_ALARM); alarmRt.play(); err = ""; }
            } catch (Exception ignored) {}
        }
        try {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(new long[]{0, 600, 400}, 0);
        } catch (Exception ignored) {}
        final String msg = err.isEmpty() ? "🔊 האזעקה מצלצלת בנגן (עד דקה)" : "❌ האזעקה לא הצליחה: " + err;
        io.execute(() -> { try { Fb.patch("d/" + Cfg.deviceId(this) + "/st", new JSONObject().put("alarmMsg", msg).toString()); } catch (Exception ignored) {} });
        main.removeCallbacks(alarmStop);
        main.postDelayed(alarmStop, 60000);
    }

    private final Runnable alarmStop = this::stopAlarm;

    private void stopAlarm() {
        try { if (alarmMp != null) { alarmMp.stop(); alarmMp.release(); } } catch (Exception ignored) {}
        alarmMp = null;
        try { if (alarmRt != null) alarmRt.stop(); } catch (Exception ignored) {}
        try {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.cancel();
        } catch (Exception ignored) {}
        io.execute(() -> { try { Fb.patch("d/" + Cfg.deviceId(this) + "/st", "{\"alarmMsg\":\"האזעקה כבויה\"}"); } catch (Exception ignored) {} });
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

    private int sulN = 0;
    private long btUntil = 0;

    /** 🆕 יש גרסה חדשה בגיטהאב? – התראה שלחיצה עליה מעדכנת מיד (פעם אחת לכל גרסה). */
    private void updTick() {
        try {
            int latest = Updater.latestBuild();
            if (latest <= 0) return;
            android.content.SharedPreferences sp = Cfg.p(this);
            sp.edit().putInt("updAvail", latest).apply();
            if (latest > BuildConfig.VERSION_CODE && sp.getInt("updNotified", 0) != latest) {
                sp.edit().putInt("updNotified", latest).apply();
                Notify.update(this);
            }
        } catch (Exception ignored) {}
    }

    /** 🪜 הסולם: התראה על הודעה/הצעה חדשה מאבא, ותזכורת יומית אם עוד לא סימן כלום. */
    private void sulamTick() {
        android.content.SharedPreferences sp = Cfg.p(this);
        try {
            String json = Fb.last("d/" + Cfg.deviceId(this) + "/s/msg", 1);
            if (json != null && !json.equals("null")) {
                JSONObject all = new JSONObject(json);
                java.util.Iterator<String> it = all.keys();
                if (it.hasNext()) {
                    String k = it.next();
                    String seen = sp.getString("sulMsg", "");
                    if (k.compareTo(seen) > 0) {
                        sp.edit().putString("sulMsg", k).apply();
                        JSONObject m = all.optJSONObject(k);
                        if (!seen.isEmpty() && m != null && m.optLong("seen", 0) == 0) {
                            boolean deal = m.optJSONObject("deal") != null;
                            Notify.sulam(this, 1101, deal ? "🎯 לאבא יש הצעה בשבילך!" : "💌 אבא שלח לך הודעה", m.optString("txt", ""));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        // 🎯 אתגרי בונוס – התראה כשנפתח אתגר
        try {
            String notes = sp.getString("sulNotes", "");
            if (!notes.isEmpty()) {
                String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
                String now = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date());
                String noted = sp.getString("sulNoted", "");
                org.json.JSONArray arr = new org.json.JSONArray(notes);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject n = arr.optJSONObject(i);
                    if (n == null || !today.equals(n.optString("d")) || now.compareTo(n.optString("at", "99")) < 0) continue;
                    String key = n.optString("d") + n.optString("id");
                    if (noted.contains("|" + key)) continue;
                    // לא מתריעים על אתגר שעבר זמנו ביותר משעתיים (למשל כשהנגן היה כבוי)
                    String[] hm = n.optString("at").split(":");
                    int atMin = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
                    String[] nw = now.split(":");
                    int nowMin = Integer.parseInt(nw[0]) * 60 + Integer.parseInt(nw[1]);
                    noted = noted + "|" + key;
                    if (noted.length() > 3000) noted = noted.substring(noted.length() - 2000);
                    sp.edit().putString("sulNoted", noted).apply();
                    if (nowMin - atMin <= 120) Notify.sulam(this, 1103 + (key.hashCode() & 0xff), n.optString("t"), n.optString("x"));
                    break;
                }
            }
        } catch (Exception ignored) {}
        // ⏰ תזכורות ליעדים – לחיצה פותחת ישר את ברסלב PRO במקום של היום
        try {
            String gr = sp.getString("sulGoalRem", "");
            if (!gr.isEmpty()) {
                String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
                String now = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date());
                String done = sp.getString("sulGoalRemDone", "");
                if (!done.startsWith(today)) done = today;
                org.json.JSONArray arr = new org.json.JSONArray(gr);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject n = arr.optJSONObject(i);
                    if (n == null) continue;
                    String at = n.optString("at", "99"), id = n.optString("id");
                    if (now.compareTo(at) < 0 || done.contains("|" + id + "|")) continue;
                    done = done + "|" + id + "|";
                    sp.edit().putString("sulGoalRemDone", done).apply();
                    String[] hm = at.split(":"), nw = now.split(":");
                    int late = Integer.parseInt(nw[0]) * 60 + Integer.parseInt(nw[1]) - Integer.parseInt(hm[0]) * 60 - Integer.parseInt(hm[1]);
                    if (late <= 120) Notify.sulamBp(this, 1300 + (id.hashCode() & 0xff), n.optString("t"), n.optString("x"), n.optString("act"));
                }
            }
        } catch (Exception ignored) {}
        // תזכורת יומית
        try {
            String rem = sp.getString("sulRem", "");
            if (rem.matches("\\d{2}:\\d{2}")) {
                String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
                String now = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date());
                if (now.compareTo(rem) >= 0 && !today.equals(sp.getString("sulRemDone", "")) ) {
                    sp.edit().putString("sulRemDone", today).apply();
                    if (!today.equals(sp.getString("sulDay", "")))
                        Notify.sulam(this, 1102, "🪜 הסולם מחכה לך!", "עוד לא סימנת היום – כמה לחיצות ואתה מטפס. קדימה אלוף! 💪");
                }
            }
        } catch (Exception ignored) {}
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
                startActivity(new Intent(this, SulamActivity.class).putExtra("view", "chat")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (Exception ignored) {}
        });
    }

    private void pullCfg(String path) {
        try {
            Cfg.flushPending(this);
            String json = Fb.get(path);
            String old = Cfg.p(this).getString("cfg", "");
            Cfg.saveCfg(this, json);
            Cfg.p(this).edit().putLong("cfgAt", System.currentTimeMillis()).putString("cfgErr", "").apply();
            if (!old.equals(json)) recheck();
            applyIcon();
            syncAlarmSound();
            handleCmds();
        } catch (Exception ex) {
            Cfg.p(this).edit().putString("cfgErr", String.valueOf(ex.getMessage())).apply();
        }
    }

    /** 🎙️ צליל אזעקה שאבא הקליט – יורד פעם אחת ונשמר בנגן (עובד גם בלי אינטרנט) */
    private void syncAlarmSound() {
        try {
            JSONObject as = Cfg.cfg(this).optJSONObject("alarmSnd");
            if (as == null || !"custom".equals(as.optString("k"))) return;
            long v = as.optLong("v", 0);
            if (v == 0 || v == Cfg.p(this).getLong("asndV", 0)) return;
            String js = Fb.get("d/" + Cfg.deviceId(this) + "/asnd");
            JSONObject o = new JSONObject(js);
            if (o.optLong("v", 0) != v) return;
            byte[] b = android.util.Base64.decode(o.getString("b64"), android.util.Base64.DEFAULT);
            java.io.File tmp = new java.io.File(getFilesDir(), "alarm_custom.tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(b); }
            tmp.renameTo(new java.io.File(getFilesDir(), "alarm_custom"));
            Cfg.p(this).edit().putLong("asndV", v).apply();
            Fb.patch("d/" + Cfg.deviceId(this) + "/st", new JSONObject().put("asndV", v).put("alarmMsg", "🎙️ ההקלטה נשמרה בנגן").toString());
        } catch (Exception ignored) {}
    }

    /** 🙈 אבא מסתיר/מציג את האייקון מהטלפון שלו */
    private void applyIcon() {
        try {
            JSONObject cfg = Cfg.cfg(this);
            if (!cfg.has("hideIcon")) return;
            boolean want = cfg.optBoolean("hideIcon", false);
            if (want != Cfg.p(this).getBoolean("iconHidden", false)) MainActivity.setIconHidden(this, want);
        } catch (Exception ignored) {}
        if (Cfg.setupDone(this)) Owner.lock(this);
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
                st.put("owner", Owner.is(this));
                st.put("hidden", Cfg.p(this).getBoolean("iconHidden", false));
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
                st.put("locBg", Loc.bgAllowed(this));
                st.put("locOn", Loc.enabled(this));
                st.put("cam", checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED);
                JSONObject lc = Loc.last(this);
                if (lc != null) Fb.patch("d/" + Cfg.deviceId(this) + "/loc", lc.toString());
                st.put("alert", "");
                Fb.patch("d/" + Cfg.deviceId(this) + "/st", st.toString());
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onInterrupt() {}

    /* ---------- בועת טיימר קטנה למעלה במסך ---------- */
    private android.widget.TextView pill;
    private final Runnable pillTick = new Runnable() {
        @Override public void run() {
            try { updatePill(); } catch (Exception ignored) {}
            if (!stop.get()) main.postDelayed(this, 1000);
        }
    };

    private android.view.WindowManager.LayoutParams pillLp;
    private boolean pillBig = false;

    /** בועת הטיימר: עיגול קטן שאפשר לגרור לכל מקום; לחיצה מגדילה (עם כל הפרטים) ולחיצה נוספת מקטינה. */
    private void updatePill() {
        Timer.State st = Cfg.p(this).getBoolean("pillOff", false) ? null : Timer.get(this, false);
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        if (st == null) {
            if (pill != null) { try { wm.removeView(pill); } catch (Exception ignored) {} pill = null; }
            return;
        }
        final float dn = getResources().getDisplayMetrics().density;
        if (pill == null) {
            pill = new android.widget.TextView(this);
            pill.setTextColor(0xFFFFFFFF);
            pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            pill.setGravity(android.view.Gravity.CENTER);
            pillLp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            pillLp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            android.content.SharedPreferences sp = Cfg.p(this);
            pillLp.x = sp.getInt("pillX", (int) (8 * dn));
            pillLp.y = sp.getInt("pillY", (int) (60 * dn));
            final float[] down = new float[4];
            final boolean[] moved = {false};
            pill.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down[0] = e.getRawX(); down[1] = e.getRawY(); down[2] = pillLp.x; down[3] = pillLp.y; moved[0] = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                        if (Math.abs(dx) > 10 * dn || Math.abs(dy) > 10 * dn) moved[0] = true;
                        if (moved[0]) {
                            pillLp.x = Math.max(0, (int) (down[2] + dx));
                            pillLp.y = Math.max(0, (int) (down[3] + dy));
                            try { wm.updateViewLayout(pill, pillLp); } catch (Exception ignored) {}
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (moved[0]) Cfg.p(this).edit().putInt("pillX", pillLp.x).putInt("pillY", pillLp.y).apply();
                        else { pillBig = !pillBig; updatePill(); }
                        return true;
                }
                return false;
            });
            try { wm.addView(pill, pillLp); } catch (Exception e) { pill = null; return; }
        }
        long left = st.end - System.currentTimeMillis();
        int color = st.locked ? 0xEEDC2626 : (left < 5 * 60000 ? 0xEEF59E0B : 0xEE16A34A);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setStroke((int) (2 * dn), 0x88FFFFFF);
        if (pillBig) {
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(60);
            pill.setTextSize(15);
            int pd = (int) (8 * dn);
            pill.setPadding(pd * 2, pd, pd * 2, pd);
            pill.setMinWidth(0); pill.setMaxWidth(Integer.MAX_VALUE); pill.setMinHeight(0); pill.setMaxHeight(Integer.MAX_VALUE);
            pill.setText(st.title + "  " + Timer.left(st.end) + "\n(בשעה " + Timer.clock(st.end) + ")");
        } else {
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            String t = Timer.left(st.end);
            pill.setTextSize(t.length() > 5 ? 11 : 13);
            pill.setPadding(0, 0, 0, 0);
            int d = (int) (54 * dn);
            pill.setMinWidth(d); pill.setMinHeight(d); pill.setWidth(d); pill.setHeight(d);
            pill.setText(t);
        }
        pill.setBackground(bg);
    }

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
