package com.chaiapps.chasima;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** עיצוב משותף: מסך כהה, כפתורים מעוגלים, עברית מימין לשמאל. */
final class Ui {
    private Ui() {}

    static final int BG1 = 0xFF0F1B3D, BG2 = 0xFF1E3A8A, CARD = 0x22FFFFFF;
    static final int GREEN = 0xFF22C55E, RED = 0xFFEF4444, GOLD = 0xFFF59E0B, BLUE = 0xFF3B82F6;

    static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    static LinearLayout screen(Activity a) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{BG1, BG2});
        sv.setBackground(g);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = dp(a, 20);
        l.setPadding(p, dp(a, 28), p, p);
        sv.addView(l, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        a.setContentView(sv);
        return l;
    }

    static TextView text(Activity a, LinearLayout parent, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 10);
        parent.addView(t, lp);
        return t;
    }

    static Button button(Activity a, LinearLayout parent, String s, int color, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(a, 16));
        b.setBackground(g);
        b.setPadding(dp(a, 12), dp(a, 14), dp(a, 12), dp(a, 14));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 10);
        parent.addView(b, lp);
        return b;
    }

    static LinearLayout card(Activity a, LinearLayout parent) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(a, 18));
        c.setBackground(g);
        int p = dp(a, 14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 12);
        parent.addView(c, lp);
        return c;
    }
}
