package com.mediasorter.organizer;

import android.content.Context;
import android.content.SharedPreferences;
import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class RuleSerializer {

    private static final String PREFS = "organizer_prefs";
    private static final String KEY   = "rules";

    // ── Save ──────────────────────────────────────────────────────────────────

    public static void saveRules(Context context, List<Rule> rules) {
        JSONArray arr = new JSONArray();
        for (Rule r : rules) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name",    r.name);
                obj.put("enabled", r.enabled);

                // Conditions
                JSONArray conds = new JSONArray();
                for (Condition c : r.conditions) {
                    JSONObject co = serializeCondition(c);
                    if (co != null) conds.put(co);
                }
                obj.put("conditions", conds);

                // Action
                if (r.action != null) {
                    JSONObject ao = serializeAction(r.action);
                    if (ao != null) obj.put("action", ao);
                }

                arr.put(obj);
            } catch (Exception ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    public static List<Rule> loadRules(Context context) {
        SharedPreferences prefs =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY, "[]");
        List<Rule> rules = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Rule r = new Rule();
                r.name    = obj.optString("name", "Rule " + i);
                r.enabled = obj.optBoolean("enabled", true);

                // Conditions
                JSONArray conds = obj.optJSONArray("conditions");
                if (conds != null) {
                    for (int j = 0; j < conds.length(); j++) {
                        Condition c = deserializeCondition(conds.getJSONObject(j));
                        if (c != null) r.conditions.add(c);
                    }
                }

                // Action
                if (obj.has("action")) {
                    r.action = deserializeAction(obj.getJSONObject("action"));
                }

                rules.add(r);
            }
        } catch (Exception ignored) {}
        return rules;
    }

    // ── Condition serialization ───────────────────────────────────────────────

    private static JSONObject serializeCondition(Condition c) {
        try {
            JSONObject o = new JSONObject();
            if (c instanceof TagCondition) {
                TagCondition tc = (TagCondition) c;
                o.put("type", "tag");
                o.put("tags", listToJsonArray(tc.tags));
                o.put("matchAny", tc.matchAny);
                o.put("negate",   tc.negate);

            } else if (c instanceof NameCondition) {
                NameCondition nc = (NameCondition) c;
                o.put("type",    "name");
                o.put("pattern", nc.pattern);
                o.put("match",   nc.type.name());
                o.put("negate",  nc.negate);

            } else if (c instanceof TypeCondition) {
                TypeCondition tc = (TypeCondition) c;
                o.put("type",       "filetype");
                o.put("fileType",   tc.type.name());
                o.put("negate",     tc.negate);

            } else if (c instanceof SizeCondition) {
                SizeCondition sc = (SizeCondition) c;
                o.put("type",        "size");
                o.put("threshold",   sc.threshold);
                o.put("greaterThan", sc.greaterThan);
                o.put("negate",      sc.negate);

            } else if (c instanceof DateCondition) {
                DateCondition dc = (DateCondition) c;
                o.put("type",      "date");
                o.put("days",      dc.days);
                o.put("olderThan", dc.olderThan);
                o.put("negate",    dc.negate);

            } else if (c instanceof StatusCondition) {
                StatusCondition sc = (StatusCondition) c;
                o.put("type",   "status");
                o.put("status", sc.status.name());
                o.put("negate", sc.negate);

            } else if (c instanceof FolderCondition) {
                FolderCondition fc = (FolderCondition) c;
                o.put("type",   "folder");
                o.put("folder", fc.folderPath);
                o.put("negate", fc.negate);
            }
            return o;
        } catch (Exception e) { return null; }
    }

    private static Condition deserializeCondition(JSONObject o) {
        try {
            String type = o.getString("type");
            switch (type) {
                case "tag": {
                    List<String> tags = jsonArrayToList(o.getJSONArray("tags"));
                    boolean matchAny  = o.optBoolean("matchAny", true);
                    boolean negate    = o.optBoolean("negate", false);
                    return Condition.tagCondition(tags, matchAny, negate);
                }
                case "name": {
                    String pattern    = o.getString("pattern");
                    Condition.MatchType mt =
                        Condition.MatchType.valueOf(o.optString("match", "CONTAINS"));
                    boolean negate    = o.optBoolean("negate", false);
                    return Condition.nameCondition(pattern, mt, negate);
                }
                case "filetype": {
                    MediaFile.Type ft =
                        MediaFile.Type.valueOf(o.getString("fileType"));
                    boolean negate    = o.optBoolean("negate", false);
                    return Condition.typeCondition(ft, negate);
                }
                case "size": {
                    long threshold    = o.getLong("threshold");
                    boolean gt        = o.optBoolean("greaterThan", true);
                    boolean negate    = o.optBoolean("negate", false);
                    return Condition.sizeCondition(threshold, gt, negate);
                }
                case "date": {
                    int days          = o.getInt("days");
                    boolean olderThan = o.optBoolean("olderThan", true);
                    boolean negate    = o.optBoolean("negate", false);
                    return Condition.dateCondition(days, olderThan, negate);
                }
                case "status": {
                    FileStatus.Status st =
                        FileStatus.Status.valueOf(o.getString("status"));
                    boolean negate    = o.optBoolean("negate", false);
                    return Condition.statusCondition(st, negate);
                }
                case "folder": {
                    String folder     = o.getString("folder");
                    boolean negate    = o.optBoolean("negate", false);
                    return Condition.folderCondition(folder, negate);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Action serialization ──────────────────────────────────────────────────

    private static JSONObject serializeAction(Action a) {
        try {
            JSONObject o = new JSONObject();
            if (a instanceof MoveAction) {
                MoveAction ma = (MoveAction) a;
                o.put("type",       "move");
                o.put("destFolder", ma.destFolder);
                o.put("conflict",   ma.conflict.name());

            } else if (a instanceof DeleteAction) {
                DeleteAction da = (DeleteAction) a;
                o.put("type",        "delete");
                o.put("useTrash",    da.useTrash);
                o.put("trashFolder", da.trashFolder != null ? da.trashFolder : "");

            } else if (a instanceof TagAction) {
                TagAction ta = (TagAction) a;
                o.put("type",          "tag");
                o.put("tagsToAdd",     listToJsonArray(ta.tagsToAdd));
                o.put("tagsToRemove",  listToJsonArray(ta.tagsToRemove));

            } else if (a instanceof StatusAction) {
                StatusAction sa = (StatusAction) a;
                o.put("type",   "status");
                o.put("status", sa.status != null ? sa.status.name() : "NONE");
                o.put("clear",  sa.clear);

            } else if (a instanceof RenameAction) {
                RenameAction ra = (RenameAction) a;
                o.put("type",    "rename");
                o.put("pattern", ra.pattern);
            }
            return o;
        } catch (Exception e) { return null; }
    }

    private static Action deserializeAction(JSONObject o) {
        try {
            String type = o.getString("type");
            switch (type) {
                case "move": {
                    String dest      = o.getString("destFolder");
                    Action.Conflict c =
                        Action.Conflict.valueOf(o.optString("conflict", "SKIP"));
                    return Action.moveAction(dest, c);
                }
                case "delete": {
                    boolean useTrash    = o.optBoolean("useTrash", false);
                    String trashFolder  = o.optString("trashFolder", null);
                    return Action.deleteAction(useTrash,
                        trashFolder != null && !trashFolder.isEmpty()
                            ? trashFolder : null);
                }
                case "tag": {
                    List<String> add    =
                        jsonArrayToList(o.optJSONArray("tagsToAdd"));
                    List<String> remove =
                        jsonArrayToList(o.optJSONArray("tagsToRemove"));
                    return Action.tagAction(add, remove);
                }
                case "status": {
                    boolean clear = o.optBoolean("clear", false);
                    FileStatus.Status st = clear ? FileStatus.Status.NONE
                        : FileStatus.Status.valueOf(o.optString("status", "NONE"));
                    return Action.statusAction(st, clear);
                }
                case "rename": {
                    String pattern = o.getString("pattern");
                    return Action.renameAction(pattern);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JSONArray listToJsonArray(List<String> list) {
        JSONArray arr = new JSONArray();
        if (list != null) for (String s : list) arr.put(s);
        return arr;
    }

    private static List<String> jsonArrayToList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            try { list.add(arr.getString(i)); } catch (Exception ignored) {}
        }
        return list;
    }
}
