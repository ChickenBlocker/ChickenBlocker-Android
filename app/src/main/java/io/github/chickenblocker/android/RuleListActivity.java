package io.github.chickenblocker.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RuleListActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshPausedTimes = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 30_000);
        }
    };
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(24));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(Ui.screenRoot(this, scroll, Ui.TAB_BLOCKS));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        handler.postDelayed(refreshPausedTimes, 30_000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshPausedTimes);
        super.onPause();
    }

    private void render() {
        content.removeAllViews();

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "Blocks", 30, true, Ui.TEXT_PRIMARY);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button add = compactButton("+ New");
        add.setOnClickListener(view -> showCreateRuleDialog());
        titleRow.addView(add);
        content.addView(titleRow);

        boolean enabled = SettingsGuardService.isServiceEnabled(this);
        TextView status = Ui.text(this,
                enabled ? "Protection on" : "Protection off",
                14,
                true,
                enabled ? Ui.STATUS_GOOD : Ui.STATUS_BAD
        );
        content.addView(status);

        for (String mode : NativeBlockPolicy.packageRuleModes(this)) {
            addRule(mode);
        }
        addWebsiteRule();
    }

    private void addWebsiteRule() {
        TextView heading = Ui.text(this,
                NativeBlockPolicy.ruleTitle(this, NativeBlockPolicy.MODE_WEBSITES),
                21, true, Ui.TEXT_PRIMARY);
        heading.setPadding(0, dp(26), 0, dp(2));
        content.addView(heading);
        content.addView(Ui.text(this, "Always blocked in detected browsers", 14, false,
                Ui.TEXT_SECONDARY));

        List<String> sites = new ArrayList<>(NativeBlockPolicy.getBlockedSites(this));
        sites.sort(String::compareToIgnoreCase);
        content.addView(Ui.text(this,
                sites.isEmpty() ? "No sites" : TextUtils.join(" · ", sites),
                13, false, Ui.TEXT_SECONDARY));

        Button edit = compactButton("Edit sites");
        edit.setOnClickListener(view -> startActivity(
                new Intent(this, RuleSiteEditorActivity.class)));
        content.addView(edit);
    }

    private void addRule(String mode) {
        TextView heading = Ui.text(this, NativeBlockPolicy.ruleTitle(this, mode), 21,
                true, Ui.TEXT_PRIMARY);
        heading.setPadding(0, dp(26), 0, dp(2));
        content.addView(heading);

        long wait = NativeBlockPolicy.waitDurationMillis(this, mode) / 60_000;
        long access = NativeBlockPolicy.accessDurationMillis(this, mode) / 60_000;
        String timing = access == 0
                ? "Always blocked"
                : wait + " min wait · " + access + " min access";
        if (NativeBlockPolicy.isRulePaused(this, mode)) {
            long remaining = NativeBlockPolicy.pausedUntilMillis(this, mode)
                    - System.currentTimeMillis();
            timing = "Paused, " + formatRemaining(remaining) + " left";
        }
        content.addView(Ui.text(this, timing, 14, false, Ui.TEXT_SECONDARY));
        long maximumPause = NativeBlockPolicy.maximumPauseDurationMillis(this, mode);
        if (maximumPause > 0 && !NativeBlockPolicy.isRulePaused(this, mode)) {
            long pauseWait = NativeBlockPolicy.waitDurationMillis(this, mode) / 60_000;
            content.addView(Ui.text(this,
                    "Pausing: " + pauseWait + " min timer · up to "
                            + formatPauseChoice(maximumPause),
                    13,
                    false,
                    Ui.TEXT_SECONDARY
            ));
        }

        boolean telegramChannels = NativeBlockPolicy.MODE_TELEGRAM_CHANNELS.equals(mode);
        if (telegramChannels) {
            content.addView(Ui.text(this,
                    "Channel screens inside Telegram are closed. Chats and groups stay open.",
                    13,
                    false,
                    Ui.TEXT_SECONDARY
            ));
        } else {
            List<String> packages = new ArrayList<>(NativeBlockPolicy.getPackages(this, mode));
            packages.sort(String::compareToIgnoreCase);
            Set<String> pendingRemovals = NativeBlockPolicy.pendingRemovals(this, mode);

            if (packages.isEmpty()) {
                content.addView(Ui.text(this, "No apps", 13, false, Ui.TEXT_SECONDARY));
            } else {
                GridLayout grid = new GridLayout(this);
                grid.setColumnCount(4);
                grid.setPadding(0, dp(8), 0, 0);
                for (String packageName : packages) {
                    grid.addView(appCell(packageName, pendingRemovals.contains(packageName)));
                }
                content.addView(grid);
            }
        }

        LinearLayout actions = new LinearLayout(this);
        if (!telegramChannels) {
            Button edit = compactButton("Edit apps");
            edit.setOnClickListener(view -> startActivity(
                    new Intent(this, RuleAppPickerActivity.class)
                            .putExtra(RuleAppPickerActivity.EXTRA_MODE, mode)
            ));
            actions.addView(edit, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }

        boolean paused = NativeBlockPolicy.isRulePaused(this, mode);
        if (NativeBlockPolicy.maximumPauseDurationMillis(this, mode) > 0) {
            Button pause = compactButton(paused ? "Resume" : "Pause");
            pause.setOnClickListener(view -> {
                if (NativeBlockPolicy.isRulePaused(this, mode)) {
                    NativeBlockPolicy.resumeRule(this, mode);
                    render();
                } else {
                    showPauseDurationDialog(mode);
                }
            });
            actions.addView(pause, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        content.addView(actions);

        if (NativeBlockPolicy.isCustomRule(mode)) {
            boolean pendingDelete = NativeBlockPolicy.pendingRuleDeletions(this).contains(mode);
            Button delete = compactButton(pendingDelete
                    ? "Cancel deletion" : "Delete at 05:00");
            delete.setOnClickListener(view -> {
                if (pendingDelete) {
                    NativeBlockPolicy.cancelRuleDeletion(this, mode);
                } else {
                    NativeBlockPolicy.scheduleRuleDeletion(this, mode);
                }
                render();
            });
            content.addView(delete);
        }
    }

    private View appCell(String packageName, boolean removalPending) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(6), dp(4), dp(6));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Ui.appIcon(this, packageName, 32));
        cell.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView label = Ui.text(this, appLabel(packageName), 11, false, Ui.TEXT_PRIMARY);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.topMargin = dp(4);
        cell.addView(label, labelParams);

        if (removalPending) {
            TextView badge = Ui.text(this, "removes 05:00", 10, false, Ui.ACCENT);
            badge.setGravity(Gravity.CENTER);
            cell.addView(badge, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        }

        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
        );
        params.width = 0;
        cell.setLayoutParams(params);
        return cell;
    }

    private void showCreateRuleDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), 0, dp(20), 0);
        EditText name = new EditText(this);
        name.setHint("List name");
        fields.addView(name);
        Spinner preset = new Spinner(this);
        String[] presets = {"30 min → 30 min", "10 min → 5 min", "Always blocked"};
        preset.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, presets));
        fields.addView(preset);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New block list")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String title = name.getText().toString().trim();
                    if (title.isEmpty()) {
                        Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    long wait = preset.getSelectedItemPosition() == 1 ? 10 : 30;
                    long access = preset.getSelectedItemPosition() == 0 ? 30
                            : preset.getSelectedItemPosition() == 1 ? 5 : 0;
                    if (access == 0) {
                        wait = 0;
                    }
                    String mode = NativeBlockPolicy.createCustomRule(this, title, wait, access);
                    dialog.dismiss();
                    startActivity(new Intent(this, RuleAppPickerActivity.class)
                            .putExtra(RuleAppPickerActivity.EXTRA_MODE, mode));
                }));
        dialog.show();
    }

    private void showPauseDurationDialog(String mode) {
        long maximum = NativeBlockPolicy.maximumPauseDurationMillis(this, mode);
        int maximumSteps = Math.max(1, (int) (maximum / (5 * 60_000L)));
        LinearLayout picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setPadding(dp(24), dp(8), dp(24), 0);
        TextView selected = Ui.text(this, "5 minutes", 18, true, Ui.TEXT_PRIMARY);
        selected.setGravity(Gravity.CENTER);
        picker.addView(selected);
        SeekBar duration = new SeekBar(this);
        duration.setMax(maximumSteps - 1);
        duration.setProgress(0);
        duration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selected.setText(formatPauseChoice((progress + 1) * 5 * 60_000L));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        picker.addView(duration);
        new AlertDialog.Builder(this)
                .setTitle("Pause for how long?")
                .setView(picker)
                .setPositiveButton("Start pausing", (dialog, which) -> {
                    requestNotificationPermissionIfNeeded();
                    long selectedDuration = (duration.getProgress() + 1) * 5 * 60_000L;
                    startActivity(new Intent(this, ActiveWaitActivity.class)
                            .putExtra(ActiveWaitActivity.EXTRA_MODE, mode)
                            .putExtra(ActiveWaitActivity.EXTRA_BEGIN_WAIT, true)
                            .putExtra(ActiveWaitActivity.EXTRA_PAUSE_DURATION_MILLIS,
                                    selectedDuration));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String formatPauseChoice(long durationMillis) {
        long minutes = durationMillis / 60_000;
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = minutes / 60;
        return hours + (hours == 1 ? " hour" : " hours");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private String formatRemaining(long durationMillis) {
        long minutes = Math.max(1, (durationMillis + 59_999) / 60_000);
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        if (hours == 0) {
            return minutes + " min";
        }
        return remainingMinutes == 0
                ? hours + (hours == 1 ? " hour" : " hours")
                : hours + " h " + remainingMinutes + " min";
    }

    private String appLabel(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return getPackageManager().getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private Button compactButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
