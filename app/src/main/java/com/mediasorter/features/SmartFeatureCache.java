package com.mediasorter.features;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Small binary cache for Smart feature hashes, rebuilt safely when corrupt. */
public final class SmartFeatureCache {
    public static class Entry {
        public long size;
        public long modified;
        public long hash;
        public boolean present;
    }
    private final File file;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();

    public SmartFeatureCache(File filesDir) {
        file = new File(filesDir, "smart_features.bin");
        load();
    }

    public synchronized Entry get(String path, long size, long modified) {
        Entry value = entries.get(path);
        if (value == null || value.size != size || value.modified != modified) return null;
        Entry result = new Entry();
        result.size = value.size; result.modified = value.modified;
        result.hash = value.hash; result.present = value.present;
        return result;
    }

    public synchronized void put(String path, long size, long modified, long hash, boolean present) {
        Entry value = new Entry();
        value.size = size; value.modified = modified; value.hash = hash; value.present = present;
        entries.put(path, value);
    }

    public synchronized void save() {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            DataOutputStream out = new DataOutputStream(new FileOutputStream(temp));
            out.writeInt(1);
            out.writeInt(entries.size());
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                out.writeUTF(item.getKey());
                Entry value = item.getValue();
                out.writeLong(value.size); out.writeLong(value.modified);
                out.writeLong(value.hash); out.writeBoolean(value.present);
            }
            out.flush(); out.close();
            if (file.exists() && !file.delete()) temp.delete();
            else if (!temp.renameTo(file)) temp.delete();
        } catch (Exception ignored) { temp.delete(); }
    }

    private synchronized void load() {
        if (!file.exists()) return;
        try {
            DataInputStream in = new DataInputStream(new FileInputStream(file));
            if (in.readInt() != 1) { in.close(); file.delete(); return; }
            int count = Math.max(0, Math.min(100000, in.readInt()));
            for (int i = 0; i < count; i++) {
                String path = in.readUTF();
                Entry value = new Entry();
                value.size = in.readLong(); value.modified = in.readLong();
                value.hash = in.readLong(); value.present = in.readBoolean();
                entries.put(path, value);
            }
            in.close();
        } catch (Exception error) {
            entries.clear(); file.delete();
        }
    }
}
