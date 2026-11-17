package com.mediasorter.models;

import com.mediasorter.TagText;
import java.util.ArrayList;
import java.util.List;
import java.io.Serializable;

public class MediaFile implements Serializable {
    
    private static final long serialVersionUID = 1L;
    public enum Type { IMAGE, VIDEO, UNSUPPORTED }

    private String path;
    private String name;
    private long   size;
    private long   dateAdded;
    private Type   type;
    private List<String> tags;
    private byte[] partialHash;
    private int width;
    private int height;
    private int manualOrder = -1;
    // Enriched by the last analysis/index pass. These fields stay in the
    // model so sorting and grouping never need to touch the UI thread.
    private String colorFamily = "";
    private boolean duplicate;
    private boolean metadataPresent;

    public MediaFile(String path, long size) {
        this.path    = path;
        this.name    = path.substring(path.lastIndexOf('/') + 1);
        this.size    = size;
        this.tags    = new ArrayList<>();
        this.type    = resolveType(name);
    }

    // Locale.US: on Turkish/Azeri devices toLowerCase() turns 'I' into a
    // dotless 'ı', which silently broke .GIF-type extension checks.
    private static final String[] IMAGE_EXTS = {"jpg", "jpeg", "png", "bmp", "webp", "gif"};
    private static final String[] VIDEO_EXTS = {"mp4", "3gp", "avi", "mkv", "mov", "webm"};

    /** Extension compare — much faster than String.matches (no regex compile per call). */
    public static boolean hasExtension(String lowerName, String[] exts) {
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = lowerName.substring(dot + 1);
        for (String e : exts) {
            if (ext.equals(e)) return true;
        }
        return false;
    }

    private Type resolveType(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        if (hasExtension(lower, IMAGE_EXTS)) return Type.IMAGE;
        if (hasExtension(lower, VIDEO_EXTS)) return Type.VIDEO;
        return Type.UNSUPPORTED;
    }

    // Getters
    public String       getPath()      { return path; }
    public String       getName()      { return name; }
    public long         getSize()      { return size; }
    public long         getDateAdded() { return dateAdded; }
    public Type         getType()      { return type; }
    public List<String> getTags()      { return tags; }
    public byte[]       getPartialHash() { return partialHash; }
    public int          getWidth()     { return width; }
    public int          getHeight()    { return height; }
    public int          getManualOrder() { return manualOrder; }
    public String       getColorFamily() { return colorFamily == null ? "" : colorFamily; }
    public boolean      isDuplicate()    { return duplicate; }
    public boolean      hasMetadata()    { return metadataPresent; }

    // Setters
    public void setDateAdded(long d)      { dateAdded    = d; }
    public void setTags(List<String> t) {
        tags = new ArrayList<>();
        if (t != null) {
            for (String tag : t) addTag(tag);
        }
    }
    public void setPartialHash(byte[] h)  { partialHash  = h; }
    public void setWidth(int w)           { width        = w; }
    public void setHeight(int h)          { height       = h; }
    public void setManualOrder(int order) { manualOrder  = order; }
    public void setColorFamily(String family) { colorFamily = family == null ? "" : family; }
    public void setDuplicate(boolean value) { duplicate = value; }
    public void setMetadataPresent(boolean value) { metadataPresent = value; }

    public void addTag(String tag) {
        String plain = TagText.plain(tag);
        if (!plain.isEmpty() && !tags.contains(plain)) tags.add(plain);
    }

    public void removeTag(String tag) {
        tags.remove(TagText.plain(tag));
    }

    public boolean hasTag(String tag) {
        return tags.contains(TagText.plain(tag));
    }

    public void setPath(String path) {
        this.path = path;
        if (path != null) this.name = path.substring(path.lastIndexOf('/') + 1);
    }

    public String getFormattedSize() {
        if (size < 1024)             return size + " B";
        if (size < 1024 * 1024)      return (size / 1024) + " KB";
        if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)) + " MB";
        return (size / (1024 * 1024 * 1024)) + " GB";
    }
}
