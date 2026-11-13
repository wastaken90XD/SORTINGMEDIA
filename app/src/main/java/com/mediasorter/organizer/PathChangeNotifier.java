package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.MainActivity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Posts file-path reconciliation back to MainActivity without coupling actions to UI. */
public final class PathChangeNotifier {
    private PathChangeNotifier() {}

    public static void renamed(Context context, final String oldPath, final String newPath) {
        if (!(context instanceof MainActivity) || oldPath == null || newPath == null) return;
        final MainActivity activity = (MainActivity) context;
        final Map<String, String> changed = new HashMap<String, String>();
        changed.put(oldPath, newPath);
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.onPathsChanged(changed, null); }
        });
    }

    public static void removed(Context context, final String path) {
        if (!(context instanceof MainActivity) || path == null) return;
        final MainActivity activity = (MainActivity) context;
        final Set<String> removed = new HashSet<String>();
        removed.add(path);
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.onPathsChanged(null, removed); }
        });
    }
}
