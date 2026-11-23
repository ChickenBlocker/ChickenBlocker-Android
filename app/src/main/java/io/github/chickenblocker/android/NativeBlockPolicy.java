package io.github.chickenblocker.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class NativeBlockPolicy {
    static final String MODE_BROWSER = "browser";
    static final String MODE_LIMITED_APPS = "limited_apps";
    static final String MODE_STRICT = "strict";
    static final String MODE_SETTINGS = "settings";
    static final String MODE_TELEGRAM_CHANNELS = "telegram_channels";
    static final String MODE_WEBSITES = "websites";
    private static final List<String> DEFAULT_MODES = List.of(
            MODE_BROWSER,
            MODE_LIMITED_APPS,
            MODE_STRICT,
            MODE_TELEGRAM_CHANNELS
    );

    private static final long MINUTE = 60 * 1000L;
    private static final String PREFERENCES = "native_block_policy";
    private static final String PACKAGES_PREFIX = "packages_";
    private static final String PENDING_REMOVALS_PREFIX = "pending_removals_";
    private static final String PENDING_EFFECTIVE_PREFIX = "pending_effective_";
    private static final String WALL_DEADLINE_PREFIX = "wall_deadline_";
    private static final String ELAPSED_DEADLINE_PREFIX = "elapsed_deadline_";
    private static final String BOOT_COUNT_PREFIX = "boot_count_";
    private static final String CUSTOM_RULE_IDS = "custom_rule_ids";
    private static final String CUSTOM_TITLE_PREFIX = "custom_title_";
    private static final String CUSTOM_WAIT_PREFIX = "custom_wait_";
    private static final String CUSTOM_ACCESS_PREFIX = "custom_access_";
    private static final String PENDING_RULE_DELETIONS = "pending_rule_deletions";
    private static final String PENDING_RULE_DELETIONS_EFFECTIVE =
            "pending_rule_deletions_effective";
    private static final String PAUSED_UNTIL_PREFIX = "paused_until_";
    private static final String BLOCKED_SITES = "blocked_sites";
    private static final String BROWSER_MIGRATION_DONE = "browser_rule_migration_1";
    private static DefaultBlockConfig defaultBlockConfig;

    private NativeBlockPolicy() {
    }

    static String modeForPackage(Context context, String packageName) {
        List<String> modes = new ArrayList<>(packageRuleModes(context));
        modes.sort(Comparator.comparingLong(mode -> accessDurationMillis(context, mode)));
        for (String mode : modes) {
            if (!isRulePaused(context, mode) && getPackages(context, mode).contains(packageName)) {
                return mode;
            }
        }
        return null;
    }

    static List<String> packageRuleModes(Context context) {
        migrateBrowserPackagesIfNeeded(context);
        applyPendingRuleDeletions(context);
        List<String> modes = new ArrayList<>(DEFAULT_MODES);
        Set<String> custom = preferences(context).getStringSet(CUSTOM_RULE_IDS, Set.of());
        if (custom != null) {
            List<String> sorted = new ArrayList<>(custom);
            sorted.sort(Comparator.comparing(mode -> ruleTitle(context, mode),
                    String.CASE_INSENSITIVE_ORDER));
            modes.addAll(sorted);
        }
        return modes;
    }

    static boolean isPackageRule(Context context, String mode) {
        return packageRuleModes(context).contains(mode);
    }

    static boolean isCustomRule(String mode) {
        return mode != null && mode.startsWith("custom_");
    }

    static String createCustomRule(
            Context context,
            String title,
            long waitMinutes,
            long accessMinutes
    ) {
        String mode = "custom_" + UUID.randomUUID().toString().replace("-", "");
        Set<String> custom = new HashSet<>(preferences(context).getStringSet(
                CUSTOM_RULE_IDS,
                Set.of()
        ));
        custom.add(mode);
        preferences(context).edit()
                .putStringSet(CUSTOM_RULE_IDS, custom)
                .putString(CUSTOM_TITLE_PREFIX + mode, title)
                .putLong(CUSTOM_WAIT_PREFIX + mode, waitMinutes * MINUTE)
                .putLong(CUSTOM_ACCESS_PREFIX + mode, accessMinutes * MINUTE)
                .putStringSet(PACKAGES_PREFIX + mode, Set.of())
                .apply();
        return mode;
    }

    static void scheduleRuleDeletion(Context context, String mode) {
        if (!isCustomRule(mode)) {
            return;
        }
        Set<String> pending = pendingRuleDeletions(context);
        pending.add(mode);
        long existingEffectiveAt = preferences(context).getLong(
                PENDING_RULE_DELETIONS_EFFECTIVE,
                0
        );
        long effectiveAt = existingEffectiveAt > System.currentTimeMillis()
                ? existingEffectiveAt : nextDayStartMillis();
        preferences(context).edit()
                .putStringSet(PENDING_RULE_DELETIONS, pending)
                .putLong(PENDING_RULE_DELETIONS_EFFECTIVE, effectiveAt)
                .apply();
    }

    static void cancelRuleDeletion(Context context, String mode) {
        Set<String> pending = pendingRuleDeletions(context);
        if (!pending.remove(mode)) {
            return;
        }
        SharedPreferences.Editor editor = preferences(context).edit()
                .putStringSet(PENDING_RULE_DELETIONS, pending);
        if (pending.isEmpty()) {
            editor.remove(PENDING_RULE_DELETIONS_EFFECTIVE);
        }
        editor.apply();
    }

    static Set<String> pendingRuleDeletions(Context context) {
        applyPendingRuleDeletions(context);
        Set<String> pending = preferences(context).getStringSet(PENDING_RULE_DELETIONS, Set.of());
        return pending == null ? new HashSet<>() : new HashSet<>(pending);
    }

    static void pauseRule(Context context, String mode, long durationMillis) {
        long pausedUntil = System.currentTimeMillis() + durationMillis;
        preferences(context).edit()
                .putLong(PAUSED_UNTIL_PREFIX + mode, pausedUntil)
                .apply();
        PauseNotificationReceiver.schedule(context, mode, pausedUntil);
    }

    static void resumeRule(Context context, String mode) {
        preferences(context).edit().remove(PAUSED_UNTIL_PREFIX + mode).apply();
        PauseNotificationReceiver.cancel(context, mode);
    }

    static boolean isRulePaused(Context context, String mode) {
        long pausedUntil = preferences(context).getLong(PAUSED_UNTIL_PREFIX + mode, 0);
        if (pausedUntil <= System.currentTimeMillis()) {
            if (pausedUntil != 0) {
                resumeRule(context, mode);
            }
            return false;
        }
        return true;
    }

    static long pausedUntilMillis(Context context, String mode) {
        return isRulePaused(context, mode)
                ? preferences(context).getLong(PAUSED_UNTIL_PREFIX + mode, 0)
                : 0;
    }

    static Set<String> getPackages(Context context, String mode) {
        migrateBrowserPackagesIfNeeded(context);
        applyPendingRemovals(context, mode);
        return rawPackages(context, mode);
    }

    static Set<String> getBlockedSites(Context context) {
        SharedPreferences preferences = preferences(context);
        if (!preferences.contains(BLOCKED_SITES)) {
            return new HashSet<>(defaultBlockConfig(context).blockedSites);
        }
        Set<String> stored = preferences.getStringSet(BLOCKED_SITES, Set.of());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    static void setBlockedSites(Context context, Set<String> sites) {
        preferences(context).edit()
                .putStringSet(BLOCKED_SITES, new HashSet<>(sites))
                .apply();
    }

    static String normalizeSitePattern(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.contains(" ")) {
            return null;
        }
        normalized = normalized.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int fragment = normalized.indexOf('#');
        if (fragment >= 0) {
            normalized = normalized.substring(0, fragment);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.startsWith(".") || normalized.endsWith(".")) {
            return null;
        }
        return normalized;
    }

    static String blockedSiteForUrl(Context context, String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty() || candidate.contains(" ")) {
            return null;
        }
        try {
            URI uri = new URI(candidate.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
                    ? candidate : "https://" + candidate);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String target = host + path;
            for (String stored : getBlockedSites(context)) {
                String pattern = normalizeSitePattern(stored);
                if (pattern != null && matchesSitePattern(host, target, pattern)) {
                    return stored;
                }
            }
        } catch (URISyntaxException ignored) {
            // Browser address fields can contain incomplete text while the user types.
        }
        return null;
    }

    private static boolean matchesSitePattern(String host, String target, String pattern) {
        if (!pattern.contains("*") && !pattern.contains("/")) {
            return host.equals(pattern) || host.endsWith("." + pattern);
        }
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '*') {
                regex.append(".*");
            } else if ("\\.[]{}()+-^$|?".indexOf(character) >= 0) {
                regex.append('\\').append(character);
            } else {
                regex.append(character);
            }
        }
        regex.append(pattern.contains("/") ? ".*$" : "(?:/.*)?$");
        return target.matches(regex.toString());
    }

    private static void migrateBrowserPackagesIfNeeded(Context context) {
        SharedPreferences preferences = preferences(context);
        if (preferences.getBoolean(BROWSER_MIGRATION_DONE, false)) {
            return;
        }
        Set<String> limited = new HashSet<>(rawPackages(context, MODE_LIMITED_APPS));
        Set<String> browser = new HashSet<>(rawPackages(context, MODE_BROWSER));
        for (String packageName : defaultPackages(context, MODE_BROWSER)) {
            if (limited.remove(packageName)) {
                browser.add(packageName);
            }
        }
        preferences.edit()
                .putBoolean(BROWSER_MIGRATION_DONE, true)
                .putStringSet(PACKAGES_PREFIX + MODE_LIMITED_APPS, limited)
                .putStringSet(PACKAGES_PREFIX + MODE_BROWSER, browser)
                .apply();
    }

    private static Set<String> rawPackages(Context context, String mode) {
        SharedPreferences preferences = preferences(context);
        String key = PACKAGES_PREFIX + mode;
        if (!preferences.contains(key)) {
            return new HashSet<>(defaultPackages(context, mode));
        }
        Set<String> stored = preferences.getStringSet(key, Set.of());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    static void setPackages(Context context, String mode, Set<String> packages) {
        preferences(context).edit()
                .putStringSet(PACKAGES_PREFIX + mode, new HashSet<>(packages))
                .apply();
    }

    static void addPackage(Context context, String mode, String packageName) {
        Set<String> packages = getPackages(context, mode);
        packages.add(packageName);
        setPackages(context, mode, packages);
        cancelPendingRemoval(context, mode, packageName);
    }

    static void schedulePackageRemoval(Context context, String mode, String packageName) {
        if (!getPackages(context, mode).contains(packageName)) {
            return;
        }
        Set<String> pending = pendingRemovals(context, mode);
        pending.add(packageName);
        long existingEffectiveAt = preferences(context).getLong(
                PENDING_EFFECTIVE_PREFIX + mode,
                0
        );
        long effectiveAt = existingEffectiveAt > System.currentTimeMillis()
                ? existingEffectiveAt : nextDayStartMillis();
        preferences(context).edit()
                .putStringSet(PENDING_REMOVALS_PREFIX + mode, pending)
                .putLong(PENDING_EFFECTIVE_PREFIX + mode, effectiveAt)
                .apply();
    }

    static void cancelPendingRemoval(Context context, String mode, String packageName) {
        Set<String> pending = pendingRemovals(context, mode);
        if (!pending.remove(packageName)) {
            return;
        }
        SharedPreferences.Editor editor = preferences(context).edit()
                .putStringSet(PENDING_REMOVALS_PREFIX + mode, pending);
        if (pending.isEmpty()) {
            editor.remove(PENDING_EFFECTIVE_PREFIX + mode);
        }
        editor.apply();
    }

    static void removePackageNow(Context context, String mode, String packageName) {
        Set<String> packages = rawPackages(context, mode);
        if (packages.remove(packageName)) {
            setPackages(context, mode, packages);
        }
        cancelPendingRemoval(context, mode, packageName);
    }

    static Set<String> pendingRemovals(Context context, String mode) {
        applyPendingRemovals(context, mode);
        Set<String> pending = preferences(context).getStringSet(
                PENDING_REMOVALS_PREFIX + mode,
                Set.of()
        );
        return pending == null ? new HashSet<>() : new HashSet<>(pending);
    }

    static long pendingEffectiveAt(Context context, String mode) {
        applyPendingRemovals(context, mode);
        return preferences(context).getLong(PENDING_EFFECTIVE_PREFIX + mode, 0);
    }

    static String ruleTitle(Context context, String mode) {
        if (MODE_BROWSER.equals(mode)) {
            return "Browser access";
        }
        if (MODE_LIMITED_APPS.equals(mode)) {
            return "No no apps";
        }
        if (MODE_STRICT.equals(mode)) {
            return "No return apps";
        }
        if (MODE_SETTINGS.equals(mode)) {
            return "Protected settings";
        }
        if (MODE_TELEGRAM_CHANNELS.equals(mode)) {
            return "Telegram channels";
        }
        if (MODE_WEBSITES.equals(mode)) {
            return "No no sites";
        }
        return preferences(context).getString(CUSTOM_TITLE_PREFIX + mode, "Custom rule");
    }

    static long waitDurationMillis(Context context, String mode) {
        if (MODE_LIMITED_APPS.equals(mode)) {
            return 30 * MINUTE;
        }
        if (MODE_STRICT.equals(mode)) {
            return 0;
        }
        if (MODE_BROWSER.equals(mode)) {
            return 40 * MINUTE;
        }
        if (MODE_TELEGRAM_CHANNELS.equals(mode)) {
            return 60 * MINUTE;
        }
        if (MODE_SETTINGS.equals(mode)) {
            return 30 * MINUTE;
        }
        return preferences(context).getLong(CUSTOM_WAIT_PREFIX + mode, 30 * MINUTE);
    }

    static long accessDurationMillis(Context context, String mode) {
        if (MODE_BROWSER.equals(mode)) {
            return 30 * MINUTE;
        }
        if (MODE_LIMITED_APPS.equals(mode)) {
            return 10 * MINUTE;
        }
        if (MODE_SETTINGS.equals(mode)) {
            return 10 * MINUTE;
        }
        if (MODE_TELEGRAM_CHANNELS.equals(mode)) {
            return 10 * MINUTE;
        }
        if (MODE_STRICT.equals(mode)) {
            return 0;
        }
        return preferences(context).getLong(CUSTOM_ACCESS_PREFIX + mode, 0);
    }

    static long maximumPauseDurationMillis(Context context, String mode) {
        if (MODE_BROWSER.equals(mode)) {
            return 30 * MINUTE;
        }
        if (MODE_LIMITED_APPS.equals(mode)) {
            return 5 * MINUTE;
        }
        if (MODE_SETTINGS.equals(mode)) {
            return 10 * MINUTE;
        }
        if (MODE_TELEGRAM_CHANNELS.equals(mode)) {
            return 10 * MINUTE;
        }
        return isCustomRule(mode) ? accessDurationMillis(context, mode) : 0;
    }

    static boolean hasSettingsAccess(Context context) {
        return remainingAccessMillis(context, MODE_SETTINGS) > 0;
    }

    static boolean hasAccess(Context context, String mode) {
        return accessDurationMillis(context, mode) > 0
                && remainingAccessMillis(context, mode) > 0;
    }

    static void grantAccess(Context context, String mode) {
        long duration = accessDurationMillis(context, mode);
        if (duration <= 0) {
            return;
        }
        preferences(context).edit()
                .putLong(WALL_DEADLINE_PREFIX + mode, System.currentTimeMillis() + duration)
                .putLong(ELAPSED_DEADLINE_PREFIX + mode, SystemClock.elapsedRealtime() + duration)
                .putInt(BOOT_COUNT_PREFIX + mode, bootCount(context))
                .apply();
    }

    static long remainingAccessMillis(Context context, String mode) {
        SharedPreferences preferences = preferences(context);
        if (preferences.getInt(BOOT_COUNT_PREFIX + mode, -1) != bootCount(context)) {
            return 0;
        }
        long wallRemaining = preferences.getLong(WALL_DEADLINE_PREFIX + mode, 0)
                - System.currentTimeMillis();
        long elapsedRemaining = preferences.getLong(ELAPSED_DEADLINE_PREFIX + mode, 0)
                - SystemClock.elapsedRealtime();
        return Math.max(0, Math.min(wallRemaining, elapsedRemaining));
    }

    static String accessStatus(Context context, String mode) {
        long remaining = remainingAccessMillis(context, mode);
        if (remaining <= 0) {
            long waitMinutes = waitDurationMillis(context, mode) / MINUTE;
            return "Blocked; " + waitMinutes + " active minutes required";
        }
        long minutes = (remaining + MINUTE - 1) / MINUTE;
        return "Unlocked for about " + minutes + " minutes";
    }

    private static Set<String> defaultPackages(Context context, String mode) {
        DefaultBlockConfig config = defaultBlockConfig(context);
        if (MODE_BROWSER.equals(mode)) {
            return config.browserPackages;
        }
        if (MODE_LIMITED_APPS.equals(mode)) {
            return config.limitedAppPackages;
        }
        if (MODE_STRICT.equals(mode)) {
            return config.strictAppPackages;
        }
        return Set.of();
    }

    private static synchronized DefaultBlockConfig defaultBlockConfig(Context context) {
        if (defaultBlockConfig != null) {
            return defaultBlockConfig;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(R.raw.block_config),
                StandardCharsets.UTF_8
        ))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            JSONObject root = new JSONObject(json.toString());
            defaultBlockConfig = new DefaultBlockConfig(
                    stringSet(root.getJSONArray("browserPackages")),
                    stringSet(root.getJSONArray("limitedAppPackages")),
                    stringSet(root.getJSONArray("strictAppPackages")),
                    stringSet(root.getJSONArray("blockedSites"))
            );
            return defaultBlockConfig;
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("Invalid embedded block configuration", error);
        }
    }

    private static Set<String> stringSet(JSONArray values) throws JSONException {
        Set<String> result = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            String value = values.getString(index).trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static final class DefaultBlockConfig {
        private final Set<String> browserPackages;
        private final Set<String> limitedAppPackages;
        private final Set<String> strictAppPackages;
        private final Set<String> blockedSites;

        private DefaultBlockConfig(
                Set<String> browserPackages,
                Set<String> limitedAppPackages,
                Set<String> strictAppPackages,
                Set<String> blockedSites
        ) {
            this.browserPackages = browserPackages;
            this.limitedAppPackages = limitedAppPackages;
            this.strictAppPackages = strictAppPackages;
            this.blockedSites = blockedSites;
        }
    }

    private static void applyPendingRemovals(Context context, String mode) {
        SharedPreferences preferences = preferences(context);
        long effectiveAt = preferences.getLong(PENDING_EFFECTIVE_PREFIX + mode, 0);
        if (effectiveAt == 0 || System.currentTimeMillis() < effectiveAt) {
            return;
        }
        Set<String> pending = preferences.getStringSet(
                PENDING_REMOVALS_PREFIX + mode,
                Set.of()
        );
        Set<String> packages = rawPackages(context, mode);
        if (pending != null) {
            packages.removeAll(pending);
        }
        preferences.edit()
                .putStringSet(PACKAGES_PREFIX + mode, packages)
                .remove(PENDING_REMOVALS_PREFIX + mode)
                .remove(PENDING_EFFECTIVE_PREFIX + mode)
                .apply();
    }

    private static long nextDayStartMillis() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime nextStart = now.withHour(5).withMinute(0).withSecond(0).withNano(0);
        if (!nextStart.isAfter(now)) {
            nextStart = nextStart.plusDays(1);
        }
        return nextStart.toInstant().toEpochMilli();
    }

    private static void applyPendingRuleDeletions(Context context) {
        SharedPreferences preferences = preferences(context);
        long effectiveAt = preferences.getLong(PENDING_RULE_DELETIONS_EFFECTIVE, 0);
        if (effectiveAt == 0 || System.currentTimeMillis() < effectiveAt) {
            return;
        }
        Set<String> pending = preferences.getStringSet(PENDING_RULE_DELETIONS, Set.of());
        Set<String> custom = new HashSet<>(preferences.getStringSet(CUSTOM_RULE_IDS, Set.of()));
        SharedPreferences.Editor editor = preferences.edit();
        if (pending != null) {
            for (String mode : pending) {
                custom.remove(mode);
                editor.remove(CUSTOM_TITLE_PREFIX + mode)
                        .remove(CUSTOM_WAIT_PREFIX + mode)
                        .remove(CUSTOM_ACCESS_PREFIX + mode)
                        .remove(PACKAGES_PREFIX + mode)
                        .remove(PENDING_REMOVALS_PREFIX + mode)
                        .remove(PENDING_EFFECTIVE_PREFIX + mode);
            }
        }
        editor.putStringSet(CUSTOM_RULE_IDS, custom)
                .remove(PENDING_RULE_DELETIONS)
                .remove(PENDING_RULE_DELETIONS_EFFECTIVE)
                .apply();
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException ignored) {
            return -1;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
