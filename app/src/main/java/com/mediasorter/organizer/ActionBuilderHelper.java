package com.mediasorter.organizer;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.mediasorter.FileStatus;
import java.util.ArrayList;
import java.util.List;

public class ActionBuilderHelper {

    private final Context context;

    public interface ActionCallback {
        void onActionSelected(Action action);
    }

    public static class ActionParamHolder {
        public EditText destEdit;
        public Spinner conflictSpinner;
        public EditText trashEdit;
        public CheckBox useTrashCheck;
        public EditText tagsToAddEdit;
        public EditText tagsToRemoveEdit;
        public Spinner statusSpinner;
        public CheckBox clearStatusCheck;
        public EditText patternEdit;
        public Spinner dateModeSpinner;      // SetDateAction
        public EditText dateValueEdit;       // SetDateAction
        public EditText extensionEdit;       // ChangeExtensionAction
        public Spinner affixPositionSpinner; // AffixAction
        public EditText affixTextEdit;       // AffixAction
        public CheckBox keepOrientationCheck;// StripMetadataAction
    }

    public ActionBuilderHelper(Context context) {
        this.context = context;
    }

    public void showActionPickerDialog(Action existingAction, final ActionCallback callback) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        layout.addView(makeLabel("Action type:"));
        String[] actTypes = {"Move", "Copy", "Delete (trash)", "Delete (permanent)",
                "Add/Remove Tags", "Set/Clear Status", "Rename (pattern)",
                "Set/Change Date", "Change Extension", "Add Prefix/Suffix", "Strip Metadata"};
        final Spinner actSpinner = makeSpinner(actTypes);
        layout.addView(actSpinner);

        final LinearLayout actParamsContainer = new LinearLayout(context);
        actParamsContainer.setOrientation(LinearLayout.VERTICAL);
        actParamsContainer.setPadding(0, 8, 0, 8);
        layout.addView(actParamsContainer);

        // Pre-select current action type
        if (existingAction instanceof MoveAction) actSpinner.setSelection(0);
        else if (existingAction instanceof CopyAction) actSpinner.setSelection(1);
        else if (existingAction instanceof DeleteAction) {
            actSpinner.setSelection(((DeleteAction) existingAction).useTrash ? 2 : 3);
        }
        else if (existingAction instanceof TagAction) actSpinner.setSelection(4);
        else if (existingAction instanceof StatusAction) actSpinner.setSelection(5);
        else if (existingAction instanceof RenameAction) actSpinner.setSelection(6);
        else if (existingAction instanceof SetDateAction) actSpinner.setSelection(7);
        else if (existingAction instanceof ChangeExtensionAction) actSpinner.setSelection(8);
        else if (existingAction instanceof AffixAction) actSpinner.setSelection(9);
        else if (existingAction instanceof StripMetadataAction) actSpinner.setSelection(10);

        final ActionParamHolder actHolder = new ActionParamHolder();
        buildActionParams(actParamsContainer, actHolder, existingAction, actSpinner.getSelectedItemPosition());

        actSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                buildActionParams(actParamsContainer, actHolder, null, pos);
            }
            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });

        ScrollView sv = new ScrollView(context);
        sv.addView(layout);

        new AlertDialog.Builder(context)
            .setTitle(existingAction == null ? "Add Action" : "Edit Action")
            .setView(sv)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    Action action = buildAction(actSpinner.getSelectedItemPosition(), actHolder);
                    if (action != null) {
                        callback.onActionSelected(action);
                    } else {
                        Toast.makeText(context, "Invalid action configuration", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    public void buildActionParams(LinearLayout container, ActionParamHolder holder,
                                   Action existingAction, int actionType) {
        container.removeAllViews();

        switch (actionType) {
            case 0: // Move
            case 1: // Copy
                container.addView(makeLabel("Destination folder:"));
                holder.destEdit = new EditText(context);
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
                holder.trashEdit = new EditText(context);
                holder.trashEdit.setTextColor(0xFFFFFFFF);
                holder.trashEdit.setHint("/sdcard/.trash");
                if (existingAction instanceof DeleteAction) {
                    holder.trashEdit.setText(((DeleteAction) existingAction).trashFolder);
                }
                container.addView(holder.trashEdit);
                break;

            case 3: // Delete (permanent)
                TextView warn = new TextView(context);
                warn.setText("WARNING: Files will be permanently deleted!");
                warn.setTextColor(0xFFFF4444);
                container.addView(warn);
                break;

            case 4: // Tags
                container.addView(makeLabel("Tags to add (comma-separated):"));
                holder.tagsToAddEdit = new EditText(context);
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
                holder.tagsToRemoveEdit = new EditText(context);
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

                holder.clearStatusCheck = new CheckBox(context);
                holder.clearStatusCheck.setText("Clear status instead");
                holder.clearStatusCheck.setTextColor(0xFFCCCCCC);
                if (existingAction instanceof StatusAction) {
                    holder.clearStatusCheck.setChecked(((StatusAction) existingAction).clear);
                }
                container.addView(holder.clearStatusCheck);
                break;

            case 6: // Rename
                container.addView(makeLabel("Rename pattern:"));
                holder.patternEdit = new EditText(context);
                holder.patternEdit.setTextColor(0xFFFFFFFF);
                holder.patternEdit.setHint("{ORIGINAL}_{TAGS}{EXT}");
                if (existingAction instanceof RenameAction) {
                    holder.patternEdit.setText(((RenameAction) existingAction).pattern);
                }
                container.addView(holder.patternEdit);

                TextView hint = new TextView(context);
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
                holder.dateValueEdit = new EditText(context);
                holder.dateValueEdit.setTextColor(0xFFFFFFFF);
                holder.dateValueEdit.setHint("days (+7, -3) or timestamp");
                if (existingAction instanceof SetDateAction) {
                    holder.dateValueEdit.setText(String.valueOf(((SetDateAction) existingAction).value));
                }
                container.addView(holder.dateValueEdit);

                TextView dateHint = new TextView(context);
                dateHint.setText("Offset: +7 means 7 days forward, -3 means 3 days back.\nAbsolute: Unix timestamp in milliseconds.");
                dateHint.setTextColor(0xFF888888);
                dateHint.setTextSize(10f);
                container.addView(dateHint);
                break;

            case 8: // Change Extension
                container.addView(makeLabel("New extension (without dot):"));
                holder.extensionEdit = new EditText(context);
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
                holder.affixTextEdit = new EditText(context);
                holder.affixTextEdit.setTextColor(0xFFFFFFFF);
                holder.affixTextEdit.setHint("IMG_, _final");
                if (existingAction instanceof AffixAction) {
                    holder.affixTextEdit.setText(((AffixAction) existingAction).text);
                }
                container.addView(holder.affixTextEdit);
                break;

            case 10: // Strip Metadata
                TextView stripWarn = new TextView(context);
                stripWarn.setText("Removes all EXIF, XMP, and embedded metadata from JPEG/PNG files.");
                stripWarn.setTextColor(0xFFFF8800);
                stripWarn.setTextSize(12f);
                container.addView(stripWarn);

                holder.keepOrientationCheck = new CheckBox(context);
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

    public Action buildAction(int actionType, ActionParamHolder holder) {
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

    private TextView makeLabel(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(12f);
        return tv;
    }

    private Spinner makeSpinner(String[] options) {
        Spinner sp = new Spinner(context);
        ArrayAdapter<String> ad = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, options);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }
}
