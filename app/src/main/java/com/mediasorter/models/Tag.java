package com.mediasorter.models;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "tags")
public class Tag {

    @PrimaryKey
    @NonNull
    private String name;
    private int    usageCount;
    private long   createdAt;

    public Tag(@NonNull String name) {
        this.name      = name;
        this.usageCount = 0;
        this.createdAt  = System.currentTimeMillis();
    }

    @NonNull
    public String getName()        { return name; }
    public int    getUsageCount()  { return usageCount; }
    public long   getCreatedAt()   { return createdAt; }

    public void setName(@NonNull String n) { name       = n; }
    public void setUsageCount(int c)       { usageCount = c; }
    public void setCreatedAt(long t)       { createdAt  = t; }

    public void incrementUsage() { usageCount++; }
    public void decrementUsage() { if (usageCount > 0) usageCount--; }
}
