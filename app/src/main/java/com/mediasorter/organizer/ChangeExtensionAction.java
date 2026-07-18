package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.io.File;

public class ChangeExtensionAction extends Action {
    public String newExtension;

    public ChangeExtensionAction(String newExtension) {
        // Normalize: strip leading dot if present
        if (newExtension != null && newExtension.startsWith(".")) {
            newExtension = newExtension.substring(1);
        }
        this.newExtension = newExtension != null ? newExtension : "";
    }

    @Override
    public String describe() { return "Change extension to ." + newExtension; }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        if (newExtension.isEmpty()) {
            log.add("No extension specified");
            return false;
        }
        File original = new File(file.getPath());
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String baseName = dot > 0 ? name.substring(0, dot) : name;
        String newName = baseName + "." + newExtension;

        if (newName.equals(name)) {
            log.add("Extension unchanged: " + name);
            return true; // already has the target extension
        }

        File renamed = new File(original.getParent(), newName);
        if (renamed.exists()) {
            log.add("Cannot change extension, target exists: " + newName);
            return false;
        }
        if (original.renameTo(renamed)) {
            file.setPath(renamed.getAbsolutePath());
            log.add("Changed extension: " + name + " -> " + newName);
            return true;
        } else {
            log.add("Failed to change extension: " + name);
            return false;
        }
    }
}
