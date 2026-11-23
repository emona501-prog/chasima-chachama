package com.chaiapps.chasima.parent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** רץ ברקע ומקפיץ התראה על כל בקשה, הודעה או אזהרה מהנגן. */
public class AlertService extends Service {

    static final String CH_ALERT = "alerts", CH_RUN = "running";
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private String id = "";
    private int nid = 100;

    static void start(Context c) {
        Intent i = new Intent(c, AlertService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        channels();
        Notification n = builder(CH_RUN)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("🛡️ חסימה חכמה מחוברת")
                .setContentText("התראות מהנגן פעילות")
                .setOngoing(true)
                .setContentIntent(open("home", 0))
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        id = MainActivity.prefs(this).getString("id", "");
        if (id.isEmpty()) {
            stopSelf();
            return;
        }
        new Thread(() -> loop(Fb.url("d/" + id + "/req"), this::onReq)).start();
        new Thread(() -> loop(Fb.url("d/" + id + "/chat") + Fb.lastQ(1), this::onChat)).start();
        new Thread(() -> loop(Fb.url("d/" + id + "/st/alert"), this::onAlert)).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop.set(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    interface Handler {
        void handle(String path, Object data);
    }

    private void loop(String url, Handler h) {
        long wait = 3000;
        while (!stop.get()) {
            try {
                Fb.streamUrl(url, (event, data) -> {
                    if (!event.equals("put") && !event.equals("patch")) return;
                    try {
                        JSONObject ev = new JSONObject(data);
                        h.handle(ev.optString("path", "/"), ev.opt("data"));
                    } catch (Exception ignored) {}
                }, stop);
                wait = 3000;
            } catch (Exception e) {
                try { Thread.sleep(wait); } catch (InterruptedException ie) { return; }
                wait = Math.min(wait * 2, 60000);
            }
        }
    }

    private SharedPreferences p() {
        return MainActivity.prefs(this);
    }

    private String kid() {
        String n = p().getString("kid", "");
        try {
            String r = Fb.get("d/" + id + "/cfg/name");
            if (r != null && r.startsWith("\"")) {
                n = new JSONObject("{\"v\":" + r + "}").optString("v", n);
                p().edit().putString("kid", n).apply();
            }
        } catch (Exception ignored) {}
        return n.isEmpty() ? "הילד" : n;
    }

    // ---------- בקשות ----------
    private synchronized void onReq(String path, Object data) {
        Set<String> seen = new HashSet<>(p().getStringSet("reqSeen", new HashSet<>()));
        boolean first = !p().getBoolean("reqInit", false);
        if (path.equals("/")) {
            Set<String> now = new HashSet<>();
            if (data instanceof JSONObject) {
                JSONObject all = (JSONObject) data;
                Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    now.add(k);
                    if (!first && !seen.contains(k)) notifyReq(all.optJSONObject(k));
                }
            }
            p().edit().putStringSet("reqSeen", now).putBoolean("reqInit", true).apply();
            return;
        }
        String k = path.substring(1);
        if (k.contains("/")) return;
        if (data instanceof JSONObject) {
            if (!seen.contains(k)) {
                seen.add(k);
                notifyReq((JSONObject) data);
            }
        } else {
            seen.remove(k);
        }
        p().edit().putStringSet("reqSeen", seen).putBoolean("reqInit", true).apply();
    }

    private void notifyReq(JSONObject r) {
        if (r == null) return;
        String app = r.optString("n", "אפליקציה");
        String who = kid();
        String title = r.optBoolean("nw") ? "🆕 " + who + " התקין: " + app : "📩 " + who + " מבקש: " + app;
        String msg = r.optString("msg", "");
        show(title, msg.isEmpty() ? "לחץ כדי לאשר או לדחות" : "💬 " + msg, "home");
    }

    // ---------- צ'אט ----------
    private synchronized void onChat(String path, Object data) {
        if (path.equals("/") && data instanceof JSONObject) {
            JSONObject all = (JSONObject) data;
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                chatMsg(k, all.optJSONObject(k));
            }
        } else if (data instanceof JSONObject && path.lastIndexOf('/') == 0) {
            chatMsg(path.substring(1), (JSONObject) data);
        }
    }

    private void chatMsg(String k, JSONObject m) {
        if (m == null) return;
        String last = p().getString("chatSeen", "");
        if (k.compareTo(last) <= 0) return;
        p().edit().putString("chatSeen", k).apply();
        if (last.isEmpty()) return;
        if (!"k".equals(m.optString("from"))) return;
        show("💬 הודעה מ" + kid(), m.optString("txt", ""), "chat");
    }

    // ---------- אזהרות ----------
    private synchronized void onAlert(String path, Object data) {
        String a = data instanceof String ? (String) data : "";
        String last = p().getString("alertSeen", "");
        if (a.equals(last)) return;
        p().edit().putString("alertSeen", a).apply();
        if (!a.isEmpty()) show("⚠️ " + a, "בדוק את הנגן", "home");
    }

    // ---------- התראות ----------
    private void channels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel a = new NotificationChannel(CH_ALERT, "בקשות והודעות מהנגן", NotificationManager.IMPORTANCE_HIGH);
        a.enableVibration(true);
        nm.createNotificationChannel(a);
        NotificationChannel r = new NotificationChannel(CH_RUN, "פעילות ברקע", NotificationManager.IMPORTANCE_MIN);
        r.setShowBadge(false);
        nm.createNotificationChannel(r);
    }

    private Notification.Builder builder(String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
    }

    private PendingIntent open(String tab, int req) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("tab", tab);
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void show(String title, String text, String tab) {
        int n = ++nid;
        Notification.Builder b = builder(CH_ALERT)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open(tab, n));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        try {
            getSystemService(NotificationManager.class).notify(n, b.build());
        } catch (Exception ignored) {}
    }
}
