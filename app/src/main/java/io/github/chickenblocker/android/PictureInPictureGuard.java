package io.github.chickenblocker.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.HashSet;
import java.util.Set;

final class PictureInPictureGuard {
    private static final String ACTION_PICTURE_IN_PICTURE_SETTINGS =
            "android.settings.PICTURE_IN_PICTURE_SETTINGS";
    private PictureInPictureGuard() {
    }

    static boolean isProtectedPackage(Context context, String packageName) {
        if (packageName == null || NativeBlockPolicy.isOneOffBypassActive(context)) {
            return false;
        }
        return NativeBlockPolicy.getPictureInPicturePackages(context).contains(packageName)
                || isBrowser(context, packageName);
    }

    static Set<String> installedProtectedPackages(Context context) {
        Set<String> packages = browserPackages(context);
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : NativeBlockPolicy.getPictureInPicturePackages(context)) {
            try {
                packageManager.getApplicationInfo(packageName, 0);
                packages.add(packageName);
            } catch (PackageManager.NameNotFoundException ignored) {
                // An installed variant becomes protected automatically when it appears.
            }
        }
        return packages;
    }

    static String appLabel(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    static Intent settingsIntent(String packageName) {
        return new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS)
                .setData(Uri.parse("package:" + packageName));
    }

    private static boolean isBrowser(Context context, String packageName) {
        Intent webIntent = webIntent().setPackage(packageName);
        return !context.getPackageManager().queryIntentActivities(webIntent, 0).isEmpty();
    }

    private static Set<String> browserPackages(Context context) {
        Set<String> packages = new HashSet<>();
        for (ResolveInfo result : context.getPackageManager().queryIntentActivities(webIntent(), 0)) {
            if (result.activityInfo != null) {
                packages.add(result.activityInfo.packageName);
            }
        }
        return packages;
    }

    private static Intent webIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
    }
}
