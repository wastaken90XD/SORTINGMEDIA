package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GestureSettings {

    public enum GestureAction {
        NEXT_FILE,
        PREV_FILE,
        QUICK_TAGS,
        SKIP,
        FLAG,
        // Kept for rules/macros and old imported mappings. It is deliberately
        // omitted from the gesture picker and from all gesture defaults.
        DONE,
        FILTER_CYCLE,
        APPLY_TAG,
        MACRO,
        REPEAT_LAST_MACRO,
        OPEN_GALLERY,
        OPEN_DASHBOARD,
        OPEN_RULES,
        OPEN_COLOR_ANALYZER,
        OPEN_DUPLICATE_FINDER,
        OPEN_SETTINGS,
        OPEN_ABOUT,
        GALLERY_SETTINGS,
        EXPORT_SETTINGS,
        TRIGGER_RESCAN,
        QUICK_RANDOM_TAG,
        SURPRISE_ME,
        CYCLE_TAG_PRESETS,
        NEXT_PAGE,
        PREVIOUS_PAGE,
        JUMP_FIRST,
        JUMP_LAST,
        TOGGLE_STATS_BAR,
        TOGGLE_INFO_OVERLAY,
        TOGGLE_SELECTION_CURRENT,
        SWEEP_SELECT_FORWARD,
        SWEEP_SELECT_BACKWARD,
        SELECT_ALL,
        DESELECT_ALL,
        TOGGLE_GALLERY,
        SORT_PICKER,
        FILTER_PICKER,
        GROUP_PICKER,
        UNDO,
        DELETE,
        SCAN,
        TOGGLE_TAG_PANEL,
        CYCLE_TAG_BAR_SORT,
        NOTHING
    }

    // Each gesture stores a list of actions + a list of tags
    // Serialized as comma-separated strings in SharedPreferences
    // Format: "APPLY_TAG|Nature,APPLY_TAG|Outdoor,NEXT_FILE|"

    private static final String PREFS            = GestureConstants.PREFS;
    private static final String KEY_SWIPE_LEFT   = GestureConstants.INPUT_SWIPE_LEFT;
    private static final String KEY_SWIPE_RIGHT  = GestureConstants.INPUT_SWIPE_RIGHT;
    private static final String KEY_SWIPE_UP     = GestureConstants.INPUT_SWIPE_UP;
    private static final String KEY_SWIPE_DOWN   = GestureConstants.INPUT_SWIPE_DOWN;
    private static final String KEY_DPAD_UP      = GestureConstants.INPUT_DPAD_UP;
    private static final String KEY_DPAD_DOWN    = GestureConstants.INPUT_DPAD_DOWN;
    private static final String KEY_DPAD_LEFT    = GestureConstants.INPUT_DPAD_LEFT;
    private static final String KEY_DPAD_RIGHT   = GestureConstants.INPUT_DPAD_RIGHT;
    private static final String KEY_DPAD_CENTER  = GestureConstants.INPUT_DPAD_CENTER;
    private static final String KEY_VOLUME_UP    = GestureConstants.INPUT_VOLUME_UP;
    private static final String KEY_VOLUME_DOWN  = GestureConstants.INPUT_VOLUME_DOWN;
    private static final String KEY_VOLUME_UP_LONG   = GestureConstants.INPUT_VOLUME_UP_LONG;
    private static final String KEY_VOLUME_DOWN_LONG = GestureConstants.INPUT_VOLUME_DOWN_LONG;
    private static final String KEY_DPAD_ENABLED = "dpad_enabled";
    private static final String KEY_TAGS_PROMPT = "tags_prompt_enabled";
    public static final String KEY_LAST_MACRO = "last_macro_id";

    private final SharedPreferences prefs;
    private final SharedPreferences settingsPrefs;

    public GestureSettings(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // dpad_enabled is a UI/control setting, so it has one canonical home
        // in settings_prefs rather than being duplicated in gesture_prefs.
        this.settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
        migrateLegacyControlKeys();
    }

    private void migrateLegacyControlKeys() {
        if (!settingsPrefs.contains(KEY_DPAD_ENABLED) && prefs.contains(KEY_DPAD_ENABLED)) {
            boolean value = prefs.getBoolean(KEY_DPAD_ENABLED, true);
            settingsPrefs.edit().putBoolean(KEY_DPAD_ENABLED, value).commit();
        }
        if (prefs.contains(KEY_DPAD_ENABLED)) {
            prefs.edit().remove(KEY_DPAD_ENABLED).commit();
        }
        migrateLegacySwipeDefault("swipe_left_default", KEY_SWIPE_LEFT);
        migrateLegacySwipeDefault("swipe_right_default", KEY_SWIPE_RIGHT);
    }

    private void migrateLegacySwipeDefault(String oldKey, String inputKey) {
        if (settingsPrefs.contains(oldKey)) {
            if (!prefs.contains(inputKey)) {
                int value = settingsPrefs.getInt(oldKey, 0);
                GestureAction action = value == 1 ? GestureAction.SKIP
                        : value == 2 ? GestureAction.FLAG
                        : value == 3 ? GestureAction.NOTHING
                        : value == 4 ? GestureAction.APPLY_TAG
                        : GestureAction.NEXT_FILE;
                prefs.edit().putString(inputKey, action.name() + "|").commit();
            }
            settingsPrefs.edit().remove(oldKey).commit();
        }
    }

    public static class GestureMacro {
        public String id;
        public String name;
        public List<com.mediasorter.organizer.Action> actions = new ArrayList<>();
    }

    public List<GestureMacro> loadMacros() {
        List<GestureMacro> list = new ArrayList<>();
        String json = prefs.getString("gesture_macros", "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                GestureMacro m = new GestureMacro();
                m.id = obj.optString("id", "");
                m.name = obj.optString("name", "");
                if (m.name == null || m.name.trim().isEmpty()) {
                    m.name = "Macro " + m.id;
                }
                m.actions = new ArrayList<>();
                org.json.JSONArray actArr = obj.optJSONArray("actions");
                if (actArr != null) {
                    for (int j = 0; j < actArr.length(); j++) {
                        com.mediasorter.organizer.Action act = com.mediasorter.organizer.RuleSerializer.deserializeAction(actArr.getJSONObject(j));
                        if (act != null) {
                            m.actions.add(act);
                        }
                    }
                }
                list.add(m);
            }
        } catch (Exception ignored) {}
        return list;
    }

    public void saveMacros(List<GestureMacro> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (GestureMacro m : list) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("id", m.id);
                obj.put("name", m.name);
                org.json.JSONArray actArr = new org.json.JSONArray();
                for (com.mediasorter.organizer.Action act : m.actions) {
                    actArr.put(com.mediasorter.organizer.RuleSerializer.serializeAction(act));
                }
                obj.put("actions", actArr);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString("gesture_macros", arr.toString()).apply();
    }

    public GestureMacro getMacro(String id) {
        if (id == null || id.isEmpty()) return null;
        for (GestureMacro m : loadMacros()) {
            if (id.equals(m.id)) return m;
        }
        return null;
    }

    public boolean macroHasSteps(String id) {
        GestureMacro macro = getMacro(id);
        return macro != null && macro.actions != null && !macro.actions.isEmpty();
    }

    public List<GestureMacro> getUsableMacros() {
        List<GestureMacro> result = new ArrayList<>();
        for (GestureMacro macro : loadMacros()) {
            if (macro != null && macro.actions != null && !macro.actions.isEmpty()) result.add(macro);
        }
        return result;
    }

    // ── Action list model ─────────────────────────────────────────────────────

    public static class GestureStep {
        public GestureAction action;
        public String        tag; // only used when action == APPLY_TAG

        public GestureStep(GestureAction action, String tag) {
            this.action = action;
            this.tag    = tag != null ? tag : "";
        }

        public String serialize() {
            return action.name() + "|" + tag;
        }

        public static GestureStep deserialize(String s) {
            String[] parts = s.split("\\|", 2);
            GestureAction action = GestureAction.NOTHING;
            String tag = "";
            try { action = GestureAction.valueOf(parts[0]); } catch (Exception ignored) {}
            if (parts.length > 1) tag = parts[1];
            return new GestureStep(action, tag);
        }
    }

    // ── Serialize / Deserialize list ──────────────────────────────────────────

    private String serialize(List<GestureStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (GestureStep step : steps) {
            if (sb.length() > 0) sb.append(",");
            sb.append(step.serialize());
        }
        return sb.toString();
    }

    private List<GestureStep> deserialize(String raw, String defaultAction) {
        List<GestureStep> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            result.add(new GestureStep(
                GestureAction.valueOf(defaultAction), ""));
            return result;
        }
        for (String part : raw.split(",")) {
            if (!part.isEmpty()) result.add(GestureStep.deserialize(part));
        }
        return result;
    }

    // ── Swipe getters ─────────────────────────────────────────────────────────

    public List<GestureStep> getLeft() {
        return deserialize(prefs.getString(KEY_SWIPE_LEFT, ""),
            GestureAction.NEXT_FILE.name());
    }

    public List<GestureStep> getRight() {
        return deserialize(prefs.getString(KEY_SWIPE_RIGHT, ""),
            GestureAction.PREV_FILE.name());
    }

    public List<GestureStep> getUp() {
        return deserialize(prefs.getString(KEY_SWIPE_UP, ""),
            GestureAction.NOTHING.name());
    }

    public List<GestureStep> getDown() {
        return deserialize(prefs.getString(KEY_SWIPE_DOWN, ""),
            GestureAction.NOTHING.name());
    }

    // ── D-pad getters ─────────────────────────────────────────────────────────

    public List<GestureStep> getDpadUp() {
        return deserialize(prefs.getString(KEY_DPAD_UP, ""),
            GestureAction.APPLY_TAG.name());
    }

    public List<GestureStep> getDpadDown() {
        return deserialize(prefs.getString(KEY_DPAD_DOWN, ""),
            GestureAction.APPLY_TAG.name());
    }

    public List<GestureStep> getDpadLeft() {
        return deserialize(prefs.getString(KEY_DPAD_LEFT, ""),
            GestureAction.PREV_FILE.name());
    }

    public List<GestureStep> getDpadRight() {
        return deserialize(prefs.getString(KEY_DPAD_RIGHT, ""),
            GestureAction.NEXT_FILE.name());
    }

    public List<GestureStep> getDpadCenter() {
        return deserialize(prefs.getString(KEY_DPAD_CENTER, ""),
            GestureAction.APPLY_TAG.name());
    }

    // ── Volume, tap and hardware mappings ─────────────────────────────────────

    public List<GestureStep> getVolumeUp() {
        return deserialize(prefs.getString(KEY_VOLUME_UP, ""),
                GestureAction.NEXT_FILE.name());
    }

    public List<GestureStep> getVolumeDown() {
        return deserialize(prefs.getString(KEY_VOLUME_DOWN, ""),
                GestureAction.PREV_FILE.name());
    }

    public List<GestureStep> getVolumeUpLong() {
        return deserialize(prefs.getString(KEY_VOLUME_UP_LONG, ""),
                GestureAction.NOTHING.name());
    }

    public List<GestureStep> getVolumeDownLong() {
        return deserialize(prefs.getString(KEY_VOLUME_DOWN_LONG, ""),
                GestureAction.NOTHING.name());
    }

    /** Return a defensive copy of the mapping for any registered input id. */
    public List<GestureStep> getSteps(String inputId) {
        if (GestureConstants.INPUT_SWIPE_LEFT.equals(inputId)) return getLeft();
        if (GestureConstants.INPUT_SWIPE_RIGHT.equals(inputId)) return getRight();
        if (GestureConstants.INPUT_SWIPE_UP.equals(inputId)) return getUp();
        if (GestureConstants.INPUT_SWIPE_DOWN.equals(inputId)) return getDown();
        if (GestureConstants.INPUT_DPAD_UP.equals(inputId)) return getDpadUp();
        if (GestureConstants.INPUT_DPAD_DOWN.equals(inputId)) return getDpadDown();
        if (GestureConstants.INPUT_DPAD_LEFT.equals(inputId)) return getDpadLeft();
        if (GestureConstants.INPUT_DPAD_RIGHT.equals(inputId)) return getDpadRight();
        if (GestureConstants.INPUT_DPAD_CENTER.equals(inputId)) return getDpadCenter();
        if (GestureConstants.INPUT_VOLUME_UP.equals(inputId)) return getVolumeUp();
        if (GestureConstants.INPUT_VOLUME_DOWN.equals(inputId)) return getVolumeDown();
        if (GestureConstants.INPUT_VOLUME_UP_LONG.equals(inputId)) return getVolumeUpLong();
        if (GestureConstants.INPUT_VOLUME_DOWN_LONG.equals(inputId)) return getVolumeDownLong();
        return deserialize(prefs.getString(inputId, ""), GestureAction.NOTHING.name());
    }

    public void setSteps(String inputId, List<GestureStep> steps) {
        if (inputId == null || !GestureConstants.getInputIds().contains(inputId)) return;
        prefs.edit().putString(inputId, serialize(steps == null
                ? new ArrayList<GestureStep>() : steps)).apply();
    }

    public void setVolumeUp(List<GestureStep> steps) { setSteps(KEY_VOLUME_UP, steps); }
    public void setVolumeDown(List<GestureStep> steps) { setSteps(KEY_VOLUME_DOWN, steps); }
    public void setVolumeUpLong(List<GestureStep> steps) { setSteps(KEY_VOLUME_UP_LONG, steps); }
    public void setVolumeDownLong(List<GestureStep> steps) { setSteps(KEY_VOLUME_DOWN_LONG, steps); }

    // ── Swipe setters ─────────────────────────────────────────────────────────

    public void setLeft(List<GestureStep> steps) {
        prefs.edit().putString(KEY_SWIPE_LEFT, serialize(steps)).apply();
    }

    public void setRight(List<GestureStep> steps) {
        prefs.edit().putString(KEY_SWIPE_RIGHT, serialize(steps)).apply();
    }

    public void setUp(List<GestureStep> steps) {
        prefs.edit().putString(KEY_SWIPE_UP, serialize(steps)).apply();
    }

    public void setDown(List<GestureStep> steps) {
        prefs.edit().putString(KEY_SWIPE_DOWN, serialize(steps)).apply();
    }

    // ── D-pad setters ─────────────────────────────────────────────────────────

    public void setDpadUp(List<GestureStep> steps) {
        prefs.edit().putString(KEY_DPAD_UP, serialize(steps)).apply();
    }

    public void setDpadDown(List<GestureStep> steps) {
        prefs.edit().putString(KEY_DPAD_DOWN, serialize(steps)).apply();
    }

    public void setDpadLeft(List<GestureStep> steps) {
        prefs.edit().putString(KEY_DPAD_LEFT, serialize(steps)).apply();
    }

    public void setDpadRight(List<GestureStep> steps) {
        prefs.edit().putString(KEY_DPAD_RIGHT, serialize(steps)).apply();
    }

    public void setDpadCenter(List<GestureStep> steps) {
        prefs.edit().putString(KEY_DPAD_CENTER, serialize(steps)).apply();
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    public String getLabel(GestureAction action) {
        if (action == null) return "None";
        return GestureConstants.label(action.name());
    }

    /** Labels used by the legacy multi-step editor; DONE is not exposed. */
    public String[] getAllLabels() {
        List<String> ids = GestureConstants.getPickerActionIds();
        String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) labels[i] = GestureConstants.label(ids.get(i));
        return labels;
    }

    public GestureAction fromLabel(String label) {
        if (label == null) return GestureAction.NOTHING;
        List<String> ids = GestureConstants.getAllActionIds();
        for (String id : ids) {
            if (GestureConstants.label(id).equals(label)) {
                try { return GestureAction.valueOf(id); }
                catch (Exception ignored) { return GestureAction.NOTHING; }
            }
        }
        return GestureAction.NOTHING;
    }

    // ── Summary label for a step list ─────────────────────────────────────────

    public String getSummary(List<GestureStep> steps) {
        if (steps.isEmpty()) return "Nothing";
        StringBuilder sb = new StringBuilder();
        for (GestureStep step : steps) {
            if (sb.length() > 0) sb.append(" + ");
            if (step.action == GestureAction.MACRO) {
                GestureMacro m = getMacro(step.tag);
                sb.append(m != null ? m.name : "Macro (" + step.tag + ")");
            } else if (step.action == GestureAction.REPEAT_LAST_MACRO) {
                sb.append("Repeat Last Macro");
            } else if (step.action == GestureAction.APPLY_TAG && !step.tag.isEmpty()) {
                sb.append(step.tag);
            } else {
                sb.append(getLabel(step.action));
            }
        }
        return sb.toString();
    }

    // ── D-pad enable / disable toggle ─────────────────────────────────────────

    public boolean isDpadEnabled() {
        return settingsPrefs.getBoolean(KEY_DPAD_ENABLED, true);
    }

    public void setDpadEnabled(boolean enabled) {
        settingsPrefs.edit().putBoolean(KEY_DPAD_ENABLED, enabled).apply();
        prefs.edit().remove(KEY_DPAD_ENABLED).apply();
    }

    // ── Always-prompt-for-tags toggle ─────────────────────────────────────────

    public boolean isTagsPromptEnabled() {
        return prefs.getBoolean(KEY_TAGS_PROMPT, true);
    }

    public void setTagsPromptEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TAGS_PROMPT, enabled).apply();
    }

    public void setLastRunMacroId(String id) {
        prefs.edit().putString(KEY_LAST_MACRO, id == null ? "" : id).apply();
    }

    public String getLastRunMacroId() {
        return prefs.getString(KEY_LAST_MACRO, "");
    }

    /** Restore all input mappings to the defaults in GestureConstants. */
    public void resetToDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String input : GestureConstants.getInputIds()) {
            String action = GestureConstants.defaultActionForInput(input);
            editor.putString(input, action + "|");
        }
        editor.putBoolean(KEY_TAGS_PROMPT, true);
        editor.apply();
        settingsPrefs.edit().putBoolean(KEY_DPAD_ENABLED, true).apply();
    }
}
