package io.github.chickenblocker.android;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.UserManager;

final class PolicyController {
    private final Context context;
    private final DevicePolicyManager dpm;
    private final ComponentName admin;

    PolicyController(Context context) {
        this.context = context.getApplicationContext();
        this.dpm = context.getSystemService(DevicePolicyManager.class);
        this.admin = new ComponentName(context, AdminReceiver.class);
    }

    boolean isDeviceOwner() {
        return dpm.isDeviceOwnerApp(context.getPackageName());
    }

    boolean isProtectionApplied() {
        return isDeviceOwner()
                && dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_APPS_CONTROL);
    }

    void applyProtection() {
        if (!isDeviceOwner()) {
            throw new IllegalStateException("ChickenBlocker is not the Device Owner");
        }
        dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
    }

    void removeDeviceOwnerForDevelopment() {
        if (!isDeviceOwner()) {
            return;
        }
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
        dpm.clearDeviceOwnerApp(context.getPackageName());
    }
}
