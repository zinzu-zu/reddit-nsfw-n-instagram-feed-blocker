package io.github.zinzuzu.feedguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The cover drawn over Firefox. It's an accessibility overlay, so it needs no
 * extra permission and sits above Firefox and its menus. Back and Home still
 * work: it doesn't take key focus, so Back goes to Firefox and leaves the page.
 */
final class Overlay {
    private final AccessibilityService service;
    private final WindowManager windowManager;
    private View view;

    Overlay(AccessibilityService service) {
        this.service = service;
        this.windowManager = service.getSystemService(WindowManager.class);
    }

    boolean isShown() {
        return view != null;
    }

    void show() {
        if (view != null || windowManager == null) return;
        View v = build();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("Feed Guard");
        try {
            windowManager.addView(v, lp);
            view = v;
        } catch (RuntimeException e) {
            view = null;
        }
    }

    void hide() {
        if (view == null) return;
        try {
            windowManager.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        view = null;
    }

    private View build() {
        Context c = service;
        boolean dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int page = dark ? 0xFF111211 : 0xFFF6F6F4;
        int card = dark ? 0xFF1F201F : 0xFFFFFFFF;
        int ink = dark ? 0xFFECECEA : 0xFF1D1D1B;
        int muted = dark ? 0xFFA3A39D : 0xFF5F5F5A;
        int accent = dark ? 0xFF7CC39C : 0xFF2F6F4F;
        int onAccent = dark ? 0xFF0E1A13 : 0xFFFFFFFF;

        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(page);
        root.setClickable(true);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);

        TextView title = text(c, "This page is blocked", 20, ink);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView body = text(c, "Instagram and Reddit only open in Firefox while the Reddit NSFW Blocker add-on is on.\n\n"
                + "To turn it back on, open a new tab, then go to Firefox's menu › Extensions.", 15, muted);
        body.setPadding(0, dp(8), 0, dp(16));
        box.addView(body);

        box.addView(button(c, "Open a new tab", accent, onAccent, true, new View.OnClickListener() {
            @Override public void onClick(View v) { openNewTab(); }
        }));
        View gap = new View(c);
        box.addView(gap, new LinearLayout.LayoutParams(1, dp(8)));
        box.addView(button(c, "Go to the home screen", accent, card, false, new View.OnClickListener() {
            @Override public void onClick(View v) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
        }));

        int side = dp(16);
        int width = Math.min(dp(400), c.getResources().getDisplayMetrics().widthPixels - 2 * side);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(box, lp);
        return root;
    }

    private void openNewTab() {
        Intent tab = new Intent(Guard.FIREFOX_NEW_TAB)
                .setClassName(Guard.FIREFOX, Guard.FIREFOX_INTENT_RECEIVER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            service.startActivity(tab);
        } catch (RuntimeException e) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    }

    private TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView button(Context c, String label, int accent, int textOrFill, boolean filled, View.OnClickListener onClick) {
        TextView b = text(c, label, 16, filled ? textOrFill : accent);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(48));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (filled) {
            bg.setColor(accent);
        } else {
            bg.setColor(textOrFill);
            bg.setStroke(dp(1), accent);
        }
        b.setBackground(bg);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.getResources().getDisplayMetrics()));
    }
}
