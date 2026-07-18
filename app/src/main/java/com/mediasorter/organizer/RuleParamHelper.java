package com.mediasorter.organizer;

import java.util.List;

/**
 * Helper to extract user-friendly parameter strings from Condition and Action objects.
 * This class lives in the organizer package so it can access the package-private subclasses.
 */
public class RuleParamHelper {

    /** Returns a string representing the first parameter of the condition, or empty. */
    public static String getConditionParam(Condition c) {
        if (c == null) return "";
        if (c instanceof TagCondition) {
            List<String> tags = ((TagCondition) c).tags;
            if (tags == null || tags.isEmpty()) return "";
            return tags.get(0);
        } else if (c instanceof NameCondition) {
            String p = ((NameCondition) c).pattern;
            return p != null ? p : "";
        } else if (c instanceof TypeCondition) {
            com.mediasorter.models.MediaFile.Type t = ((TypeCondition) c).type;
            return t != null ? t.name() : "";
        } else if (c instanceof SizeCondition) {
            return String.valueOf(((SizeCondition) c).threshold / (1024L * 1024L));
        } else if (c instanceof DateCondition) {
            return String.valueOf(((DateCondition) c).days);
        } else if (c instanceof FolderCondition) {
            String fp = ((FolderCondition) c).folderPath;
            return fp != null ? fp.replaceFirst("/$", "") : "";
        } else if (c instanceof StatusCondition) {
            com.mediasorter.FileStatus.Status s = ((StatusCondition) c).status;
            return s != null ? s.name() : "";
        }
        return "";
    }

    /** Returns a string representing the action's main parameter, or empty. */
    public static String getActionParam(Action a) {
        if (a instanceof MoveAction) {
            return ((MoveAction) a).destFolder;
        } else if (a instanceof CopyAction) {
            return ((CopyAction) a).destFolder;
        } else if (a instanceof DeleteAction) {
            return ((DeleteAction) a).trashFolder != null ? ((DeleteAction) a).trashFolder : "";
        } else if (a instanceof TagAction) {
            List<String> tags = ((TagAction) a).tagsToAdd;
            return tags.isEmpty() ? "" : tags.get(0);
        } else if (a instanceof StatusAction) {
            return ((StatusAction) a).status.name();
        } else if (a instanceof RenameAction) {
            return ((RenameAction) a).pattern;
        }
        return "";
    }
}
