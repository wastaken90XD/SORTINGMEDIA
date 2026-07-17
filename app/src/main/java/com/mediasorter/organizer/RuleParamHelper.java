package com.mediasorter.organizer;

import java.util.List;

/**
 * Helper to extract user‑friendly parameter strings from Condition and Action objects.
 * This class lives in the organizer package so it can access the package‑private subclasses.
 */
public class RuleParamHelper {

    /** Returns a string representing the first parameter of the condition, or empty. */
    public static String getConditionParam(Condition c) {
        if (c instanceof TagCondition) {
            List<String> tags = ((TagCondition) c).tags;
            return tags.isEmpty() ? "" : tags.get(0);
        } else if (c instanceof NameCondition) {
            return ((NameCondition) c).pattern;
        } else if (c instanceof TypeCondition) {
            return ((TypeCondition) c).type.name();
        } else if (c instanceof SizeCondition) {
            // Display size in MB
            return String.valueOf(((SizeCondition) c).threshold / (1024 * 1024));
        } else if (c instanceof DateCondition) {
            return String.valueOf(((DateCondition) c).days);
        }
        return "";
    }

    /** Returns a string representing the action's main parameter, or empty. */
    public static String getActionParam(Action a) {
        if (a instanceof MoveAction) {
            return ((MoveAction) a).destFolder;
        } else if (a instanceof DeleteAction) {
            return ((DeleteAction) a).trashFolder != null ? ((DeleteAction) a).trashFolder : "";
        } else if (a instanceof TagAction) {
            List<String> tags = ((TagAction) a).tagsToAdd;
            return tags.isEmpty() ? "" : tags.get(0);
        } else if (a instanceof StatusAction) {
            return ((StatusAction) a).status.name();
        }
        return "";
    }
}
