package io.github.chickenblocker.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

public final class PictureInPictureBlockedActivity extends Activity {
    static final String EXTRA_TARGET_PACKAGE = "target_package";

    private String targetPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        targetPackage = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        if (!PictureInPictureGuard.isProtectedPackage(this, targetPackage)) {
            finish();
            return;
        }
        buildUi();
    }

    private void buildUi() {
        int padding = Ui.dp(this, 28);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(padding, Ui.dp(this, 56), padding, padding);
        layout.setBackgroundColor(Ui.BACKGROUND);

        layout.addView(Ui.text(this, "BLOCKED", 13, true, Ui.ACCENT));

        android.widget.TextView heading = Ui.text(
                this,
                "Picture-in-picture is not allowed",
                30,
                true,
                Ui.TEXT_PRIMARY
        );
        heading.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 18));
        layout.addView(heading);

        String appLabel = PictureInPictureGuard.appLabel(this, targetPackage);
        layout.addView(Ui.text(
                this,
                appLabel + " still has picture-in-picture permission. Open its system settings "
                        + "and turn off Allow picture-in-picture.",
                17,
                false,
                Ui.TEXT_PRIMARY
        ));

        Button settings = button("Open picture-in-picture settings");
        settings.setOnClickListener(view -> {
            startActivity(PictureInPictureGuard.settingsIntent(targetPackage));
            finish();
        });
        layout.addView(settings);

        Button close = button("Close");
        close.setOnClickListener(view -> finish());
        layout.addView(close);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        Ui.applySystemBarPadding(root);
        root.addView(layout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = Ui.dp(this, 24);
        button.setLayoutParams(params);
        return button;
    }
}
