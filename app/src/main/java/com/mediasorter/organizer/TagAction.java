package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class TagAction extends Action {
    public List<String> tagsToAdd;
    public List<String> tagsToRemove;

    public TagAction(List<String> add, List<String> remove) {
        this.tagsToAdd = add != null ? add : new ArrayList<String>();
        this.tagsToRemove = remove != null ? remove : new ArrayList<String>();
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("Tags: ");
        if (!tagsToAdd.isEmpty()) sb.append("+").append(tagsToAdd);
        if (!tagsToRemove.isEmpty()) sb.append(" -").append(tagsToRemove);
        return sb.toString();
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        for (String t : tagsToAdd)    tagManager.applyTag(file, t);
        for (String t : tagsToRemove) tagManager.removeTag(file, t);
        log.add("Changed tags for: " + file.getName());
        return true;
    }
}
