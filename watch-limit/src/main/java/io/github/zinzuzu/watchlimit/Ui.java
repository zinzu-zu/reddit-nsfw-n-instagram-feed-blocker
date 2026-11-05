package io.github.zinzuzu.watchlimit;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small helpers for building the screens in code, without layout files or libraries. */
final class Ui {
    static final int GOOD = 0xFF43A047;
    static final int BAD = 0xFFE53935;
    static final int NEUTRAL = 0xFF9E9E9E;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** A scrolling page that stays clear of the status and navigation bars. */
    static LinearLayout page(Activity a) {
        FrameLayout outer = new FrameLayout(a);
        outer.setFitsSystemWindows(true);
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        LinearLayout col = column(a);
        int pad = dp(a, 20);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        a.setContentView(outer);
        return col;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** A rounded box that reads well in light and dark mode. */
    static LinearLayout card(Context c, LinearLayout parent) {
        LinearLayout l = column(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1F808080);
        bg.setCornerRadius(dp(c, 16));
        l.setBackground(bg);
        int pad = dp(c, 16);
        l.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 16);
        parent.addView(l, lp);
        return l;
    }

    static TextView text(Context c, LinearLayout parent, CharSequence s, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 4);
        parent.addView(t, lp);
        return t;
    }

    static Button button(Context c, LinearLayout parent, CharSequence label,
            View.OnClickListener onClick) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        parent.addView(b, lp);
        return b;
    }

    /** A label on the left and a number field on the right. */
    static EditText numberField(Context c, LinearLayout parent, String label, int value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setGravity(Gravity.END);
        e.setSelectAllOnFocus(true);
        e.setMinWidth(dp(c, 88));
        row.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 4);
        parent.addView(row, lp);
        return e;
    }

    static EditText textField(Context c, LinearLayout parent, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 4);
        parent.addView(e, lp);
        return e;
    }

    /** Reads a whole number, or returns {@code fallback} if the field is empty or invalid. */
    static int readInt(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
