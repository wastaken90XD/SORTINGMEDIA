package com.mediasorter.organizer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class RuleSerializer {
    private static final String PREFS = "organizer_prefs";
    private static final String KEY   = "rules";

    public static List<Rule> loadRules(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY, "[]");
        List<Rule> rules = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Rule r = new Rule();
                r.name = obj.getString("name");
                r.enabled = obj.optBoolean("enabled", true);
                // reconstruct conditions and action – simplified for this example
                // (full deserialization would be long; we'll add later)
                rules.add(r);
            }
        } catch (Exception ignored) {}
        return rules;
    }

    public static void saveRules(Context context, List<Rule> rules) {
        JSONArray arr = new JSONArray();
        for (Rule r : rules) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("name", r.name);
                obj.put("enabled", r.enabled);
                // serialize conditions/action
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}
