package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import com.mediasorter.features.RandomGenerator;
import org.json.JSONObject;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.mediasorter.models.Tag;
import com.mediasorter.models.TagList;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {

    private CacheManager    cacheManager;
    private FolderManager   folderManager;
    private ThumbnailLoader thumbnailLoader;
    private GestureSettings gestureSettings;
    private TagListManager  tagListManager;
    private TagManager      tagManager;
    private MediaIndexer    indexer;

    private SharedPreferences settingsPrefs;

    // View references for onResume re-read
    private CheckBox precacheCheck, videoAutoplayCheck, videoLoopCheck;
    private View precacheRadiusRow, videoLoopRow;
    private boolean refreshingResumeViews;
    private boolean isInitializing = true;
    private EditText randomPatternInput;
    private String originalTheme;
    private LinearLayout tagListsContainer;
    private LinearLayout foldersContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        isInitializing = true;
        super.onCreate(savedInstanceState);
        cacheManager    = new CacheManager(this);
        folderManager   = new FolderManager(this);
        thumbnailLoader = new ThumbnailLoader(this);
        gestureSettings = new GestureSettings(this);
        initializeTagListDefaultsIfMissing();
        tagListManager  = new TagListManager(this);
        tagManager      = new TagManager(this);
        indexer         = new MediaIndexer();
        settingsPrefs   = getSharedPreferences("settings_prefs", MODE_PRIVATE);
        originalTheme   = "AppTheme";

        buildSettings();
        isInitializing = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isInitializing) isInitializing = false;
        if (!hasFocus && !isInitializing && randomPatternInput != null) {
            saveValidatedRandomPattern(randomPatternInput);
        }
    }

    /**
     * TagListManager historically persisted its first default list from its
     * constructor. Keep that first-launch default explicit and one-time, while
     * every subsequent SettingsActivity initialization remains read-only.
     */
    private void initializeTagListDefaultsIfMissing() {
        SharedPreferences tagPrefs = getSharedPreferences("tag_list_prefs", MODE_PRIVATE);
        if (tagPrefs.contains("list_count")) return;
        tagPrefs.edit()
                .putInt("list_count", 1)
                .putInt("active_list", 0)
                .putString("tag_lists_name_0", "Default")
                .putBoolean("tag_lists_default_0", true)
                .putString("tag_lists_tags_0", "")
                .apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read settings state without replaying listeners or requesting a
        // layout when the current views already match the saved values.
        if (settingsPrefs == null) {
            settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE);
        }
        refreshingResumeViews = true;
        try {
            if (precacheCheck != null && precacheRadiusRow != null) {
                boolean precacheOn = settingsPrefs.getBoolean("precache_enabled", true);
                if (precacheCheck.isChecked() != precacheOn) {
                    precacheCheck.setChecked(precacheOn);
                }
                setVisibilityIfChanged(precacheRadiusRow,
                        precacheOn ? View.VISIBLE : View.GONE);
            }
            if (videoAutoplayCheck != null && videoLoopRow != null) {
                boolean autoplayOn = settingsPrefs.getBoolean("video_autoplay", false);
                if (videoAutoplayCheck.isChecked() != autoplayOn) {
                    videoAutoplayCheck.setChecked(autoplayOn);
                }
                setVisibilityIfChanged(videoLoopRow,
                        autoplayOn ? View.VISIBLE : View.GONE);
            }
        } finally {
            refreshingResumeViews = false;
        }
    }

    private void setVisibilityIfChanged(View view, int visibility) {
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    private void addUiCustomizationSection(LinearLayout root) {
        root.addView(makeTitle("UI Customization"));
        root.addView(makeCheckBoxRow("Show stats bar", settingsPrefs.getBoolean("show_stats_bar", true),
                new OnCheckedChangeListener() {
                    @Override public void onChecked(boolean checked) { saveBoolean("show_stats_bar", checked); }
                }));
        root.addView(makeCheckBoxRow("Show tag bar", settingsPrefs.getBoolean("show_tag_bar", true),
                new OnCheckedChangeListener() {
                    @Override public void onChecked(boolean checked) { saveBoolean("show_tag_bar", checked); }
                }));
        root.addView(makeCheckBoxRow("Show search bar", settingsPrefs.getBoolean("show_search_bar", true),
                new OnCheckedChangeListener() {
                    @Override public void onChecked(boolean checked) { saveBoolean("show_search_bar", checked); }
                }));
        root.addView(makeCheckBoxRow("Show preview panel", settingsPrefs.getBoolean("show_preview", true),
                new OnCheckedChangeListener() {
                    @Override public void onChecked(boolean checked) { saveBoolean("show_preview", checked); }
                }));
        root.addView(makeNumericInputRow("Explorer width when preview is visible (20-80%):",
                settingsPrefs.getInt("explorer_width_percent", 40), 20, 80,
                new OnNumericChangeListener() {
                    @Override public void onChange(int value) { saveInt("explorer_width_percent", value); }
                }));
    }

    private void addToolbarSection(LinearLayout root) {
        root.addView(makeTitle("Toolbar"));
        root.addView(makeLabel("Choose up to five visible action slots. Everything else remains in the overflow menu."));
        root.addView(makeLabel("Available actions:"));
        final List<String> ids = GestureConstants.getToolbarActionIds();
        final String[] labels = new String[ids.size()];
        StringBuilder available = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            labels[i] = GestureConstants.label(ids.get(i));
            if (i > 0) available.append(", ");
            available.append(labels[i]);
        }
        root.addView(makeLabel(available.toString()));
        List<String> saved = loadToolbarSlots();
        final List<Spinner> spinners = new ArrayList<Spinner>();
        for (int slot = 0; slot < 5; slot++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = makeLabel("Slot " + (slot + 1));
            label.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);
            Spinner spinner = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            String selected = slot < saved.size() ? saved.get(slot) : GestureConstants.ACTION_NOTHING;
            int selectedPosition = ids.indexOf(selected);
            if (selectedPosition < 0) selectedPosition = 0;
            spinner.setSelection(selectedPosition);
            final Spinner currentSpinner = spinner;
            final boolean[] spinnerReady = new boolean[]{false};
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (!spinnerReady[0]) {
                        spinnerReady[0] = true;
                        return;
                    }
                    if (!isInitializing) saveToolbarSlots(spinners, ids);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            row.addView(spinner);
            spinners.add(currentSpinner);
            root.addView(row);
        }
    }

    private List<String> loadToolbarSlots() {
        List<String> result = new ArrayList<String>();
        String raw = settingsPrefs.getString("toolbar_slots", "");
        boolean hasSavedSlots = raw != null && !raw.trim().isEmpty();
        try {
            org.json.JSONArray array = new org.json.JSONArray(hasSavedSlots ? raw : "[]");
            for (int i = 0; i < array.length() && result.size() < 5; i++) {
                String id = array.optString(i, "");
                if (GestureConstants.isKnownAction(id) && !GestureConstants.ACTION_DONE.equals(id)
                        && !GestureConstants.ACTION_NOTHING.equals(id)) result.add(id);
            }
        } catch (Exception ignored) {}
        if (result.isEmpty() && !hasSavedSlots) {
            result.add(GestureConstants.ACTION_FLAG);
            result.add(GestureConstants.ACTION_QUICK_TAGS);
            result.add(GestureConstants.ACTION_SURPRISE_ME);
            result.add(GestureConstants.ACTION_UNDO);
            result.add(GestureConstants.ACTION_SORT_PICKER);
        }
        return result;
    }

    private void saveToolbarSlots(List<Spinner> spinners, List<String> ids) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (Spinner spinner : spinners) {
            int position = spinner.getSelectedItemPosition();
            if (position < 0 || position >= ids.size()) continue;
            String id = ids.get(position);
            if (GestureConstants.ACTION_NOTHING.equals(id) || GestureConstants.ACTION_DONE.equals(id)) continue;
            boolean duplicate = false;
            for (int i = 0; i < array.length(); i++) if (id.equals(array.optString(i))) duplicate = true;
            if (!duplicate && array.length() < 5) array.put(id);
        }
        saveString("toolbar_slots", array.toString());
    }

    private void addGestureSettingsSection(LinearLayout root) {
        root.addView(makeTitle("Gestures"));
        root.addView(makeLabel("Inputs are grouped by category. Edit opens a searchable action picker."));
        addGestureInputGroup(root, "D-Pad", new String[]{
                GestureConstants.INPUT_DPAD_UP, GestureConstants.INPUT_DPAD_DOWN,
                GestureConstants.INPUT_DPAD_LEFT, GestureConstants.INPUT_DPAD_RIGHT,
                GestureConstants.INPUT_DPAD_CENTER});
        addGestureInputGroup(root, "Swipes", new String[]{
                GestureConstants.INPUT_SWIPE_LEFT, GestureConstants.INPUT_SWIPE_RIGHT,
                GestureConstants.INPUT_SWIPE_UP, GestureConstants.INPUT_SWIPE_DOWN});
        addGestureInputGroup(root, "Taps", new String[]{
                GestureConstants.INPUT_TAP_SINGLE, GestureConstants.INPUT_TAP_DOUBLE,
                GestureConstants.INPUT_TAP_LONG});
        addGestureInputGroup(root, "Volume", new String[]{
                GestureConstants.INPUT_VOLUME_UP, GestureConstants.INPUT_VOLUME_DOWN,
                GestureConstants.INPUT_VOLUME_UP_LONG, GestureConstants.INPUT_VOLUME_DOWN_LONG});
        addGestureInputGroup(root, "Hardware", new String[]{
                GestureConstants.INPUT_HARDWARE_BACK, GestureConstants.INPUT_HARDWARE_MENU});
        Button reset = makeButton("Reset to defaults");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                gestureSettings.resetToDefaults();
                Toast.makeText(SettingsActivity.this, "Gesture defaults restored", Toast.LENGTH_SHORT).show();
                // The next open re-reads every row; no recreate is needed here.
            }
        });
        root.addView(reset);
    }

    private void addGestureInputGroup(LinearLayout root, String title, String[] inputIds) {
        root.addView(makeTitle(title));
        for (String inputId : inputIds) {
            final String id = inputId;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView input = makeLabel(GestureConstants.inputLabel(id));
            input.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
            row.addView(input);
            final TextView assignment = makeLabel(gestureAssignmentLabel(id));
            assignment.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));
            row.addView(assignment);
            Button edit = makeSmallButton("Edit");
            edit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    showGestureActionPicker(id, assignment);
                }
            });
            row.addView(edit);
            root.addView(row);
        }
    }

    private String gestureAssignmentLabel(String inputId) {
        List<GestureSettings.GestureStep> steps = gestureSettings.getSteps(inputId);
        String summary = gestureSettings.getSummary(steps);
        return summary == null || summary.isEmpty() ? "None" : summary;
    }

    private void showGestureActionPicker(final String inputId, final TextView assignment) {
        final String currentAction = firstGestureAction(inputId);
        final EditText search = new EditText(this);
        search.setHint("Search actions…");
        search.setSingleLine(true);
        search.setTextColor(0xFFFFFFFF);
        final ListView list = new ListView(this);
        final List<String> allRows = buildGesturePickerRows(currentAction, "");
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, allRows);
        list.setAdapter(adapter);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(search);
        content.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Choose action")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .create();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                List<String> filtered = buildGesturePickerRows(currentAction, s.toString());
                adapter.clear();
                adapter.addAll(filtered);
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String row = String.valueOf(parent.getItemAtPosition(position));
                if (row.startsWith("[")) return;
                if (row.startsWith("✓ ")) row = row.substring(2);
                GestureSettings.GestureAction action = gestureSettings.fromLabel(row);
                if (action == GestureSettings.GestureAction.MACRO) {
                    showMacroAssignmentPicker(inputId, assignment, dialog);
                    return;
                }
                List<GestureSettings.GestureStep> steps = new ArrayList<GestureSettings.GestureStep>();
                steps.add(new GestureSettings.GestureStep(action, ""));
                gestureSettings.setSteps(inputId, steps);
                assignment.setText(gestureSettings.getSummary(steps));
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private List<String> buildGesturePickerRows(String currentAction, String query) {
        List<String> rows = new ArrayList<String>();
        rows.add(GestureConstants.label(GestureConstants.ACTION_NOTHING));
        String lower = query == null ? "" : query.trim().toLowerCase();
        for (GestureConstants.Category category : GestureConstants.Category.values()) {
            List<String> ids = GestureConstants.getActionIds(category);
            boolean headerAdded = false;
            for (String id : ids) {
                if (GestureConstants.ACTION_DONE.equals(id)
                        || GestureConstants.ACTION_NOTHING.equals(id)) continue;
                String label = GestureConstants.label(id);
                if (!lower.isEmpty() && !label.toLowerCase().contains(lower)) continue;
                if (!headerAdded) {
                    rows.add("[" + GestureConstants.categoryLabel(category) + "]");
                    headerAdded = true;
                }
                rows.add(id.equals(currentAction) ? "✓ " + label : label);
            }
        }
        return rows;
    }

    private String firstGestureAction(String inputId) {
        List<GestureSettings.GestureStep> steps = gestureSettings.getSteps(inputId);
        if (steps == null || steps.isEmpty() || steps.get(0).action == null) return GestureConstants.ACTION_NOTHING;
        return steps.get(0).action.name();
    }

    private void showMacroAssignmentPicker(final String inputId, final TextView assignment,
                                           final AlertDialog parentDialog) {
        final List<GestureSettings.GestureMacro> macros = gestureSettings.getUsableMacros();
        if (macros.isEmpty()) {
            Toast.makeText(this, "No steps", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[macros.size()];
        for (int i = 0; i < macros.size(); i++) names[i] = macros.get(i).name;
        new AlertDialog.Builder(this).setTitle("Choose macro").setItems(names,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        GestureSettings.GestureMacro macro = macros.get(which);
                        List<GestureSettings.GestureStep> steps = new ArrayList<GestureSettings.GestureStep>();
                        steps.add(new GestureSettings.GestureStep(
                                GestureSettings.GestureAction.MACRO, macro.id));
                        gestureSettings.setSteps(inputId, steps);
                        assignment.setText(macro.name);
                        parentDialog.dismiss();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private View makeValidatedRandomPatternRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(makeLabel("Custom pattern ({syl}, {hex}, {seq}, {date}):"));
        final EditText input = new EditText(this);
        randomPatternInput = input;
        input.setText(settingsPrefs.getString("random_tag_custom_pattern", "{syl}-{date}"));
        input.setTextColor(0xFFFFFFFF);
        input.setSingleLine(true);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (isInitializing) return;
                String value = editable.toString().trim();
                if (RandomGenerator.findUnknownPlaceholders(value).isEmpty()) {
                    saveString("random_tag_custom_pattern",
                            value.isEmpty() ? "{syl}-{date}" : value);
                }
            }
        });
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus || isInitializing) return;
                saveValidatedRandomPattern(input);
            }
        });
        row.addView(input);
        return row;
    }

    private void saveValidatedRandomPattern(EditText input) {
        String value = input.getText().toString().trim();
        List<String> unknown = RandomGenerator.findUnknownPlaceholders(value);
        if (!unknown.isEmpty()) {
            input.setError("Unknown placeholder: {" + unknown.get(0) + "}");
            Toast.makeText(this, "Unknown placeholder: {" + unknown.get(0) + "}",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        input.setError(null);
        saveString("random_tag_custom_pattern", value.isEmpty() ? "{syl}-{date}" : value);
    }

    private void addRandomTagFormatSection(LinearLayout root) {
        root.addView(makeTitle("Random Tag Format"));
        final String[] formats = {"Syllable triplet", "Hex placeholder", "Custom pattern", "Random pattern"};
        String saved = settingsPrefs.getString("random_tag_format", "syllable");
        int selected = "hex".equalsIgnoreCase(saved) ? 1
                : "custom".equalsIgnoreCase(saved) ? 2
                : "random".equalsIgnoreCase(saved) ? 3 : 0;
        root.addView(makeSpinnerRow("Format:", formats, selected,
                new OnSpinnerSelectedListener() {
                    @Override public void onSelected(String value, int position) {
                        String id = position == 1 ? "hex" : position == 2 ? "custom"
                                : position == 3 ? "random" : "syllable";
                        saveString("random_tag_format", id);
                    }
                }));
        root.addView(makeValidatedRandomPatternRow());
        root.addView(makeLabel("Example: {syl}-{date} → ka-mi-ra-" +
                new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(new java.util.Date())));
    }

    private void refreshTagLists() {
        if (tagListsContainer == null) return;
        tagListsContainer.removeAllViews();
        List<TagList> allLists = tagListManager.getAllLists();
        for (int i = 0; i < allLists.size(); i++) {
            tagListsContainer.addView(makeTagListRow(allLists.get(i), i));
        }
    }

    private View makeTagListRow(final TagList list, final int index) {
        LinearLayout listRow = new LinearLayout(this);
        listRow.setOrientation(LinearLayout.VERTICAL);
        listRow.setBackgroundColor(0xFF1A1A2E);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        listLp.bottomMargin = 8;
        listRow.setLayoutParams(listLp);
        listRow.setPadding(8, 8, 8, 8);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView listName = makeLabel(list.getName()
            + (list.isDefault() ? " (Default)" : "")
            + "  —  " + list.size() + " tags");
        listName.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nameRow.addView(listName);

        Button btnEdit = makeSmallButton("Edit");
        btnEdit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEditListDialog(index); }
        });
        nameRow.addView(btnEdit);

        if (!list.isDefault()) {
            Button btnDel = makeSmallButton("Delete");
            btnDel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    tagListManager.deleteList(index);
                    refreshTagLists();
                }
            });
            nameRow.addView(btnDel);
        }

        listRow.addView(nameRow);

        if (!list.getTags().isEmpty()) {
            TextView tagsPreview = makeLabel(joinTags(list.getTags()));
            tagsPreview.setTextColor(0xFF888888);
            tagsPreview.setTextSize(10f);
            listRow.addView(tagsPreview);
        }
        return listRow;
    }

    private void refreshFolders() {
        if (foldersContainer == null) return;
        foldersContainer.removeAllViews();
        List<String> folders = folderManager.getFolders();
        if (folders.isEmpty()) {
            foldersContainer.addView(makeLabel("No folders added"));
            return;
        }
        for (final String folder : folders) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView lbl = makeLabel(folder);
            lbl.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(lbl);

            Button rm = makeButton("Remove");
            rm.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    folderManager.removeFolder(folder);
                    refreshFolders();
                }
            });
            row.addView(rm);
            foldersContainer.addView(row);
        }
    }

    private void buildSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(32, 32, 32, 32);

        root.addView(makeTitle("Settings"));

        // UI controls are kept together at the top. MainActivity applies them
        // when this Activity closes, avoiding an initialization/recreate loop.
        addUiCustomizationSection(root);
        addToolbarSection(root);

        // ── 1. Cache ──────────────────────────────────────────────────────────
        root.addView(makeTitle("Cache"));

        final TextView cacheSizeLabel = makeLabel(
            "Current: " + cacheManager.getFormattedCacheSize()
            + " / " + cacheManager.getLimitMB() + " MB");
        root.addView(cacheSizeLabel);

        final TextView limitLabel = makeLabel("Cache limit: " + cacheManager.getLimitMB() + " MB");
        root.addView(limitLabel);

        SeekBar limitSeek = new SeekBar(this);
        limitSeek.setMax(500);
        limitSeek.setProgress(cacheManager.getLimitMB());
        limitSeek.setOnSeekBarChangeListener(simple(new ProgressCallback() {
            @Override
            public void onProgress(int progress) {
                int mb = Math.max(10, progress);
                cacheManager.setLimitMB(mb);
                limitLabel.setText("Cache limit: " + mb + " MB");
            }
        }));
        root.addView(limitSeek);

        Button btnClear = makeButton("Clear Cache");
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cacheManager.clearAll();
                cacheSizeLabel.setText("Current: " + cacheManager.getFormattedCacheSize());
                Toast.makeText(SettingsActivity.this, "Cache cleared", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnClear);

        // ── 2. Thumbnails ─────────────────────────────────────────────────────
        root.addView(makeTitle("Thumbnails"));

        final TextView qualityLabel = makeLabel(
            "Quality: " + qualityName(thumbnailLoader.getQuality()));
        root.addView(qualityLabel);

        SeekBar qualitySeek = new SeekBar(this);
        qualitySeek.setMax(2);
        qualitySeek.setProgress(qualityIndex(thumbnailLoader.getQuality()));
        qualitySeek.setOnSeekBarChangeListener(simple(new ProgressCallback() {
            @Override
            public void onProgress(int progress) {
                int size = qualityFromIndex(progress);
                thumbnailLoader.setQuality(size);
                qualityLabel.setText("Quality: " + qualityName(size));
            }
        }));
        root.addView(qualitySeek);

        final TextView memLabel = makeLabel(
            "Max thumbnail memory: " + thumbnailLoader.getMaxMB() + " MB");
        root.addView(memLabel);

        SeekBar memSeek = new SeekBar(this);
        memSeek.setMax(90);
        memSeek.setProgress(thumbnailLoader.getMaxMB() - 10);
        memSeek.setOnSeekBarChangeListener(simple(new ProgressCallback() {
            @Override
            public void onProgress(int progress) {
                int mb = progress + 10;
                thumbnailLoader.setMaxMB(mb);
                memLabel.setText("Max thumbnail memory: " + mb + " MB");
            }
        }));
        root.addView(memSeek);

        // ── 3. Memory window ──────────────────────────────────────────────────
        root.addView(makeTitle("Memory Window"));

        final SharedPreferences windowPrefs =
            getSharedPreferences("window_prefs", MODE_PRIVATE);
        int currentWindow = windowPrefs.getInt("window_size", 20);

        final TextView windowLabel = makeLabel("Window size: " + currentWindow + " files");
        root.addView(windowLabel);

        SeekBar windowSeek = new SeekBar(this);
        windowSeek.setMax(90);
        windowSeek.setProgress(currentWindow - 10);
        windowSeek.setOnSeekBarChangeListener(simple(new ProgressCallback() {
            @Override
            public void onProgress(int progress) {
                int size = progress + 10;
                windowPrefs.edit().putInt("window_size", size).apply();
                windowLabel.setText("Window size: " + size + " files");
            }
        }));
        root.addView(windowSeek);

        // ── 4. Main window UI toggles ─────────────────────────────────────────
        root.addView(makeTitle("Main Window"));
        root.addView(makeToggleRow("Tag menus & prompts",
            tagManager.isTagsEnabled(),
            new ToggleHandler() {
                @Override public void onToggle(boolean enabled) { tagManager.setTagsEnabled(enabled); }
            }));

        // ── 5. Gesture settings ───────────────────────────────────────────────
        addGestureSettingsSection(root);

        // ── Macros ────────────────────────────────────────────────────────────
        root.addView(makeTitle("Gesture Macros"));
        final LinearLayout macrosContainer = new LinearLayout(this);
        macrosContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(macrosContainer);

        renderMacros(macrosContainer);

        Button btnAddMacro = makeSmallButton("+ Add Macro");
        btnAddMacro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<GestureSettings.GestureMacro> mList = gestureSettings.loadMacros();
                if (mList.size() >= 10) {
                    Toast.makeText(SettingsActivity.this, "Maximum of 10 macros allowed", Toast.LENGTH_SHORT).show();
                    return;
                }
                GestureSettings.GestureMacro newM = new GestureSettings.GestureMacro();
                int maxId = 0;
                for (GestureSettings.GestureMacro m : mList) {
                    try {
                        int parsed = Integer.parseInt(m.id);
                        if (parsed > maxId) maxId = parsed;
                    } catch (Exception ignored) {}
                }
                newM.id = String.valueOf(maxId + 1);
                newM.name = "Macro " + newM.id;
                newM.actions = new ArrayList<>();
                mList.add(newM);
                gestureSettings.saveMacros(mList);
                renderMacros(macrosContainer);
            }
        });
        root.addView(btnAddMacro);

        // ── Tag lists ─────────────────────────────────────────────────────────
        root.addView(makeTitle("Tag Lists"));

        tagListsContainer = new LinearLayout(this);
        tagListsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(tagListsContainer);
        refreshTagLists();

        Button btnNewList = makeButton("+ New Tag List");
        btnNewList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showNewListDialog(); }
        });
        root.addView(btnNewList);

        Button btnBulkActive = makeButton("Auto-fill active list from all tags");
        btnBulkActive.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                List<Tag> allTags = tagManager.getAllTags();
                List<String> tagNames = new ArrayList<>();
                for (Tag t : allTags) tagNames.add(t.getName());
                int added = tagListManager.bulkAddToActiveList(tagNames);
                Toast.makeText(SettingsActivity.this,
                    added + " tags added to " + tagListManager.getActiveList().getName(),
                    Toast.LENGTH_SHORT).show();
                refreshTagLists();
            }
        });
        root.addView(btnBulkActive);

        Button btnAutoPopulate = makeButton("Auto-populate lists from scanned files");
        btnAutoPopulate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(SettingsActivity.this,
                    "Rescan files — tags auto-import on scan complete",
                    Toast.LENGTH_LONG).show();
            }
        });
        root.addView(btnAutoPopulate);

        // ── 8. Watched Folders ────────────────────────────────────────────────
        root.addView(makeTitle("Watched Folders"));

        foldersContainer = new LinearLayout(this);
        foldersContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(foldersContainer);
        refreshFolders();

        Button btnAdd = makeButton("+ Add Folder");
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAddFolderDialog(); }
        });
        root.addView(btnAdd);

        Button btnFullRescan = makeButton("Full Rescan");
        btnFullRescan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                indexer.fullReset(folderManager.getFolders());
                Toast.makeText(SettingsActivity.this, "Full rescan started", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnFullRescan);

        // ── 9. Backup & Restore ───────────────────────────────────────────────
        root.addView(makeTitle("Backup & Restore"));

        Button btnExport = makeButton("Export Settings");
        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String prefix = RandomGenerator.randomGroupPrefix(new java.util.HashSet<String>());
                java.util.Calendar cal = java.util.Calendar.getInstance();
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
                String dateStr = sdf.format(cal.getTime());
                String defaultFilename = "export_" + prefix + "_" + dateStr + ".json";

                LinearLayout container = new LinearLayout(SettingsActivity.this);
                container.setOrientation(LinearLayout.VERTICAL);

                container.addView(makeLabel("Export Directory:"));
                final EditText dirEdit = new EditText(SettingsActivity.this);
                dirEdit.setText(SettingsExporter.getBackupDir(SettingsActivity.this).getAbsolutePath());
                dirEdit.setTextColor(0xFFFFFFFF);
                container.addView(dirEdit);

                container.addView(makeLabel("Filename:"));
                final EditText nameEdit = new EditText(SettingsActivity.this);
                nameEdit.setText(defaultFilename);
                nameEdit.setTextColor(0xFFFFFFFF);
                container.addView(nameEdit);

                android.widget.FrameLayout box = new android.widget.FrameLayout(SettingsActivity.this);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(pad, pad, pad, pad);
                container.setLayoutParams(lp);
                box.addView(container);

                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Export Settings")
                        .setView(box)
                        .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                final String directoryPath = dirEdit.getText().toString().trim();
                                String filename = nameEdit.getText().toString().trim();
                                if (directoryPath.isEmpty() || filename.isEmpty()) {
                                    Toast.makeText(SettingsActivity.this, "Fields cannot be empty", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                if (!filename.endsWith(".json")) {
                                    filename += ".json";
                                }

                                final String finalFilename = filename;
                                final String path = SettingsExporter.exportSettings(SettingsActivity.this, directoryPath, finalFilename);
                                if (path != null) {
                                    Toast.makeText(SettingsActivity.this, "Exported successfully to:\n" + path, Toast.LENGTH_LONG).show();

                                    // Backup rotation check
                                    File dir = new File(directoryPath);
                                    File[] files = dir.listFiles(new java.io.FilenameFilter() {
                                        @Override
                                        public boolean accept(File d, String name) {
                                            return name.startsWith("export_") && name.endsWith(".json");
                                        }
                                    });

                                    if (files != null && files.length > 5) {
                                        final int toDeleteCount = files.length - 5;
                                        // Sort ascending by lastModified to get oldest first
                                        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                                            @Override
                                            public int compare(File f1, File f2) {
                                                return Long.compare(f1.lastModified(), f2.lastModified());
                                            }
                                        });

                                        final File[] sortedFiles = files;
                                        new AlertDialog.Builder(SettingsActivity.this)
                                                .setTitle("Backup Rotation")
                                                .setMessage(toDeleteCount + " old backups found. Delete the oldest to keep only 5?")
                                                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                                    @Override
                                                    public void onClick(DialogInterface dialogInterface, int whichButton) {
                                                        int deleted = 0;
                                                        for (int i = 0; i < toDeleteCount; i++) {
                                                            if (sortedFiles[i].delete()) {
                                                                deleted++;
                                                            }
                                                        }
                                                        Toast.makeText(SettingsActivity.this, "Deleted " + deleted + " old backups", Toast.LENGTH_SHORT).show();
                                                    }
                                                })
                                                .setNegativeButton("No", null)
                                                .show();
                                    }
                                } else {
                                    Toast.makeText(SettingsActivity.this,
                                            "Export failed — storage error", Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        root.addView(btnExport);

        Button btnImport = makeButton("Import Settings");
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final File[] backups = SettingsExporter.listBackups(SettingsActivity.this);
                if (backups.length == 0) {
                    Toast.makeText(SettingsActivity.this, "No backups found", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] names = new String[backups.length];
                for (int i = 0; i < backups.length; i++) names[i] = backups[i].getName();
                new AlertDialog.Builder(SettingsActivity.this)
                    .setTitle("Select backup to import")
                    .setItems(names, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, final int whichIdx) {
                            final File selectedFile = backups[whichIdx];
                            if (!selectedFile.canRead()) {
                                Toast.makeText(SettingsActivity.this, "Cannot read that file", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            // Read file and parse as JSONObject first for validation
                            String jsonStr = "";
                            java.io.FileInputStream fis = null;
                            java.io.InputStreamReader isr = null;
                            java.io.BufferedReader br = null;
                            try {
                                fis = new java.io.FileInputStream(selectedFile);
                                isr = new java.io.InputStreamReader(fis, "UTF-8");
                                br = new java.io.BufferedReader(isr);
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) {
                                    sb.append(line).append("\n");
                                }
                                jsonStr = sb.toString();
                            } catch (Exception e) {
                                Toast.makeText(SettingsActivity.this, "Cannot read that file", Toast.LENGTH_SHORT).show();
                                return;
                            } finally {
                                if (br != null) try { br.close(); } catch (Exception ignored) {}
                                if (isr != null) try { isr.close(); } catch (Exception ignored) {}
                                if (fis != null) try { fis.close(); } catch (Exception ignored) {}
                            }

                            final JSONObject rootObj;
                            try {
                                rootObj = new JSONObject(jsonStr);
                            } catch (Exception e) {
                                Toast.makeText(SettingsActivity.this, "Import failed — file is not a valid backup", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            // Validate top-level keys
                            String[] requiredKeys = {"version", "timestamp", "preferences", "folders", "gestures", "macros", "tag_presets", "rules"};
                            boolean malformed = false;
                            for (String k : requiredKeys) {
                                if (!rootObj.has(k)) {
                                    malformed = true;
                                    break;
                                }
                            }
                            if (malformed) {
                                Toast.makeText(SettingsActivity.this, "Import failed — file is not a valid backup", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            final Runnable doApplyAction = new Runnable() {
                                @Override
                                public void run() {
                                    SettingsExporter.ApplyResult res = SettingsExporter.applyImport(SettingsActivity.this, rootObj);
                                    if (res.isSuccess) {
                                        // MainActivity reconciles newly imported folders when this
                                        // screen finishes; this Activity does not restart itself.
                                        String summary = "Import complete — " 
                                                + res.preferencesCount + " preferences, "
                                                + res.foldersCount + " folders, "
                                                + res.rulesCount + " rules, "
                                                + res.gesturesCount + " gestures, "
                                                + res.macrosCount + " macros restored.";
                                        if (res.rulesSkipped > 0) {
                                            summary += "\n" + res.rulesSkipped + " rules skipped (unreadable).";
                                        }
                                        if (res.failedKeys > 0) {
                                            summary += "\n" + res.failedKeys + " settings could not be verified and were skipped.";
                                        }

                                        // All writes and verification are complete; delay
                                        // the one MainActivity recreation to let slow API 21
                                        // storage finish flushing.
                                        MainActivity.requestRecreateAfterImport();
                                        final String importSummary = summary;
                                        new AlertDialog.Builder(SettingsActivity.this)
                                                .setTitle("Import Successful")
                                                .setMessage(importSummary)
                                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                                    @Override
                                                    public void onClick(DialogInterface d3, int w3) {
                                                        // MainActivity applies the imported values in place from
                                                        // onResume; do not recreate this screen or start a loop.
                                                        setResult(RESULT_OK);
                                                        finish();
                                                    }
                                                })
                                                .show();
                                    } else {
                                        String message = res.errorMessage != null
                                                ? res.errorMessage
                                                : "Import failed — settings reset to defaults.";
                                        Toast.makeText(SettingsActivity.this, message,
                                                Toast.LENGTH_LONG).show();
                                        setResult(RESULT_CANCELED);
                                        finish();
                                    }
                                }
                            };

                            // Forward compatibility detection
                            int backupVersion = rootObj.optInt("version", 0);
                            if (backupVersion > BuildConfig.VERSION_CODE) {
                                new AlertDialog.Builder(SettingsActivity.this)
                                        .setTitle("Version Warning")
                                        .setMessage("This backup was made with a newer version. Some settings may not apply. Do you want to proceed?")
                                        .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialogInterface, int whichButton) {
                                                doApplyAction.run();
                                            }
                                        })
                                        .setNegativeButton("No", null)
                                        .show();
                            } else {
                                doApplyAction.run();
                            }
                        }
                    })
                    .show();
            }
        });
        root.addView(btnImport);

        // ── 10. Duplicate Files ───────────────────────────────────────────────
        root.addView(makeTitle("Duplicate Files"));

        Button btnDupes = makeButton("Find Duplicates");
        btnDupes.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final List<MediaFile> files = MainActivity.getLatestFullList();
                if (files.isEmpty()) {
                    Toast.makeText(SettingsActivity.this, "No files scanned yet", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(SettingsActivity.this, "Scanning for duplicates...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final List<DuplicateFinder.DuplicateGroup> dupes =
                                DuplicateFinder.findDuplicates(files, new DuplicateFinder.ProgressCallback() {
                                    @Override public void onProgress(int scanned, int total, String fileName) {}
                                });
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                for (MediaFile file : files) file.setDuplicate(false);
                                for (DuplicateFinder.DuplicateGroup group : dupes) {
                                    for (MediaFile file : group.files) file.setDuplicate(true);
                                }
                                if (dupes.isEmpty()) {
                                    Toast.makeText(SettingsActivity.this, "No duplicates found", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                int totalDupes = 0;
                                for (DuplicateFinder.DuplicateGroup g : dupes) totalDupes += g.files.size() - 1;
                                StringBuilder sb = new StringBuilder();
                                sb.append(dupes.size()).append(" groups, ").append(totalDupes).append(" extra copies\n\n");
                                int shown = Math.min(dupes.size(), 15);
                                for (int i = 0; i < shown; i++) {
                                    DuplicateFinder.DuplicateGroup g = dupes.get(i);
                                    sb.append("[").append(g.files.size()).append(" files, ")
                                      .append(g.size / 1024).append(" KB]\n");
                                    for (MediaFile f : g.files) sb.append("  ").append(f.getName()).append("\n");
                                    sb.append("\n");
                                }
                                if (dupes.size() > 15) sb.append("... and ").append(dupes.size() - 15).append(" more");
                                new AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle("Duplicates")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("OK", null)
                                    .show();
                            }
                        });
                    }
                }).start();
            }
        });
        root.addView(btnDupes);

        // ── 11. Crash Log ─────────────────────────────────────────────────────
        root.addView(makeTitle("Crash Log"));

        Button btnLog = makeButton("View Crash Log");
        btnLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String log = CrashLogger.readLog(SettingsActivity.this);
                ScrollView sv = new ScrollView(SettingsActivity.this);
                TextView tv = new TextView(SettingsActivity.this);
                tv.setText(log);
                tv.setTextColor(0xFFCCCCCC);
                tv.setTextSize(10f);
                tv.setPadding(16, 16, 16, 16);
                sv.addView(tv);
                new AlertDialog.Builder(SettingsActivity.this)
                    .setTitle("Crash Log")
                    .setView(sv)
                    .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            ClipboardManager cm =
                                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("crash", log));
                            Toast.makeText(SettingsActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Clear", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            CrashLogger.clearLog(SettingsActivity.this);
                        }
                    })
                    .setNeutralButton("Close", null)
                    .show();
            }
        });
        root.addView(btnLog);

        // ======================================================================
        // EXPANDED SETTINGS (PART 2)
        // ======================================================================

        // ── 12. Browsing ──────────────────────────────────────────────────────
        root.addView(makeTitle("Browsing"));

        // Window size is the single canonical page/window setting.
        root.addView(makeCheckBoxRow("Info overlay default", settingsPrefs.getBoolean("info_overlay_default", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("info_overlay_default", checked); }
        }));

        root.addView(makeCheckBoxRow("Skip videos", settingsPrefs.getBoolean("skip_videos", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) {
                saveBoolean("skip_videos", checked);
                checkBothSkipWarning();
            }
        }));

        root.addView(makeCheckBoxRow("Skip images", settingsPrefs.getBoolean("skip_images", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) {
                saveBoolean("skip_images", checked);
                checkBothSkipWarning();
            }
        }));

        root.addView(makeCheckBoxRow("Show hidden files", settingsPrefs.getBoolean("show_hidden", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("show_hidden", checked); }
        }));

        // ── 13. Sorting Behavior ──────────────────────────────────────────────
        root.addView(makeTitle("Sorting Behavior"));

        root.addView(makeCheckBoxRow("Auto-advance after tag", settingsPrefs.getBoolean("auto_advance_tag", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("auto_advance_tag", checked); }
        }));

        root.addView(makeCheckBoxRow("Auto-advance after flag", settingsPrefs.getBoolean("auto_advance_flag", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("auto_advance_flag", checked); }
        }));

        root.addView(makeConfirmationCheckBoxRow("Confirm delete", "confirm_delete", true, "Disable confirmation warning before permanently deleting files?"));
        root.addView(makeConfirmationCheckBoxRow("Confirm trash", "confirm_trash", true, "Disable confirmation warning before moving files to trash?"));

        // ── 14. Preview ───────────────────────────────────────────────────────
        root.addView(makeTitle("Preview"));

        String[] zoomOptions = {"Fit", "Fill", "100%"};
        String currentZoom = settingsPrefs.getString("default_zoom", "Fit");
        int zoomIdx = 0;
        for (int i = 0; i < zoomOptions.length; i++) {
            if (zoomOptions[i].equalsIgnoreCase(currentZoom)) { zoomIdx = i; break; }
        }
        root.addView(makeSpinnerRow("Default zoom:", zoomOptions, zoomIdx, new OnSpinnerSelectedListener() {
            @Override public void onSelected(String value, int pos) { saveString("default_zoom", value); }
        }));

        boolean precacheOn = settingsPrefs.getBoolean("precache_enabled", true);
        int precacheRad = settingsPrefs.getInt("precache_radius", 2);
        precacheRadiusRow = makeNumericInputRow("Precache radius (1-10):", precacheRad, 1, 10, new OnNumericChangeListener() {
            @Override public void onChange(int val) { saveInt("precache_radius", val); }
        });
        precacheRadiusRow.setVisibility(precacheOn ? View.VISIBLE : View.GONE);

        precacheCheck = (CheckBox) makeCheckBoxRow("Enable precache", precacheOn, new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) {
                if (!refreshingResumeViews) saveBoolean("precache_enabled", checked);
                if (precacheRadiusRow != null) {
                    setVisibilityIfChanged(precacheRadiusRow,
                            checked ? View.VISIBLE : View.GONE);
                }
            }
        }).findViewById(R.id.settingCheckbox);

        root.addView(precacheCheck.getParent() instanceof View ? (View) precacheCheck.getParent() : precacheCheck);
        root.addView(precacheRadiusRow);

        boolean autoplayOn = settingsPrefs.getBoolean("video_autoplay", false);
        boolean loopOn = settingsPrefs.getBoolean("video_loop", false);

        videoLoopRow = makeCheckBoxRow("Video loop", loopOn, new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("video_loop", checked); }
        });
        videoLoopRow.setVisibility(autoplayOn ? View.VISIBLE : View.GONE);

        videoAutoplayCheck = (CheckBox) makeCheckBoxRow("Video autoplay", autoplayOn, new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) {
                if (!refreshingResumeViews) saveBoolean("video_autoplay", checked);
                if (videoLoopRow != null) {
                    setVisibilityIfChanged(videoLoopRow,
                            checked ? View.VISIBLE : View.GONE);
                }
            }
        }).findViewById(R.id.settingCheckbox);

        root.addView(videoAutoplayCheck.getParent() instanceof View ? (View) videoAutoplayCheck.getParent() : videoAutoplayCheck);
        root.addView(videoLoopRow);

        // ── 15. Metadata ──────────────────────────────────────────────────────
        root.addView(makeTitle("Metadata"));

        root.addView(makeCheckBoxRow("Write metadata immediately", settingsPrefs.getBoolean("metadata_write_immediate", true), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("metadata_write_immediate", checked); }
        }));

        root.addView(makeCheckBoxRow("Backup metadata on edit", settingsPrefs.getBoolean("metadata_backup", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("metadata_backup", checked); }
        }));

        root.addView(makeCheckBoxRow("Strip metadata on move", settingsPrefs.getBoolean("strip_on_move", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("strip_on_move", checked); }
        }));

        // ── 16. Duplicates ────────────────────────────────────────────────────
        root.addView(makeTitle("Duplicates"));

        String[] hashOptions = {"MD5", "SHA256"};
        String currentHash = settingsPrefs.getString("hash_algorithm", "MD5");
        int hashIdx = "SHA256".equalsIgnoreCase(currentHash) ? 1 : 0;
        root.addView(makeSpinnerRow("Hash algorithm:", hashOptions, hashIdx, new OnSpinnerSelectedListener() {
            @Override public void onSelected(String value, int pos) { saveString("hash_algorithm", value); }
        }));

        root.addView(makeCheckBoxRow("Auto-skip duplicates", settingsPrefs.getBoolean("auto_skip_dupes", false), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("auto_skip_dupes", checked); }
        }));

        // ── 17. Controls ──────────────────────────────────────────────────────
        root.addView(makeTitle("Controls"));

        root.addView(makeCheckBoxRow("D-Pad enabled", gestureSettings.isDpadEnabled() && settingsPrefs.getBoolean("dpad_enabled", true), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) {
                saveBoolean("dpad_enabled", checked);
                gestureSettings.setDpadEnabled(checked);
            }
        }));

        root.addView(makeCheckBoxRow("Volume keys navigation", settingsPrefs.getBoolean("volume_keys_enabled", true), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("volume_keys_enabled", checked); }
        }));

        root.addView(makeNumericInputRow("Swipe min distance (20-200):", settingsPrefs.getInt("swipe_min_distance", 50), 20, 200, new OnNumericChangeListener() {
            @Override public void onChange(int val) { saveInt("swipe_min_distance", val); }
        }));

        root.addView(makeNumericInputRow("Swipe min velocity (50-1000):", settingsPrefs.getInt("swipe_min_velocity", 200), 50, 1000, new OnNumericChangeListener() {
            @Override public void onChange(int val) { saveInt("swipe_min_velocity", val); }
        }));

        root.addView(makeNumericInputRow("Long press duration ms (200-2000):", settingsPrefs.getInt("long_press_duration", 500), 200, 2000, new OnNumericChangeListener() {
            @Override public void onChange(int val) { saveInt("long_press_duration", val); }
        }));

        // ── 18. Organization ──────────────────────────────────────────────────
        root.addView(makeTitle("Organization"));

        root.addView(makeTextInputRow("Default move path:", settingsPrefs.getString("default_move_path", ""), new OnTextChangeListener() {
            @Override public void onChange(String text) { saveString("default_move_path", text); }
        }));

        List<String> watchedFolders = folderManager.getFolders();
        String defaultTrash = (!watchedFolders.isEmpty()) ? (watchedFolders.get(0) + "/.trash") : "/sdcard/.trash";
        root.addView(makeTextInputRow("Trash path:", settingsPrefs.getString("trash_path", defaultTrash), new OnTextChangeListener() {
            @Override public void onChange(String text) { saveString("trash_path", text); }
        }));

        root.addView(makeNumericInputRow("Max undo history (1-100):", settingsPrefs.getInt("max_undo_history", 20), 1, 100, new OnNumericChangeListener() {
            @Override public void onChange(int val) { saveInt("max_undo_history", val); }
        }));

        // ── 19. Appearance ────────────────────────────────────────────────────
        root.addView(makeTitle("Appearance"));

        String[] themeOptions = {"AppTheme"};
        final boolean[] themeSpinnerReady = {false};
        View themeSpinnerRow = makeSpinnerRow("App theme:", themeOptions, 0,
                new OnSpinnerSelectedListener() {
                    @Override public void onSelected(String value, int pos) {
                        if (isInitializing) return;
                        if (themeSpinnerReady[0] && !value.equals(originalTheme)) recreate();
                    }
                });
        root.addView(themeSpinnerRow);
        themeSpinnerReady[0] = true;

        root.addView(makeCheckBoxRow("Show selection order badges", settingsPrefs.getBoolean("show_seq_labels", true), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("show_seq_labels", checked); }
        }));

        root.addView(makeCheckBoxRow("Show tag count in list", settingsPrefs.getBoolean("show_tag_count", true), new OnCheckedChangeListener() {
            @Override public void onChecked(boolean checked) { saveBoolean("show_tag_count", checked); }
        }));

        addRandomTagFormatSection(root);

        // ── Back Button ───────────────────────────────────────────────────────
        Button btnBack = makeButton("← Back");
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        backLp.topMargin = 48;
        btnBack.setLayoutParams(backLp);
        root.addView(btnBack);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        isInitializing = false;
    }

    // ── Helper methods for Expanded Settings ──────────────────────────────────

    private void saveBoolean(String key, boolean val) {
        if (isInitializing) return;
        boolean ok = settingsPrefs.edit().putBoolean(key, val).commit();
        if (!ok) Toast.makeText(this, "Failed to save " + key, Toast.LENGTH_SHORT).show();
    }

    private void saveInt(String key, int val) {
        if (isInitializing) return;
        boolean ok = settingsPrefs.edit().putInt(key, val).commit();
        if (!ok) Toast.makeText(this, "Failed to save " + key, Toast.LENGTH_SHORT).show();
    }

    private void saveString(String key, String val) {
        if (isInitializing) return;
        boolean ok = settingsPrefs.edit().putString(key, val).commit();
        if (!ok) Toast.makeText(this, "Failed to save " + key, Toast.LENGTH_SHORT).show();
    }

    private void checkBothSkipWarning() {
        boolean skipV = settingsPrefs.getBoolean("skip_videos", false);
        boolean skipI = settingsPrefs.getBoolean("skip_images", false);
        if (skipV && skipI) {
            Toast.makeText(this, "Warning: Both skip videos and skip images are enabled", Toast.LENGTH_SHORT).show();
        }
    }

    private interface OnCheckedChangeListener {
        void onChecked(boolean checked);
    }

    private interface OnNumericChangeListener {
        void onChange(int value);
    }

    private interface OnTextChangeListener {
        void onChange(String text);
    }

    private interface OnSpinnerSelectedListener {
        void onSelected(String value, int pos);
    }

    private View makeCheckBoxRow(String label, boolean initial, final OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = 4;
        rowLp.bottomMargin = 4;
        row.setLayoutParams(rowLp);

        TextView lbl = makeLabel(label);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lbl);

        CheckBox cb = new CheckBox(this);
        cb.setId(R.id.settingCheckbox);
        cb.setChecked(initial);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isInitializing) return;
                listener.onChecked(isChecked);
            }
        });
        row.addView(cb);
        return row;
    }

    private View makeConfirmationCheckBoxRow(final String label, final String key, boolean defaultVal, final String warnMessage) {
        boolean initial = settingsPrefs.getBoolean(key, defaultVal);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = 4;
        rowLp.bottomMargin = 4;
        row.setLayoutParams(rowLp);

        TextView lbl = makeLabel(label);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lbl);

        final CheckBox cb = new CheckBox(this);
        cb.setChecked(initial);
        cb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean target = cb.isChecked();
                if (!target) {
                    // Changing from true to false: warn OK/Cancel
                    new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(label)
                        .setMessage(warnMessage)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                saveBoolean(key, false);
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                cb.setChecked(true);
                                saveBoolean(key, true);
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface dialog) {
                                cb.setChecked(true);
                                saveBoolean(key, true);
                            }
                        })
                        .show();
                } else {
                    saveBoolean(key, true);
                }
            }
        });
        row.addView(cb);
        return row;
    }

    private View makeNumericInputRow(final String label, final int initial, final int min, final int max, final OnNumericChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = 4;
        rowLp.bottomMargin = 4;
        row.setLayoutParams(rowLp);

        TextView lbl = makeLabel(label);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lbl);

        final int[] validVal = {initial};
        final EditText input = new EditText(this);
        input.setText(String.valueOf(initial));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(12f);
        input.setGravity(Gravity.END);
        input.setLayoutParams(new LinearLayout.LayoutParams(
            (int) (80 * getResources().getDisplayMetrics().density),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (isInitializing) return;
                String txt = s.toString().trim();
                if (txt.isEmpty()) return;
                try {
                    int val = Integer.parseInt(txt);
                    if (val >= min && val <= max) {
                        validVal[0] = val;
                        listener.onChange(val);
                    }
                } catch (Exception ignored) {}
            }
        });

        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (isInitializing) return;
                if (!hasFocus) {
                    String txt = input.getText().toString().trim();
                    try {
                        int val = Integer.parseInt(txt);
                        if (val < min || val > max) {
                            Toast.makeText(SettingsActivity.this, "Invalid " + label + " (" + min + "-" + max + ")", Toast.LENGTH_SHORT).show();
                            input.setText(String.valueOf(validVal[0]));
                        } else {
                            validVal[0] = val;
                            listener.onChange(val);
                        }
                    } catch (Exception e) {
                        Toast.makeText(SettingsActivity.this, "Invalid " + label, Toast.LENGTH_SHORT).show();
                        input.setText(String.valueOf(validVal[0]));
                    }
                }
            }
        });

        row.addView(input);
        return row;
    }

    private View makeTextInputRow(String label, String initial, final OnTextChangeListener listener) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        colLp.topMargin = 4;
        colLp.bottomMargin = 8;
        col.setLayoutParams(colLp);

        TextView lbl = makeLabel(label);
        col.addView(lbl);

        final EditText input = new EditText(this);
        input.setText(initial != null ? initial : "");
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(12f);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (isInitializing) return;
                listener.onChange(s.toString().trim());
            }
        });
        col.addView(input);
        return col;
    }

    private View makeSpinnerRow(String label, final String[] options, int initialPos, final OnSpinnerSelectedListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = 4;
        rowLp.bottomMargin = 4;
        row.setLayoutParams(rowLp);

        TextView lbl = makeLabel(label);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lbl);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, options);
        ad.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(ad);
        if (initialPos >= 0 && initialPos < options.length) {
            spinner.setSelection(initialPos);
        }
        final boolean[] spinnerInitialized = {false};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerInitialized[0]) {
                    spinnerInitialized[0] = true;
                    if (isInitializing) return;
                }
                if (isInitializing) return;
                listener.onSelected(options[position], position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        row.addView(spinner);
        return row;
    }

    // ── Multi-gesture row ─────────────────────────────────────────────────────

    interface MultiGestureCallback {
        void set(List<GestureSettings.GestureStep> steps);
    }

    private LinearLayout makeMultiGestureRow(String label,
            List<GestureSettings.GestureStep> current,
            final MultiGestureCallback callback) {

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        colLp.bottomMargin = 16;
        col.setLayoutParams(colLp);
        col.setBackgroundColor(0xFF1A1A2E);
        col.setPadding(8, 8, 8, 8);

        final TextView lbl = makeLabel(label + ": " + gestureSettings.getSummary(current));
        col.addView(lbl);

        final LinearLayout stepsList = new LinearLayout(this);
        stepsList.setOrientation(LinearLayout.VERTICAL);
        col.addView(stepsList);

        final List<GestureSettings.GestureStep> steps = new ArrayList<>(current);
        final String gestureLabel = label;
        renderSteps(stepsList, steps, lbl, gestureLabel, callback);

        Button btnAdd = makeSmallButton("+ Add Step");
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                steps.add(new GestureSettings.GestureStep(
                    GestureSettings.GestureAction.NOTHING, ""));
                callback.set(steps);
                renderSteps(stepsList, steps, lbl, gestureLabel, callback);
            }
        });
        col.addView(btnAdd);

        return col;
    }

    private void renderSteps(final LinearLayout container,
            final List<GestureSettings.GestureStep> steps,
            final TextView summaryLabel,
            final String gestureLabel,
            final MultiGestureCallback callback) {

        container.removeAllViews();

        final List<Tag> allTags = tagManager.getAllTags();
        final String[] allTagNames = new String[allTags.size() + 1];
        allTagNames[0] = "(no tag)";
        for (int i = 0; i < allTags.size(); i++) {
            allTagNames[i + 1] = allTags.get(i).getName();
        }

        for (int i = 0; i < steps.size(); i++) {
            final int idx = i;
            GestureSettings.GestureStep step = steps.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = 4;
            row.setLayoutParams(rowLp);

            // Action spinner
            Spinner actionSpin = new Spinner(this);
            final String[] actionLabels = gestureSettings.getAllLabels();
            ArrayAdapter<String> actionAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, actionLabels);
            actionAd.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
            actionSpin.setAdapter(actionAd);

            String curLabel = gestureSettings.getLabel(step.action);
            for (int j = 0; j < actionLabels.length; j++) {
                if (actionLabels[j].equals(curLabel)) {
                    actionSpin.setSelection(j);
                    break;
                }
            }

            actionSpin.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(actionSpin);

            // Tag search input
            final EditText tagSearch = new EditText(this);
            tagSearch.setHint("Search tag…");
            tagSearch.setTextColor(0xFFFFFFFF);
            tagSearch.setHintTextColor(0xFF666666);
            tagSearch.setTextSize(11f);
            tagSearch.setSingleLine(true);
            tagSearch.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // Tag spinner
            final Spinner tagSpin = new Spinner(this);
            ArrayAdapter<String> tagAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, allTagNames);
            tagAd.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
            tagSpin.setAdapter(tagAd);
            tagSpin.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!step.tag.isEmpty()) {
                for (int j = 1; j < allTagNames.length; j++) {
                    if (allTagNames[j].equals(step.tag)) {
                        tagSpin.setSelection(j);
                        break;
                    }
                }
            }

            boolean isApply = step.action == GestureSettings.GestureAction.APPLY_TAG;
            tagSearch.setVisibility(isApply ? View.VISIBLE : View.GONE);
            tagSpin.setVisibility(isApply ? View.VISIBLE : View.GONE);

            row.addView(tagSearch);
            row.addView(tagSpin);

            tagSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    if (isInitializing) return;
                    String q = s.toString().toLowerCase().trim();
                    List<String> filtered = new ArrayList<>();
                    filtered.add("(no tag)");
                    for (Tag t : allTags) {
                        if (q.isEmpty() || t.getName().toLowerCase().contains(q)) {
                            filtered.add(t.getName());
                        }
                    }
                    ArrayAdapter<String> fa = new ArrayAdapter<>(
                        SettingsActivity.this,
                        android.R.layout.simple_spinner_item,
                        filtered.toArray(new String[0]));
                    fa.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item);
                    tagSpin.setAdapter(fa);
                    tagSpin.setVisibility(View.VISIBLE);
                }
            });

            Button btnRemove = makeSmallButton("✕");
            btnRemove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    steps.remove(idx);
                    callback.set(steps);
                    summaryLabel.setText(gestureLabel + ": "
                        + gestureSettings.getSummary(steps));
                    renderSteps(container, steps, summaryLabel, gestureLabel, callback);
                }
            });
            row.addView(btnRemove);

            final boolean[] actionSpinnerInitialized = {false};
            actionSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (isInitializing) return;
                    if (!actionSpinnerInitialized[0]) {
                        actionSpinnerInitialized[0] = true;
                        return;
                    }
                    GestureSettings.GestureAction action =
                        gestureSettings.fromLabel(actionLabels[pos]);
                    boolean show = action == GestureSettings.GestureAction.APPLY_TAG;
                    tagSearch.setVisibility(show ? View.VISIBLE : View.GONE);
                    tagSpin.setVisibility(show ? View.VISIBLE : View.GONE);
                    steps.get(idx).action = action;
                    callback.set(steps);
                    summaryLabel.setText(gestureLabel + ": "
                        + gestureSettings.getSummary(steps));
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });

            final boolean[] tagSpinnerInitialized = {false};
            tagSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (isInitializing) return;
                    if (!tagSpinnerInitialized[0]) {
                        tagSpinnerInitialized[0] = true;
                        return;
                    }
                    String tag = pos > 0 ? p.getItemAtPosition(pos).toString() : "";
                    steps.get(idx).tag = tag;
                    callback.set(steps);
                    summaryLabel.setText(gestureLabel + ": "
                        + gestureSettings.getSummary(steps));
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });

            container.addView(row);
        }
    }

    // ── Tag list dialogs ──────────────────────────────────────────────────────

    private void showNewListDialog() {
        final EditText input = new EditText(this);
        input.setHint("List name");
        new AlertDialog.Builder(this)
            .setTitle("New Tag List")
            .setView(input)
            .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        tagListManager.createList(name);
                        refreshTagLists();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showEditListDialog(final int listIndex) {
        TagList list = tagListManager.getList(listIndex);
        if (list == null) return;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        final EditText nameInput = new EditText(this);
        nameInput.setText(list.getName());
        layout.addView(makeLabel("List name:"));
        layout.addView(nameInput);

        layout.addView(makeLabel("Tags in list:"));
        final LinearLayout tagRows = new LinearLayout(this);
        tagRows.setOrientation(LinearLayout.VERTICAL);

        for (final String tag : list.getTags()) {
            final LinearLayout tagRow = new LinearLayout(this);
            tagRow.setOrientation(LinearLayout.HORIZONTAL);
            tagRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tagLbl = makeLabel(tag);
            tagLbl.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tagRow.addView(tagLbl);

            Button rm = makeSmallButton("✕");
            rm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tagListManager.removeTagFromList(listIndex, tag);
                    tagRows.removeView(tagRow);
                }
            });
            tagRow.addView(rm);
            tagRows.addView(tagRow);
        }
        layout.addView(tagRows);

        layout.addView(makeLabel("Add tag from library:"));

        EditText tagSearchInput = new EditText(this);
        tagSearchInput.setHint("Search tags…");
        tagSearchInput.setTextColor(0xFFFFFFFF);
        tagSearchInput.setHintTextColor(0xFF666666);
        layout.addView(tagSearchInput);

        final List<Tag> allTags = tagManager.getAllTags();
        String[] tagNames = new String[allTags.size()];
        for (int i = 0; i < allTags.size(); i++) {
            tagNames[i] = allTags.get(i).getName();
        }

        final Spinner tagPicker = new Spinner(this);
        ArrayAdapter<String> tagAd = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, tagNames);
        tagAd.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        tagPicker.setAdapter(tagAd);
        layout.addView(tagPicker);

        tagSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (isInitializing) return;
                String q = s.toString().toLowerCase();
                List<String> filtered = new ArrayList<>();
                for (Tag t : allTags) {
                    if (t.getName().toLowerCase().contains(q))
                        filtered.add(t.getName());
                }
                ArrayAdapter<String> fa = new ArrayAdapter<>(SettingsActivity.this,
                    android.R.layout.simple_spinner_item,
                    filtered.toArray(new String[0]));
                fa.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
                tagPicker.setAdapter(fa);
            }
        });

        Button btnAddToList = makeButton("Add Selected Tag");
        btnAddToList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tagPicker.getSelectedItemPosition() >= 0
                        && tagPicker.getAdapter().getCount() > 0) {
                    String sel = tagPicker.getSelectedItem().toString();
                    tagListManager.addTagToList(listIndex, sel);
                    Toast.makeText(SettingsActivity.this, sel + " added", Toast.LENGTH_SHORT).show();
                }
            }
        });
        layout.addView(btnAddToList);
        
        Button btnBulkAdd = makeButton("Add all tags from scanned files");
        btnBulkAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Tag> scanTags = tagManager.getAllTags();
                List<String> scanNames = new ArrayList<>();
                for (Tag t : scanTags) scanNames.add(t.getName());
                int added = tagListManager.bulkAddToList(listIndex, scanNames);
                Toast.makeText(SettingsActivity.this, added + " tags added", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btnBulkAdd);

        ScrollView sv = new ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle("Edit: " + list.getName())
            .setView(sv)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    String newName = nameInput.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        tagListManager.renameList(listIndex, newName);
                        refreshTagLists();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Folder dialog ─────────────────────────────────────────────────────────

    private void showAddFolderDialog() {
        final EditText input = new EditText(this);
        input.setHint("/sdcard/DCIM");
        new AlertDialog.Builder(this)
            .setTitle("Add Folder")
            .setView(input)
            .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    String path = input.getText().toString().trim();
                    if (!path.isEmpty()) {
                        folderManager.addFolder(path);
                        refreshFolders();
                        Toast.makeText(SettingsActivity.this, "Folder added", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── SeekBar helper ────────────────────────────────────────────────────────

    interface ProgressCallback { void onProgress(int progress); }

    private SeekBar.OnSeekBarChangeListener simple(final ProgressCallback cb) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (isInitializing) return;
                cb.onProgress(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
    }

    // ── Quality helpers ───────────────────────────────────────────────────────

    private String qualityName(int size) {
        if (size <= ThumbnailLoader.QUALITY_LOW)    return "Low (128px)";
        if (size <= ThumbnailLoader.QUALITY_MEDIUM) return "Medium (256px)";
        return "High (512px)";
    }

    private int qualityIndex(int size) {
        if (size <= ThumbnailLoader.QUALITY_LOW)    return 0;
        if (size <= ThumbnailLoader.QUALITY_MEDIUM) return 1;
        return 2;
    }

    private int qualityFromIndex(int index) {
        switch (index) {
            case 0:  return ThumbnailLoader.QUALITY_LOW;
            case 2:  return ThumbnailLoader.QUALITY_HIGH;
            default: return ThumbnailLoader.QUALITY_MEDIUM;
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private interface ToggleHandler {
        void onToggle(boolean enabled);
    }

    private View makeToggleRow(String label, boolean initial, final ToggleHandler handler) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = 4;
        row.setLayoutParams(rowLp);

        TextView lbl = makeLabel(label);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lbl);

        final Button btn = new Button(this);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(12f);
        final boolean[] state = {initial};
        btn.setText(state[0] ? "ON" : "OFF");
        btn.setBackgroundColor(state[0] ? 0xFFE94560 : 0xFF2A2A3E);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                btn.setText(state[0] ? "ON" : "OFF");
                btn.setBackgroundColor(state[0] ? 0xFFE94560 : 0xFF2A2A3E);
                handler.onToggle(state[0]);
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        btn.setLayoutParams(btnLp);
        row.addView(btn);
        return row;
    }

    private TextView makeTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFE94560);
        tv.setTextSize(16f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin    = 24;
        lp.bottomMargin = 8;
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 6;
        tv.setLayoutParams(lp);
        return tv;
    }

    private Button makeButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(0xFF1A1A2E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        btn.setLayoutParams(lp);
        return btn;
    }

    private static String joinTags(List<String> tags) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String tag : tags) {
            if (i++ > 0) sb.append(", ");
            sb.append(tag);
        }
        return sb.toString();
    }

    private Button makeSmallButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(0xFF2A2A3E);
        btn.setTextSize(11f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(4);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void renderMacros(final LinearLayout container) {
        container.removeAllViews();
        List<GestureSettings.GestureMacro> mList = gestureSettings.loadMacros();
        for (int i = 0; i < mList.size(); i++) {
            final int idx = i;
            final GestureSettings.GestureMacro m = mList.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(0xFF1A1A2E);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            row.setLayoutParams(lp);
            row.setPadding(8, 8, 8, 8);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            EditText nameEdit = new EditText(this);
            nameEdit.setText(m.name);
            nameEdit.setTextColor(0xFFFFFFFF);
            nameEdit.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            nameEdit.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (isInitializing) return;
                    m.name = s.toString().trim();
                    List<GestureSettings.GestureMacro> currentList = gestureSettings.loadMacros();
                    for (GestureSettings.GestureMacro currentM : currentList) {
                        if (currentM.id.equals(m.id)) {
                            currentM.name = m.name;
                            break;
                        }
                    }
                    gestureSettings.saveMacros(currentList);
                }
            });
            header.addView(nameEdit);

            TextView stepCount = makeLabel(m.actions.isEmpty()
                    ? "No steps" : "Steps: " + m.actions.size());
            stepCount.setPadding(8, 0, 8, 0);
            header.addView(stepCount);

            Button btnEdit = makeSmallButton("Edit");
            btnEdit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMacroStepBuilder(m, container);
                }
            });
            header.addView(btnEdit);

            Button btnDelete = makeSmallButton("Delete");
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<GestureSettings.GestureMacro> currentList = gestureSettings.loadMacros();
                    for (int j = 0; j < currentList.size(); j++) {
                        if (currentList.get(j).id.equals(m.id)) {
                            currentList.remove(j);
                            break;
                        }
                    }
                    gestureSettings.saveMacros(currentList);
                    renderMacros(container);
                }
            });
            header.addView(btnDelete);

            row.addView(header);
            container.addView(row);
        }
    }

    private void showMacroStepBuilder(final GestureSettings.GestureMacro macro, final LinearLayout macrosContainer) {
        final List<com.mediasorter.organizer.Action> tempActions = new ArrayList<>(macro.actions);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(32, 16, 32, 16);

        final LinearLayout stepsContainer = new LinearLayout(this);
        stepsContainer.setOrientation(LinearLayout.VERTICAL);
        mainLayout.addView(stepsContainer);

        final Runnable renderStepsList = new Runnable() {
            @Override
            public void run() {
                stepsContainer.removeAllViews();
                for (int i = 0; i < tempActions.size(); i++) {
                    final int stepIdx = i;
                    com.mediasorter.organizer.Action act = tempActions.get(i);

                    LinearLayout stepRow = new LinearLayout(SettingsActivity.this);
                    stepRow.setOrientation(LinearLayout.HORIZONTAL);
                    stepRow.setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = 4;
                    stepRow.setLayoutParams(lp);

                    TextView stepLbl = makeLabel((stepIdx + 1) + ". " + act.describe());
                    stepLbl.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                    stepRow.addView(stepLbl);

                    Button btnUp = makeSmallButton("▲");
                    btnUp.setEnabled(stepIdx > 0);
                    btnUp.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            com.mediasorter.organizer.Action current = tempActions.remove(stepIdx);
                            tempActions.add(stepIdx - 1, current);
                            run();
                        }
                    });
                    stepRow.addView(btnUp);

                    Button btnDown = makeSmallButton("▼");
                    btnDown.setEnabled(stepIdx < tempActions.size() - 1);
                    btnDown.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            com.mediasorter.organizer.Action current = tempActions.remove(stepIdx);
                            tempActions.add(stepIdx + 1, current);
                            run();
                        }
                    });
                    stepRow.addView(btnDown);

                    Button btnEditStep = makeSmallButton("Edit");
                    btnEditStep.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            com.mediasorter.organizer.ActionBuilderHelper helper = new com.mediasorter.organizer.ActionBuilderHelper(SettingsActivity.this);
                            helper.showActionPickerDialog(tempActions.get(stepIdx), new com.mediasorter.organizer.ActionBuilderHelper.ActionCallback() {
                                @Override
                                public void onActionSelected(com.mediasorter.organizer.Action updatedAction) {
                                    tempActions.set(stepIdx, updatedAction);
                                    run();
                                }
                            });
                        }
                    });
                    stepRow.addView(btnEditStep);

                    Button btnDel = makeSmallButton("✕");
                    btnDel.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            tempActions.remove(stepIdx);
                            run();
                        }
                    });
                    stepRow.addView(btnDel);

                    stepsContainer.addView(stepRow);
                }
            }
        };

        renderStepsList.run();

        Button btnAddStep = makeSmallButton("+ Add Step");
        btnAddStep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tempActions.size() >= 10) {
                    Toast.makeText(SettingsActivity.this, "Maximum of 10 steps per macro allowed", Toast.LENGTH_SHORT).show();
                    return;
                }
                com.mediasorter.organizer.ActionBuilderHelper helper = new com.mediasorter.organizer.ActionBuilderHelper(SettingsActivity.this);
                helper.showActionPickerDialog(null, new com.mediasorter.organizer.ActionBuilderHelper.ActionCallback() {
                    @Override
                    public void onActionSelected(com.mediasorter.organizer.Action action) {
                        if (action != null) {
                            tempActions.add(action);
                            renderStepsList.run();
                        }
                    }
                });
            }
        });
        mainLayout.addView(btnAddStep);

        ScrollView sv = new ScrollView(this);
        sv.addView(mainLayout);

        new AlertDialog.Builder(this)
            .setTitle("Edit Macro: " + macro.name)
            .setView(sv)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    macro.actions = tempActions;
                    List<GestureSettings.GestureMacro> mList = gestureSettings.loadMacros();
                    for (GestureSettings.GestureMacro existingM : mList) {
                        if (existingM.id.equals(macro.id)) {
                            existingM.actions = macro.actions;
                            break;
                        }
                    }
                    gestureSettings.saveMacros(mList);
                    renderMacros(macrosContainer);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
