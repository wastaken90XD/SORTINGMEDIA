package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FileStatus {

    public enum Status { NONE, SKIPPED, FLAGGED, DONE }

    private static final String PREFS    = "file_status_prefs";
    private static final String KEY_SKIP = "skipped";
    private static final String KEY_FLAG = "flagged";
    private static final String KEY_DONE = "done";

    private final SharedPreferences prefs;
    private final Set<String> skipped;
    private final Set<String> flagged;
    private final Set<String> done;

    public FileStatus(Context context) {
        this.prefs   = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.skipped = new HashSet<>(prefs.getStringSet(KEY_SKIP, new HashSet<>()));
        this.flagged = new HashSet<>(prefs.getStringSet(KEY_FLAG, new HashSet<>()));
        this.done    = new HashSet<>(prefs.getStringSet(KEY_DONE, new HashSet<>()));
    }

    public void setSkipped(String path) {
        skipped.add(path);
        flagged.remove(path);
        done.remove(path);
        save();
    }

    public void setFlagged(String path) {
        flagged.add(path);
        skipped.remove(path);
        done.remove(path);
        save();
    }

    public void setDone(String path) {
        done.add(path);
        skipped.remove(path);
        flagged.remove(path);
        save();
    }

    public void clearStatus(String path) {
        skipped.remove(path);
        flagged.remove(path);
        done.remove(path);
        save();
    }

    public Status getStatus(String path) {
        if (done.contains(path))    return Status.DONE;
        if (flagged.contains(path)) return Status.FLAGGED;
        if (skipped.contains(path)) return Status.SKIPPED;
        return Status.NONE;
    }

    public boolean isSkipped(String path) { return skipped.contains(path); }
    public boolean isFlagged(String path) { return flagged.contains(path); }
    public boolean isDone(String path)    { return done.contains(path); }

    public Set<String> getAllFlagged() { return new HashSet<>(flagged); }
    public Set<String> getAllSkipped() { return new HashSet<>(skipped); }
    public Set<String> getAllDone()    { return new HashSet<>(done); }

    private void save() {
        prefs.edit()
            .putStringSet(KEY_SKIP, skipped)
            .putStringSet(KEY_FLAG, flagged)
            .putStringSet(KEY_DONE, done)
            .apply();
    }
}
