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
        public final String[] colorFamily;
        public final String[] type;
        public final String[] nameTokens;

        Snapshot(List<MediaFile> source) {
            files = new ArrayList<MediaFile>(source);
            int size = files.size();
            hashes = new long[size];
            hashPresent = new boolean[size];
            aspect = new float[size];
            logSize = new float[size];
            modified = new long[size];
            folders = new String[size];
            colorFamily = new String[size];
            type = new String[size];
            nameTokens = new String[size];
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
            SmartFeatureCache.Entry cached = cache.get(file.getPath(), file.getSize(),
                    file.getDateAdded(), -1);
            if (cached != null && cached.present) {
                result.hashes[i] = cached.hash;
                result.hashPresent[i] = true;
            }
            result.logSize[i] = file.getSize() <= 0 ? 0f
                    : (float) (Math.log(file.getSize()) / Math.log(2d));
            result.aspect[i] = file.getWidth() > 0 && file.getHeight() > 0
                    ? file.getWidth() / (float) file.getHeight() : 0f;
            result.colorFamily[i] = file.getColorFamily();
            result.type[i] = file.getType() == null ? "" : file.getType().name();
            result.nameTokens[i] = file.getName() == null ? ""
                    : file.getName().toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
            java.io.File parent = new java.io.File(file.getPath()).getParentFile();
            result.folders[i] = parent == null ? "" : parent.getAbsolutePath();
        }
        return result;
    }

    /** Keep feature cache keys aligned with organizer path mutations. */
    public void remapPaths(Map<String, String> oldToNew, java.util.Set<String> removed) {
        if (removed != null) {
            for (String path : removed) cache.removePath(path);
        }
        if (oldToNew != null) {
            for (Map.Entry<String, String> entry : oldToNew.entrySet()) {
                cache.remapPath(entry.getKey(), entry.getValue());
            }
        }
        cache.save();
    }

    public SmartFeatureCache getCache() { return cache; }
    public void clearMemory() { cache.clearMemory(); }
}
