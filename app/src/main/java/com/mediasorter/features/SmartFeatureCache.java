package com.mediasorter.features;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Small binary cache for Smart image/video feature hashes.
 *
 * The cache is deliberately keyed by path, size, and modified time.  A bad
 * cache is disposable state: it is deleted and the next Smart pass rebuilds
 * it rather than allowing an IO error to reach the Activity.
 */
public final class SmartFeatureCache {
    private static final int VERSION = 2;

    public static class Entry {
        public long size;
        public long modified;
        public long hash;
        public boolean present;
        /** 0 = no image hash, 1 = image dHash, 2 = sampled video frame. */
        public int kind;
    }

    private final File file;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();

    public SmartFeatureCache(File filesDir) {
        file = new File(filesDir, "smart_features.bin");
        load();
    }

    public synchronized Entry get(String path, long size, long modified) {
        return get(path, size, modified, -1);
    }

    public synchronized Entry get(String path, long size, long modified, int expectedKind) {
        Entry value = entries.get(path);
        if (value == null || value.size != size || value.modified != modified) return null;
        if (expectedKind >= 0 && value.kind != expectedKind) return null;
        Entry result = new Entry();
        result.size = value.size;
        result.modified = value.modified;
        result.hash = value.hash;
        result.present = value.present;
        result.kind = value.kind;
        return result;
    }

    public synchronized void put(String path, long size, long modified,
                                  long hash, boolean present) {
        put(path, size, modified, hash, present, present ? 1 : 0);
    }

    public synchronized void put(String path, long size, long modified,
                                  long hash, boolean present, int kind) {
        if (path == null) return;
        Entry value = new Entry();
        value.size = size;
        value.modified = modified;
        value.hash = hash;
        value.present = present;
        value.kind = kind;
        entries.put(path, value);
    }

    public synchronized void save() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        File temp = new File(parent, file.getName() + ".tmp");
        try {
            DataOutputStream out = new DataOutputStream(new FileOutputStream(temp));
            out.writeInt(VERSION);
            out.writeInt(entries.size());
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                out.writeUTF(item.getKey());
                Entry value = item.getValue();
                out.writeLong(value.size);
                out.writeLong(value.modified);
                out.writeLong(value.hash);
                out.writeBoolean(value.present);
                out.writeInt(value.kind);
            }
            out.flush();
            out.close();
            File backup = new File(parent, file.getName() + ".bak");
            if (backup.exists()) backup.delete();
            boolean hadOld = file.exists() && file.renameTo(backup);
            if (temp.renameTo(file)) {
                if (hadOld) backup.delete();
            } else {
                temp.delete();
                if (hadOld) backup.renameTo(file);
            }
        } catch (Exception ignored) {
            temp.delete();
        }
    }

    private synchronized void load() {
        if (!file.exists()) return;
        try {
            DataInputStream in = new DataInputStream(new FileInputStream(file));
            int version = in.readInt();
            if (version != 1 && version != VERSION) {
                in.close();
                entries.clear();
                file.delete();
                return;
            }
            int count = Math.max(0, Math.min(100000, in.readInt()));
            for (int i = 0; i < count; i++) {
                String path = in.readUTF();
                Entry value = new Entry();
                value.size = in.readLong();
                value.modified = in.readLong();
                value.hash = in.readLong();
                value.present = in.readBoolean();
                value.kind = version >= VERSION ? in.readInt() : (value.present ? 1 : 0);
                entries.put(path, value);
            }
            in.close();
        } catch (Throwable error) {
            entries.clear();
            file.delete();
        }
    }
}
