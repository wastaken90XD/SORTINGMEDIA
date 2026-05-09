package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class SearchManager {

    public enum Mode { NAME, TAG, BOTH }

    private List<MediaFile> fullList = new ArrayList<>();
    private Mode            mode     = Mode.BOTH;

    public void setFullList(List<MediaFile> files) {
        this.fullList = files;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    // AND logic — file must match ALL query tokens
    public List<MediaFile> search(String query) {
        if (query == null || query.trim().isEmpty()) return new ArrayList<>(fullList);

        String[]        tokens  = query.trim().toLowerCase().split("\\s+");
        List<MediaFile> results = new ArrayList<>();

        for (MediaFile file : fullList) {
            if (matchesAll(file, tokens)) results.add(file);
        }

        return results;
    }

    private boolean matchesAll(MediaFile file, String[] tokens) {
        for (String token : tokens) {
            if (!matchesToken(file, token)) return false;
        }
        return true;
    }

    private boolean matchesToken(MediaFile file, String token) {
        switch (mode) {
            case NAME:
                return file.getName().toLowerCase().contains(token);
            case TAG:
                return hasMatchingTag(file, token);
            case BOTH:
            default:
                return file.getName().toLowerCase().contains(token)
                    || hasMatchingTag(file, token);
        }
    }

    private boolean hasMatchingTag(MediaFile file, String token) {
        for (String tag : file.getTags()) {
            if (tag.toLowerCase().contains(token)) return true;
        }
        return false;
    }
}
