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

    private static final byte[] XMP_MAGIC =
        "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.UTF_8);

    private static final Pattern TAG_PATTERN =
        Pattern.compile("<rdf:li>([^<]+)</rdf:li>");

    // ── Public API ────────────────────────────────────────────────────────────

    public static List<String> readTags(String filePath) {
        try {
            String lower = filePath.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return readJpegTags(filePath);
            } else if (lower.endsWith(".png")) {
                return readPngTags(filePath);
            } else if (lower.endsWith(".mp4") || lower.endsWith(".mov")) {
                return readMp4Tags(filePath);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read tags from: " + filePath + " " + e.getMessage());
        }
        return new ArrayList<>();
    }

    // ── JPEG ──────────────────────────────────────────────────────────────────

    private static List<String> readJpegTags(String filePath) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(filePath, "r");
        byte[] data = new byte[(int) Math.min(raf.length(), 65536)];
        raf.readFully(data);
        raf.close();

        // Find XMP APP1 marker
        for (int i = 0; i < data.length - XMP_MAGIC.length - 4; i++) {
            if (data[i] == (byte)0xFF && data[i+1] == (byte)0xE1) {
                // Check for XMP magic
                int offset = i + 4;
                if (offset + XMP_MAGIC.length < data.length) {
                    boolean match = true;
                    for (int j = 0; j < XMP_MAGIC.length; j++) {
                        if (data[offset + j] != XMP_MAGIC[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        int xmpStart = offset + XMP_MAGIC.length;
                        int segLen   = ((data[i+2] & 0xFF) << 8) | (data[i+3] & 0xFF);
                        int xmpEnd   = Math.min(i + 2 + segLen, data.length);
                        String xmp   = new String(data, xmpStart,
                            xmpEnd - xmpStart, StandardCharsets.UTF_8);
                        return extractTagsFromXmp(xmp);
                    }
                }
            }
        }
        return new ArrayList<>();
    }

    // ── PNG ───────────────────────────────────────────────────────────────────

    private static final byte[] PNG_ITXT_KEYWORD =
        "XML:com.adobe.xmp".getBytes(StandardCharsets.UTF_8);

    private static List<String> readPngTags(String filePath) throws Exception {
        FileInputStream fis = new FileInputStream(filePath);
        byte[] data = new byte[fis.available()];
        fis.read(data);
        fis.close();

        if (data.length < 8) return new ArrayList<>();

        int pos = 8; // skip PNG signature
        while (pos < data.length - 12) {
            int chunkLen  = readInt(data, pos);
            if (chunkLen < 0 || pos + 12 + chunkLen > data.length) break;

            byte[] type = new byte[]{data[pos+4], data[pos+5], data[pos+6], data[pos+7]};
            String typeStr = new String(type, StandardCharsets.US_ASCII);

            if (typeStr.equals("iTXt")) {
                int dataStart = pos + 8;
                // Check keyword
                if (dataStart + PNG_ITXT_KEYWORD.length < data.length) {
                    boolean match = true;
                    for (int i = 0; i < PNG_ITXT_KEYWORD.length; i++) {
                        if (data[dataStart + i] != PNG_ITXT_KEYWORD[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        // Skip keyword + null + flags (4 bytes) + null lang + null translated
                        int xmpStart = dataStart + PNG_ITXT_KEYWORD.length + 1 + 4;
                        // Skip two null-terminated strings (lang tag, translated keyword)
                        while (xmpStart < data.length && data[xmpStart] != 0) xmpStart++;
                        xmpStart++; // skip null
                        while (xmpStart < data.length && data[xmpStart] != 0) xmpStart++;
                        xmpStart++; // skip null

                        int xmpEnd = pos + 8 + chunkLen;
                        if (xmpStart < xmpEnd && xmpEnd <= data.length) {
                            String xmp = new String(data, xmpStart,
                                xmpEnd - xmpStart, StandardCharsets.UTF_8);
                            return extractTagsFromXmp(xmp);
                        }
                    }
                }
            }

            pos += 12 + chunkLen;
        }
        return new ArrayList<>();
    }

    // ── MP4 ───────────────────────────────────────────────────────────────────

    private static final byte[] MP4_XMP_UUID = {
        (byte)0xBE, (byte)0x7A, (byte)0xCF, (byte)0xCB,
        (byte)0x97, (byte)0xA9, (byte)0x42, (byte)0xE8,
        (byte)0x9C, (byte)0x71, (byte)0x99, (byte)0x94,
        (byte)0x91, (byte)0xE3, (byte)0xAF, (byte)0xAC
    };

    private static List<String> readMp4Tags(String filePath) throws Exception {
        FileInputStream fis = new FileInputStream(filePath);
        // Only read first 512KB — XMP is usually near the start
        byte[] data = new byte[(int) Math.min(new File(filePath).length(), 524288)];
        fis.read(data);
        fis.close();

        int pos = 0;
        while (pos < data.length - 8) {
            int size = readInt(data, pos);
            if (size < 8 || pos + size > data.length) break;

            // Check for uuid atom
            if (data[pos+4] == 0x75 && data[pos+5] == 0x75
                    && data[pos+6] == 0x69 && data[pos+7] == 0x64
                    && pos + 24 <= data.length) {
                boolean match = true;
                for (int i = 0; i < MP4_XMP_UUID.length; i++) {
                    if (data[pos + 8 + i] != MP4_XMP_UUID[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    int xmpStart = pos + 24;
                    int xmpEnd   = pos + size;
                    if (xmpEnd <= data.length) {
                        String xmp = new String(data, xmpStart,
                            xmpEnd - xmpStart, StandardCharsets.UTF_8);
                        return extractTagsFromXmp(xmp);
                    }
                }
            }
            pos += size;
        }
        return new ArrayList<>();
    }

    // ── XMP parser ────────────────────────────────────────────────────────────

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

    // ── Helper ────────────────────────────────────────────────────────────────

    private static int readInt(byte[] data, int offset) {
        return ((data[offset]   & 0xFF) << 24)
             | ((data[offset+1] & 0xFF) << 16)
             | ((data[offset+2] & 0xFF) << 8)
             |  (data[offset+3] & 0xFF);
    }
}
