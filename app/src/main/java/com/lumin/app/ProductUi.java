package com.lumin.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared premium visual language for the REBORN AI Android product. */
public final class ProductUi {
    public static final int BG = Color.rgb(5, 8, 17);
    public static final int BG_TOP = Color.rgb(8, 12, 26);
    public static final int CARD = Color.rgb(14, 19, 34);
    public static final int CARD_2 = Color.rgb(20, 27, 46);
    public static final int CARD_3 = Color.rgb(27, 35, 57);
    public static final int BORDER = Color.rgb(42, 53, 79);
    public static final int BORDER_SOFT = Color.rgb(30, 39, 61);
    public static final int ACCENT = Color.rgb(113, 241, 190);
    public static final int ACCENT_DARK = Color.rgb(38, 132, 101);
    public static final int ACCENT_SOFT = Color.rgb(24, 61, 52);
    public static final int BLUE = Color.rgb(123, 155, 255);
    public static final int TEXT = Color.rgb(241, 244, 251);
    public static final int MUTED = Color.rgb(139, 153, 184);
    public static final int SOFT = Color.rgb(187, 198, 222);
    public static final int DANGER = Color.rgb(255, 117, 130);

    private ProductUi() {}

    public static void applyWindow(Activity a) {
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(BG);
            w.setNavigationBarColor(BG);
        }
        if (Build.VERSION.SDK_INT >= 23) w.getDecorView().setSystemUiVisibility(0);
    }

    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(0, 1.10f);
        v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        else v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return v;
    }

    public static TextView eyebrow(Context c, String s) {
        TextView v = text(c, s.toUpperCase(), 11, ACCENT, true);
        v.setLetterSpacing(0.12f);
        return v;
    }

    public static TextView title(Context c, String s) {
        TextView v = text(c, s, 34, TEXT, true);
        v.setLetterSpacing(-0.025f);
        return v;
    }

    public static TextView section(Context c, String s) {
        TextView v = text(c, s.toUpperCase(), 11, MUTED, true);
        v.setLetterSpacing(0.10f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c, 28);
        p.bottomMargin = dp(c, 10);
        v.setLayoutParams(p);
        return v;
    }

    public static TextView badge(Context c, String label, boolean active) {
        TextView v = text(c, label, 11, active ? ACCENT : SOFT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        v.setBackground(stroked(c, active ? ACCENT_SOFT : CARD_2, active ? ACCENT_DARK : BORDER, 999, 1));
        return v;
    }

    public static LinearLayout card(Context c) {
        LinearLayout x = new LinearLayout(c);
        x.setOrientation(LinearLayout.VERTICAL);
        x.setPadding(dp(c,18), dp(c,17), dp(c,18), dp(c,17));
        x.setBackground(stroked(c, CARD, BORDER_SOFT, 22, 1));
        x.setElevation(dp(c, 1));
        return x;
    }

    public static LinearLayout heroCard(Context c) {
        LinearLayout x = new LinearLayout(c);
        x.setOrientation(LinearLayout.VERTICAL);
        x.setPadding(dp(c,20), dp(c,20), dp(c,20), dp(c,20));
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(20, 34, 49), Color.rgb(13, 20, 37), Color.rgb(12, 17, 30)});
        g.setCornerRadius(dp(c, 26));
        g.setStroke(dp(c,1), Color.rgb(44, 72, 74));
        x.setBackground(g);
        x.setElevation(dp(c, 4));
        return x;
    }

    public static Button primary(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(Color.rgb(4, 20, 16));
        b.setBackground(stroked(c, ACCENT, ACCENT, 18, 1));
        b.setStateListAnimator(null);
        b.setElevation(dp(c, 2));
        if (Build.VERSION.SDK_INT >= 21) b.setBackgroundTintList(null);
        return b;
    }

    public static Button secondary(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c,18), 0, dp(c,18), 0);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(TEXT);
        b.setBackground(stroked(c, CARD_2, BORDER, 18, 1));
        b.setStateListAnimator(null);
        if (Build.VERSION.SDK_INT >= 21) b.setBackgroundTintList(null);
        return b;
    }

    public static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(111,124,155));
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(c,16), dp(c,14), dp(c,16), dp(c,14));
        e.setBackground(stroked(c, Color.rgb(11,16,29), BORDER, 16, 1));
        if (Build.VERSION.SDK_INT >= 21) e.setBackgroundTintList(null);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c,8);
        e.setLayoutParams(p);
        return e;
    }

    public static GradientDrawable round(Context c, int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    public static GradientDrawable stroked(Context c, int fill, int stroke, int radius, int widthDp) {
        GradientDrawable g = round(c, fill, radius);
        g.setStroke(dp(c,widthDp), stroke);
        return g;
    }

    public static LinearLayout.LayoutParams buttonParams(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(c,58));
        p.topMargin = dp(c,10);
        return p;
    }

    public static int dp(Context c, int n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }
}
