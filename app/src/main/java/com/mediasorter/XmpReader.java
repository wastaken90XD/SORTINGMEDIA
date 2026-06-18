package com.mediasorter;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmpReader {

    private static final String TAG = "XmpReader";

    private static final Pattern TAG_PATTERN =
        Pattern.compile("<rdf:li[^>]*>([^<]+)</rdf:li>");

    // ── Public API ────────────────────────────────────────────────────────────

    public static List<String> readTags(String filePath) {
        if (filePath == null || filePath.isEmpty()) return new ArrayList<>();
        try {
            String lower = filePath.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return readJpeg(filePath);
            } else if (lower.endsWith(".png")) {
                return readGeneric(filePath, false);
            } else if (lower.endsWith(".mp4") || lower.endsWith(".mov")) {
                return readGeneric(filePath, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed: " + filePath + " " + e.getMessage());
        }
        return new ArrayList<>();
    }

    // ── JPEG ──────────────────────────────────────────────────────────────────

    private static List<String> readJpeg(String filePath) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(filePath, "r");

        // Read first 128KB — XMP is always near start in JPEG
        int readSize = (int) Math.min(raf.length(), 131072);
        byte[] data  = new byte[readSize];
        raf.readFully(data);
        raf.close();

        // Validate JPEG
        if (data.length < 2
                || (data[0] & 0xFF) != 0xFF
                || (data[1] & 0xFF) != 0xD8) {
            return new ArrayList<>();
        }

        // Search for XMP magic bytes
        String content = new String(data, StandardCharsets.ISO_8859_1);
        return extractFromContent(content);
    }

    // ── PNG / MP4 — generic search ────────────────────────────────────────────

    private static List<String> readGeneric(String filePath,
                                             boolean fromEnd) throws Exception {
        File f        = new File(filePath);
        long fileSize = f.length();
        if (fileSize == 0) return new ArrayList<>();

        int readSize = (int) Math.min(fileSize, 524288); // 512KB
        byte[] data  = new byte[readSize];

        RandomAccessFile raf = new RandomAccessFile(filePath, "r");
        if (fromEnd) {
            // MP4 — XMP appended at end
            raf.seek(Math.max(0, fileSize - readSize));
        }
        raf.readFully(data);
        raf.close();

        String content = new String(data, StandardCharsets.ISO_8859_1);
        return extractFromContent(content);
    }

    // ── XMP extraction ────────────────────────────────────────────────────────

    private static List<String> extractFromContent(String content) {
        // Find XMP packet
        int xmpStart = content.indexOf("<?xpacket begin");
        if (xmpStart < 0) {
            // Try finding dc:subject directly without full xpacket
            xmpStart = content.indexOf("<dc:subject>");
            if (xmpStart < 0) return new ArrayList<>();
        }

        int xmpEnd = content.indexOf("<?xpacket end", xmpStart);
        String xmp = xmpEnd > xmpStart
            ? content.substring(xmpStart, xmpEnd + 30)
            : content.substring(xmpStart);

        return extractTagsFromXmp(xmp);
    }

    private static List<String> extractTagsFromXmp(String xmp) {
        List<String> tags = new ArrayList<>();
        if (xmp == null || xmp.isEmpty()) return tags;

        // Find dc:subject section
        int subjectStart = xmp.indexOf("<dc:subject>");
        int subjectEnd   = xmp.indexOf("</dc:subject>");
        if (subjectStart < 0 || subjectEnd < 0) return tags;

        String subject = xmp.substring(subjectStart, subjectEnd);

        Matcher m = TAG_PATTERN.matcher(subject);
        while (m.find()) {
            String tag = m.group(1).trim();
            if (!tag.isEmpty()) tags.add(tag);
        }

        return tags;
    }
}
