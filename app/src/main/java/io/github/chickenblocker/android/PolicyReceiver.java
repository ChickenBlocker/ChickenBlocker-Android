package io.github.chickenblocker.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public final class PolicyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Uri data = intent.getData();
        if (data != null && (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                || Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction()))) {
            // Package add/replace events no longer affect policy.
            return;
        }

        for (String mode : NativeBlockPolicy.packageRuleModes(context)) {
            long pausedUntil = NativeBlockPolicy.pausedUntilMillis(context, mode);
            if (pausedUntil > 0) {
                PauseNotificationReceiver.schedule(context, mode, pausedUntil);
            }
        }

        PolicyController controller = new PolicyController(context);
        if (controller.isDeviceOwner()) {
            controller.applyProtection();
        }
    }
}
