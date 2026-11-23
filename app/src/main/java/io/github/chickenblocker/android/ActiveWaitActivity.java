package io.github.chickenblocker.android;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ActiveWaitActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_TARGET_PACKAGE = "target_package";
    static final String EXTRA_PAUSE_DURATION_MILLIS = "pause_duration_millis";
    static final String EXTRA_BEGIN_WAIT = "begin_wait";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::renderCountdown;
    private String mode;
    private String targetPackage;
    private long waitStartedAt;
    private long pauseDurationMillis;
    private boolean beginWait;
    private boolean completed;
    private boolean screenReceiverRegistered;
    private TextView countdown;
    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                cancelWait();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (!NativeBlockPolicy.MODE_SETTINGS.equals(mode)
                && !NativeBlockPolicy.MODE_WEBSITES.equals(mode)
                && !NativeBlockPolicy.isPackageRule(this, mode)) {
            mode = NativeBlockPolicy.MODE_BROWSER;
        }
        targetPackage = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        pauseDurationMillis = getIntent().getLongExtra(EXTRA_PAUSE_DURATION_MILLIS, 0);
        beginWait = getIntent().getBooleanExtra(EXTRA_BEGIN_WAIT, false);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        completed = false;
        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        screenReceiverRegistered = true;
        if (beginWait) {
            waitStartedAt = SystemClock.elapsedRealtime();
            renderCountdown();
        }
    }

    @Override
    protected void onPause() {
        cancelWait();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelWait();
        super.onDestroy();
    }

    private void buildUi() {
        int padding = dp(28);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(padding, dp(56), padding, padding);
        layout.setBackgroundColor(Ui.BACKGROUND);

        TextView eyebrow = text(
                !beginWait ? "BLOCKED" : pauseDurationMillis > 0 ? "PAUSING" : "UNLOCKING",
                13,
                true,
                Ui.ACCENT
        );
        layout.addView(eyebrow);

        String title = (pauseDurationMillis > 0 ? "Pause " : "")
                + NativeBlockPolicy.ruleTitle(this, mode);
        TextView heading = text(title, 30, true, Ui.TEXT_PRIMARY);
        heading.setPadding(0, dp(16), 0, dp(18));
        layout.addView(heading);

        long waitMinutes = waitDurationMillis() / 60_000;
        long accessMinutes = NativeBlockPolicy.accessDurationMillis(this, mode) / 60_000;
        String explanationText;
        if (!beginWait) {
            explanationText = "Blocked by ChickenBlocker.";
        } else if (pauseDurationMillis > 0) {
            explanationText = "Keep this screen open for " + waitMinutes
                    + " minutes to pause the rule for "
                    + formatLongDuration(pauseDurationMillis) + ".";
        } else {
            explanationText = "Keep this screen open for " + waitMinutes
                    + " minutes to unlock " + accessMinutes + " minutes of access.";
        }
        layout.addView(text(explanationText, 17, false, Ui.TEXT_PRIMARY));

        if (beginWait) {
            countdown = text(formatDuration(waitDurationMillis()), 48, true, Ui.TEXT_PRIMARY);
            countdown.setPadding(0, dp(42), 0, dp(30));
            layout.addView(countdown);
            layout.addView(text(
                    "Locking the screen or leaving restarts the timer.",
                    14,
                    false,
                    Ui.TEXT_SECONDARY
            ));
        }

        Button cancel = new Button(this);
        cancel.setText(beginWait ? "Cancel" : "OK");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.topMargin = dp(36);
        cancel.setLayoutParams(buttonParams);
        layout.addView(cancel);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        Ui.applySystemBarPadding(root);
        root.addView(layout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
    }

    private void renderCountdown() {
        if (completed || isFinishing() || waitStartedAt == 0) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - waitStartedAt;
        long remaining = waitDurationMillis() - elapsed;
        if (remaining <= 0) {
            completed = true;
            if (pauseDurationMillis > 0) {
                NativeBlockPolicy.pauseRule(this, mode, pauseDurationMillis);
            } else {
                NativeBlockPolicy.grantAccess(this, mode);
            }
            openGrantedTarget();
            return;
        }
        long totalSeconds = (remaining + 999) / 1000;
        countdown.setText(formatDuration(totalSeconds * 1000));
        handler.postDelayed(tick, 1000);
    }

    private void openGrantedTarget() {
        handler.removeCallbacks(tick);
        Intent destination = null;
        if (pauseDurationMillis > 0) {
            destination = new Intent(this, RuleListActivity.class);
        } else if (!NativeBlockPolicy.MODE_SETTINGS.equals(mode) && targetPackage != null) {
            destination = getPackageManager().getLaunchIntentForPackage(targetPackage);
        } else if (NativeBlockPolicy.MODE_SETTINGS.equals(mode)) {
            destination = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        }
        if (destination != null) {
            destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(destination);
        }
        finish();
    }

    private void cancelWait() {
        handler.removeCallbacks(tick);
        waitStartedAt = 0;
        if (screenReceiverRegistered) {
            unregisterReceiver(screenOffReceiver);
            screenReceiverRegistered = false;
        }
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatDuration(long durationMillis) {
        long totalSeconds = (durationMillis + 999) / 1000;
        return String.format(
                java.util.Locale.ROOT,
                "%02d:%02d",
                totalSeconds / 60,
                totalSeconds % 60
        );
    }

    private long waitDurationMillis() {
        return NativeBlockPolicy.waitDurationMillis(this, mode);
    }

    private String formatLongDuration(long durationMillis) {
        long minutes = durationMillis / 60_000;
        if (minutes < 60) {
            return minutes + " minutes";
        }
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        return remainingMinutes == 0
                ? hours + (hours == 1 ? " hour" : " hours")
                : hours + " h " + remainingMinutes + " min";
    }
}
