package com.lumin.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ProductUi {
    public static final int BG = Color.rgb(6, 9, 20);
    public static final int CARD = Color.rgb(16, 22, 40);
    public static final int CARD_2 = Color.rgb(22, 29, 50);
    public static final int ACCENT = Color.rgb(106, 235, 183);
    public static final int TEXT = Color.rgb(235, 239, 250);
    public static final int MUTED = Color.rgb(145, 157, 191);
    public static final int SOFT = Color.rgb(187, 198, 224);

    private ProductUi() {}

    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0, 1.12f);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    public static TextView section(Context c, String s) {
        TextView v = text(c, s, 12, MUTED, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c, 24); p.bottomMargin = dp(c, 8); v.setLayoutParams(p);
        return v;
    }

    public static LinearLayout card(Context c) {
        LinearLayout x = new LinearLayout(c);
        x.setOrientation(LinearLayout.VERTICAL);
        x.setPadding(dp(c,16), dp(c,14), dp(c,16), dp(c,14));
        x.setBackground(round(c, CARD, 18));
        return x;
    }

    public static Button primary(Context c, String label) {
        Button b = new Button(c);
        b.setText(label); b.setTextSize(15); b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        b.setTextColor(Color.rgb(5,15,17)); b.setBackground(round(c, ACCENT, 18));
        return b;
    }

    public static Button secondary(Context c, String label) {
        Button b = new Button(c);
        b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        b.setTextColor(TEXT); b.setBackground(round(c, CARD_2, 16));
        return b;
    }

    public static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint); e.setHintTextColor(Color.rgb(120,132,164)); e.setTextColor(TEXT);
        e.setTextSize(14); e.setPadding(dp(c,14), dp(c,12), dp(c,14), dp(c,12));
        e.setBackground(round(c, CARD, 14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c,7); e.setLayoutParams(p);
        return e;
    }

    public static GradientDrawable round(Context c, int color, int radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, radius)); return g;
    }

    public static LinearLayout.LayoutParams buttonParams(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(c,56)); p.topMargin = dp(c,9); return p;
    }

    public static int dp(Context c, int n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
}
