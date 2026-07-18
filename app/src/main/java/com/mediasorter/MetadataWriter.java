package com.mediasorter;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.io.IOException;

public class MetadataWriter {

    private static final String TAG = "MetadataWriter";

    // ── XMP block builder ─────────────────────────────────────────────────────

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

    private static byte[] buildXmp(List<String> tags) {
        StringBuilder xmp = new StringBuilder();
        xmp.append(XMP_HEADER);
        for (String tag : tags) {
            xmp.append("<rdf:li>")
               .append(escapeXml(tag))
               .append("</rdf:li>\n");
        }
        xmp.append(XMP_FOOTER);
        return xmp.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean writeTags(String filePath, List<String> tags) {
        File file = new File(filePath);
        Log.d(TAG, "Writing to: " + filePath);
        Log.d(TAG, "Exists: " + file.exists() + " Writable: " + file.canWrite());

        if (!file.exists() || !file.canWrite()) {
            Log.e(TAG, "File not writable");
            return false;
        }

        String lower = filePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return writeJpeg(filePath, tags);
        } else if (lower.endsWith(".png")) {
            return writePng(filePath, tags);
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mov")) {
            return writeMp4(filePath, tags);
        }

        Log.w(TAG, "Unsupported format: " + filePath);
        return false;
    }

    // ── JPEG ──────────────────────────────────────────────────────────────────

    private static final int    JPEG_SOI  = 0xFFD8;
    private static final int    JPEG_APP1 = 0xFFE1;
    private static final byte[] XMP_MAGIC =
        "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.UTF_8);

    private static boolean writeJpeg(String filePath, List<String> tags) {
        try {
            byte[] xmpBytes  = buildXmp(tags);
            byte[] appMarker = buildJpegApp1(xmpBytes);

            RandomAccessFile raf = new RandomAccessFile(filePath, "r");
            byte[] original = new byte[(int) raf.length()];
            raf.readFully(original);
            raf.close();

            if (original.length < 2 ||
                ((original[0] & 0xFF) << 8 | (original[1] & 0xFF)) != JPEG_SOI) {
                Log.e(TAG, "Not a valid JPEG");
                return false;
            }

            // Remove existing XMP APP1 if present
            original = removeJpegXmp(original);

            // Inject new XMP after SOI
            byte[] output = new byte[2 + appMarker.length + original.length - 2];
            output[0] = original[0];
            output[1] = original[1];
            System.arraycopy(appMarker, 0, output, 2, appMarker.length);
            System.arraycopy(original, 2, output, 2 + appMarker.length,
                original.length - 2);

            FileOutputStream fos = new FileOutputStream(filePath);
            fos.write(output);
            fos.close();

            Log.d(TAG, "JPEG XMP written successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "JPEG write failed: " + e.getMessage());
            return false;
        }
    }

    private static byte[] buildJpegApp1(byte[] xmpBytes) {
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

    private static byte[] removeJpegXmp(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(data[0]);
            out.write(data[1]);
            int pos = 2;
            while (pos < data.length - 1) {
                int marker = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                if ((marker & 0xFF00) != 0xFF00) break;
                if (pos + 3 >= data.length) break;
                int segLen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                boolean isXmp = marker == JPEG_APP1
                    && isXmpSegment(data, pos + 4);
                if (!isXmp) {
                    out.write(data, pos, 2 + segLen);
                }
                pos += 2 + segLen;
                if (marker == 0xFFDA) {
                    // Start of scan — write rest as-is
                    out.write(data, pos, data.length - pos);
                    break;
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            return data;
        }
    }

    private static boolean isXmpSegment(byte[] data, int offset) {
        if (offset + XMP_MAGIC.length > data.length) return false;
        for (int i = 0; i < XMP_MAGIC.length; i++) {
            if (data[offset + i] != XMP_MAGIC[i]) return false;
        }
        return true;
    }

    // ── PNG ───────────────────────────────────────────────────────────────────

    private static final byte[] PNG_SIGNATURE =
        {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND =
        {0x49, 0x45, 0x4E, 0x44};
    private static final byte[] PNG_XTXT =
        {0x69, 0x54, 0x58, 0x74}; // iTXt chunk type

    private static boolean writePng(String filePath, List<String> tags) {
    try {
        File file = new File(filePath);
        byte[] original = readAllBytes(file);   // safe full read
        if (original == null) {
            Log.e(TAG, "Failed to read PNG");
            return false;
        }

        // Validate PNG signature
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (original[i] != PNG_SIGNATURE[i]) {
                Log.e(TAG, "Not a valid PNG");
                return false;
            }
        }

        byte[] xmpBytes = buildXmp(tags);
        byte[] iTXtChunk = buildPngITXtChunk(xmpBytes);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PNG_SIGNATURE);

        int pos = 8;
        boolean injected = false;

        while (pos < original.length - 4) {
            int chunkLen = readInt(original, pos);
            byte[] chunkType = Arrays.copyOfRange(original, pos + 4, pos + 8);

            boolean isXmpChunk = Arrays.equals(chunkType, PNG_XTXT) &&
                    isXmpITXt(original, pos + 8);

            if (Arrays.equals(chunkType, PNG_IEND) && !injected) {
                out.write(iTXtChunk);
                injected = true;
            }

            if (!isXmpChunk) {
                out.write(original, pos, 12 + chunkLen);
            }

            pos += 12 + chunkLen;
        }

        FileOutputStream fos = new FileOutputStream(filePath);
        fos.write(out.toByteArray());
        fos.close();

        Log.d(TAG, "PNG XMP written successfully");
        return true;

    } catch (Exception e) {
        Log.e(TAG, "PNG write failed: " + e.getMessage());
        return false;
    }
}

    private static byte[] buildPngITXtChunk(byte[] xmpBytes) throws Exception {
        // iTXt keyword for XMP
        byte[] keyword = "XML:com.adobe.xmp\0".getBytes(StandardCharsets.UTF_8);
        // Compression flag (0=uncompressed), compression method (0), lang tag, translated keyword
        byte[] flags = {0x00, 0x00, 0x00, 0x00};

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(keyword);
        data.write(flags);
        data.write(xmpBytes);

        byte[] chunkData = data.toByteArray();
        int len = chunkData.length;

        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        // Length
        chunk.write((len >> 24) & 0xFF);
        chunk.write((len >> 16) & 0xFF);
        chunk.write((len >> 8) & 0xFF);
        chunk.write(len & 0xFF);
        // Type
        chunk.write(PNG_XTXT);
        // Data
        chunk.write(chunkData);
        // CRC
        CRC32 crc = new CRC32();
        crc.update(PNG_XTXT);
        crc.update(chunkData);
        long crcVal = crc.getValue();
        chunk.write((int)((crcVal >> 24) & 0xFF));
        chunk.write((int)((crcVal >> 16) & 0xFF));
        chunk.write((int)((crcVal >> 8) & 0xFF));
        chunk.write((int)(crcVal & 0xFF));

        return chunk.toByteArray();
    }

    private static boolean isXmpITXt(byte[] data, int offset) {
        byte[] keyword = "XML:com.adobe.xmp".getBytes(StandardCharsets.UTF_8);
        if (offset + keyword.length > data.length) return false;
        for (int i = 0; i < keyword.length; i++) {
            if (data[offset + i] != keyword[i]) return false;
        }
        return true;
    }

    // ── MP4 ───────────────────────────────────────────────────────────────────

    private static boolean writeMp4(String filePath, List<String> tags) {
    try {
        byte[] xmpBytes = buildXmp(tags);

        File file = new File(filePath);
        byte[] original = readAllBytes(file);
        if (original == null) {
            Log.e(TAG, "Failed to read MP4");
            return false;
        }

        byte[] xmpUuid = {
                (byte)0xBE, (byte)0x7A, (byte)0xCF, (byte)0xCB,
                (byte)0x97, (byte)0xA9, (byte)0x42, (byte)0xE8,
                (byte)0x9C, (byte)0x71, (byte)0x99, (byte)0x94,
                (byte)0x91, (byte)0xE3, (byte)0xAF, (byte)0xAC
        };

        int atomSize = 8 + 16 + xmpBytes.length;
        byte[] atom = new byte[atomSize];

        atom[0] = (byte)((atomSize >> 24) & 0xFF);
        atom[1] = (byte)((atomSize >> 16) & 0xFF);
        atom[2] = (byte)((atomSize >> 8) & 0xFF);
        atom[3] = (byte)(atomSize & 0xFF);
        atom[4] = 0x75; // u
        atom[5] = 0x75; // u
        atom[6] = 0x69; // i
        atom[7] = 0x64; // d
        System.arraycopy(xmpUuid, 0, atom, 8, 16);
        System.arraycopy(xmpBytes, 0, atom, 24, xmpBytes.length);

        byte[] cleaned = removeMp4Xmp(original, xmpUuid);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(cleaned);
        out.write(atom);

        FileOutputStream fos = new FileOutputStream(filePath);
        fos.write(out.toByteArray());
        fos.close();

        Log.d(TAG, "MP4 XMP written successfully");
        return true;

    } catch (Exception e) {
        Log.e(TAG, "MP4 write failed: " + e.getMessage());
        return false;
    }
}

    private static byte[] removeMp4Xmp(byte[] data, byte[] uuid) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int pos = 0;
            while (pos < data.length - 8) {
                int size = readInt(data, pos);
                if (size < 8 || pos + size > data.length) {
                    out.write(data, pos, data.length - pos);
                    break;
                }
                // Check if uuid atom with XMP uuid
                boolean isXmpAtom = data[pos+4] == 0x75 && data[pos+5] == 0x75
                    && data[pos+6] == 0x69 && data[pos+7] == 0x64
                    && pos + 24 <= data.length
                    && matchesUuid(data, pos + 8, uuid);

                if (!isXmpAtom) {
                    out.write(data, pos, size);
                }
                pos += size;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return data;
        }
    }

    private static boolean matchesUuid(byte[] data, int offset, byte[] uuid) {
        for (int i = 0; i < uuid.length; i++) {
            if (data[offset + i] != uuid[i]) return false;
        }
        return true;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
             | ((data[offset+1] & 0xFF) << 16)
             | ((data[offset+2] & 0xFF) << 8)
             |  (data[offset+3] & 0xFF);
    }

    private static byte[] readAllBytes(File file) {
    if (!file.exists() || file.length() > Integer.MAX_VALUE) return null;
    try (FileInputStream fis = new FileInputStream(file)) {
        byte[] data = new byte[(int) file.length()];
        int offset = 0;
        int remaining = data.length;
        while (remaining > 0) {
            int read = fis.read(data, offset, remaining);
            if (read < 0) break;
            offset += read;
            remaining -= read;
        }
        return (offset == data.length) ? data : Arrays.copyOf(data, offset);
    } catch (IOException e) {
        Log.e(TAG, "readAllBytes failed: " + e.getMessage());
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

    // ── Strip metadata ────────────────────────────────────────────────────────

    /**
     * Strip all JPEG metadata (EXIF, XMP, IPTC, comments) from a file.
     * If keepOrientation is true, the EXIF Orientation tag is preserved.
     * Re-encodes the image data without any APP1/APP13/COM segments.
     */
    public static boolean stripJpegMetadata(String filePath, boolean keepOrientation) {
        try {
            RandomAccessFile raf = new RandomAccessFile(filePath, "r");
            byte[] original = new byte[(int) raf.length()];
            raf.readFully(original);
            raf.close();

            if (original.length < 2 ||
                ((original[0] & 0xFF) << 8 | (original[1] & 0xFF)) != JPEG_SOI) {
                Log.e(TAG, "Not a valid JPEG for strip");
                return false;
            }

            // Extract orientation byte before stripping (if requested)
            byte[] orientationSegment = null;
            if (keepOrientation) {
                orientationSegment = extractExifOrientation(original);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // Write SOI
            out.write(original[0]);
            out.write(original[1]);

            int pos = 2;
            boolean passedSos = false;
            while (pos < original.length - 1) {
                int marker = ((original[pos] & 0xFF) << 8) | (original[pos + 1] & 0xFF);
                if ((marker & 0xFF00) != 0xFF00) {
                    if (!passedSos) break;
                    // After SOS, raw scan data — copy verbatim
                    out.write(original, pos, original.length - pos);
                    break;
                }
                if (pos + 3 >= original.length) break;
                int segLen = ((original[pos + 2] & 0xFF) << 8) | (original[pos + 3] & 0xFF);

                if (marker == 0xFFDA) {
                    // SOS — keep this marker and everything after
                    out.write(original, pos, original.length - pos);
                    passedSos = true;
                    break;
                }

                // Keep SOF, DQT, DHT, SOF markers (image structure)
                // Strip: APP1 (EXIF/XMP), APP13 (IPTC/Photoshop), COM (comment), APP2 (ICC)
                boolean keep = (marker == 0xFFC0 || marker == 0xFFC2 ||  // SOF0/SOF2
                                marker == 0xFFC4 ||                       // DHT
                                marker == 0xFFDB ||                       // DQT
                                marker == 0xFFDD ||                       // DRI
                                marker == 0xFFEE);                        // APP14 (Adobe)
                // Also keep DQT (0xFFDB), DHT (0xFFC4)
                if (!keep && marker != 0xFFE1 && marker != 0xFFED && marker != 0xFFFE && marker != 0xFFE2) {
                    // Keep unknown APP markers that are not EXIF/IPTC/Comment
                    if ((marker & 0xFFF0) == 0xFFE0 && marker != 0xFFE0) {
                        // Other APPn — strip
                    } else if (marker != 0xFFE1 && marker != 0xFFED && marker != 0xFFFE && marker != 0xFFE2) {
                        keep = true;
                    }
                }
                // Keep: SOF(0xC0-0xCF except EXIF), DHT(0xC4), DQT(0xDB), DRI(0xDD)
                // Strip: APP1(0xE1=EXIF/XMP), APP13(0xED=IPTC), COM(0xFE), APP2(0xE2=ICC)
                boolean isStructure = (marker >= 0xFFC0 && marker <= 0xFFC4) ||
                                      marker == 0xFFDB || marker == 0xFFDD;
                boolean isStrip = marker == JPEG_APP1 || marker == 0xFFED ||
                                  marker == 0xFFFE || marker == 0xFFE2;

                if (isStructure || !isStrip) {
                    out.write(original, pos, 2 + segLen);
                }
                pos += 2 + segLen;
            }

            // If we kept orientation, re-inject a minimal EXIF with just orientation
            if (orientationSegment != null) {
                // Insert orientation segment right after SOI
                byte[] fullOut = out.toByteArray();
                out.reset();
                out.write(fullOut[0]);
                out.write(fullOut[1]);
                out.write(orientationSegment);
                out.write(fullOut, 2, fullOut.length - 2);
            }

            FileOutputStream fos = new FileOutputStream(filePath);
            fos.write(out.toByteArray());
            fos.close();

            Log.d(TAG, "JPEG metadata stripped");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "JPEG strip failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Build a minimal EXIF APP1 segment that contains only the Orientation tag.
     * Returns null if orientation not found in original.
     */
    private static byte[] extractExifOrientation(byte[] data) {
        try {
            // Search for EXIF APP1 and extract orientation byte
            int pos = 2;
            while (pos < data.length - 4) {
                int marker = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                if ((marker & 0xFF00) != 0xFF00) break;
                if (pos + 3 >= data.length) break;
                int segLen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                if (marker == 0xFFE1) {
                    // Check if it's EXIF
                    if (pos + 10 < data.length) {
                        String header = new String(data, pos + 4, 6, StandardCharsets.US_ASCII);
                        if ("Exif\0\0".equals(header)) {
                            // Find orientation tag (0x0112) in IFD0
                            // Byte order at offset pos+10+8 = pos+18
                            int tiffStart = pos + 10; // after "Exif\0\0"
                            if (tiffStart + 8 < data.length) {
                                boolean bigEndian;
                                if (data[tiffStart] == 'M' && data[tiffStart + 1] == 'M') {
                                    bigEndian = true;
                                } else if (data[tiffStart] == 'I' && data[tiffStart + 1] == 'I') {
                                    bigEndian = false;
                                } else {
                                    return null;
                                }
                                int ifd0Offset = readShort(data, tiffStart + 4, bigEndian, tiffStart);
                                int entryPos = tiffStart + ifd0Offset;
                                if (entryPos + 2 >= data.length) return null;
                                int entryCount = readShort(data, entryPos, bigEndian, 0);
                                for (int i = 0; i < entryCount; i++) {
                                    int ePos = entryPos + 2 + (i * 12);
                                    if (ePos + 12 > data.length) break;
                                    int tag = readShort(data, ePos, bigEndian, 0);
                                    if (tag == 0x0112) {
                                        int orientation = readShort(data, ePos + 8, bigEndian, 0);
                                        if (orientation >= 1 && orientation <= 8) {
                                            return buildMinimalExifOrientation((byte) orientation, bigEndian);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return null;
                }
                pos += 2 + segLen;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not extract orientation: " + e.getMessage());
        }
        return null;
    }

    private static int readShort(byte[] data, int offset, boolean bigEndian, int tiffBase) {
        // For IFD offset calculation: value is relative to tiffBase
        // But for tag values stored inline, tiffBase should be 0
        if (bigEndian) {
            return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        } else {
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }
    }

    private static byte[] readInt32ForIfd(byte[] data, int offset, boolean bigEndian) {
        byte[] result = new byte[4];
        if (bigEndian) {
            result[0] = data[offset]; result[1] = data[offset + 1];
            result[2] = data[offset + 2]; result[3] = data[offset + 3];
        } else {
            result[0] = data[offset + 3]; result[1] = data[offset + 2];
            result[2] = data[offset + 1]; result[3] = data[offset];
        }
        return result;
    }

    /**
     * Build a minimal EXIF APP1 segment with just the Orientation tag (0x0112).
     */
    private static byte[] buildMinimalExifOrientation(byte orientation, boolean bigEndian) {
        try {
            ByteArrayOutputStream exif = new ByteArrayOutputStream();
            // "Exif\0\0" header
            exif.write("Exif\0\0".getBytes(StandardCharsets.US_ASCII));

            // TIFF header
            if (bigEndian) {
                exif.write('M'); exif.write('M');
            } else {
                exif.write('I'); exif.write('I');
            }
            // TIFF magic (42)
            if (bigEndian) { exif.write(0x00); exif.write(0x2A); }
            else { exif.write(0x2A); exif.write(0x00); }
            // IFD0 offset (8)
            if (bigEndian) { exif.write(0x00); exif.write(0x00); exif.write(0x00); exif.write(0x08); }
            else { exif.write(0x08); exif.write(0x00); exif.write(0x00); exif.write(0x00); }

            // IFD0: 1 entry
            if (bigEndian) { exif.write(0x00); exif.write(0x01); } // 1 entry
            else { exif.write(0x01); exif.write(0x00); }

            // Tag 0x0112 (Orientation)
            if (bigEndian) { exif.write(0x01); exif.write(0x12); }
            else { exif.write(0x12); exif.write(0x01); }
            // Type: SHORT (3)
            if (bigEndian) { exif.write(0x00); exif.write(0x03); }
            else { exif.write(0x03); exif.write(0x00); }
            // Count: 1
            if (bigEndian) { exif.write(0x00); exif.write(0x00); exif.write(0x00); exif.write(0x01); }
            else { exif.write(0x01); exif.write(0x00); exif.write(0x00); exif.write(0x00); }
            // Value (orientation, in upper byte for SHORT)
            if (bigEndian) { exif.write(0x00); exif.write(orientation); exif.write(0x00); exif.write(0x00); }
            else { exif.write(orientation); exif.write(0x00); exif.write(0x00); exif.write(0x00); }
            // Next IFD offset (0 = none)
            exif.write(0x00); exif.write(0x00); exif.write(0x00); exif.write(0x00);

            byte[] exifData = exif.toByteArray();
            int totalLen = 2 + exifData.length; // length field includes itself
            byte[] app1 = new byte[2 + totalLen];
            app1[0] = (byte) 0xFF;
            app1[1] = (byte) 0xE1;
            app1[2] = (byte) ((totalLen >> 8) & 0xFF);
            app1[3] = (byte) (totalLen & 0xFF);
            System.arraycopy(exifData, 0, app1, 4, exifData.length);
            return app1;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Strip all metadata from a PNG file (iTXt, tEXt, zTXt chunks).
     * Keeps only critical chunks: IHDR, PLTE, IDAT, IEND.
     */
    public static boolean stripPngMetadata(String filePath) {
        try {
            File file = new File(filePath);
            byte[] original = readAllBytes(file);
            if (original == null) {
                Log.e(TAG, "Failed to read PNG for strip");
                return false;
            }

            // Validate PNG signature
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (original[i] != PNG_SIGNATURE[i]) {
                    Log.e(TAG, "Not a valid PNG for strip");
                    return false;
                }
            }

            // Critical chunks to keep
            byte[] IHDR = {0x49, 0x48, 0x44, 0x52};
            byte[] PLTE = {0x50, 0x4C, 0x54, 0x45};
            byte[] IDAT = {0x49, 0x44, 0x41, 0x54};

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(PNG_SIGNATURE);

            int pos = 8;
            while (pos < original.length - 4) {
                int chunkLen = readInt(original, pos);
                byte[] chunkType = Arrays.copyOfRange(original, pos + 4, pos + 8);

                // Keep IHDR, PLTE, IDAT, IEND
                boolean isCritical = Arrays.equals(chunkType, IHDR) ||
                                     Arrays.equals(chunkType, PLTE) ||
                                     Arrays.equals(chunkType, IDAT) ||
                                     Arrays.equals(chunkType, PNG_IEND);

                if (isCritical) {
                    out.write(original, pos, 12 + chunkLen);
                }
                // Skip ancillary chunks (iTXt, tEXt, zTXt, gAMA, sRGB, etc.)
                pos += 12 + chunkLen;
            }

            FileOutputStream fos = new FileOutputStream(filePath);
            fos.write(out.toByteArray());
            fos.close();

            Log.d(TAG, "PNG metadata stripped");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "PNG strip failed: " + e.getMessage());
            return false;
        }
    }
}
