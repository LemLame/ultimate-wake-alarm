package jp.waketerminal;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(16, 24, 32), INK = Color.rgb(243, 245, 239);
    static final int MUTED = Color.rgb(166, 184, 190), GOLD = Color.rgb(255, 209, 102), GREEN = Color.rgb(125, 216, 163);
    static int dp(Activity a, float n) { return (int)(a.getResources().getDisplayMetrics().density * n + .5f); }
    static TextView text(Activity a, String s, int size, int color) {
        TextView t = new TextView(a); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0, dp(a, 6), 0, dp(a, 6)); return t;
    }
    static void bold(TextView t) { t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); }
    static Button button(Activity a, String label, boolean primary) {
        Button b = new Button(a); b.setText(label); b.setAllCaps(false); b.setTextSize(16);
        b.setTextColor(primary ? BG : INK); b.setMinHeight(dp(a, 54));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? GOLD : Color.rgb(36, 49, 60));
        bg.setCornerRadius(dp(a, 16)); b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(a, 10);
        b.setLayoutParams(lp); return b;
    }
    static void insets(Activity a, View root, int padding) {
        int p = dp(a, padding);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(p + insets.getSystemWindowInsetLeft(), p + insets.getSystemWindowInsetTop(),
                p + insets.getSystemWindowInsetRight(), p + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }
}
