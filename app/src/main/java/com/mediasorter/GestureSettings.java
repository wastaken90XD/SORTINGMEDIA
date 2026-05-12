package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;

public class GestureSettings {

    public enum GestureAction {
        NEXT_FILE,
        PREV_FILE,
        QUICK_TAGS,
        SKIP,
        FLAG,
        DONE,
        FILTER_CYCLE,
        NOTHING
    }

    private static final String PREFS      = "gesture_prefs";
    private static final String KEY_LEFT   = "gesture_left";
    private static final String KEY_RIGHT  = "gesture_right";
    private static final String KEY_UP     = "gesture_up";
    private static final String KEY_DOWN   = "gesture_down";

    private final SharedPreferences prefs;

    public GestureSettings(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public GestureAction getLeft()  {
        return GestureAction.valueOf(
            prefs.getString(KEY_LEFT, GestureAction.NEXT_FILE.name()));
    }

    public GestureAction getRight() {
        return GestureAction.valueOf(
            prefs.getString(KEY_RIGHT, GestureAction.PREV_FILE.name()));
    }

    public GestureAction getUp() {
        return GestureAction.valueOf(
            prefs.getString(KEY_UP, GestureAction.QUICK_TAGS.name()));
    }

    public GestureAction getDown() {
        return GestureAction.valueOf(
            prefs.getString(KEY_DOWN, GestureAction.NOTHING.name()));
    }

    public void setLeft(GestureAction a)  {
        prefs.edit().putString(KEY_LEFT,  a.name()).apply();
    }

    public void setRight(GestureAction a) {
        prefs.edit().putString(KEY_RIGHT, a.name()).apply();
    }

    public void setUp(GestureAction a) {
        prefs.edit().putString(KEY_UP,    a.name()).apply();
    }

    public void setDown(GestureAction a) {
        prefs.edit().putString(KEY_DOWN,  a.name()).apply();
    }

    public String getLabel(GestureAction action) {
        switch (action) {
            case NEXT_FILE:    return "Next File";
            case PREV_FILE:    return "Prev File";
            case QUICK_TAGS:   return "Quick Tags";
            case SKIP:         return "Skip";
            case FLAG:         return "Flag";
            case DONE:         return "Done";
            case FILTER_CYCLE: return "Filter Cycle";
            case NOTHING:      return "Nothing";
            default:           return "Nothing";
        }
    }

    public String[] getAllLabels() {
        return new String[]{
            "Next File", "Prev File", "Quick Tags",
            "Skip", "Flag", "Done", "Filter Cycle", "Nothing"
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
            default:             return GestureAction.NOTHING;
        }
    }
}
