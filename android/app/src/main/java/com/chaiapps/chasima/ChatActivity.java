package com.chaiapps.chasima;

import android.app.Activity;
import android.content.Intent;
import android.app.NotificationManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** צ'אט בין הילד לאבא. */
public class ChatActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private String lastShown = "";
    private boolean visible;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            load();
            h.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // ההודעות עברו ל"הסולם שלי" (עם תמונות, קבצים ושירים)
        try {
            startActivity(new android.content.Intent(this, SulamActivity.class).putExtra("view", "chat")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        } catch (Exception ignored) {}
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.BG1, Ui.BG2}));
        int p = Ui.dp(this, 12);
        root.setPadding(p, Ui.dp(this, 20), p, p);

        TextView title = new TextView(this);
        title.setText("💬 הודעות עם אבא");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        final TextView updSt = new TextView(this);
        updSt.setTextColor(0xFFBBF7D0);
        updSt.setTextSize(13);
        updSt.setGravity(Gravity.CENTER);

        Button upd = new Button(this);
        upd.setText("🔄 עדכן אפליקציה");
        upd.setAllCaps(false);
        upd.setTextColor(Color.WHITE);
        upd.setTextSize(14);
        GradientDrawable ug = new GradientDrawable();
        ug.setColor(0x33FFFFFF);
        ug.setCornerRadius(Ui.dp(this, 12));
        upd.setBackground(ug);
        upd.setOnClickListener(v -> Updater.checkAndUpdate(this, updSt::setText, false));
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        up.gravity = Gravity.CENTER;
        up.topMargin = Ui.dp(this, 4);
        root.addView(upd, up);
        root.addView(updSt);

        Button bt = new Button(this);
        bt.setText("📶 בלוטות'");
        bt.setAllCaps(false);
        bt.setTextColor(Color.WHITE);
        bt.setTextSize(14);
        GradientDrawable bg2 = new GradientDrawable();
        bg2.setColor(0x333B82F6);
        bg2.setCornerRadius(Ui.dp(this, 12));
        bt.setBackground(bg2);
        bt.setOnClickListener(v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) {}
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER;
        root.addView(bt, bp);

        scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        input = new EditText(this);
        input.setHint("כתוב הודעה...");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x88FFFFFF);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(Ui.dp(this, 14));
        input.setBackground(g);
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = new Button(this);
        send.setText("שלח");
        send.setTextColor(Color.WHITE);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(Ui.GREEN);
        sg.setCornerRadius(Ui.dp(this, 14));
        send.setBackground(sg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(Ui.dp(this, 8));
        bar.addView(send, slp);
        root.addView(bar);
        setContentView(root);

        send.setOnClickListener(v -> {
            final String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            input.setText("");
            new Thread(() -> {
                boolean ok = sendMsg(this, t);
                runOnUiThread(() -> {
                    if (!ok) input.setText(t);
                    load();
                });
            }).start();
        });
    }

    static boolean sendMsg(android.content.Context c, String txt) {
        try {
            JSONObject m = new JSONObject();
            m.put("from", "k");
            m.put("txt", txt);
            m.put("t", new JSONObject().put(".sv", "timestamp"));
            Fb.post("d/" + Cfg.deviceId(c) + "/chat", m.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void load() {
        new Thread(() -> {
            try {
                String json = Fb.last("d/" + Cfg.deviceId(this) + "/chat", 60);
                if (json == null || json.equals("null")) return;
                JSONObject all = new JSONObject(json);
                List<String> keys = new ArrayList<>();
                Iterator<String> it = all.keys();
                while (it.hasNext()) keys.add(it.next());
                Collections.sort(keys);
                String newest = keys.isEmpty() ? "" : keys.get(keys.size() - 1);
                if (newest.equals(lastShown)) return;
                runOnUiThread(() -> render(all, keys, newest));
            } catch (Exception ignored) {}
        }).start();
    }

    private void render(JSONObject all, List<String> keys, String newest) {
        lastShown = newest;
        list.removeAllViews();
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        for (String k : keys) {
            JSONObject m = all.optJSONObject(k);
            if (m == null) continue;
            boolean me = "k".equals(m.optString("from"));
            TextView t = new TextView(this);
            String time = m.optLong("t", 0) > 0 ? f.format(new Date(m.optLong("t"))) : "";
            t.setText(m.optString("txt") + "\n" + time);
            t.setTextColor(Color.WHITE);
            t.setTextSize(16);
            GradientDrawable g = new GradientDrawable();
            g.setColor(me ? 0xFF2563EB : 0x44FFFFFF);
            g.setCornerRadius(Ui.dp(this, 14));
            t.setBackground(g);
            t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 8);
            // RTL: start = ימין. ההודעות שלי בימין, של אבא בשמאל
            lp.gravity = me ? Gravity.START : Gravity.END;
            t.setMaxWidth(Ui.dp(this, 260));
            list.addView(t, lp);
        }
        if (keys.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("אין עדיין הודעות. אפשר לכתוב לאבא כאן 👇");
            e.setTextColor(0xAAFFFFFF);
            e.setGravity(Gravity.CENTER);
            list.addView(e);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(1001);
        h.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        h.removeCallbacks(poll);
    }
}
