package io.github.chickenblocker.android;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public final class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        PolicyController controller = new PolicyController(context);
        if (controller.isDeviceOwner()) {
            controller.applyProtection();
        }
    }
}
