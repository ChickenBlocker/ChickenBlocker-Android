package io.github.chickenblocker.android;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RuleAppPickerActivity extends Activity {
    static final String EXTRA_MODE = "mode";

    private String mode;
    private LinearLayout content;
    private LinearLayout appsList;
    private Map<String, String> launchableApps;
    private Set<String> initialChecked;
    private Set<String> pendingRemovalsAtOpen;
    private final Set<String> selection = new HashSet<>();
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (!NativeBlockPolicy.isPackageRule(this, mode)) {
            finish();
            return;
        }
        launchableApps = launchableApps();
        Set<String> stored = NativeBlockPolicy.getPackages(this, mode);
        pendingRemovalsAtOpen = NativeBlockPolicy.pendingRemovals(this, mode);
        initialChecked = new HashSet<>(stored);
        initialChecked.removeAll(pendingRemovalsAtOpen);
        selection.addAll(initialChecked);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(28), dp(22), dp(28));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(Ui.screenRoot(this, scroll, Ui.TAB_BLOCKS));
        render();
    }

    private void render() {
        content.removeAllViews();

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "Edit " + NativeBlockPolicy.ruleTitle(this, mode),
                28, true, Ui.TEXT_PRIMARY);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setOnClickListener(view -> saveChanges());
        titleRow.addView(save);
        content.addView(titleRow);

        TextView note = Ui.text(
                this,
                "Select apps, then save. Additions apply immediately after saving. "
                        + "Removing an installed app waits until 05:00; uninstalled "
                        + "package IDs are removed immediately.",
                14,
                false,
                Ui.TEXT_SECONDARY
        );
        note.setPadding(0, dp(6), 0, dp(14));
        content.addView(note);

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setText(query);
        search.setSelection(search.getText().length());
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                renderApps();
            }
        });
        content.addView(search);

        EditText packageInput = new EditText(this);
        packageInput.setHint("Package ID, for example com.example.game");
        packageInput.setSingleLine(true);
        content.addView(packageInput);

        Button add = new Button(this);
        add.setText("Add package ID");
        add.setAllCaps(false);
        add.setOnClickListener(view -> {
            String packageName = packageInput.getText().toString().trim();
            if (!isValidPackageName(packageName)) {
                Toast.makeText(this, "Enter a valid package ID", Toast.LENGTH_SHORT).show();
                return;
            }
            if (getPackageName().equals(packageName)) {
                Toast.makeText(this, "ChickenBlocker cannot block itself",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            selection.add(packageName);
            packageInput.setText("");
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(packageInput.getWindowToken(), 0);
            renderApps();
        });
        content.addView(add);

        appsList = new LinearLayout(this);
        appsList.setOrientation(LinearLayout.VERTICAL);
        content.addView(appsList);
        renderApps();
    }

    private void renderApps() {
        appsList.removeAllViews();

        TextView installedHeading = Ui.text(this, "Installed apps", 20, true, Ui.TEXT_PRIMARY);
        installedHeading.setPadding(0, dp(18), 0, dp(6));
        appsList.addView(installedHeading);

        boolean anyInstalled = false;
        for (Map.Entry<String, String> app : launchableApps.entrySet()) {
            String packageName = app.getKey();
            if (!matchesQuery(app.getValue(), packageName)) {
                continue;
            }
            anyInstalled = true;
            appsList.addView(appCheckBox(
                    packageName,
                    app.getValue(),
                    selection.contains(packageName),
                    pendingRemovalsAtOpen.contains(packageName)
            ));
        }
        if (!anyInstalled) {
            appsList.addView(Ui.text(this, "No apps", 13, false, Ui.TEXT_SECONDARY));
        }

        List<String> hidden = new ArrayList<>();
        for (String packageName : NativeBlockPolicy.getPackages(this, mode)) {
            if (!launchableApps.containsKey(packageName) && matchesQuery(packageName, packageName)) {
                hidden.add(packageName);
            }
        }
        for (String packageName : selection) {
            if (!launchableApps.containsKey(packageName) && !hidden.contains(packageName)
                    && matchesQuery(packageName, packageName)) {
                hidden.add(packageName);
            }
        }
        hidden.sort(String::compareToIgnoreCase);
        if (!hidden.isEmpty()) {
            TextView hiddenHeading = Ui.text(
                    this,
                    "Uninstalled or hidden package IDs",
                    20,
                    true,
                    Ui.TEXT_PRIMARY
            );
            hiddenHeading.setPadding(0, dp(24), 0, dp(6));
            appsList.addView(hiddenHeading);
            for (String packageName : hidden) {
                appsList.addView(appCheckBox(
                        packageName,
                        packageName,
                        selection.contains(packageName),
                        pendingRemovalsAtOpen.contains(packageName)
                ));
            }
        }
    }

    private boolean matchesQuery(String label, String packageName) {
        return query.isEmpty()
                || label.toLowerCase(Locale.ROOT).contains(query)
                || packageName.toLowerCase(Locale.ROOT).contains(query);
    }

    private CheckBox appCheckBox(String packageName, String title, boolean checked,
            boolean pendingRemoval) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(title
                + (pendingRemoval && !checked ? "\nRemoval pending for next 05:00" : ""));
        checkBox.setChecked(checked);
        Drawable icon = Ui.appIcon(this, packageName, 34);
        checkBox.setCompoundDrawables(icon, null, null, null);
        checkBox.setCompoundDrawablePadding(dp(12));
        checkBox.setOnCheckedChangeListener((button, isChecked) -> {
            if (isChecked) {
                selection.add(packageName);
            } else {
                selection.remove(packageName);
            }
        });
        return checkBox;
    }

    private void saveChanges() {
        PackageManager packageManager = getPackageManager();
        for (String packageName : selection) {
            if (getPackageName().equals(packageName)) {
                continue;
            }
            NativeBlockPolicy.addPackage(this, mode, packageName);
            for (String otherMode : NativeBlockPolicy.packageRuleModes(this)) {
                if (!otherMode.equals(mode)
                        && NativeBlockPolicy.isDirectPackageRule(this, otherMode)
                        && NativeBlockPolicy.getPackages(this, otherMode).contains(packageName)) {
                    NativeBlockPolicy.schedulePackageRemoval(this, otherMode, packageName);
                }
            }
        }
        for (String packageName : initialChecked) {
            if (selection.contains(packageName)) {
                continue;
            }
            if (isInstalled(packageManager, packageName)) {
                NativeBlockPolicy.schedulePackageRemoval(this, mode, packageName);
            } else {
                NativeBlockPolicy.removePackageNow(this, mode, packageName);
            }
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean isInstalled(PackageManager packageManager, String packageName) {
        try {
            packageManager.getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private Map<String, String> launchableApps() {
        android.content.Intent launcher =
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = getPackageManager().queryIntentActivities(launcher, 0);
        activities.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                left.loadLabel(getPackageManager()).toString(),
                right.loadLabel(getPackageManager()).toString()
        ));
        Map<String, String> apps = new LinkedHashMap<>();
        for (ResolveInfo activity : activities) {
            String packageName = activity.activityInfo.packageName;
            if (!getPackageName().equals(packageName)) {
                apps.putIfAbsent(packageName, activity.loadLabel(getPackageManager()).toString());
            }
        }
        return apps;
    }

    private boolean isValidPackageName(String packageName) {
        return packageName.length() <= 255
                && packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
