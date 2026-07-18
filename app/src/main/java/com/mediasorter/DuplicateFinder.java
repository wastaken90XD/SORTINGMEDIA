package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds duplicate files by grouping on file size, then comparing partial hashes.
 */
public class DuplicateFinder {

    public static class DuplicateGroup {
        public long size;
        public List<MediaFile> files = new ArrayList<>();

        DuplicateGroup(long size) {
            this.size = size;
        }
    }

    public interface ProgressCallback {
        void onProgress(int scanned, int total, String fileName);
    }

    /**
     * Scan the given file list and return groups of 2+ files that share
     * the same size AND the same partial-hash (first 64 KB MD5).
     */
    public static List<DuplicateGroup> findDuplicates(List<MediaFile> files,
                                                       ProgressCallback callback) {
        // Step 1: group by exact size
        Map<Long, List<MediaFile>> sizeGroups = new HashMap<>();
        for (MediaFile f : files) {
            long size = f.getSize();
            List<MediaFile> group = sizeGroups.get(size);
            if (group == null) {
                group = new ArrayList<>();
                sizeGroups.put(size, group);
            }
            group.add(f);
        }

        // Step 2: within each size-group with 2+ files, compare partial hashes
        List<DuplicateGroup> results = new ArrayList<>();
        int scanned = 0;
        int total = files.size();

        for (Map.Entry<Long, List<MediaFile>> entry : sizeGroups.entrySet()) {
            List<MediaFile> group = entry.getValue();
            if (group.size() < 2) continue;

            // Group by hash within this size-group
            Map<String, DuplicateGroup> hashGroups = new HashMap<>();
            for (MediaFile f : group) {
                scanned++;
                if (callback != null) callback.onProgress(scanned, total, f.getName());

                byte[] hash = f.getPartialHash();
                if (hash == null) {
                    hash = HashScanner.partialHash(f.getPath());
                    f.setPartialHash(hash);
                }
                String hex = HashScanner.hashToHex(hash);

                DuplicateGroup dg = hashGroups.get(hex);
                if (dg == null) {
                    dg = new DuplicateGroup(entry.getKey());
                    hashGroups.put(hex, dg);
                }
                dg.files.add(f);
            }

            // Only keep groups with actual duplicates
            for (DuplicateGroup dg : hashGroups.values()) {
                if (dg.files.size() >= 2) {
                    results.add(dg);
                }
            }
        }

        return results;
    }
}
