package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
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
        TextView title = new TextView(this);
        title.setText("Organizer Rules");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20f);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

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
        listView.setOnItemClickListener((parent, view, pos, id) -> showRuleOptions(pos));
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Button row 1
        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        btnRow1.setPadding(0, 8, 0, 4);

        Button addBtn = makeButton("+ Add Rule");
        addBtn.setOnClickListener(v -> showRuleDialog(null, -1));
        btnRow1.addView(addBtn, rowParam());

        Button runBtn = makeButton("Run Now");
        runBtn.setOnClickListener(v -> runOrganizerBackground());
        btnRow1.addView(runBtn, rowParam());

        Button previewBtn = makeButton("Preview");
        previewBtn.setOnClickListener(v -> showPreview());
        btnRow1.addView(previewBtn, rowParam());

        root.addView(btnRow1);

        // Button row 2
        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setPadding(0, 4, 0, 8);

        Button undoBtn = makeButton("Undo");
        undoBtn.setOnClickListener(v -> doUndo());
        btnRow2.addView(undoBtn, rowParam());

        Button logBtn = makeButton("Log");
        logBtn.setOnClickListener(v -> showLog());
        btnRow2.addView(logBtn, rowParam());

        // Spacer
        View spacer = new View(this);
        btnRow2.addView(spacer, rowParam());

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
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: showRuleDialog(rule, pos); break;
                    case 1: moveRuleUp(pos); break;
                    case 2: moveRuleDown(pos); break;
                    case 3: toggleRule(pos); break;
                    case 4: toggleAutoApply(pos); break;
                    case 5: deleteRule(pos); break;
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
            .setPositiveButton("Delete", (d, w) -> {
                rules.remove(pos);
                organizer.setRules(rules);
                refreshList();
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
        addCondBtn.setOnClickListener(v -> addConditionRow(condContainer, condEdits, null));
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
        final ActionParamHolder actHolder = new ActionParamHolder();
        buildActionParams(actParamsContainer, actHolder, rule.action, actSpinner.getSelectedItemPosition());

        actSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                buildActionParams(actParamsContainer, actHolder, rule.action, pos);
            }
            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle(isNew ? "Add Rule" : "Edit Rule")
            .setView(sv)
            .setPositiveButton("Save", (d, w) -> {
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
                rule.action = buildAction(actSpinner.getSelectedItemPosition(), actHolder);

                if (isNew) {
                    rules.add(rule);
                }
                organizer.setRules(rules);
                refreshList();
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
        row.addView(ce.paramEdit);

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
        removeBtn.setOnClickListener(v -> {
            container.removeView(row);
            edits.remove(ce);
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

    private static class ActionParamHolder {
        EditText destEdit;
        Spinner conflictSpinner;
        EditText trashEdit;
        CheckBox useTrashCheck;
        EditText tagsToAddEdit;
        EditText tagsToRemoveEdit;
        Spinner statusSpinner;
        CheckBox clearStatusCheck;
        EditText patternEdit;
        // New action fields
        Spinner dateModeSpinner;      // SetDateAction
        EditText dateValueEdit;       // SetDateAction
        EditText extensionEdit;       // ChangeExtensionAction
        Spinner affixPositionSpinner; // AffixAction
        EditText affixTextEdit;       // AffixAction
        CheckBox keepOrientationCheck;// StripMetadataAction
    }

    private void buildActionParams(LinearLayout container, ActionParamHolder holder,
                                   Action existingAction, int actionType) {
        container.removeAllViews();

        switch (actionType) {
            case 0: // Move
            case 1: // Copy
                container.addView(makeLabel("Destination folder:"));
                holder.destEdit = new EditText(this);
                holder.destEdit.setTextColor(0xFFFFFFFF);
                holder.destEdit.setHint("/sdcard/destination");
                if (actionType == 0 && existingAction instanceof MoveAction) {
                    holder.destEdit.setText(((MoveAction) existingAction).destFolder);
                } else if (actionType == 1 && existingAction instanceof CopyAction) {
                    holder.destEdit.setText(((CopyAction) existingAction).destFolder);
                }
                container.addView(holder.destEdit);

                container.addView(makeLabel("Conflict resolution:"));
                String[] conflicts = {"Skip if exists", "Overwrite", "Auto-rename"};
                holder.conflictSpinner = makeSpinner(conflicts);
                // Pre-select
                Action.Conflict c = Action.Conflict.SKIP;
                if (actionType == 0 && existingAction instanceof MoveAction) c = ((MoveAction) existingAction).conflict;
                else if (actionType == 1 && existingAction instanceof CopyAction) c = ((CopyAction) existingAction).conflict;
                if (c != null) holder.conflictSpinner.setSelection(c.ordinal());
                container.addView(holder.conflictSpinner);
                break;

            case 2: // Delete (trash)
                container.addView(makeLabel("Trash folder:"));
                holder.trashEdit = new EditText(this);
                holder.trashEdit.setTextColor(0xFFFFFFFF);
                holder.trashEdit.setHint("/sdcard/.trash");
                if (existingAction instanceof DeleteAction) {
                    holder.trashEdit.setText(((DeleteAction) existingAction).trashFolder);
                }
                container.addView(holder.trashEdit);
                break;

            case 3: // Delete (permanent)
                // No extra params needed
                TextView warn = new TextView(this);
                warn.setText("WARNING: Files will be permanently deleted!");
                warn.setTextColor(0xFFFF4444);
                container.addView(warn);
                break;

            case 4: // Tags
                container.addView(makeLabel("Tags to add (comma-separated):"));
                holder.tagsToAddEdit = new EditText(this);
                holder.tagsToAddEdit.setTextColor(0xFFFFFFFF);
                holder.tagsToAddEdit.setHint("vacation, family");
                if (existingAction instanceof TagAction) {
                    TagAction ta = (TagAction) existingAction;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ta.tagsToAdd.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(ta.tagsToAdd.get(i));
                    }
                    holder.tagsToAddEdit.setText(sb.toString());
                }
                container.addView(holder.tagsToAddEdit);

                container.addView(makeLabel("Tags to remove (comma-separated):"));
                holder.tagsToRemoveEdit = new EditText(this);
                holder.tagsToRemoveEdit.setTextColor(0xFFFFFFFF);
                holder.tagsToRemoveEdit.setHint("old_tag");
                if (existingAction instanceof TagAction) {
                    TagAction ta = (TagAction) existingAction;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ta.tagsToRemove.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(ta.tagsToRemove.get(i));
                    }
                    holder.tagsToRemoveEdit.setText(sb.toString());
                }
                container.addView(holder.tagsToRemoveEdit);
                break;

            case 5: // Status
                container.addView(makeLabel("Status:"));
                String[] statusOpts = {"SKIPPED", "FLAGGED", "DONE"};
                holder.statusSpinner = makeSpinner(statusOpts);
                if (existingAction instanceof StatusAction) {
                    StatusAction sa = (StatusAction) existingAction;
                    if (!sa.clear) {
                        switch (sa.status) {
                            case SKIPPED: holder.statusSpinner.setSelection(0); break;
                            case FLAGGED: holder.statusSpinner.setSelection(1); break;
                            case DONE:    holder.statusSpinner.setSelection(2); break;
                            default: break;
                        }
                    }
                }
                container.addView(holder.statusSpinner);

                holder.clearStatusCheck = new CheckBox(this);
                holder.clearStatusCheck.setText("Clear status instead");
                holder.clearStatusCheck.setTextColor(0xFFCCCCCC);
                if (existingAction instanceof StatusAction) {
                    holder.clearStatusCheck.setChecked(((StatusAction) existingAction).clear);
                }
                container.addView(holder.clearStatusCheck);
                break;

            case 6: // Rename
                container.addView(makeLabel("Rename pattern:"));
                holder.patternEdit = new EditText(this);
                holder.patternEdit.setTextColor(0xFFFFFFFF);
                holder.patternEdit.setHint("{ORIGINAL}_{TAGS}{EXT}");
                if (existingAction instanceof RenameAction) {
                    holder.patternEdit.setText(((RenameAction) existingAction).pattern);
                }
                container.addView(holder.patternEdit);

                TextView hint = new TextView(this);
                hint.setText("Placeholders: {ORIGINAL}, {TAGS}, {EXT}, {DATE}, {COUNTER}, {PREFIX}, {SUFFIX}");
                hint.setTextColor(0xFF888888);
                hint.setTextSize(10f);
                container.addView(hint);
                break;

            case 7: // Set/Change Date
                container.addView(makeLabel("Mode:"));
                String[] dateModes = {"Offset (add/subtract days)", "Absolute (specific date)"};
                holder.dateModeSpinner = makeSpinner(dateModes);
                if (existingAction instanceof SetDateAction) {
                    holder.dateModeSpinner.setSelection(
                            "ABSOLUTE".equals(((SetDateAction) existingAction).mode) ? 1 : 0);
                }
                container.addView(holder.dateModeSpinner);

                container.addView(makeLabel("Value:"));
                holder.dateValueEdit = new EditText(this);
                holder.dateValueEdit.setTextColor(0xFFFFFFFF);
                holder.dateValueEdit.setHint("days (+7, -3) or timestamp");
                if (existingAction instanceof SetDateAction) {
                    holder.dateValueEdit.setText(String.valueOf(((SetDateAction) existingAction).value));
                }
                container.addView(holder.dateValueEdit);

                TextView dateHint = new TextView(this);
                dateHint.setText("Offset: +7 means 7 days forward, -3 means 3 days back.\nAbsolute: Unix timestamp in milliseconds.");
                dateHint.setTextColor(0xFF888888);
                dateHint.setTextSize(10f);
                container.addView(dateHint);
                break;

            case 8: // Change Extension
                container.addView(makeLabel("New extension (without dot):"));
                holder.extensionEdit = new EditText(this);
                holder.extensionEdit.setTextColor(0xFFFFFFFF);
                holder.extensionEdit.setHint("png, jpg, webp");
                if (existingAction instanceof ChangeExtensionAction) {
                    holder.extensionEdit.setText(((ChangeExtensionAction) existingAction).newExtension);
                }
                container.addView(holder.extensionEdit);
                break;

            case 9: // Add Prefix/Suffix
                container.addView(makeLabel("Position:"));
                String[] affixPositions = {"Prefix (before name)", "Suffix (after name, before ext)"};
                holder.affixPositionSpinner = makeSpinner(affixPositions);
                if (existingAction instanceof AffixAction) {
                    holder.affixPositionSpinner.setSelection(
                            "SUFFIX".equals(((AffixAction) existingAction).position) ? 1 : 0);
                }
                container.addView(holder.affixPositionSpinner);

                container.addView(makeLabel("Text to insert:"));
                holder.affixTextEdit = new EditText(this);
                holder.affixTextEdit.setTextColor(0xFFFFFFFF);
                holder.affixTextEdit.setHint("IMG_, _final");
                if (existingAction instanceof AffixAction) {
                    holder.affixTextEdit.setText(((AffixAction) existingAction).text);
                }
                container.addView(holder.affixTextEdit);
                break;

            case 10: // Strip Metadata
                TextView stripWarn = new TextView(this);
                stripWarn.setText("Removes all EXIF, XMP, and embedded metadata from JPEG/PNG files.");
                stripWarn.setTextColor(0xFFFF8800);
                stripWarn.setTextSize(12f);
                container.addView(stripWarn);

                holder.keepOrientationCheck = new CheckBox(this);
                holder.keepOrientationCheck.setText("Keep orientation tag (recommended)");
                holder.keepOrientationCheck.setTextColor(0xFFCCCCCC);
                holder.keepOrientationCheck.setChecked(true);
                if (existingAction instanceof StripMetadataAction) {
                    holder.keepOrientationCheck.setChecked(((StripMetadataAction) existingAction).keepOrientation);
                }
                container.addView(holder.keepOrientationCheck);
                break;
        }
    }

    private Action buildAction(int actionType, ActionParamHolder holder) {
        switch (actionType) {
            case 0: // Move
                String dest0 = holder.destEdit != null ? holder.destEdit.getText().toString().trim() : "";
                if (dest0.isEmpty()) return null;
                Action.Conflict c0 = conflictFromSpinner(holder.conflictSpinner);
                return Action.moveAction(dest0, c0);

            case 1: // Copy
                String dest1 = holder.destEdit != null ? holder.destEdit.getText().toString().trim() : "";
                if (dest1.isEmpty()) return null;
                Action.Conflict c1 = conflictFromSpinner(holder.conflictSpinner);
                return Action.copyAction(dest1, c1);

            case 2: // Delete (trash)
                String trash = holder.trashEdit != null ? holder.trashEdit.getText().toString().trim() : "";
                return Action.deleteAction(true, trash);

            case 3: // Delete (permanent)
                return Action.deleteAction(false, "");

            case 4: // Tags
                List<String> addTags = parseCommaList(
                        holder.tagsToAddEdit != null ? holder.tagsToAddEdit.getText().toString() : "");
                List<String> remTags = parseCommaList(
                        holder.tagsToRemoveEdit != null ? holder.tagsToRemoveEdit.getText().toString() : "");
                return Action.tagAction(addTags, remTags);

            case 5: // Status
                boolean clear = holder.clearStatusCheck != null && holder.clearStatusCheck.isChecked();
                FileStatus.Status[] statuses = {FileStatus.Status.SKIPPED, FileStatus.Status.FLAGGED, FileStatus.Status.DONE};
                int sIdx = holder.statusSpinner != null ? holder.statusSpinner.getSelectedItemPosition() : 0;
                return Action.statusAction(statuses[sIdx], clear);

            case 6: // Rename
                String pattern = holder.patternEdit != null ? holder.patternEdit.getText().toString().trim() : "";
                if (pattern.isEmpty()) return null;
                return Action.renameAction(pattern);

            case 7: // Set/Change Date
                try {
                    String dateMode = holder.dateModeSpinner != null && holder.dateModeSpinner.getSelectedItemPosition() == 1
                            ? "ABSOLUTE" : "OFFSET";
                    long dateVal = Long.parseLong(holder.dateValueEdit.getText().toString().trim());
                    return Action.setDateAction(dateMode, dateVal);
                } catch (Exception e) { return null; }

            case 8: // Change Extension
                String ext = holder.extensionEdit != null ? holder.extensionEdit.getText().toString().trim() : "";
                if (ext.isEmpty()) return null;
                return Action.changeExtensionAction(ext);

            case 9: // Add Prefix/Suffix
                String affixPos = holder.affixPositionSpinner != null && holder.affixPositionSpinner.getSelectedItemPosition() == 1
                        ? "SUFFIX" : "PREFIX";
                String affixText = holder.affixTextEdit != null ? holder.affixTextEdit.getText().toString() : "";
                if (affixText.isEmpty()) return null;
                return Action.affixAction(affixPos, affixText);

            case 10: // Strip Metadata
                boolean keepOrient = holder.keepOrientationCheck != null && holder.keepOrientationCheck.isChecked();
                return Action.stripMetadataAction(keepOrient);
        }
        return null;
    }

    private Action.Conflict conflictFromSpinner(Spinner sp) {
        if (sp == null) return Action.Conflict.SKIP;
        switch (sp.getSelectedItemPosition()) {
            case 1: return Action.Conflict.OVERWRITE;
            case 2: return Action.Conflict.RENAME;
            default: return Action.Conflict.SKIP;
        }
    }

    private List<String> parseCommaList(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        for (String s : text.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
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

        new Thread(() -> {
            final int affected = organizer.applyTo(files);
            mainHandler.post(() -> {
                progress.dismiss();
                Toast.makeText(this, "Rules applied. Files affected: " + affected, Toast.LENGTH_SHORT).show();
            });
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
            .setPositiveButton("Undo", (d, w) -> {
                int restored = organizer.undoLastRun();
                Toast.makeText(this, "Undone: " + restored + " operations", Toast.LENGTH_SHORT).show();
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

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
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
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
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
