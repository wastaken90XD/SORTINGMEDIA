package com.mediasorter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical list of actions and input ids understood by the application.
 * Keeping these values in one place prevents an action from being available in
 * one picker but silently missing from another.  Values are strings on disk so
 * old gesture preference files remain readable on API 21 devices.
 */
public final class GestureConstants {

    private GestureConstants() {}

    public static final String PREFS = "gesture_prefs";

    // Action ids.  Do not rename existing ids: they are persisted in gesture
    // mappings and in macro-compatible gesture exports.
    public static final String ACTION_NEXT_FILE = "NEXT_FILE";
    public static final String ACTION_PREV_FILE = "PREV_FILE";
    public static final String ACTION_QUICK_TAGS = "QUICK_TAGS";
    public static final String ACTION_SKIP = "SKIP";
    public static final String ACTION_FLAG = "FLAG";
    public static final String ACTION_DONE = "DONE";
    public static final String ACTION_FILTER_CYCLE = "FILTER_CYCLE";
    public static final String ACTION_APPLY_TAG = "APPLY_TAG";
    public static final String ACTION_MACRO = "MACRO";
    public static final String ACTION_REPEAT_LAST_MACRO = "REPEAT_LAST_MACRO";
    public static final String ACTION_OPEN_GALLERY = "OPEN_GALLERY";
    public static final String ACTION_OPEN_DASHBOARD = "OPEN_DASHBOARD";
    public static final String ACTION_OPEN_RULES = "OPEN_RULES";
    public static final String ACTION_OPEN_COLOR_ANALYZER = "OPEN_COLOR_ANALYZER";
    public static final String ACTION_OPEN_DUPLICATE_FINDER = "OPEN_DUPLICATE_FINDER";
    public static final String ACTION_OPEN_SETTINGS = "OPEN_SETTINGS";
    public static final String ACTION_EXPORT_SETTINGS = "EXPORT_SETTINGS";
    public static final String ACTION_TRIGGER_RESCAN = "TRIGGER_RESCAN";
    public static final String ACTION_QUICK_RANDOM_TAG = "QUICK_RANDOM_TAG";
    public static final String ACTION_SURPRISE_ME = "SURPRISE_ME";
    public static final String ACTION_CYCLE_TAG_PRESETS = "CYCLE_TAG_PRESETS";
    public static final String ACTION_NEXT_PAGE = "NEXT_PAGE";
    public static final String ACTION_PREVIOUS_PAGE = "PREVIOUS_PAGE";
    public static final String ACTION_JUMP_FIRST = "JUMP_FIRST";
    public static final String ACTION_JUMP_LAST = "JUMP_LAST";
    public static final String ACTION_TOGGLE_STATS_BAR = "TOGGLE_STATS_BAR";
    public static final String ACTION_TOGGLE_INFO_OVERLAY = "TOGGLE_INFO_OVERLAY";
    public static final String ACTION_TOGGLE_SELECTION_CURRENT = "TOGGLE_SELECTION_CURRENT";
    public static final String ACTION_SWEEP_SELECT_FORWARD = "SWEEP_SELECT_FORWARD";
    public static final String ACTION_SWEEP_SELECT_BACKWARD = "SWEEP_SELECT_BACKWARD";
    public static final String ACTION_SELECT_ALL = "SELECT_ALL";
    public static final String ACTION_DESELECT_ALL = "DESELECT_ALL";
    public static final String ACTION_TOGGLE_GALLERY = "TOGGLE_GALLERY";
    public static final String ACTION_SORT_PICKER = "SORT_PICKER";
    public static final String ACTION_FILTER_PICKER = "FILTER_PICKER";
    public static final String ACTION_GROUP_PICKER = "GROUP_PICKER";
    public static final String ACTION_UNDO = "UNDO";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_SCAN = "SCAN";
    public static final String ACTION_TOGGLE_TAG_PANEL = "TOGGLE_TAG_PANEL";
    public static final String ACTION_CYCLE_TAG_BAR_SORT = "CYCLE_TAG_BAR_SORT";
    public static final String ACTION_NOTHING = "NOTHING";

    /** DONE remains a valid programmatic/rules action, but is not a gesture default. */
    public enum Category {
        NAVIGATION, TAGGING, ORGANIZATION, METADATA, VIEWING, UTILITY, MACROS
    }

    public static final String INPUT_SWIPE_LEFT = "swipe_left_v2";
    public static final String INPUT_SWIPE_RIGHT = "swipe_right_v2";
    public static final String INPUT_SWIPE_UP = "swipe_up_v2";
    public static final String INPUT_SWIPE_DOWN = "swipe_down_v2";
    public static final String INPUT_DPAD_UP = "dpad_up_v2";
    public static final String INPUT_DPAD_DOWN = "dpad_down_v2";
    public static final String INPUT_DPAD_LEFT = "dpad_left_v2";
    public static final String INPUT_DPAD_RIGHT = "dpad_right_v2";
    public static final String INPUT_DPAD_CENTER = "dpad_center_v2";
    public static final String INPUT_VOLUME_UP = "volume_up_v2";
    public static final String INPUT_VOLUME_DOWN = "volume_down_v2";
    public static final String INPUT_VOLUME_UP_LONG = "volume_up_long_v2";
    public static final String INPUT_VOLUME_DOWN_LONG = "volume_down_long_v2";
    public static final String INPUT_TAP_SINGLE = "tap_single_v2";
    public static final String INPUT_TAP_DOUBLE = "tap_double_v2";
    public static final String INPUT_TAP_LONG = "tap_long_v2";
    public static final String INPUT_HARDWARE_BACK = "hardware_back_v2";
    public static final String INPUT_HARDWARE_MENU = "hardware_menu_v2";

    private static final LinkedHashMap<String, String> LABELS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Category> CATEGORIES = new LinkedHashMap<>();

    static {
        add(ACTION_NOTHING, "None", Category.UTILITY);
        add(ACTION_NEXT_FILE, "Next File", Category.NAVIGATION);
        add(ACTION_PREV_FILE, "Previous File", Category.NAVIGATION);
        add(ACTION_NEXT_PAGE, "Next Page", Category.NAVIGATION);
        add(ACTION_PREVIOUS_PAGE, "Previous Page", Category.NAVIGATION);
        add(ACTION_JUMP_FIRST, "Jump to First File", Category.NAVIGATION);
        add(ACTION_JUMP_LAST, "Jump to Last File", Category.NAVIGATION);
        add(ACTION_TOGGLE_SELECTION_CURRENT, "Toggle selection on current file", Category.NAVIGATION);
        add(ACTION_SWEEP_SELECT_FORWARD, "Sweep select forward", Category.NAVIGATION);
        add(ACTION_SWEEP_SELECT_BACKWARD, "Sweep select backward", Category.NAVIGATION);
        add(ACTION_SELECT_ALL, "Select all", Category.NAVIGATION);
        add(ACTION_DESELECT_ALL, "Deselect all", Category.NAVIGATION);

        add(ACTION_QUICK_TAGS, "Tag dialog", Category.TAGGING);
        add(ACTION_APPLY_TAG, "Apply Tag", Category.TAGGING);
        add(ACTION_QUICK_RANDOM_TAG, "Quick random tag", Category.TAGGING);
        add(ACTION_SURPRISE_ME, "Surprise me", Category.NAVIGATION);
        add(ACTION_CYCLE_TAG_PRESETS, "Cycle tag presets", Category.TAGGING);
        add(ACTION_TOGGLE_TAG_PANEL, "Toggle tag bar", Category.TAGGING);
        add(ACTION_CYCLE_TAG_BAR_SORT, "Cycle tag bar sort", Category.TAGGING);

        add(ACTION_FILTER_CYCLE, "Filter cycle", Category.VIEWING);
        add(ACTION_SKIP, "Skip", Category.ORGANIZATION);
        add(ACTION_FLAG, "Flag", Category.ORGANIZATION);
        // DONE is intentionally listed only for programmatic compatibility;
        // gesture pickers use getPickerActionIds(), which omits it.
        add(ACTION_DONE, "Done", Category.ORGANIZATION);
        add(ACTION_UNDO, "Undo", Category.ORGANIZATION);
        add(ACTION_DELETE, "Delete", Category.ORGANIZATION);
        add(ACTION_SCAN, "Trigger rescan", Category.ORGANIZATION);

        add(ACTION_OPEN_COLOR_ANALYZER, "Open color analyzer", Category.METADATA);
        add(ACTION_OPEN_DUPLICATE_FINDER, "Open duplicate finder", Category.METADATA);
        add(ACTION_EXPORT_SETTINGS, "Export settings", Category.METADATA);
        add(ACTION_OPEN_RULES, "Open rules editor", Category.ORGANIZATION);

        add(ACTION_OPEN_GALLERY, "Open gallery mode", Category.VIEWING);
        add(ACTION_TOGGLE_GALLERY, "Gallery/list view toggle", Category.VIEWING);
        add(ACTION_OPEN_DASHBOARD, "Open dashboard", Category.VIEWING);
        add(ACTION_SORT_PICKER, "Sort picker", Category.VIEWING);
        add(ACTION_FILTER_PICKER, "Filter picker", Category.VIEWING);
        add(ACTION_GROUP_PICKER, "Group picker", Category.VIEWING);
        add(ACTION_TOGGLE_STATS_BAR, "Toggle stats bar", Category.VIEWING);
        add(ACTION_TOGGLE_INFO_OVERLAY, "Toggle info overlay", Category.VIEWING);

        add(ACTION_OPEN_SETTINGS, "Open settings", Category.UTILITY);
        add(ACTION_TRIGGER_RESCAN, "Trigger rescan", Category.UTILITY);
        add(ACTION_REPEAT_LAST_MACRO, "Repeat last macro", Category.MACROS);
        add(ACTION_MACRO, "Macro", Category.MACROS);
    }

    private static void add(String id, String label, Category category) {
        LABELS.put(id, label);
        CATEGORIES.put(id, category);
    }

    public static boolean isKnownAction(String id) {
        return id != null && LABELS.containsKey(id);
    }

    public static String label(String id) {
        String label = LABELS.get(id);
        return label == null ? "None" : label;
    }

    public static Category category(String id) {
        Category category = CATEGORIES.get(id);
        return category == null ? Category.UTILITY : category;
    }

    /** All actions suitable for gesture assignment, with None at the top. */
    public static List<String> getPickerActionIds() {
        List<String> ids = new ArrayList<>();
        for (String id : LABELS.keySet()) {
            if (!ACTION_DONE.equals(id)) ids.add(id);
        }
        return ids;
    }

    /** All actions suitable for toolbar slots. DONE is not a user-facing slot. */
    public static List<String> getToolbarActionIds() {
        List<String> ids = getPickerActionIds();
        return ids;
    }

    public static List<String> getAllActionIds() {
        return new ArrayList<>(LABELS.keySet());
    }

    public static List<String> getActionIds(Category category) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Category> entry : CATEGORIES.entrySet()) {
            if (entry.getValue() == category) result.add(entry.getKey());
        }
        return result;
    }

    public static String defaultActionForInput(String inputId) {
        if (INPUT_SWIPE_LEFT.equals(inputId)) return ACTION_NEXT_FILE;
        if (INPUT_SWIPE_RIGHT.equals(inputId)) return ACTION_PREV_FILE;
        if (INPUT_SWIPE_UP.equals(inputId) || INPUT_SWIPE_DOWN.equals(inputId)) return ACTION_NOTHING;
        if (INPUT_DPAD_UP.equals(inputId) || INPUT_DPAD_DOWN.equals(inputId)) return ACTION_APPLY_TAG;
        if (INPUT_DPAD_LEFT.equals(inputId)) return ACTION_PREV_FILE;
        if (INPUT_DPAD_RIGHT.equals(inputId)) return ACTION_NEXT_FILE;
        if (INPUT_DPAD_CENTER.equals(inputId)) return ACTION_APPLY_TAG;
        if (INPUT_VOLUME_UP.equals(inputId)) return ACTION_NEXT_FILE;
        if (INPUT_VOLUME_DOWN.equals(inputId)) return ACTION_PREV_FILE;
        return ACTION_NOTHING;
    }

    public static List<String> getInputIds() {
        return new ArrayList<>(Arrays.asList(
                INPUT_DPAD_UP, INPUT_DPAD_DOWN, INPUT_DPAD_LEFT,
                INPUT_DPAD_RIGHT, INPUT_DPAD_CENTER,
                INPUT_SWIPE_LEFT, INPUT_SWIPE_RIGHT,
                INPUT_SWIPE_UP, INPUT_SWIPE_DOWN,
                INPUT_TAP_SINGLE, INPUT_TAP_DOUBLE, INPUT_TAP_LONG,
                INPUT_VOLUME_UP, INPUT_VOLUME_DOWN,
                INPUT_VOLUME_UP_LONG, INPUT_VOLUME_DOWN_LONG,
                INPUT_HARDWARE_BACK, INPUT_HARDWARE_MENU));
    }

    public static String inputLabel(String inputId) {
        if (INPUT_DPAD_UP.equals(inputId)) return "D-Pad Up";
        if (INPUT_DPAD_DOWN.equals(inputId)) return "D-Pad Down";
        if (INPUT_DPAD_LEFT.equals(inputId)) return "D-Pad Left";
        if (INPUT_DPAD_RIGHT.equals(inputId)) return "D-Pad Right";
        if (INPUT_DPAD_CENTER.equals(inputId)) return "D-Pad Center";
        if (INPUT_SWIPE_LEFT.equals(inputId)) return "Swipe Left";
        if (INPUT_SWIPE_RIGHT.equals(inputId)) return "Swipe Right";
        if (INPUT_SWIPE_UP.equals(inputId)) return "Swipe Up";
        if (INPUT_SWIPE_DOWN.equals(inputId)) return "Swipe Down";
        if (INPUT_TAP_SINGLE.equals(inputId)) return "Single Tap";
        if (INPUT_TAP_DOUBLE.equals(inputId)) return "Double Tap";
        if (INPUT_TAP_LONG.equals(inputId)) return "Long Tap";
        if (INPUT_VOLUME_UP.equals(inputId)) return "Volume Up";
        if (INPUT_VOLUME_DOWN.equals(inputId)) return "Volume Down";
        if (INPUT_VOLUME_UP_LONG.equals(inputId)) return "Volume Up Long Press";
        if (INPUT_VOLUME_DOWN_LONG.equals(inputId)) return "Volume Down Long Press";
        if (INPUT_HARDWARE_BACK.equals(inputId)) return "Hardware Back";
        if (INPUT_HARDWARE_MENU.equals(inputId)) return "Hardware Menu";
        return inputId == null ? "Unknown input" : inputId;
    }

    public static String categoryLabel(Category category) {
        if (category == null) return "Utility";
        switch (category) {
            case NAVIGATION: return "Navigation";
            case TAGGING: return "Tagging";
            case ORGANIZATION: return "Organization";
            case METADATA: return "Metadata";
            case VIEWING: return "Viewing";
            case MACROS: return "Macros";
            case UTILITY:
            default: return "Utility";
        }
    }
}
