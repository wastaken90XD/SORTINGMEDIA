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
                r.name = obj.optString("name", "Unnamed");
                r.enabled = obj.optBoolean("enabled", true);

                // Conditions
                r.conditions = new ArrayList<>();
                if (obj.has("conditions")) {
                    JSONArray condArr = obj.getJSONArray("conditions");
                    for (int j = 0; j < condArr.length(); j++) {
                        JSONObject cObj = condArr.getJSONObject(j);
                        String cType = cObj.optString("condType", "");
                        Condition cond = null;
                        if ("TAG".equals(cType)) {
                            JSONArray tagsArr = cObj.optJSONArray("tags");
                            List<String> tags = new ArrayList<>();
                            if (tagsArr != null) {
                                for (int k = 0; k < tagsArr.length(); k++) tags.add(tagsArr.getString(k));
                            }
                            boolean matchAny = cObj.optBoolean("matchAny", true);
                            boolean negate = cObj.optBoolean("negate", false);
                            cond = new TagCondition(tags, matchAny, negate);
                        } else if ("NAME".equals(cType)) {
                            String pattern = cObj.optString("pattern", "");
                            String matchStr = cObj.optString("matchType", "CONTAINS");
                            Condition.MatchType matchType = Condition.MatchType.CONTAINS;
                            try { matchType = Condition.MatchType.valueOf(matchStr); } catch (Exception ignored) {}
                            boolean negate = cObj.optBoolean("negate", false);
                            cond = new NameCondition(pattern, matchType, negate);
                        } else if ("TYPE".equals(cType)) {
                            String typeStr = cObj.optString("typeValue", "UNSUPPORTED");
                            com.mediasorter.models.MediaFile.Type type = com.mediasorter.models.MediaFile.Type.UNSUPPORTED;
                            try { type = com.mediasorter.models.MediaFile.Type.valueOf(typeStr); } catch (Exception ignored) {}
                            boolean negate = cObj.optBoolean("negate", false);
                            cond = new TypeCondition(type, negate);
                        } else if ("SIZE".equals(cType)) {
                            long threshold = cObj.optLong("threshold", 0);
                            boolean greaterThan = cObj.optBoolean("greaterThan", true);
                            boolean negate = cObj.optBoolean("negate", false);
                            cond = new SizeCondition(threshold, greaterThan, negate);
                        } else if ("DATE".equals(cType)) {
                            int days = cObj.optInt("days", 0);
                            boolean olderThan = cObj.optBoolean("olderThan", true);
                            boolean negate = cObj.optBoolean("negate", false);
                            cond = new DateCondition(days, olderThan, negate);
                        } else if ("STATUS".equals(cType)) {
                            String statusStr = cObj.optString("status", "NONE");
                            com.mediasorter.FileStatus.Status status = com.mediasorter.FileStatus.Status.NONE;
                            try { status = com.mediasorter.FileStatus.Status.valueOf(statusStr); } catch (Exception ignored) {}
                            boolean negate = cObj.optBoolean("negate", false);
                            cond = new StatusCondition(status, negate);
                        } else if ("FOLDER".equals(cType)) {
                            String folderPath = cObj.optString("folderPath", "");
                            boolean negate = cObj.optBoolean("negate", false);
                            cond = new FolderCondition(folderPath, negate);
                        }
                        if (cond != null) r.conditions.add(cond);
                    }
                }

                // Action
                r.action = null;
                if (obj.has("action")) {
                    JSONObject aObj = obj.getJSONObject("action");
                    String aType = aObj.optString("actionType", "");
                    if ("MOVE".equals(aType)) {
                        String dest = aObj.optString("destFolder", "");
                        String conflictStr = aObj.optString("conflict", "SKIP");
                        Action.Conflict conflict = Action.Conflict.SKIP;
                        try { conflict = Action.Conflict.valueOf(conflictStr); } catch (Exception ignored) {}
                        r.action = new MoveAction(dest, conflict);
                    } else if ("DELETE".equals(aType)) {
                        boolean useTrash = aObj.optBoolean("useTrash", true);
                        String trashFolder = aObj.optString("trashFolder", "");
                        r.action = new DeleteAction(useTrash, trashFolder);
                    } else if ("TAG".equals(aType)) {
                        JSONArray addArr = aObj.optJSONArray("tagsToAdd");
                        JSONArray remArr = aObj.optJSONArray("tagsToRemove");
                        List<String> addTags = new ArrayList<>();
                        List<String> remTags = new ArrayList<>();
                        if (addArr != null) {
                            for (int k = 0; k < addArr.length(); k++) addTags.add(addArr.getString(k));
                        }
                        if (remArr != null) {
                            for (int k = 0; k < remArr.length(); k++) remTags.add(remArr.getString(k));
                        }
                        r.action = new TagAction(addTags, remTags);
                    } else if ("STATUS".equals(aType)) {
                        String statusStr = aObj.optString("status", "NONE");
                        com.mediasorter.FileStatus.Status status = com.mediasorter.FileStatus.Status.NONE;
                        try { status = com.mediasorter.FileStatus.Status.valueOf(statusStr); } catch (Exception ignored) {}
                        boolean clear = aObj.optBoolean("clear", false);
                        r.action = new StatusAction(status, clear);
                    } else if ("RENAME".equals(aType)) {
                        String pattern = aObj.optString("pattern", "");
                        r.action = new RenameAction(pattern);
                    }
                }

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
                obj.put("name", r.name != null ? r.name : "Unnamed");
                obj.put("enabled", r.enabled);

                // Conditions
                JSONArray condArr = new JSONArray();
                if (r.conditions != null) {
                    for (Condition c : r.conditions) {
                        JSONObject cObj = new JSONObject();
                        if (c instanceof TagCondition) {
                            TagCondition tc = (TagCondition) c;
                            cObj.put("condType", "TAG");
                            JSONArray tagsArr = new JSONArray();
                            if (tc.tags != null) {
                                for (String t : tc.tags) tagsArr.put(t);
                            }
                            cObj.put("tags", tagsArr);
                            cObj.put("matchAny", tc.matchAny);
                            cObj.put("negate", tc.negate);
                        } else if (c instanceof NameCondition) {
                            NameCondition nc = (NameCondition) c;
                            cObj.put("condType", "NAME");
                            cObj.put("pattern", nc.pattern);
                            cObj.put("matchType", nc.type != null ? nc.type.name() : "CONTAINS");
                            cObj.put("negate", nc.negate);
                        } else if (c instanceof TypeCondition) {
                            TypeCondition tc = (TypeCondition) c;
                            cObj.put("condType", "TYPE");
                            cObj.put("typeValue", tc.type != null ? tc.type.name() : "UNSUPPORTED");
                            cObj.put("negate", tc.negate);
                        } else if (c instanceof SizeCondition) {
                            SizeCondition sc = (SizeCondition) c;
                            cObj.put("condType", "SIZE");
                            cObj.put("threshold", sc.threshold);
                            cObj.put("greaterThan", sc.greaterThan);
                            cObj.put("negate", sc.negate);
                        } else if (c instanceof DateCondition) {
                            DateCondition dc = (DateCondition) c;
                            cObj.put("condType", "DATE");
                            cObj.put("days", dc.days);
                            cObj.put("olderThan", dc.olderThan);
                            cObj.put("negate", dc.negate);
                        } else if (c instanceof StatusCondition) {
                            StatusCondition sc = (StatusCondition) c;
                            cObj.put("condType", "STATUS");
                            cObj.put("status", sc.status != null ? sc.status.name() : "NONE");
                            cObj.put("negate", sc.negate);
                        } else if (c instanceof FolderCondition) {
                            FolderCondition fc = (FolderCondition) c;
                            cObj.put("condType", "FOLDER");
                            cObj.put("folderPath", fc.folderPath != null ? fc.folderPath.replaceFirst("/$", "") : "");
                            cObj.put("negate", fc.negate);
                        }
                        condArr.put(cObj);
                    }
                }
                obj.put("conditions", condArr);

                // Action
                JSONObject aObj = new JSONObject();
                if (r.action instanceof MoveAction) {
                    MoveAction ma = (MoveAction) r.action;
                    aObj.put("actionType", "MOVE");
                    aObj.put("destFolder", ma.destFolder);
                    aObj.put("conflict", ma.conflict != null ? ma.conflict.name() : "SKIP");
                } else if (r.action instanceof DeleteAction) {
                    DeleteAction da = (DeleteAction) r.action;
                    aObj.put("actionType", "DELETE");
                    aObj.put("useTrash", da.useTrash);
                    aObj.put("trashFolder", da.trashFolder);
                } else if (r.action instanceof TagAction) {
                    TagAction ta = (TagAction) r.action;
                    aObj.put("actionType", "TAG");
                    JSONArray addArr = new JSONArray();
                    JSONArray remArr = new JSONArray();
                    if (ta.tagsToAdd != null) {
                        for (String t : ta.tagsToAdd) addArr.put(t);
                    }
                    if (ta.tagsToRemove != null) {
                        for (String t : ta.tagsToRemove) remArr.put(t);
                    }
                    aObj.put("tagsToAdd", addArr);
                    aObj.put("tagsToRemove", remArr);
                } else if (r.action instanceof StatusAction) {
                    StatusAction sa = (StatusAction) r.action;
                    aObj.put("actionType", "STATUS");
                    aObj.put("status", sa.status != null ? sa.status.name() : "NONE");
                    aObj.put("clear", sa.clear);
                } else if (r.action instanceof RenameAction) {
                    RenameAction ra = (RenameAction) r.action;
                    aObj.put("actionType", "RENAME");
                    aObj.put("pattern", ra.pattern);
                }
                obj.put("action", aObj);

                arr.put(obj);
            } catch (Exception ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}
