package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class Rule {
    public String name;
    public boolean enabled = true;
    public boolean autoApply = false;
    public List<Condition> conditions = new ArrayList<>();
    public Action action;

    /** AND logic by default – all conditions must match. */
    public boolean matchesFile(MediaFile file, FileStatus fileStatus) {
        if (conditions == null || conditions.isEmpty()) return false;
        for (Condition c : conditions) {
            if (c == null || !c.matches(file, fileStatus)) return false;
        }
        return true;
    }

    public boolean execute(MediaFile file, android.content.Context ctx,
            com.mediasorter.TagManager tm,
            com.mediasorter.BatchRenameManager rm,
            FileStatus fs) {
        if (action == null) return false;
        return action.execute(file, ctx, tm, rm, fs);
    }
}
