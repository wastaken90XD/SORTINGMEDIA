package com.mediasorter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** A persisted ordered gesture-input sequence and its assigned result. */
public class GestureCombo {

    public static final int MIN_SEQUENCE_LENGTH = 2;
    public static final int MAX_SEQUENCE_LENGTH = 8;
    public static final int DEFAULT_TIMEOUT_MS = 800;
    public static final int MIN_TIMEOUT_MS = 300;
    public static final int MAX_TIMEOUT_MS = 2000;

    public String id = "";
    public String name = "";
    public final List<String> sequence = new ArrayList<String>();
    /** Ordered actions run when this combo completes. */
    public final List<GestureSettings.GestureStep> actions =
            new ArrayList<GestureSettings.GestureStep>();
    // Legacy single-action fields remain for backward-compatible JSON/imports.
    public String actionId = GestureConstants.ACTION_NOTHING;
    public String macroId = "";
    public int timeoutMs = DEFAULT_TIMEOUT_MS;

    public GestureCombo() {}

    public GestureCombo copy() {
        GestureCombo result = new GestureCombo();
        result.id = id;
        result.name = name;
        result.sequence.addAll(sequence);
        for (GestureSettings.GestureStep step : getActionSteps()) {
            result.actions.add(new GestureSettings.GestureStep(step.action, step.tag));
        }
        result.actionId = actionId;
        result.macroId = macroId;
        result.timeoutMs = timeoutMs;
        return result;
    }

    public List<GestureSettings.GestureStep> getActionSteps() {
        if (!actions.isEmpty()) return new ArrayList<GestureSettings.GestureStep>(actions);
        List<GestureSettings.GestureStep> legacy = new ArrayList<GestureSettings.GestureStep>();
        if (macroId != null && !macroId.isEmpty()) {
            legacy.add(new GestureSettings.GestureStep(
                    GestureSettings.GestureAction.MACRO, macroId));
        } else if (actionId != null && !actionId.isEmpty()) {
            try {
                legacy.add(new GestureSettings.GestureStep(
                        GestureSettings.GestureAction.valueOf(actionId), ""));
            } catch (Exception ignored) {
                legacy.add(new GestureSettings.GestureStep(
                        GestureSettings.GestureAction.NOTHING, ""));
            }
        }
        return legacy;
    }

    public void setActionSteps(List<GestureSettings.GestureStep> steps) {
        actions.clear();
        if (steps != null) {
            for (GestureSettings.GestureStep step : steps) {
                if (step != null) actions.add(new GestureSettings.GestureStep(step.action, step.tag));
            }
        }
        if (actions.size() == 1) {
            GestureSettings.GestureStep step = actions.get(0);
            actionId = step.action == null ? GestureConstants.ACTION_NOTHING : step.action.name();
            macroId = step.action == GestureSettings.GestureAction.MACRO ? step.tag : "";
        } else {
            actionId = GestureConstants.ACTION_NOTHING;
            macroId = "";
        }
    }

    public boolean isMacro() {
        List<GestureSettings.GestureStep> steps = getActionSteps();
        return steps.size() == 1 && steps.get(0).action == GestureSettings.GestureAction.MACRO;
    }

    public boolean hasValidSequence() {
        if (sequence.size() < MIN_SEQUENCE_LENGTH || sequence.size() > MAX_SEQUENCE_LENGTH) {
            return false;
        }
        for (String input : sequence) {
            if (!GestureConstants.isComboInputId(input)) return false;
        }
        return true;
    }

    public void clampTimeout() {
        timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
    }

    public JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", id == null ? "" : id);
        object.put("name", name == null ? "" : name);
        JSONArray inputs = new JSONArray();
        for (String input : sequence) inputs.put(input);
        object.put("sequence", inputs);
        object.put("timeout", timeoutMs);
        JSONArray actionsJson = new JSONArray();
        for (GestureSettings.GestureStep step : getActionSteps()) {
            JSONObject action = new JSONObject();
            action.put("action", step.action == null
                    ? GestureConstants.ACTION_NOTHING : step.action.name());
            action.put("tag", step.tag == null ? "" : step.tag);
            actionsJson.put(action);
        }
        object.put("actions", actionsJson);
        if (isMacro()) object.put("macro", macroId);
        else object.put("action", actionId == null ? GestureConstants.ACTION_NOTHING : actionId);
        return object;
    }

    public static GestureCombo fromJson(JSONObject object) {
        if (object == null) return null;
        GestureCombo combo = new GestureCombo();
        combo.id = object.optString("id", "");
        combo.name = object.optString("name", "");
        combo.timeoutMs = object.optInt("timeout", DEFAULT_TIMEOUT_MS);
        combo.clampTimeout();
        if (object.has("macro") || object.has("macroId")) {
            combo.macroId = object.has("macro")
                    ? object.optString("macro", "") : object.optString("macroId", "");
            combo.actionId = GestureConstants.ACTION_MACRO;
        } else {
            combo.actionId = object.has("action")
                    ? object.optString("action", GestureConstants.ACTION_NOTHING)
                    : object.optString("actionId", GestureConstants.ACTION_NOTHING);
            combo.macroId = "";
        }
        JSONArray actionArray = object.optJSONArray("actions");
        if (actionArray != null) {
            for (int i = 0; i < actionArray.length() && combo.actions.size() < 10; i++) {
                JSONObject action = actionArray.optJSONObject(i);
                if (action == null) continue;
                GestureSettings.GestureAction parsed = GestureSettings.GestureAction.NOTHING;
                try {
                    parsed = GestureSettings.GestureAction.valueOf(
                            action.optString("action", GestureConstants.ACTION_NOTHING));
                } catch (Exception ignored) {}
                combo.actions.add(new GestureSettings.GestureStep(
                        parsed, action.optString("tag", "")));
            }
        }
        JSONArray inputs = object.optJSONArray("sequence");
        if (inputs != null) {
            for (int i = 0; i < inputs.length() && combo.sequence.size() < MAX_SEQUENCE_LENGTH; i++) {
                String input = inputs.optString(i, "");
                if (GestureConstants.isComboInputId(input)) combo.sequence.add(input);
            }
        }
        if (!combo.actions.isEmpty() && combo.actions.size() == 1) {
            combo.actionId = combo.actions.get(0).action.name();
            combo.macroId = combo.actions.get(0).action == GestureSettings.GestureAction.MACRO
                    ? combo.actions.get(0).tag : "";
        }
        return combo;
    }
}
