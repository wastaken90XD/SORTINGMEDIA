package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.io.File;

public class AffixAction extends Action {
    public String position; // "PREFIX" or "SUFFIX"
    public String text;

    public AffixAction(String position, String text) {
        this.position = position != null ? position : "PREFIX";
        this.text = text != null ? text : "";
    }

    @Override
    public String describe() {
        return "PREFIX".equals(position) ? "Add prefix '" + text + "'" : "Add suffix '" + text + "'";
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        if (text.isEmpty()) {
            log.add("No affix text specified");
            return false;
        }
        File original = new File(file.getPath());
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String baseName = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";

        String newName;
        if ("PREFIX".equals(position)) {
            newName = text + baseName + ext;
        } else {
            newName = baseName + text + ext;
        }

        if (newName.equals(name)) {
            log.add("Affix unchanged: " + name);
            return true;
        }

        File renamed = new File(original.getParent(), newName);
        if (renamed.exists()) {
            log.add("Cannot add affix, target exists: " + newName);
            return false;
        }
        if (original.renameTo(renamed)) {
            file.setPath(renamed.getAbsolutePath());
            log.add(("PREFIX".equals(position) ? "Prefixed: " : "Suffixed: ") + name + " -> " + newName);
            return true;
        } else {
            log.add("Failed to add affix: " + name);
            return false;
        }
    }
}
