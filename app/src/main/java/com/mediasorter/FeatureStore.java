package com.mediasorter;

import com.mediasorter.features.SmartFeatureCache;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared, small primitive feature snapshot used by interactive sort modes. */
public final class FeatureStore {
    public static final class Snapshot {
        public final List<MediaFile> files;
        public final long[] hashes;
        public final boolean[] hashPresent;
        public final float[] aspect;
        public final float[] logSize;
        public final long[] modified;
        public final String[] folders;

        Snapshot(List<MediaFile> source) {
            files = new ArrayList<MediaFile>(source);
            int size = files.size();
            hashes = new long[size];
            hashPresent = new boolean[size];
            aspect = new float[size];
            logSize = new float[size];
            modified = new long[size];
            folders = new String[size];
        }
    }

    private final SmartFeatureCache cache;

    public FeatureStore(android.content.Context context) {
        cache = new SmartFeatureCache(context.getFilesDir());
    }

    /**
     * Builds metadata arrays without decoding images. SmartSortEngine remains
     * the owner of dHash extraction; this store shares its disk cache and
     * provides cheap structural features to Compare/Game workers.
     */
    public Snapshot snapshot(List<MediaFile> source) {
        List<MediaFile> safe = source == null ? new ArrayList<MediaFile>() : source;
        Snapshot result = new Snapshot(safe);
        for (int i = 0; i < safe.size(); i++) {
            MediaFile file = safe.get(i);
            if (file == null) continue;
            result.modified[i] = file.getDateAdded();
            result.logSize[i] = file.getSize() <= 0 ? 0f
                    : (float) (Math.log(file.getSize()) / Math.log(2d));
            result.aspect[i] = file.getWidth() > 0 && file.getHeight() > 0
                    ? file.getWidth() / (float) file.getHeight() : 0f;
            java.io.File parent = new java.io.File(file.getPath()).getParentFile();
            result.folders[i] = parent == null ? "" : parent.getAbsolutePath();
        }
        return result;
    }

    public void clearMemory() { cache.clearMemory(); }
}
