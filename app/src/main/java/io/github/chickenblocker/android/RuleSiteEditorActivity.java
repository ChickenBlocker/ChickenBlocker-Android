package io.github.chickenblocker.android;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RuleSiteEditorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(28), dp(22), dp(28));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "Edit No no sites", 28, true, Ui.TEXT_PRIMARY);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        titleRow.addView(save);
        content.addView(titleRow);

        TextView note = Ui.text(this,
                "Enter host or wildcard patterns separated by spaces or new lines. "
                        + "Plain hosts also match their "
                        + "subdomains. Examples: example.com or *.example.net",
                14, false, Ui.TEXT_SECONDARY);
        note.setPadding(0, dp(6), 0, dp(14));
        content.addView(note);

        EditText sites = new EditText(this);
        sites.setGravity(Gravity.TOP | Gravity.START);
        sites.setHint("example.com\n*.example.net");
        sites.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        sites.setMinLines(12);
        List<String> current = new ArrayList<>(NativeBlockPolicy.getBlockedSites(this));
        current.sort(String::compareToIgnoreCase);
        sites.setText(String.join("\n", current));
        content.addView(sites, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        save.setOnClickListener(view -> saveSites(sites.getText().toString()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(Ui.screenRoot(this, scroll, Ui.TAB_BLOCKS));
    }

    private void saveSites(String text) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String token : text.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            String site = NativeBlockPolicy.normalizeSitePattern(token);
            if (site == null) {
                Toast.makeText(this, "Invalid site: " + token, Toast.LENGTH_SHORT).show();
                return;
            }
            normalized.add(site);
        }
        NativeBlockPolicy.setBlockedSites(this, normalized);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
