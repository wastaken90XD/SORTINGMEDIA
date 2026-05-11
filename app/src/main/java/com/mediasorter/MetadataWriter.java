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
            FileInputStream fis = new FileInputStream(filePath);
            byte[] original = new byte[fis.available()];
            fis.read(original);
            fis.close();

            // Validate PNG signature
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (original[i] != PNG_SIGNATURE[i]) {
                    Log.e(TAG, "Not a valid PNG");
                    return false;
                }
            }

            // Build XMP as iTXt chunk
            byte[] xmpBytes = buildXmp(tags);
            byte[] iTXtChunk = buildPngITXtChunk(xmpBytes);

            // Find position before IEND chunk
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(PNG_SIGNATURE);

            int pos = 8;
            boolean injected = false;

            while (pos < original.length - 4) {
                int chunkLen = readInt(original, pos);
                byte[] chunkType = Arrays.copyOfRange(original, pos + 4, pos + 8);

                // Remove existing XMP iTXt if present
                boolean isXmpChunk = Arrays.equals(chunkType, PNG_XTXT) &&
                    isXmpITXt(original, pos + 8);

                if (Arrays.equals(chunkType, PNG_IEND) && !injected) {
                    // Inject XMP before IEND
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

            FileInputStream fis = new FileInputStream(filePath);
            byte[] original = new byte[fis.available()];
            fis.read(original);
            fis.close();

            // Build XMP UUID atom
            // UUID for XMP: BE7ACFCB97A942E89C71999491E3AFAC
            byte[] xmpUuid = {
                (byte)0xBE, (byte)0x7A, (byte)0xCF, (byte)0xCB,
                (byte)0x97, (byte)0xA9, (byte)0x42, (byte)0xE8,
                (byte)0x9C, (byte)0x71, (byte)0x99, (byte)0x94,
                (byte)0x91, (byte)0xE3, (byte)0xAF, (byte)0xAC
            };

            int atomSize = 8 + 16 + xmpBytes.length; // size + 'uuid' + uuid + data
            byte[] atom = new byte[atomSize];

            // Size
            atom[0] = (byte)((atomSize >> 24) & 0xFF);
            atom[1] = (byte)((atomSize >> 16) & 0xFF);
            atom[2] = (byte)((atomSize >> 8) & 0xFF);
            atom[3] = (byte)(atomSize & 0xFF);
            // Type 'uuid'
            atom[4] = 0x75; // u
            atom[5] = 0x75; // u
            atom[6] = 0x69; // i
            atom[7] = 0x64; // d
            // UUID
            System.arraycopy(xmpUuid, 0, atom, 8, 16);
            // XMP data
            System.arraycopy(xmpBytes, 0, atom, 24, xmpBytes.length);

            // Remove existing XMP uuid atom if present
            byte[] cleaned = removeMp4Xmp(original, xmpUuid);

            // Append XMP atom before mdat or at end
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

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
