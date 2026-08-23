package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/** Persistent per-file status. DONE intentionally remains a model status even
 * though the primary UI no longer exposes a Done button. */
public class FileStatus {

    public enum Status { NONE, SKIPPED, FLAGGED, DONE }

    private static final String PREFS = "file_status_prefs";
    private static final String KEY_SKIP = "skipped";
    private static final String KEY_FLAG = "flagged";
    private static final String KEY_DONE = "done";

    private final SharedPreferences prefs;
    private final Set<String> skipped;
    private final Set<String> flagged;
    private final Set<String> done;
    private StatusChangeListener listener;

    public interface StatusChangeListener {
        void onStatusChanged(String path);
    }

    public FileStatus(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.skipped = new HashSet<>(prefs.getStringSet(KEY_SKIP, new HashSet<String>()));
        this.flagged = new HashSet<>(prefs.getStringSet(KEY_FLAG, new HashSet<String>()));
        this.done = new HashSet<>(prefs.getStringSet(KEY_DONE, new HashSet<String>()));
    }

    public void setStatusChangeListener(StatusChangeListener value) {
        listener = value;
    }

    public synchronized void setSkipped(String path) {
        if (path == null) return;
        skipped.add(path);
        flagged.remove(path);
        done.remove(path);
        save();
        notifyChanged(path);
    }

    public synchronized void setFlagged(String path) {
        if (path == null) return;
        flagged.add(path);
        skipped.remove(path);
        done.remove(path);
        save();
        notifyChanged(path);
    }

    public synchronized void setDone(String path) {
        if (path == null) return;
        done.add(path);
        skipped.remove(path);
        flagged.remove(path);
        save();
        notifyChanged(path);
    }

    public synchronized void clearStatus(String path) {
        if (path == null) return;
        skipped.remove(path);
        flagged.remove(path);
        done.remove(path);
        save();
        notifyChanged(path);
    }

    public synchronized Status getStatus(String path) {
        if (done.contains(path)) return Status.DONE;
        if (flagged.contains(path)) return Status.FLAGGED;
        if (skipped.contains(path)) return Status.SKIPPED;
        return Status.NONE;
    }

    public synchronized boolean isSkipped(String path) { return skipped.contains(path); }
    public synchronized boolean isFlagged(String path) { return flagged.contains(path); }
    public synchronized boolean isDone(String path) { return done.contains(path); }
    public synchronized Set<String> getAllFlagged() { return new HashSet<>(flagged); }
    public synchronized Set<String> getAllSkipped() { return new HashSet<>(skipped); }
    public synchronized Set<String> getAllDone() { return new HashSet<>(done); }

    private void save() {
        prefs.edit()
                .putStringSet(KEY_SKIP, new HashSet<>(skipped))
                .putStringSet(KEY_FLAG, new HashSet<>(flagged))
                .putStringSet(KEY_DONE, new HashSet<>(done))
                .apply();
    }

    private void notifyChanged(String path) {
        if (listener != null) listener.onStatusChanged(path);
    }
}
