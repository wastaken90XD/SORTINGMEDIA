package com.mediasorter.models;

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

    public MediaFile(String path, long size) {
        this.path    = path;
        this.name    = path.substring(path.lastIndexOf('/') + 1);
        this.size    = size;
        this.tags    = new ArrayList<>();
        this.type    = resolveType(name);
    }

     private Type resolveType(String name) {
        String lower = name.toLowerCase();
        if (lower.matches(".*\\.(jpg|jpeg|png|bmp|webp|gif)")) return Type.IMAGE;
        if (lower.matches(".*\\.(mp4|3gp|avi|mkv|mov|webm)"))  return Type.VIDEO;
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

    // Setters
    public void setDateAdded(long d)      { dateAdded    = d; }
    public void setTags(List<String> t)   { tags         = t; }
    public void setPartialHash(byte[] h)  { partialHash  = h; }
    public void setWidth(int w)           { width        = w; }
    public void setHeight(int h)          { height       = h; }

    public void addTag(String tag) {
        if (!tags.contains(tag)) tags.add(tag);
    }

    public void removeTag(String tag) {
        tags.remove(tag);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getFormattedSize() {
        if (size < 1024)             return size + " B";
        if (size < 1024 * 1024)      return (size / 1024) + " KB";
        if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)) + " MB";
        return (size / (1024 * 1024 * 1024)) + " GB";
    }
}
