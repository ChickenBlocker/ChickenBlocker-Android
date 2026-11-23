package io.github.chickenblocker.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SettingsGuardService extends AccessibilityService {
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String PREFERENCES = "settings_guard";
    private static final String LAST_MATCH = "last_match";
    private static final long ACTION_DEBOUNCE_MILLIS = 750;
    private static final long CHANNEL_INSPECTION_DEBOUNCE_MILLIS = 250;
    private static final Set<String> TELEGRAM_PACKAGES = Set.of(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.wibble"
    );

    private long lastBackActionAt;
    private long lastBlockedAppActionAt;
    private long lastTelegramInspectionAt;
    private long lastTelegramBackAt;
    private long lastWebsiteBackAt;
    private Set<String> browserPackages;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable browserExpiryCheck = this::enforceCurrentBlockedApp;
    private final Runnable settingsExpiryCheck = this::inspectProtectedWindow;
    private final Runnable telegramExpiryCheck = this::inspectTelegramChannelWindow;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) {
            return;
        }
        String packageName = event.getPackageName().toString();
        handler.removeCallbacks(browserExpiryCheck);
        handler.removeCallbacks(telegramExpiryCheck);
        String ruleMode = NativeBlockPolicy.modeForPackage(this, packageName);
        if (ruleMode != null) {
            long remaining = NativeBlockPolicy.remainingAccessMillis(this, ruleMode);
            if (NativeBlockPolicy.accessDurationMillis(this, ruleMode) == 0 || remaining <= 0) {
                handler.postDelayed(
                        () -> blockPackageIfForeground(packageName, ruleMode),
                        100
                );
                return;
            }
            handler.postDelayed(browserExpiryCheck, remaining + 50);
        }

        inspectProtectedWindow();
        inspectTelegramChannelWindow();
        inspectBrowserWindow();
    }

    private void inspectProtectedWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return;
        }

        String rootPackage = root.getPackageName().toString();
        if (!isSettingsSurface(rootPackage)) {
            return;
        }

        PageSnapshot page = PageSnapshot.from(root);
        if ((page.matchesAccessibilityPage("chickenblocker settings guard")
                || page.matchesAccessibilityPage("chickenblocker blocker and settings guard"))
                || page.matchesAppInfoPage("chickenblocker")
                || page.matchesUninstallConfirmation("chickenblocker")) {
            if (NativeBlockPolicy.hasSettingsAccess(this)) {
                handler.removeCallbacks(settingsExpiryCheck);
                handler.postDelayed(
                        settingsExpiryCheck,
                        NativeBlockPolicy.remainingAccessMillis(
                                this,
                                NativeBlockPolicy.MODE_SETTINGS
                        ) + 50
                );
                return;
            }
            requestSettingsWait("ChickenBlocker protected settings");
        }
    }

    private void inspectTelegramChannelWindow() {
        if (NativeBlockPolicy.isRulePaused(this, NativeBlockPolicy.MODE_TELEGRAM_CHANNELS)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTelegramInspectionAt < CHANNEL_INSPECTION_DEBOUNCE_MILLIS) {
            return;
        }
        lastTelegramInspectionAt = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return;
        }
        String rootPackage = root.getPackageName().toString();
        if (!TELEGRAM_PACKAGES.contains(rootPackage)) {
            return;
        }
        if (!PageSnapshot.from(root).matchesTelegramChannelPage()) {
            return;
        }
        long remaining = NativeBlockPolicy.remainingAccessMillis(
                this,
                NativeBlockPolicy.MODE_TELEGRAM_CHANNELS
        );
        if (remaining > 0) {
            handler.postDelayed(telegramExpiryCheck, remaining + 50);
            return;
        }
        backOutOfTelegramChannel(rootPackage);
    }

    private void backOutOfTelegramChannel(String packageName) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTelegramBackAt < ACTION_DEBOUNCE_MILLIS) {
            return;
        }
        lastTelegramBackAt = now;
        preferences(this).edit().putString(LAST_MATCH, "telegram channel").apply();
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(
                () -> openActiveWait(NativeBlockPolicy.MODE_TELEGRAM_CHANNELS, packageName),
                200
        );
    }

    private void inspectBrowserWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return;
        }
        String packageName = root.getPackageName().toString();
        if (!isBrowserPackage(packageName)) {
            return;
        }
        String matchedSite = findBlockedAddress(root);
        if (matchedSite == null) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastWebsiteBackAt < ACTION_DEBOUNCE_MILLIS) {
            return;
        }
        lastWebsiteBackAt = now;
        preferences(this).edit().putString(LAST_MATCH, "website: " + matchedSite).apply();
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(
                () -> openActiveWait(NativeBlockPolicy.MODE_WEBSITES, packageName),
                200
        );
    }

    private String findBlockedAddress(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            String viewId = node.getViewIdResourceName();
            boolean addressField = !isInsideWebContent(node) && (node.isEditable() || (viewId != null
                    && (viewId.toLowerCase(Locale.ROOT).contains("url")
                    || viewId.toLowerCase(Locale.ROOT).contains("address")
                    || viewId.toLowerCase(Locale.ROOT).contains("location"))));
            if (addressField) {
                String match = NativeBlockPolicy.blockedSiteForUrl(
                        this,
                        node.getText() == null ? null : node.getText().toString()
                );
                if (match != null) {
                    return match;
                }
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        return null;
    }

    private boolean isInsideWebContent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            CharSequence className = parent.getClassName();
            if (className != null
                    && className.toString().toLowerCase(Locale.ROOT).contains("webview")) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private boolean isBrowserPackage(String packageName) {
        if (browserPackages().contains(packageName)) {
            return true;
        }
        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(packageName);
        if (!getPackageManager().queryIntentActivities(webIntent, 0).isEmpty()) {
            browserPackages.add(packageName);
            return true;
        }
        return false;
    }

    private Set<String> browserPackages() {
        if (browserPackages != null) {
            return browserPackages;
        }
        browserPackages = new HashSet<>();
        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        for (ResolveInfo result : getPackageManager().queryIntentActivities(webIntent, 0)) {
            if (result.activityInfo != null) {
                browserPackages.add(result.activityInfo.packageName);
            }
        }
        return browserPackages;
    }

    private void blockPackage(String packageName, String mode) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBlockedAppActionAt < ACTION_DEBOUNCE_MILLIS) {
            return;
        }
        lastBlockedAppActionAt = now;
        // Do not interrupt the launcher's Home transition with the blocked screen.
        openActiveWait(mode, packageName);
    }

    private void blockPackageIfForeground(String packageName, String mode) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !packageName.contentEquals(root.getPackageName())) {
            return;
        }
        String activeMode = NativeBlockPolicy.modeForPackage(this, packageName);
        if (mode.equals(activeMode) && !NativeBlockPolicy.hasAccess(this, mode)) {
            blockPackage(packageName, mode);
        }
    }

    private void enforceCurrentBlockedApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return;
        }
        String packageName = root.getPackageName().toString();
        String mode = NativeBlockPolicy.modeForPackage(this, packageName);
        if (mode != null && !NativeBlockPolicy.hasAccess(this, mode)) {
            blockPackage(packageName, mode);
        }
    }

    private boolean isSettingsSurface(String packageName) {
        return SETTINGS_PACKAGE.equals(packageName)
                || "com.android.permissioncontroller".equals(packageName)
                || "com.google.android.permissioncontroller".equals(packageName)
                || packageName.contains("packageinstaller");
    }

    private void requestSettingsWait(String matchedRule) {
        if (blockCurrentPage(matchedRule)) {
            handler.postDelayed(
                    () -> openActiveWait(NativeBlockPolicy.MODE_SETTINGS, null),
                    200
            );
        }
    }

    private void openActiveWait(String mode, String targetPackage) {
        Intent intent = new Intent(this, ActiveWaitActivity.class)
                .putExtra(ActiveWaitActivity.EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (targetPackage != null) {
            intent.putExtra(ActiveWaitActivity.EXTRA_TARGET_PACKAGE, targetPackage);
        }
        startActivity(intent);
    }

    private boolean blockCurrentPage(String matchedRule) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBackActionAt < ACTION_DEBOUNCE_MILLIS) {
            return false;
        }
        lastBackActionAt = now;
        preferences(this).edit().putString(LAST_MATCH, matchedRule).apply();
        performGlobalAction(GLOBAL_ACTION_BACK);
        return true;
    }

    @Override
    public void onInterrupt() {
        // Android calls this when feedback is interrupted; the next event resumes matching.
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(browserExpiryCheck);
        handler.removeCallbacks(settingsExpiryCheck);
        handler.removeCallbacks(telegramExpiryCheck);
        super.onDestroy();
    }

    static boolean isServiceEnabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) {
            return false;
        }
        ComponentName expected = new ComponentName(context, SettingsGuardService.class);
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        );
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            ComponentName actual = new ComponentName(
                    info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name
            );
            if (expected.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    static String lastMatch(Context context) {
        return preferences(context).getString(LAST_MATCH, "None yet");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static final class PageSnapshot {
        private static final java.util.regex.Pattern SUBSCRIBERS_PATTERN =
                java.util.regex.Pattern.compile(
                        "[0-9.,\\s]+(тыс\\.?\\s*)?[km]?\\s*(subscribers?|подписчик[а-я]*)"
                );

        private final Set<String> texts = new HashSet<>();
        private final Set<String> viewIds = new HashSet<>();

        static PageSnapshot from(AccessibilityNodeInfo root) {
            PageSnapshot snapshot = new PageSnapshot();
            ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                snapshot.addText(node.getText());
                snapshot.addText(node.getContentDescription());
                if (node.getViewIdResourceName() != null) {
                    snapshot.viewIds.add(node.getViewIdResourceName().toLowerCase(Locale.ROOT));
                }
                for (int index = 0; index < node.getChildCount(); index++) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) {
                        pending.addLast(child);
                    }
                }
            }
            return snapshot;
        }

        boolean matchesAppInfoPage(String targetLabel) {
            return hasText(targetLabel)
                    && (hasText("force stop") || hasText("uninstall") || hasText("disable"))
                    && (hasText("permissions") || hasText("storage & cache") || hasText("open"));
        }

        boolean matchesUninstallConfirmation(String targetLabel) {
            return hasText(targetLabel)
                    && hasTextContaining("uninstall")
                    && (hasTextContaining("do you want") || hasText("cancel"));
        }

        boolean matchesAccessibilityPage(String targetLabel) {
            return hasText(targetLabel)
                    && hasText("use " + targetLabel)
                    && hasSwitch();
        }

        boolean matchesTelegramChannelPage() {
            return hasSubscribersCount() || (hasText("gift") && hasText("search"));
        }

        private boolean hasSubscribersCount() {
            for (String text : texts) {
                if (SUBSCRIBERS_PATTERN.matcher(text).matches()) {
                    return true;
                }
            }
            return false;
        }

        private void addText(CharSequence value) {
            if (value != null) {
                String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    texts.add(normalized);
                }
            }
        }

        private boolean hasText(String expected) {
            return texts.contains(expected);
        }

        private boolean hasTextContaining(String expected) {
            for (String text : texts) {
                if (text.contains(expected)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasSwitch() {
            for (String viewId : viewIds) {
                if (viewId.endsWith(":id/switchwidget")
                        || viewId.endsWith(":id/switch_widget")
                        || viewId.endsWith(":id/switch_bar")) {
                    return true;
                }
            }
            return false;
        }
    }
}
