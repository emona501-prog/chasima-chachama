package com.chaiapps.chasima;

import android.accessibilityservice.AccessibilityService;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
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
    private long lastBlockAt = 0;
    private String lastBlockPkg = "";
    private long lastBeat = 0;

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
            check(current);
            if (System.currentTimeMillis() - lastBeat > 55000) beat();
            main.postDelayed(this, 15000);
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
        io.execute(() -> Apps.report(this));
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
        current = pkg;
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

    private void check(String pkg) {
        String reason = Rules.check(this, pkg, launchable, exempt);
        if (reason == null) return;
        long now = System.currentTimeMillis();
        if (pkg.equals(lastBlockPkg) && now - lastBlockAt < 1200) return;
        lastBlockPkg = pkg;
        lastBlockAt = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
        Intent i = new Intent(this, BlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("pkg", pkg)
                .putExtra("name", Apps.label(this, pkg))
                .putExtra("reason", reason);
        main.postDelayed(() -> startActivity(i), 150);
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

    private void pullCfg(String path) {
        try {
            String json = Fb.get(path);
            Cfg.saveCfg(this, json);
            recheck();
        } catch (Exception ignored) {}
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
