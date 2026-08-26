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
    public String actionId = GestureConstants.ACTION_NOTHING;
    public String macroId = "";
    public int timeoutMs = DEFAULT_TIMEOUT_MS;

    public GestureCombo() {}

    public GestureCombo copy() {
        GestureCombo result = new GestureCombo();
        result.id = id;
        result.name = name;
        result.sequence.addAll(sequence);
        result.actionId = actionId;
        result.macroId = macroId;
        result.timeoutMs = timeoutMs;
        return result;
    }

    public boolean isMacro() {
        return macroId != null && !macroId.isEmpty();
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
        JSONArray inputs = object.optJSONArray("sequence");
        if (inputs != null) {
            for (int i = 0; i < inputs.length() && combo.sequence.size() < MAX_SEQUENCE_LENGTH; i++) {
                String input = inputs.optString(i, "");
                if (GestureConstants.isComboInputId(input)) combo.sequence.add(input);
            }
        }
        return combo;
    }
}
