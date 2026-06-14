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
        APPLY_TAG,
        NOTHING
    }

    private static final String PREFS         = "gesture_prefs";

    // Swipe directions
    private static final String KEY_LEFT      = "gesture_left";
    private static final String KEY_RIGHT     = "gesture_right";
    private static final String KEY_UP        = "gesture_up";
    private static final String KEY_DOWN      = "gesture_down";

    // Swipe tag assignments
    private static final String KEY_LEFT_TAG  = "gesture_left_tag";
    private static final String KEY_RIGHT_TAG = "gesture_right_tag";
    private static final String KEY_UP_TAG    = "gesture_up_tag";
    private static final String KEY_DOWN_TAG  = "gesture_down_tag";

    // D-pad directions
    private static final String KEY_DPAD_UP   = "dpad_up";
    private static final String KEY_DPAD_DOWN = "dpad_down";
    private static final String KEY_DPAD_LEFT = "dpad_left";
    private static final String KEY_DPAD_RIGHT= "dpad_right";
    private static final String KEY_DPAD_CENTER = "dpad_center";

    // D-pad tag assignments
    private static final String KEY_DPAD_UP_TAG    = "dpad_up_tag";
    private static final String KEY_DPAD_DOWN_TAG  = "dpad_down_tag";
    private static final String KEY_DPAD_LEFT_TAG  = "dpad_left_tag";
    private static final String KEY_DPAD_RIGHT_TAG = "dpad_right_tag";
    private static final String KEY_DPAD_CENTER_TAG= "dpad_center_tag";

    private final SharedPreferences prefs;

    public GestureSettings(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── Swipe getters ─────────────────────────────────────────────────────────

    public GestureAction getLeft() {
        return GestureAction.valueOf(
            prefs.getString(KEY_LEFT, GestureAction.NEXT_FILE.name()));
    }

    public GestureAction getRight() {
        return GestureAction.valueOf(
            prefs.getString(KEY_RIGHT, GestureAction.PREV_FILE.name()));
    }

    public GestureAction getUp() {
        return GestureAction.valueOf(
            prefs.getString(KEY_UP, GestureAction.NOTHING.name()));
    }

    public GestureAction getDown() {
        return GestureAction.valueOf(
            prefs.getString(KEY_DOWN, GestureAction.NOTHING.name()));
    }

    // ── Swipe tag getters ─────────────────────────────────────────────────────

    public String getLeftTag()  { return prefs.getString(KEY_LEFT_TAG,  ""); }
    public String getRightTag() { return prefs.getString(KEY_RIGHT_TAG, ""); }
    public String getUpTag()    { return prefs.getString(KEY_UP_TAG,    ""); }
    public String getDownTag()  { return prefs.getString(KEY_DOWN_TAG,  ""); }

    // ── Swipe setters ─────────────────────────────────────────────────────────

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

    // ── Swipe tag setters ─────────────────────────────────────────────────────

    public void setLeftTag(String tag)  { prefs.edit().putString(KEY_LEFT_TAG,  tag).apply(); }
    public void setRightTag(String tag) { prefs.edit().putString(KEY_RIGHT_TAG, tag).apply(); }
    public void setUpTag(String tag)    { prefs.edit().putString(KEY_UP_TAG,    tag).apply(); }
    public void setDownTag(String tag)  { prefs.edit().putString(KEY_DOWN_TAG,  tag).apply(); }

    // ── D-pad getters ─────────────────────────────────────────────────────────

    public GestureAction getDpadUp() {
        return GestureAction.valueOf(
            prefs.getString(KEY_DPAD_UP, GestureAction.APPLY_TAG.name()));
    }

    public GestureAction getDpadDown() {
        return GestureAction.valueOf(
            prefs.getString(KEY_DPAD_DOWN, GestureAction.APPLY_TAG.name()));
    }

    public GestureAction getDpadLeft() {
        return GestureAction.valueOf(
            prefs.getString(KEY_DPAD_LEFT, GestureAction.PREV_FILE.name()));
    }

    public GestureAction getDpadRight() {
        return GestureAction.valueOf(
            prefs.getString(KEY_DPAD_RIGHT, GestureAction.NEXT_FILE.name()));
    }

    public GestureAction getDpadCenter() {
        return GestureAction.valueOf(
            prefs.getString(KEY_DPAD_CENTER, GestureAction.APPLY_TAG.name()));
    }

    // ── D-pad tag getters ─────────────────────────────────────────────────────

    public String getDpadUpTag()     { return prefs.getString(KEY_DPAD_UP_TAG,     ""); }
    public String getDpadDownTag()   { return prefs.getString(KEY_DPAD_DOWN_TAG,   ""); }
    public String getDpadLeftTag()   { return prefs.getString(KEY_DPAD_LEFT_TAG,   ""); }
    public String getDpadRightTag()  { return prefs.getString(KEY_DPAD_RIGHT_TAG,  ""); }
    public String getDpadCenterTag() { return prefs.getString(KEY_DPAD_CENTER_TAG, ""); }

    // ── D-pad setters ─────────────────────────────────────────────────────────

    public void setDpadUp(GestureAction a) {
        prefs.edit().putString(KEY_DPAD_UP, a.name()).apply();
    }

    public void setDpadDown(GestureAction a) {
        prefs.edit().putString(KEY_DPAD_DOWN, a.name()).apply();
    }

    public void setDpadLeft(GestureAction a) {
        prefs.edit().putString(KEY_DPAD_LEFT, a.name()).apply();
    }

    public void setDpadRight(GestureAction a) {
        prefs.edit().putString(KEY_DPAD_RIGHT, a.name()).apply();
    }

    public void setDpadCenter(GestureAction a) {
        prefs.edit().putString(KEY_DPAD_CENTER, a.name()).apply();
    }

    // ── D-pad tag setters ─────────────────────────────────────────────────────

    public void setDpadUpTag(String tag)     { prefs.edit().putString(KEY_DPAD_UP_TAG,     tag).apply(); }
    public void setDpadDownTag(String tag)   { prefs.edit().putString(KEY_DPAD_DOWN_TAG,   tag).apply(); }
    public void setDpadLeftTag(String tag)   { prefs.edit().putString(KEY_DPAD_LEFT_TAG,   tag).apply(); }
    public void setDpadRightTag(String tag)  { prefs.edit().putString(KEY_DPAD_RIGHT_TAG,  tag).apply(); }
    public void setDpadCenterTag(String tag) { prefs.edit().putString(KEY_DPAD_CENTER_TAG, tag).apply(); }

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
            case NOTHING:      return "Nothing";
            default:           return "Nothing";
        }
    }

    public String[] getAllLabels() {
        return new String[]{
            "Next File", "Prev File", "Quick Tags",
            "Skip", "Flag", "Done",
            "Filter Cycle", "Apply Tag", "Nothing"
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
            default:             return GestureAction.NOTHING;
        }
    }
}
