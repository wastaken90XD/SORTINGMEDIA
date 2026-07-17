package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class Rule {
    public String name;
    public boolean enabled = true;
    public List<Condition> conditions = new ArrayList<>();
    public Action action;

    public boolean matchesFile(MediaFile file, FileStatus fileStatus) {
        for (Condition c : conditions) {
            if (!c.matches(file, fileStatus)) return false;
        }
        return true;
    }

    public boolean execute(MediaFile file, android.content.Context ctx,
            com.mediasorter.TagManager tm,
            com.mediasorter.BatchRenameManager rm,
            FileStatus fs) {
        return action.execute(file, ctx, tm, rm, fs);
    }
}
