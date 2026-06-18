package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class SearchManager {

    private List<MediaFile> fullList = new ArrayList<>();

    public void setFullList(List<MediaFile> files) {
        this.fullList = files;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public List<MediaFile> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(fullList);
        }

        String[] terms = query.toLowerCase().trim().split("\\s+");
        List<MediaFile> result = new ArrayList<>();

        for (MediaFile f : fullList) {
            if (matchesAll(f, terms)) result.add(f);
        }

        return result;
    }

    // All terms must match — AND logic
    private boolean matchesAll(MediaFile f, String[] terms) {
        for (String term : terms) {
            if (!matchesTerm(f, term)) return false;
        }
        return true;
    }

    private boolean matchesTerm(MediaFile f, String term) {
        // Filename
        if (f.getName().toLowerCase().contains(term)) return true;

        // Tags
        for (String tag : f.getTags()) {
            if (tag.toLowerCase().contains(term)) return true;
        }

        // Type filter — e.g. "type:image" or "type:video"
        if (term.startsWith("type:")) {
            String type = term.substring(5);
            return f.getType().name().toLowerCase().contains(type);
        }

        // Extension filter — e.g. "ext:jpg"
        if (term.startsWith("ext:")) {
            String ext = term.substring(4);
            return f.getName().toLowerCase().endsWith("." + ext);
        }

        // Size filter — e.g. "size:>1mb" or "size:<500kb"
        if (term.startsWith("size:")) {
            return matchesSize(f, term.substring(5));
        }

        // Tagged/untagged filter
        if (term.equals("tagged"))   return !f.getTags().isEmpty();
        if (term.equals("untagged")) return f.getTags().isEmpty();

        return false;
    }

    private boolean matchesSize(MediaFile f, String sizeExpr) {
        try {
            boolean gt = sizeExpr.startsWith(">");
            boolean lt = sizeExpr.startsWith("<");
            String  val = sizeExpr.substring(1).toLowerCase();

            long bytes = parseSize(val);
            if (gt) return f.getSize() > bytes;
            if (lt) return f.getSize() < bytes;
        } catch (Exception ignored) {}
        return false;
    }

    private long parseSize(String val) {
        if (val.endsWith("mb")) return Long.parseLong(
            val.replace("mb", "").trim()) * 1024 * 1024;
        if (val.endsWith("kb")) return Long.parseLong(
            val.replace("kb", "").trim()) * 1024;
        if (val.endsWith("gb")) return Long.parseLong(
            val.replace("gb", "").trim()) * 1024 * 1024 * 1024;
        return Long.parseLong(val.trim());
    }
}
