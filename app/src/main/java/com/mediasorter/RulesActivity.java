package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.mediasorter.models.MediaFile;
import com.mediasorter.organizer.*;
import java.util.ArrayList;
import java.util.List;

public class RulesActivity extends Activity {

    private AutoOrganizer organizer;
    private FileStatus fileStatus;
    private List<Rule> rules;
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private TextView titleView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Managers
        TagManager tagManager = new TagManager(this);
        BatchRenameManager renamer = new BatchRenameManager();
        fileStatus = new FileStatus(this);
        organizer = new AutoOrganizer(this, tagManager, renamer, fileStatus);

        rules = organizer.getRules();
        if (rules == null) rules = new ArrayList<>();

        // UI
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(16, 16, 16, 16);

        // Title
        titleView = new TextView(this);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(20f);
        titleView.setPadding(0, 0, 0, 16);
        root.addView(titleView);
        updateRuleCount();

        // Rule list
        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                getRuleDescriptions()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(0xFFCCCCCC);
                tv.setTextSize(13f);
                return tv;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent,
                                              View view, int pos, long id) {
                showRuleOptions(pos);
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Button row 1
        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        btnRow1.setPadding(0, 8, 0, 4);

        Button addBtn = makeButton("+ Add Rule");
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRuleDialog(null, -1); }
        });
        btnRow1.addView(addBtn, rowParam());

        Button runBtn = makeButton("Run Now");
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runOrganizerBackground(); }
        });
        btnRow1.addView(runBtn, rowParam());

        Button previewBtn = makeButton("Preview");
        previewBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPreview(); }
        });
        btnRow1.addView(previewBtn, rowParam());

        root.addView(btnRow1);

        // Button row 2
        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setPadding(0, 4, 0, 8);

        Button undoBtn = makeButton("Undo");
        undoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doUndo(); }
        });
        btnRow2.addView(undoBtn, rowParam());

        Button logBtn = makeButton("Log");
        logBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLog(); }
        });
        btnRow2.addView(logBtn, rowParam());

        Button removeBtn = makeButton("Remove Rules");
        removeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRemoveRulesDialog(); }
        });
        btnRow2.addView(removeBtn, rowParam());

        root.addView(btnRow2);

        setContentView(root);
    }

    // ── Rule list display ───────────────────────────────────────────────

    private List<String> getRuleDescriptions() {
        List<String> descs = new ArrayList<>();
        for (Rule r : rules) {
            StringBuilder sb = new StringBuilder();
            sb.append(r.enabled ? "[ON] " : "[OFF] ");
            if (r.autoApply) sb.append("[AUTO] ");
            sb.append(r.name != null ? r.name : "Unnamed");
            sb.append("\n  ");
            // Show condition summary
            if (r.conditions != null && !r.conditions.isEmpty()) {
                for (int i = 0; i < r.conditions.size(); i++) {
                    if (i > 0) sb.append(" AND ");
                    sb.append(r.conditions.get(i).describe());
                }
            } else {
                sb.append("no conditions");
            }
            sb.append(" -> ");
            sb.append(r.action != null ? r.action.describe() : "no action");
            descs.add(sb.toString());
        }
        return descs;
    }

    private void refreshList() {
        adapter.clear();
        adapter.addAll(getRuleDescriptions());
        adapter.notifyDataSetChanged();
        updateRuleCount();
    }

    private void updateRuleCount() {
        if (titleView == null) return;
        int enabled = 0;
        if (rules != null) for (Rule rule : rules) if (rule != null && rule.enabled) enabled++;
        int total = rules == null ? 0 : rules.size();
        titleView.setText("Organizer Rules (" + total + ") — " + enabled + " enabled");
    }

    /** Select and remove several rules without opening every rule separately. */
    private void showRemoveRulesDialog() {
        if (rules == null || rules.isEmpty()) {
            Toast.makeText(this, "No rules to remove", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[rules.size()];
        boolean[] selected = new boolean[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            String name = rules.get(i).name;
            names[i] = name == null || name.trim().isEmpty() ? "Unnamed" : name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Remove rules")
                .setMultiChoiceItems(names, selected,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which, boolean checked) {
                                selected[which] = checked;
                            }
                        })
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        int removed = 0;
                        for (int i = selected.length - 1; i >= 0; i--) {
                            if (selected[i]) { rules.remove(i); removed++; }
                        }
                        if (removed > 0) {
                            organizer.setRules(rules);
                            refreshList();
                            Toast.makeText(RulesActivity.this,
                                    "Removed " + removed + " rules", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Rule options (tap) ──────────────────────────────────────────────

    private void showRuleOptions(int pos) {
        Rule rule = rules.get(pos);
        String[] options = {"Edit", "Move Up", "Move Down",
                rule.enabled ? "Disable" : "Enable",
                rule.autoApply ? "Auto-apply OFF" : "Auto-apply ON",
                "Delete"};
        new AlertDialog.Builder(this)
            .setTitle(rule.name)
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case 0: showRuleDialog(rule, pos); break;
                        case 1: moveRuleUp(pos); break;
                        case 2: moveRuleDown(pos); break;
                        case 3: toggleRule(pos); break;
                        case 4: toggleAutoApply(pos); break;
                        case 5: deleteRule(pos); break;
                    }
                }
            })
            .show();
    }

    private void moveRuleUp(int pos) {
        if (pos <= 0) return;
        Rule tmp = rules.get(pos);
        rules.set(pos, rules.get(pos - 1));
        rules.set(pos - 1, tmp);
        organizer.setRules(rules);
        refreshList();
    }

    private void moveRuleDown(int pos) {
        if (pos >= rules.size() - 1) return;
        Rule tmp = rules.get(pos);
        rules.set(pos, rules.get(pos + 1));
        rules.set(pos + 1, tmp);
        organizer.setRules(rules);
        refreshList();
    }

    private void toggleRule(int pos) {
        rules.get(pos).enabled = !rules.get(pos).enabled;
        organizer.setRules(rules);
        refreshList();
    }

    private void toggleAutoApply(int pos) {
        rules.get(pos).autoApply = !rules.get(pos).autoApply;
        organizer.setRules(rules);
        refreshList();
    }

    private void deleteRule(int pos) {
        new AlertDialog.Builder(this)
            .setTitle("Delete rule?")
            .setMessage(rules.get(pos).name)
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    rules.remove(pos);
                    organizer.setRules(rules);
                    refreshList();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Full rule editor dialog ─────────────────────────────────────────

    private void showRuleDialog(Rule existing, int position) {
        final boolean isNew = (existing == null);
        final Rule rule = isNew ? new Rule() : existing;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        // Name
        layout.addView(makeLabel("Rule name:"));
        EditText nameEdit = new EditText(this);
        nameEdit.setText(rule.name != null ? rule.name : "");
        nameEdit.setTextColor(0xFFFFFFFF);
        layout.addView(nameEdit);

        // Enabled + Auto-apply
        LinearLayout checkRow = new LinearLayout(this);
        checkRow.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox enabledCheck = new CheckBox(this);
        enabledCheck.setText("Enabled");
        enabledCheck.setTextColor(0xFFCCCCCC);
        enabledCheck.setChecked(rule.enabled);
        checkRow.addView(enabledCheck);

        CheckBox autoCheck = new CheckBox(this);
        autoCheck.setText("Auto-apply");
        autoCheck.setTextColor(0xFFCCCCCC);
        autoCheck.setChecked(rule.autoApply);
        checkRow.addView(autoCheck);
        layout.addView(checkRow);

        // ── Conditions section ───────────────────────────────────────────
        layout.addView(makeSectionHeader("Conditions"));

        // Container for condition rows
        LinearLayout condContainer = new LinearLayout(this);
        condContainer.setOrientation(LinearLayout.VERTICAL);
        condContainer.setId(View.generateViewId());
        layout.addView(condContainer);

        // Temp list to hold condition edits
        final List<ConditionEdit> condEdits = new ArrayList<>();

        // Add existing conditions
        if (rule.conditions != null) {
            for (Condition c : rule.conditions) {
                addConditionRow(condContainer, condEdits, c);
            }
        }
        // Start with one empty condition if none exist
        if (condEdits.isEmpty()) {
            addConditionRow(condContainer, condEdits, null);
        }

        Button addCondBtn = new Button(this);
        addCondBtn.setText("+ Add Condition (AND)");
        addCondBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                addConditionRow(condContainer, condEdits, null);
            }
        });
        layout.addView(addCondBtn);

        // ── Action section ───────────────────────────────────────────────
        layout.addView(makeSectionHeader("Action"));

        layout.addView(makeLabel("Action type:"));
        String[] actTypes = {"Move", "Copy", "Delete (trash)", "Delete (permanent)",
                "Add/Remove Tags", "Set/Clear Status", "Rename (pattern)",
                "Set/Change Date", "Change Extension", "Add Prefix/Suffix", "Strip Metadata"};
        Spinner actSpinner = makeSpinner(actTypes);
        layout.addView(actSpinner);

        // Action parameters container (dynamically changes based on type)
        LinearLayout actParamsContainer = new LinearLayout(this);
        actParamsContainer.setOrientation(LinearLayout.VERTICAL);
        actParamsContainer.setPadding(0, 8, 0, 8);
        layout.addView(actParamsContainer);

        // Pre-select current action type
        if (rule.action instanceof MoveAction) actSpinner.setSelection(0);
        else if (rule.action instanceof CopyAction) actSpinner.setSelection(1);
        else if (rule.action instanceof DeleteAction) {
            actSpinner.setSelection(((DeleteAction) rule.action).useTrash ? 2 : 3);
        }
        else if (rule.action instanceof TagAction) actSpinner.setSelection(4);
        else if (rule.action instanceof StatusAction) actSpinner.setSelection(5);
        else if (rule.action instanceof RenameAction) actSpinner.setSelection(6);
        else if (rule.action instanceof SetDateAction) actSpinner.setSelection(7);
        else if (rule.action instanceof ChangeExtensionAction) actSpinner.setSelection(8);
        else if (rule.action instanceof AffixAction) actSpinner.setSelection(9);
        else if (rule.action instanceof StripMetadataAction) actSpinner.setSelection(10);

        // Build initial action params
        final com.mediasorter.organizer.ActionBuilderHelper helper = new com.mediasorter.organizer.ActionBuilderHelper(this);
        final com.mediasorter.organizer.ActionBuilderHelper.ActionParamHolder actHolder = new com.mediasorter.organizer.ActionBuilderHelper.ActionParamHolder();
        helper.buildActionParams(actParamsContainer, actHolder, rule.action, actSpinner.getSelectedItemPosition());

        actSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                helper.buildActionParams(actParamsContainer, actHolder, rule.action, pos);
            }
            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle(isNew ? "Add Rule" : "Edit Rule")
            .setView(sv)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    rule.name = nameEdit.getText().toString().trim();
                    if (rule.name.isEmpty()) rule.name = "Unnamed";
                    rule.enabled = enabledCheck.isChecked();
                    rule.autoApply = autoCheck.isChecked();

                    // Build conditions from edits
                    rule.conditions.clear();
                    for (ConditionEdit ce : condEdits) {
                        Condition c = ce.buildCondition();
                        if (c != null) rule.conditions.add(c);
                    }

                    // Build action
                    rule.action = helper.buildAction(actSpinner.getSelectedItemPosition(), actHolder);

                    if (isNew) {
                        rules.add(rule);
                    }
                    organizer.setRules(rules);
                    refreshList();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Condition row builder ───────────────────────────────────────────

    private static class ConditionEdit {
        Spinner typeSpinner;
        EditText paramEdit;
        CheckBox negateCheck;
        // Extra params for specific types
        Spinner matchTypeSpinner;   // for Name
        CheckBox matchAnyCheck;     // for Tag
        Spinner greaterLessSpinner; // for Size
        Spinner olderNewerSpinner;  // for Date
        Spinner statusSpinner;      // for Status

        Condition buildCondition() {
            String param = paramEdit.getText().toString().trim();
            boolean negate = negateCheck.isChecked();
            int typeIdx = typeSpinner.getSelectedItemPosition();
            if (param.isEmpty() && typeIdx != 5 /*Status can use spinner*/) return null;

            switch (typeIdx) {
                case 0: // Tag
                    List<String> tags = new ArrayList<>();
                    for (String t : param.split(",")) {
                        String s = t.trim();
                        if (!s.isEmpty()) tags.add(s);
                    }
                    if (tags.isEmpty()) return null;
                    return Condition.tagCondition(tags, matchAnyCheck.isChecked(), negate);
                case 1: // Name
                    Condition.MatchType mt;
                    switch (matchTypeSpinner.getSelectedItemPosition()) {
                        case 1: mt = Condition.MatchType.STARTS_WITH; break;
                        case 2: mt = Condition.MatchType.ENDS_WITH; break;
                        case 3: mt = Condition.MatchType.REGEX; break;
                        default: mt = Condition.MatchType.CONTAINS;
                    }
                    return Condition.nameCondition(param, mt, negate);
                case 2: // File type
                    try {
                        MediaFile.Type ft = MediaFile.Type.valueOf(param.toUpperCase());
                        return Condition.typeCondition(ft, negate);
                    } catch (Exception e) { return null; }
                case 3: // Size
                    try {
                        long bytes = Long.parseLong(param) * 1024 * 1024;
                        boolean gt = greaterLessSpinner.getSelectedItemPosition() == 0;
                        return Condition.sizeCondition(bytes, gt, negate);
                    } catch (Exception e) { return null; }
                case 4: // Date
                    try {
                        int days = Integer.parseInt(param);
                        boolean older = olderNewerSpinner.getSelectedItemPosition() == 0;
                        return Condition.dateCondition(days, older, negate);
                    } catch (Exception e) { return null; }
                case 5: // Status
                    FileStatus.Status[] vals = {FileStatus.Status.SKIPPED, FileStatus.Status.FLAGGED, FileStatus.Status.DONE};
                    return Condition.statusCondition(vals[statusSpinner.getSelectedItemPosition()], negate);
                case 6: // Folder
                    return Condition.folderCondition(param, negate);
            }
            return null;
        }
    }

    private void addConditionRow(LinearLayout container, List<ConditionEdit> edits, Condition existing) {
        ConditionEdit ce = new ConditionEdit();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 8, 0, 8);

        // Type spinner
        String[] condTypes = {"Tag", "Name", "File type", "Size (MB)", "Date (days)", "Status", "Folder"};
        ce.typeSpinner = makeSpinner(condTypes);
        row.addView(ce.typeSpinner);

        // Param
        ce.paramEdit = new EditText(this);
        ce.paramEdit.setTextColor(0xFFFFFFFF);
        ce.paramEdit.setHint("parameter");
        LinearLayout parameterRow = new LinearLayout(this);
        parameterRow.setOrientation(LinearLayout.HORIZONTAL);
        ce.paramEdit.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parameterRow.addView(ce.paramEdit);
        Button variableButton = makeButton("Variables");
        variableButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showVariablePicker(ce.paramEdit); }
        });
        parameterRow.addView(variableButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(parameterRow);

        // Negate
        ce.negateCheck = new CheckBox(this);
        ce.negateCheck.setText("Negate (NOT)");
        ce.negateCheck.setTextColor(0xFFCCCCCC);
        row.addView(ce.negateCheck);

        // Extra spinners (hidden by default, shown based on type)
        ce.matchAnyCheck = new CheckBox(this);
        ce.matchAnyCheck.setText("Match any (vs all)");
        ce.matchAnyCheck.setTextColor(0xFFCCCCCC);
        ce.matchAnyCheck.setChecked(true);
        row.addView(ce.matchAnyCheck);

        String[] matchTypes = {"Contains", "Starts with", "Ends with", "Regex"};
        ce.matchTypeSpinner = makeSpinner(matchTypes);
        row.addView(ce.matchTypeSpinner);

        String[] glOptions = {"Greater than", "Less than"};
        ce.greaterLessSpinner = makeSpinner(glOptions);
        row.addView(ce.greaterLessSpinner);

        String[] onOptions = {"Older than", "Newer than"};
        ce.olderNewerSpinner = makeSpinner(onOptions);
        row.addView(ce.olderNewerSpinner);

        String[] statusOpts = {"SKIPPED", "FLAGGED", "DONE"};
        ce.statusSpinner = makeSpinner(statusOpts);
        row.addView(ce.statusSpinner);

        // Show/hide extras based on type
        ce.typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                ce.matchAnyCheck.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                ce.matchTypeSpinner.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                ce.greaterLessSpinner.setVisibility(pos == 3 ? View.VISIBLE : View.GONE);
                ce.olderNewerSpinner.setVisibility(pos == 4 ? View.VISIBLE : View.GONE);
                ce.statusSpinner.setVisibility(pos == 5 ? View.VISIBLE : View.GONE);
                ce.paramEdit.setVisibility(pos == 5 ? View.GONE : View.VISIBLE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });

        // Populate from existing
        if (existing instanceof TagCondition) {
            TagCondition tc = (TagCondition) existing;
            ce.typeSpinner.setSelection(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tc.tags.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(tc.tags.get(i));
            }
            ce.paramEdit.setText(sb.toString());
            ce.matchAnyCheck.setChecked(tc.matchAny);
            ce.negateCheck.setChecked(tc.negate);
        } else if (existing instanceof NameCondition) {
            NameCondition nc = (NameCondition) existing;
            ce.typeSpinner.setSelection(1);
            ce.paramEdit.setText(nc.pattern);
            ce.matchTypeSpinner.setSelection(nc.type != null ? nc.type.ordinal() : 0);
            ce.negateCheck.setChecked(nc.negate);
        } else if (existing instanceof TypeCondition) {
            ce.typeSpinner.setSelection(2);
            ce.paramEdit.setText(((TypeCondition) existing).type.name());
            ce.negateCheck.setChecked(((TypeCondition) existing).negate);
        } else if (existing instanceof SizeCondition) {
            SizeCondition sc = (SizeCondition) existing;
            ce.typeSpinner.setSelection(3);
            ce.paramEdit.setText(String.valueOf(sc.threshold / (1024L * 1024L)));
            ce.greaterLessSpinner.setSelection(sc.greaterThan ? 0 : 1);
            ce.negateCheck.setChecked(sc.negate);
        } else if (existing instanceof DateCondition) {
            DateCondition dc = (DateCondition) existing;
            ce.typeSpinner.setSelection(4);
            ce.paramEdit.setText(String.valueOf(dc.days));
            ce.olderNewerSpinner.setSelection(dc.olderThan ? 0 : 1);
            ce.negateCheck.setChecked(dc.negate);
        } else if (existing instanceof StatusCondition) {
            StatusCondition sc = (StatusCondition) existing;
            ce.typeSpinner.setSelection(5);
            switch (sc.status) {
                case SKIPPED: ce.statusSpinner.setSelection(0); break;
                case FLAGGED: ce.statusSpinner.setSelection(1); break;
                case DONE:    ce.statusSpinner.setSelection(2); break;
                default: break;
            }
            ce.negateCheck.setChecked(sc.negate);
        } else if (existing instanceof FolderCondition) {
            ce.typeSpinner.setSelection(6);
            ce.paramEdit.setText(((FolderCondition) existing).folderPath.replaceFirst("/$", ""));
            ce.negateCheck.setChecked(((FolderCondition) existing).negate);
        }

        // Trigger initial visibility
        ce.typeSpinner.setSelection(ce.typeSpinner.getSelectedItemPosition());

        // Remove button
        Button removeBtn = new Button(this);
        removeBtn.setText("Remove");
        removeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                container.removeView(row);
                edits.remove(ce);
            }
        });
        row.addView(removeBtn);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(0xFF333333);
        row.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2));

        container.addView(row);
        edits.add(ce);
    }

    // ── Action params builder ───────────────────────────────────────────

    // ── Action params builder ───────────────────────────────────────────

    public interface ActionCallback {
        void onActionSelected(Action action);
    }


    // ── Preview / Dry-Run ───────────────────────────────────────────────

    private void showPreview() {
        List<MediaFile> files = MainActivity.getLatestFullList();
        if (files == null || files.isEmpty()) {
            Toast.makeText(this, "No files to preview against", Toast.LENGTH_SHORT).show();
            return;
        }

        AutoOrganizer.PreviewResult result = organizer.preview(files);

        StringBuilder sb = new StringBuilder();
        sb.append("Matched ").append(result.matchedFiles).append(" files:\n\n");
        int shown = Math.min(result.entries.size(), 30);
        for (int i = 0; i < shown; i++) {
            AutoOrganizer.PreviewEntry e = result.entries.get(i);
            sb.append("[").append(e.ruleName).append("] ")
              .append(e.fileName).append(" -> ").append(e.actionDescription).append("\n");
        }
        if (result.entries.size() > 30) {
            sb.append("... and ").append(result.entries.size() - 30).append(" more");
        }

        new AlertDialog.Builder(this)
                .setTitle("Preview (Dry Run)")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    // ── Run Now (background thread) ─────────────────────────────────────

    private void runOrganizerBackground() {
        List<MediaFile> files = MainActivity.getLatestFullList();
        if (files == null || files.isEmpty()) {
            Toast.makeText(this, "No files to organize", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Running rules...");
        progress.setMessage("Applying " + rules.size() + " rules to " + files.size() + " files");
        progress.setCancelable(false);
        progress.show();

        new Thread(new Runnable() {
            @Override public void run() {
                final int affected = organizer.applyTo(files);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        progress.dismiss();
                        Toast.makeText(RulesActivity.this,
                                "Rules applied. Files affected: " + affected,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    // ── Undo ────────────────────────────────────────────────────────────

    private void doUndo() {
        if (!organizer.canUndo()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Undo last run?")
            .setMessage("This will reverse the last batch of actions (move, delete, tags, status).")
            .setPositiveButton("Undo", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    int restored = organizer.undoLastRun();
                    Toast.makeText(RulesActivity.this,
                            "Undone: " + restored + " operations", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Log ─────────────────────────────────────────────────────────────

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

    // ── UI helpers ──────────────────────────────────────────────────────

    private void showVariablePicker(final EditText target) {
        final String[] values = {
                "{filename} — name without extension",
                "{ext} — file extension",
                "{date} — modification date yyyyMMdd",
                "{year} — modification year",
                "{month} — modification month",
                "{day} — modification day",
                "{size} — file size in bytes",
                "{tag:N} — Nth tag (zero based)",
                "{seq} — next sequence label",
                "{random} — random syllable tag",
                "{index} — active list index"
        };
        new AlertDialog.Builder(this).setTitle("Variables").setItems(values,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String row = values[which];
                        int space = row.indexOf(" ");
                        String token = space > 0 ? row.substring(0, space) : row;
                        int cursor = Math.max(0, target.getSelectionStart());
                        target.getText().insert(cursor, token);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private TextView makeLabel(String text) {
        return makeLabel(this, text);
    }

    public static TextView makeLabel(android.content.Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(12f);
        return tv;
    }

    private TextView makeSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFE94560);
        tv.setTextSize(14f);
        tv.setPadding(0, 16, 0, 8);
        return tv;
    }

    private Spinner makeSpinner(String[] options) {
        return makeSpinner(this, options);
    }

    public static Spinner makeSpinner(android.content.Context context, String[] options) {
        Spinner sp = new Spinner(context);
        ArrayAdapter<String> ad = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, options);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private Button makeButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12f);
        return btn;
    }

    private LinearLayout.LayoutParams rowParam() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }
}
