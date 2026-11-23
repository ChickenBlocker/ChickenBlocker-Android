package io.github.chickenblocker.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.TypedValue;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class Ui {
    static final String TAB_BLOCKS = "blocks";
    static final String TAB_SETTINGS = "settings";

    static final int BACKGROUND = Color.rgb(38, 38, 38);
    static final int SURFACE = Color.rgb(47, 47, 47);
    static final int BORDER = Color.rgb(70, 70, 70);
    static final int TEXT_PRIMARY = Color.rgb(236, 233, 228);
    static final int TEXT_SECONDARY = Color.rgb(168, 166, 160);
    static final int ACCENT = Color.rgb(231, 133, 99);
    static final int STATUS_GOOD = Color.rgb(150, 214, 173);
    static final int STATUS_BAD = Color.rgb(235, 143, 130);

    private Ui() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String value, int size, boolean bold, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    static Drawable appIcon(Context context, String packageName, int sizeDp) {
        Drawable icon = null;
        try {
            icon = context.getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Fall through to the default icon for uninstalled package IDs.
        }
        if (icon == null) {
            icon = context.getDrawable(R.drawable.ic_app_default);
        }
        int size = dp(context, sizeDp);
        icon.setBounds(0, 0, size, size);
        return icon;
    }

    static void applySystemBarPadding(View root) {
        if (Build.VERSION.SDK_INT >= 29) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(
                        Math.max(bars.left, cutout.left),
                        Math.max(bars.top, cutout.top),
                        Math.max(bars.right, cutout.right),
                        Math.max(Math.max(bars.bottom, cutout.bottom), ime.bottom)
                );
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
    }

    static LinearLayout screenRoot(Activity activity, ScrollView scroll, String selectedTab) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        applySystemBarPadding(root);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        root.addView(bottomBar(activity, selectedTab), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        return root;
    }

    static View bottomBar(Activity activity, String selectedTab) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(SURFACE);

        View border = new View(activity);
        border.setBackgroundColor(BORDER);
        bar.addView(border, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(activity, 1))
        ));

        LinearLayout items = new LinearLayout(activity);
        items.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(items, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        items.addView(
                tabItem(activity, TAB_BLOCKS, "Blocks", R.drawable.ic_tab_blocks,
                        TAB_BLOCKS.equals(selectedTab)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
        );
        items.addView(
                tabItem(activity, TAB_SETTINGS, "Settings", R.drawable.ic_tab_settings,
                        TAB_SETTINGS.equals(selectedTab)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
        );
        return bar;
    }

    private static View tabItem(
            Activity activity,
            String tab,
            String label,
            int iconRes,
            boolean selected
    ) {
        int color = selected ? ACCENT : TEXT_SECONDARY;

        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        int vertical = dp(activity, 10);
        item.setPadding(0, vertical, 0, vertical);

        ImageView icon = new ImageView(activity);
        Drawable drawable = activity.getDrawable(iconRes);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(color);
        }
        icon.setImageDrawable(drawable);
        item.addView(icon, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)));

        TextView text = text(activity, label, 12, selected, color);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.topMargin = dp(activity, 5);
        item.addView(text, textParams);

        if (!selected) {
            item.setClickable(true);
            TypedValue ripple = new TypedValue();
            activity.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground,
                    ripple,
                    true
            );
            item.setBackgroundResource(ripple.resourceId);
            item.setOnClickListener(view -> {
                Class<?> target = TAB_BLOCKS.equals(tab)
                        ? RuleListActivity.class
                        : MainActivity.class;
                activity.startActivity(new Intent(activity, target)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            });
        }
        return item;
    }
}
