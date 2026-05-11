package com.mediasorter;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MetadataWriter {

    private static final String TAG = "MetadataWriter";

    // XMP namespace declarations
    private static final String XMP_HEADER =
        "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
        "<rdf:Description rdf:about=\"\"\n" +
        "    xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
        "<dc:subject>\n" +
        "<rdf:Bag>\n";

    private static final String XMP_FOOTER =
        "</rdf:Bag>\n" +
        "</dc:subject>\n" +
        "</rdf:Description>\n" +
        "</rdf:RDF>\n" +
        "</x:xmpmeta>\n" +
        "<?xpacket end=\"w\"?>";

    // JPEG markers
    private static final int JPEG_SOI  = 0xFFD8;
    private static final int JPEG_APP1 = 0xFFE1;
    private static final int JPEG_SOF0 = 0xFFC0;

    private static final byte[] XMP_MAGIC =
        "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.UTF_8);

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean writeTags(String filePath, List<String> tags) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return writeJpegXmp(filePath, tags);
        }
        // PNG and others — skip for now
        Log.w(TAG, "Unsupported format for metadata writing: " + filePath);
        return false;
    }

    // ── JPEG XMP writer ───────────────────────────────────────────────────────

    private static boolean writeJpegXmp(String filePath, List<String> tags) {
        try {
            File file = new File(filePath);
            Log.d(TAG, "Attempting write to: " + filePath);
            Log.d(TAG, "File exists: " + file.exists());
            Log.d(TAG, "File writable: " + file.canWrite());
            Log.d(TAG, "File size: " + file.length());
            if (!file.exists() || !file.canWrite()) return false;

            // Build XMP block
            StringBuilder xmp = new StringBuilder();
            xmp.append(XMP_HEADER);
            for (String tag : tags) {
                xmp.append("<rdf:li>")
                   .append(escapeXml(tag))
                   .append("</rdf:li>\n");
            }
            xmp.append(XMP_FOOTER);

            byte[] xmpBytes  = xmp.toString().getBytes(StandardCharsets.UTF_8);
            byte[] appMarker = buildApp1Marker(xmpBytes);

            // Read original JPEG
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            byte[] original = new byte[(int) raf.length()];
            raf.readFully(original);
            raf.close();

            // Validate JPEG
            if (original.length < 2 ||
                ((original[0] & 0xFF) << 8 | (original[1] & 0xFF)) != JPEG_SOI) {
                Log.e(TAG, "Not a valid JPEG: " + filePath);
                return false;
            }

            // Find insertion point — after SOI, skip existing XMP APP1 if present
            int insertAt = 2;
            int pos      = 2;

            while (pos < original.length - 1) {
                int marker = ((original[pos] & 0xFF) << 8) | (original[pos + 1] & 0xFF);
                if (marker == JPEG_SOF0 || pos == 2) {
                    insertAt = pos;
                    break;
                }
                if ((marker & 0xFF00) != 0xFF00) break;

                // Check if this APP1 is XMP
                if (marker == JPEG_APP1 && pos + 4 < original.length) {
                    int segLen = ((original[pos + 2] & 0xFF) << 8)
                               | (original[pos + 3] & 0xFF);
                    boolean isXmp = isXmpSegment(original, pos + 4);
                    if (isXmp) {
                        // Skip existing XMP segment
                        insertAt = pos;
                        // Rebuild without old XMP
                        byte[] rebuilt = rebuildWithoutXmp(original, pos, segLen + 2);
                        if (rebuilt != null) original = rebuilt;
                        break;
                    }
                    pos += 2 + segLen;
                } else {
                    if (pos + 3 >= original.length) break;
                    int segLen = ((original[pos + 2] & 0xFF) << 8)
                               | (original[pos + 3] & 0xFF);
                    pos += 2 + segLen;
                }
            }

            // Write new JPEG with XMP injected
            byte[] output = new byte[2 + appMarker.length + original.length - 2];
            // SOI
            output[0] = original[0];
            output[1] = original[1];
            // New XMP APP1
            System.arraycopy(appMarker, 0, output, 2, appMarker.length);
            // Rest of original JPEG (after SOI)
            System.arraycopy(original, 2, output, 2 + appMarker.length,
                original.length - 2);

            // Write back to file
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(output);
            fos.close();

            Log.d(TAG, "XMP written to: " + filePath + " tags: " + tags);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to write XMP: " + e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] buildApp1Marker(byte[] xmpBytes) {
        // APP1 = 0xFFE1 + 2 byte length + XMP magic + xmp data
        int totalLen = 2 + XMP_MAGIC.length + xmpBytes.length;
        byte[] marker = new byte[2 + totalLen];
        marker[0] = (byte) 0xFF;
        marker[1] = (byte) 0xE1;
        marker[2] = (byte) ((totalLen >> 8) & 0xFF);
        marker[3] = (byte) (totalLen & 0xFF);
        System.arraycopy(XMP_MAGIC, 0, marker, 4, XMP_MAGIC.length);
        System.arraycopy(xmpBytes, 0, marker, 4 + XMP_MAGIC.length, xmpBytes.length);
        return marker;
    }

    private static boolean isXmpSegment(byte[] data, int offset) {
        if (offset + XMP_MAGIC.length > data.length) return false;
        for (int i = 0; i < XMP_MAGIC.length; i++) {
            if (data[offset + i] != XMP_MAGIC[i]) return false;
        }
        return true;
    }

    private static byte[] rebuildWithoutXmp(byte[] original, int xmpStart, int xmpLen) {
        try {
            byte[] rebuilt = new byte[original.length - xmpLen];
            System.arraycopy(original, 0, rebuilt, 0, xmpStart);
            System.arraycopy(original, xmpStart + xmpLen, rebuilt,
                xmpStart, original.length - xmpStart - xmpLen);
            return rebuilt;
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
