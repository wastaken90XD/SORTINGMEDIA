package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.mediasorter.models.MediaFile;
import com.mediasorter.organizer.*;
import java.util.ArrayList;
import java.util.List;
import com.mediasorter.organizer.RuleParamHelper;

public class RulesActivity extends Activity {

    private AutoOrganizer organizer;
    private List<Rule> rules;
    private ArrayAdapter<String> adapter;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Managers
        TagManager tagManager = new TagManager(this);
        BatchRenameManager renamer = new BatchRenameManager();
        FileStatus fileStatus = new FileStatus(this);
        organizer = new AutoOrganizer(this, tagManager, renamer, fileStatus);

        rules = organizer.getRules();
        if (rules == null) rules = new ArrayList<>();

        // UI
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);

        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                getRuleDescriptions()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(0xFFCCCCCC);
                return tv;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> editRule(pos));
        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            deleteRule(pos);
            return true;
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Button row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button addBtn = new Button(this);
        addBtn.setText("+ Add Rule");
        addBtn.setOnClickListener(v -> showRuleDialog(null, -1));
        btnRow.addView(addBtn);

        Button runBtn = new Button(this);
        runBtn.setText("Run Now");
        runBtn.setOnClickListener(v -> runOrganizer());
        btnRow.addView(runBtn);

        Button logBtn = new Button(this);
        logBtn.setText("Log");
        logBtn.setOnClickListener(v -> showLog());
        btnRow.addView(logBtn);

        root.addView(btnRow);
        setContentView(root);
    }

    private List<String> getRuleDescriptions() {
        List<String> descs = new ArrayList<>();
        for (Rule r : rules) {
            String status = r.enabled ? "✓ " : "✗ ";
            descs.add(status + r.name + " — " + (r.action != null ? r.action.describe() : "no action"));
        }
        return descs;
    }

    private void refreshList() {
        adapter.clear();
        adapter.addAll(getRuleDescriptions());
    }

    private void deleteRule(int pos) {
        new AlertDialog.Builder(this)
            .setTitle("Delete rule?")
            .setMessage(rules.get(pos).name)
            .setPositiveButton("Delete", (d, w) -> {
                rules.remove(pos);
                organizer.setRules(rules);
                refreshList();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showRuleDialog(Rule existing, int position) {
        final boolean isNew = (existing == null);
        final Rule rule = isNew ? new Rule() : existing;
        rule.name = isNew ? "" : rule.name;
        rule.enabled = isNew ? true : rule.enabled;

        // Build view
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        // Name
        layout.addView(makeLabel("Rule name:"));
        EditText nameEdit = new EditText(this);
        nameEdit.setText(rule.name);
        nameEdit.setTextColor(0xFFFFFFFF);
        layout.addView(nameEdit);

        // Enabled
        CheckBox enabledCheck = new CheckBox(this);
        enabledCheck.setText("Enabled");
        enabledCheck.setTextColor(0xFFCCCCCC);
        enabledCheck.setChecked(rule.enabled);
        layout.addView(enabledCheck);

        // Condition type
        layout.addView(makeLabel("Condition type:"));
        String[] condTypes = {"Has tag", "Name contains", "File type", "Size > MB", "Older than days"};
        Spinner condSpinner = makeSpinner(condTypes);
        layout.addView(condSpinner);

        // Condition parameter
        EditText condParamEdit = new EditText(this);
        condParamEdit.setText(getConditionParam(rule));
        condParamEdit.setHint("tag / text / IMAGE/VIDEO / size / days");
        condParamEdit.setTextColor(0xFFFFFFFF);
        layout.addView(condParamEdit);

        // Action type
        layout.addView(makeLabel("Action:"));
        String[] actTypes = {"Move to folder", "Delete (trash)", "Add tags", "Set status"};
        Spinner actSpinner = makeSpinner(actTypes);
        layout.addView(actSpinner);

        // Action parameter
        EditText actParamEdit = new EditText(this);
        actParamEdit.setText(getActionParam(rule));
        actParamEdit.setHint("folder path / trash folder / tags / SKIPPED,FLAGGED,DONE");
        actParamEdit.setTextColor(0xFFFFFFFF);
        layout.addView(actParamEdit);

        ScrollView sv = new ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle(isNew ? "Add Rule" : "Edit Rule")
            .setView(sv)
            .setPositiveButton("Save", (d, w) -> {
                rule.name = nameEdit.getText().toString().trim();
                if (rule.name.isEmpty()) rule.name = "Unnamed";
                rule.enabled = enabledCheck.isChecked();

                // Build condition
                int condIdx = condSpinner.getSelectedItemPosition();
                String condParam = condParamEdit.getText().toString().trim();
                Condition cond = buildCondition(condIdx, condParam);
                rule.conditions.clear();
                if (cond != null) rule.conditions.add(cond);

                // Build action
                int actIdx = actSpinner.getSelectedItemPosition();
                String actParam = actParamEdit.getText().toString().trim();
                rule.action = buildAction(actIdx, actParam);

                if (isNew) {
                    rules.add(rule);
                } else {
                    rules.set(position, rule);
                }
                organizer.setRules(rules);
                refreshList();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void editRule(int pos) {
        showRuleDialog(rules.get(pos), pos);
    }

    // ── Helper methods for parameter extraction from existing rule ────────
    private String getConditionParam(Rule r) {
    if (r.conditions.isEmpty()) return "";
    return RuleParamHelper.getConditionParam(r.conditions.get(0));
}

    private String getActionParam(Rule r) {
    if (r.action == null) return "";
    return RuleParamHelper.getActionParam(r.action);
}

    // ── Build condition from dialog index ──────────────────────────────────
    private Condition buildCondition(int index, String param) {
        if (param.isEmpty()) return null;
        switch (index) {
            case 0: // Has tag
                List<String> tags = new ArrayList<>();
                tags.add(param);
                return Condition.tagCondition(tags, true, false);
            case 1: // Name contains
                return Condition.nameCondition(param, Condition.MatchType.CONTAINS, false);
            case 2: // File type
                try {
                    MediaFile.Type type = MediaFile.Type.valueOf(param.toUpperCase());
                    return Condition.typeCondition(type, false);
                } catch (Exception e) { return null; }
            case 3: // Size > MB
                try {
                    long bytes = Long.parseLong(param) * 1024 * 1024;
                    return Condition.sizeCondition(bytes, true, false);
                } catch (Exception e) { return null; }
            case 4: // Older than days
                try {
                    int days = Integer.parseInt(param);
                    return Condition.dateCondition(days, true, false);
                } catch (Exception e) { return null; }
        }
        return null;
    }

    // ── Build action from dialog index ────────────────────────────────────
    private Action buildAction(int index, String param) {
        if (param.isEmpty()) return null;
        switch (index) {
            case 0: // Move to folder
                return Action.moveAction(param, Action.Conflict.SKIP);
            case 1: // Delete (trash)
                return Action.deleteAction(true, param);
            case 2: // Add tags
                List<String> addTags = new ArrayList<>();
                addTags.add(param);
                return Action.tagAction(addTags, null);
            case 3: // Set status
                try {
                    FileStatus.Status status = FileStatus.Status.valueOf(param.toUpperCase());
                    return Action.statusAction(status, false);
                } catch (Exception e) { return null; }
        }
        return null;
    }

    // ── Simple UI helpers ─────────────────────────────────────────────────
    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(12f);
        return tv;
    }

    private Spinner makeSpinner(String[] options) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    // ── Organizer actions ─────────────────────────────────────────────────
    private void runOrganizer() {
        List<MediaFile> files = MainActivity.getLatestFullList();
        if (files == null || files.isEmpty()) {
            Toast.makeText(this, "No files to organize", Toast.LENGTH_SHORT).show();
            return;
        }
        int affected = organizer.applyTo(files);
        Toast.makeText(this, "Rules applied. Files affected: " + affected, Toast.LENGTH_SHORT).show();
    }

    private void showLog() {
        List<String> log = organizer.getLog();
        if (log.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : log) sb.append(line).append("\n");
        new AlertDialog.Builder(this)
                .setTitle("Organizer Log")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }
}
