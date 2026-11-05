package io.github.zinzuzu.watchlimit;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.zinzuzu.watchlimit.core.Fmt;
import io.github.zinzuzu.watchlimit.core.Rules;

/**
 * Shown on top of a watched app once the day's time is used up. The app underneath is
 * only covered, not stopped, so whatever it was playing keeps playing.
 */
public final class BlockActivity extends Activity {
    static final String EXTRA_PACKAGE = "package";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String pkg;
    private TextView used;
    private TextView reset;
    private Button passButton;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        LinearLayout page = Ui.page(this);
        Ui.text(this, page, "That's today's YouTube time", 26, true);
        used = Ui.text(this, page, "", 18, false);
        Ui.text(this, page, "Whatever was playing keeps going. Turn the screen off to keep "
                + "listening: time with the screen off never counts.", 16, false);
        reset = Ui.text(this, page, "", 16, false);
        passButton = Ui.button(this, page, "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                usePass();
            }
        });
        Ui.button(this, page, "Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goHome();
            }
        });
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String p = intent.getStringExtra(EXTRA_PACKAGE);
        if (p != null) pkg = p;
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Going back would land on the app this screen covers.
        goHome();
    }

    private void refresh() {
        Engine e = Engine.get(this);
        e.refresh();
        Rules r = e.activeRules();
        used.setText("You've had " + Fmt.duration(e.usedMs()) + " of screen time in YouTube "
                + "today. " + (r.limitMin == 0 ? "Your daily limit is set to 0."
                        : "Your limit is " + Fmt.duration(r.limitMs()) + "."));
        reset.setText("A new day starts in " + Fmt.durationUp(e.untilResetMs()) + ".");
        int left = e.passesLeft();
        if (left > 0 && launchIntent() != null) {
            passButton.setVisibility(View.VISIBLE);
            passButton.setText("Pick something to listen to (" + Fmt.duration(r.passSec * 1000L)
                    + ", " + left + " left today)");
        } else {
            passButton.setVisibility(View.GONE);
        }
    }

    private Intent launchIntent() {
        return pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
    }

    private void usePass() {
        Intent launch = launchIntent();
        if (launch == null || !Engine.get(this).startPass()) {
            refresh();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        finish();
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (RuntimeException ignored) {
            // No launcher to go to; just close.
        }
        finish();
    }
}
