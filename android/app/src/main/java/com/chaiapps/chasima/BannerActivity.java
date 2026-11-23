package com.chaiapps.chasima;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** הודעת עידוד מאבא – גדולה, על כל המסך, עם לב שחוזר אליו. */
public class BannerActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        String txt = getIntent() != null ? getIntent().getStringExtra("txt") : "";
        if (txt == null) txt = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF7C3AED, 0xFFDB2777, 0xFFF59E0B}));
        int p = Ui.dp(this, 28);
        root.setPadding(p, p, p, p);

        TextView heart = new TextView(this);
        heart.setText("💙");
        heart.setTextSize(64);
        heart.setGravity(Gravity.CENTER);
        root.addView(heart);

        TextView msg = new TextView(this);
        msg.setText(txt);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(30);
        msg.setTypeface(Typeface.DEFAULT_BOLD);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(this, 20);
        mp.bottomMargin = Ui.dp(this, 36);
        root.addView(msg, mp);

        final TextView sent = new TextView(this);
        sent.setText("");
        sent.setTextColor(0xFFFFE4E6);
        sent.setTextSize(18);
        sent.setGravity(Gravity.CENTER);

        final Button like = new Button(this);
        like.setText("❤️  שלח לב לאבא");
        like.setAllCaps(false);
        like.setTextColor(0xFFDB2777);
        like.setTextSize(22);
        like.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable lg = new GradientDrawable();
        lg.setColor(Color.WHITE);
        lg.setCornerRadius(Ui.dp(this, 22));
        like.setBackground(lg);
        like.setPadding(p, Ui.dp(this, 16), p, Ui.dp(this, 16));
        like.setOnClickListener(v -> {
            like.setEnabled(false);
            sent.setText("❤️ נשלח לאבא!");
            new Thread(() -> ChatActivity.sendMsg(this, "❤️")).start();
            new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1200);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(like, lp);
        root.addView(sent, lp);

        Button close = new Button(this);
        close.setText("סגור");
        close.setAllCaps(false);
        close.setTextColor(Color.WHITE);
        close.setBackgroundColor(0x33000000);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(this, 30);
        cp.gravity = Gravity.CENTER;
        root.addView(close, cp);

        setContentView(root);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        finish();
    }
}
