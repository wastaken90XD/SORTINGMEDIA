package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.io.File;

public class DeleteAction extends Action {
    public boolean useTrash;
    public String trashFolder;

    public DeleteAction(boolean useTrash, String trashFolder) {
        this.useTrash = useTrash;
        this.trashFolder = trashFolder;
    }

    @Override
    public String describe() {
        return useTrash ? "Move to trash" : "Delete";
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        File src = new File(file.getPath());
        if (useTrash && trashFolder != null && !trashFolder.isEmpty()) {
            File destDir = new File(trashFolder);
            if (!destDir.exists()) destDir.mkdirs();
            File destFile = new File(destDir, src.getName());
            if (src.renameTo(destFile)) {
                log.add("Trashed: " + src.getName());
                return true;
            } else {
                log.add("Trash failed: " + src.getName());
                return false;
            }
        } else {
            if (src.delete()) {
                log.add("Deleted: " + src.getName());
                return true;
            } else {
                log.add("Delete failed: " + src.getName());
                return false;
            }
        }
    }
}
