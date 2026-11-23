package io.github.chickenblocker.android;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;

public final class ProvisioningActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent().getAction();
        if (DevicePolicyManager.ACTION_GET_PROVISIONING_MODE.equals(action)) {
            Intent result = new Intent();
            result.putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            );
            setResult(RESULT_OK, result);
            finish();
            return;
        }

        if (DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE.equals(action)) {
            PolicyController controller = new PolicyController(this);
            if (controller.isDeviceOwner()) {
                controller.applyProtection();
            }
            setResult(RESULT_OK);
            finish();
            return;
        }

        setResult(RESULT_CANCELED);
        finish();
    }
}
