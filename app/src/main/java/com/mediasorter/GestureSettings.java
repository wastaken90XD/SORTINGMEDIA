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
        DONE,
        FILTER_CYCLE,
        APPLY_TAG,
        MACRO,
        REPEAT_LAST_MACRO,
        NOTHING
    }

    // Each gesture stores a list of actions + a list of tags
    // Serialized as comma-separated strings in SharedPreferences
    // Format: "APPLY_TAG|Nature,APPLY_TAG|Outdoor,NEXT_FILE|"

    private static final String PREFS            = "gesture_prefs";
    private static final String KEY_SWIPE_LEFT   = "swipe_left_v2";
    private static final String KEY_SWIPE_RIGHT  = "swipe_right_v2";
    private static final String KEY_SWIPE_UP     = "swipe_up_v2";
    private static final String KEY_SWIPE_DOWN   = "swipe_down_v2";
    private static final String KEY_DPAD_UP      = "dpad_up_v2";
    private static final String KEY_DPAD_DOWN    = "dpad_down_v2";
    private static final String KEY_DPAD_LEFT    = "dpad_left_v2";
    private static final String KEY_DPAD_RIGHT   = "dpad_right_v2";
    private static final String KEY_DPAD_CENTER  = "dpad_center_v2";
    private static final String KEY_DPAD_ENABLED = "dpad_enabled";
    private static final String KEY_TAGS_PROMPT = "tags_prompt_enabled";

    private final SharedPreferences prefs;

    public GestureSettings(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        switch (action) {
            case NEXT_FILE:    return "Next File";
            case PREV_FILE:    return "Prev File";
            case QUICK_TAGS:   return "Quick Tags";
            case SKIP:         return "Skip";
            case FLAG:         return "Flag";
            case DONE:         return "Done";
            case FILTER_CYCLE: return "Filter Cycle";
            case APPLY_TAG:    return "Apply Tag";
            case MACRO:        return "Macro";
            case REPEAT_LAST_MACRO: return "Repeat Last Macro";
            case NOTHING:      return "Nothing";
            default:           return "Nothing";
        }
    }

    public String[] getAllLabels() {
        return new String[]{
            "Next File", "Prev File", "Quick Tags",
            "Skip", "Flag", "Done",
            "Filter Cycle", "Apply Tag", "Repeat Last Macro", "Nothing"
        };
    }

    public GestureAction fromLabel(String label) {
        switch (label) {
            case "Next File":    return GestureAction.NEXT_FILE;
            case "Prev File":    return GestureAction.PREV_FILE;
            case "Quick Tags":   return GestureAction.QUICK_TAGS;
            case "Skip":         return GestureAction.SKIP;
            case "Flag":         return GestureAction.FLAG;
            case "Done":         return GestureAction.DONE;
            case "Filter Cycle": return GestureAction.FILTER_CYCLE;
            case "Apply Tag":    return GestureAction.APPLY_TAG;
            case "Repeat Last Macro": return GestureAction.REPEAT_LAST_MACRO;
            case "Macro":        return GestureAction.MACRO;
            default:             return GestureAction.NOTHING;
        }
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
        return prefs.getBoolean(KEY_DPAD_ENABLED, true);
    }

    public void setDpadEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DPAD_ENABLED, enabled).apply();
    }

    // ── Always-prompt-for-tags toggle ─────────────────────────────────────────

    public boolean isTagsPromptEnabled() {
        return prefs.getBoolean(KEY_TAGS_PROMPT, true);
    }

    public void setTagsPromptEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TAGS_PROMPT, enabled).apply();
    }
}
