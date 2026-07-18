package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public class CopyAction extends Action {
    public String destFolder;
    public Action.Conflict conflict;

    public CopyAction(String destFolder, Action.Conflict conflict) {
        this.destFolder = destFolder;
        this.conflict = conflict;
    }

    @Override
    public String describe() { return "Copy to " + destFolder; }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        File src = new File(file.getPath());
        File destDir = new File(destFolder);
        if (!destDir.exists()) destDir.mkdirs();
        File destFile = new File(destDir, src.getName());
        if (destFile.exists()) {
            switch (conflict) {
                case SKIP:
                    log.add("Copy skipped (exists): " + src.getName());
                    return false;
                case OVERWRITE:
                    if (!destFile.delete()) {
                        log.add("Failed to overwrite for copy: " + src.getName());
                        return false;
                    }
                    break;
                case RENAME:
                    destFile = findAvailable(destFile);
                    break;
            }
        }
        try {
            copyFile(src, destFile);
            log.add("Copied: " + src.getName() + " to " + destFile.getParent());
            return true;
        } catch (IOException e) {
            log.add("Copy failed: " + src.getName());
            return false;
        }
    }

    private void copyFile(File src, File dest) throws IOException {
        try (FileChannel in = new FileInputStream(src).getChannel();
             FileChannel out = new FileOutputStream(dest).getChannel()) {
            in.transferTo(0, in.size(), out);
        }
    }

    private File findAvailable(File f) {
        String base = f.getName();
        int dot = base.lastIndexOf('.');
        String name = dot > 0 ? base.substring(0, dot) : base;
        String ext  = dot > 0 ? base.substring(dot) : "";
        int i = 1;
        File candidate;
        do {
            candidate = new File(f.getParent(), name + "(" + i + ")" + ext);
            i++;
        } while (candidate.exists());
        return candidate;
    }
}
