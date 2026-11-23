package io.github.chickenblocker.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    // Keep enabled during the feasibility spike. Set false for hardened releases.
    private static final boolean ALLOW_DEVICE_OWNER_REMOVAL = true;

    private PolicyController controller;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = new PolicyController(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 24);
        content.setPadding(padding, Ui.dp(this, 32), padding, Ui.dp(this, 24));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(Ui.screenRoot(this, scroll, Ui.TAB_SETTINGS));
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content != null) {
            render();
        }
    }

    private void render() {
        content.removeAllViews();

        TextView title = Ui.text(this, "Settings", 32, true, Ui.TEXT_PRIMARY);
        title.setPadding(0, 0, 0, Ui.dp(this, 6));
        content.addView(title);

        if (controller.isDeviceOwner()) {
            addSection("System protection", controller.isProtectionApplied()
                    ? "Applied" : "Not active");
        }
        addSection(
                "Accessibility",
                SettingsGuardService.isServiceEnabled(this)
                        ? "Enabled"
                        : "Disabled · blocking is off"
        );
        addSection("Last blocked page", SettingsGuardService.lastMatch(this));

        Button accessibilitySettings = button("Accessibility settings");
        accessibilitySettings.setOnClickListener(view -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        ));
        content.addView(accessibilitySettings);

        if (controller.isDeviceOwner()) {
            Button apply = button("Apply protection");
            apply.setOnClickListener(view -> {
                try {
                    controller.applyProtection();
                    toast("Protection applied");
                    render();
                } catch (RuntimeException error) {
                    showError(error);
                }
            });
            content.addView(apply);

            if (ALLOW_DEVICE_OWNER_REMOVAL) {
                if (NativeBlockPolicy.hasSettingsAccess(this)) {
                    Button remove = button("Remove Device Owner (development)");
                    remove.setOnClickListener(view -> confirmOwnerRemoval());
                    content.addView(remove);
                } else {
                    Button maintenance = button("Unlock protected settings");
                    maintenance.setOnClickListener(view -> startUnlocking(
                            NativeBlockPolicy.MODE_SETTINGS,
                            null
                    ));
                    content.addView(maintenance);
                }
            }
        }

        TextView note = Ui.text(this,
                "ADB, safe mode, and unknown system routes can bypass this build.",
                13, false, Ui.TEXT_SECONDARY);
        note.setPadding(0, Ui.dp(this, 24), 0, 0);
        content.addView(note);
    }

    private void addSection(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 22), 0, 0);
        row.addView(Ui.text(this, label.toUpperCase(), 12, true, Ui.TEXT_SECONDARY));
        row.addView(Ui.text(this, value, 18, false, Ui.TEXT_PRIMARY));
        content.addView(row);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = Ui.dp(this, 16);
        button.setLayoutParams(params);
        return button;
    }

    private void confirmOwnerRemoval() {
        new AlertDialog.Builder(this)
                .setTitle("Remove Device Owner?")
                .setMessage("This clears ChickenBlocker's policies and makes the controller removable. "
                        + "This action exists only in debug builds.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    try {
                        controller.removeDeviceOwnerForDevelopment();
                        toast("Device Owner removed");
                        render();
                    } catch (RuntimeException error) {
                        showError(error);
                    }
                })
                .show();
    }

    private void startUnlocking(String mode, String targetPackage) {
        Intent intent = new Intent(this, ActiveWaitActivity.class)
                .putExtra(ActiveWaitActivity.EXTRA_MODE, mode)
                .putExtra(ActiveWaitActivity.EXTRA_BEGIN_WAIT, true);
        if (targetPackage != null) {
            intent.putExtra(ActiveWaitActivity.EXTRA_TARGET_PACKAGE, targetPackage);
        }
        startActivity(intent);
    }

    private void showError(Throwable error) {
        new AlertDialog.Builder(this)
                .setTitle("Operation failed")
                .setMessage(error.getClass().getSimpleName() + ": " + error.getMessage())
                .setPositiveButton("Close", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
