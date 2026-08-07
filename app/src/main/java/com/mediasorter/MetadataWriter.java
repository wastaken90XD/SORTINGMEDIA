package com.mediasorter;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Writes XMP tags into JPEG / PNG / MP4 files and strips metadata.
 *
 * RENOVATED (OOM + data-safety):
 *  - Everything is streamed with a small fixed buffer. The previous
 *    implementation read each ENTIRE file into memory (up to 3 copies of a
 *    500 MB video) and OOM-crashed on any realistically-sized library.
 *  - Writes go to a temporary sibling file first and are then swapped in via
 *    delete+rename. The previous implementation truncated the original file
 *    and rewrote it in place — an interruption mid-write destroyed photos.
 */
public class MetadataWriter {

    private static final String TAG = "MetadataWriter";
    private static final int    BUFFER_SIZE = 256 * 1024; // 256 KB streaming buffer
    private static final String TMP_SUFFIX  = ".xmp_tmp";

    // ── XMP block builder ─────────────────────────────────────────────────────

    private static final String XMP_HEADER =
        "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
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

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean writeTags(String filePath, List<String> tags) {
        File file = new File(filePath);
        if (!file.exists() || !file.canWrite()) {
            Log.e(TAG, "File not writable: " + filePath);
            return false;
        }

        String lower = filePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return transform(file, (in, out, buf) -> writeJpegStream(in, out, buf, tags));
        } else if (lower.endsWith(".png")) {
            return transform(file, (in, out, buf) -> writePngStream(in, out, buf, tags));
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mov")) {
            return transform(file, (in, out, buf) -> writeMp4Stream(in, out, buf, tags));
        }

        Log.w(TAG, "Unsupported format: " + filePath);
        return false;
    }

    // ── Transform plumbing: stream original -> temp, then swap atomically ─────

    private interface StreamTransform {
        /** Copy/transform in -> out. Throw IOException on failure. */
        void run(InputStream in, OutputStream out, byte[] buf) throws IOException;
    }

    /**
     * Runs the transform into a temp file in the same directory and swaps it
     * in only when the transform completed cleanly. If anything goes wrong
     * the original file is left untouched.
     */
    private static boolean transform(File file, StreamTransform t) {
        File tmp = new File(file.getParentFile(), file.getName() + TMP_SUFFIX);
        boolean ok = false;
        try (FileInputStream in = new FileInputStream(file);
             FileOutputStream out = new FileOutputStream(tmp)) {
            t.run(in, out, new byte[BUFFER_SIZE]);
            out.getFD().sync();
            ok = true;
        } catch (Exception e) {
            Log.e(TAG, "Transform failed for " + file.getName() + ": " + e.getMessage());
        }

        if (!ok) {
            if (tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "Could not delete temp file " + tmp.getAbsolutePath());
            }
            return false;
        }

        // Swap: original -> backup name, temp -> original. Keep it simple and
        // safe on the same directory (renameTo is atomic within one mount).
        if (!file.delete()) {
            Log.e(TAG, "Could not delete original before swap: " + file.getAbsolutePath());
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(file)) {
            Log.e(TAG, "Rename of temp file failed: " + tmp.getAbsolutePath());
            tmp.delete();
            return false;
        }
        return true;
    }

    private static long copyBytes(InputStream in, OutputStream out, byte[] buf,
                                   long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) throw new EOFException("Unexpected end of stream");
            out.write(buf, 0, n);
            remaining -= n;
        }
        return count;
    }

    private static void copyRest(InputStream in, OutputStream out, byte[] buf)
            throws IOException {
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long n = in.skip(remaining);
            if (n <= 0) {
                if (in.read() < 0) throw new EOFException("Unexpected end of stream");
                n = 1;
            }
            remaining -= n;
        }
    }

    // ── JPEG ──────────────────────────────────────────────────────────────────

    private static final byte[] XMP_MAGIC =
        "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_JPEG_SEGMENT = 0xFFFF; // 16-bit segment length

    private static byte[] buildJpegApp1(byte[] xmpBytes) throws IOException {
        int totalLen = 2 + XMP_MAGIC.length + xmpBytes.length;
        if (totalLen > MAX_JPEG_SEGMENT) {
            throw new IOException("XMP too large for JPEG segment");
        }
        byte[] marker = new byte[4 + totalLen - 2];
        marker[0] = (byte) 0xFF;
        marker[1] = (byte) 0xE1;
        marker[2] = (byte) ((totalLen >> 8) & 0xFF);
        marker[3] = (byte) (totalLen & 0xFF);
        System.arraycopy(XMP_MAGIC, 0, marker, 4, XMP_MAGIC.length);
        System.arraycopy(xmpBytes, 0, marker, 4 + XMP_MAGIC.length, xmpBytes.length);
        return marker;
    }

    /** Marker codes that have no length field (standalone). */
    private static boolean isStandaloneMarker(int marker) {
        return marker == 0x01                      // TEM
            || marker == 0xD8 || marker == 0xD9    // SOI / EOI
            || (marker >= 0xD0 && marker <= 0xD7); // RSTn
    }

    private static void writeJpegStream(InputStream in, OutputStream out,
                                        byte[] buf, List<String> tags) throws IOException {
        int soi0 = in.read();
        int soi1 = in.read();
        if (soi0 != 0xFF || soi1 != 0xD8) throw new IOException("Not a valid JPEG");

        out.write(0xFF);
        out.write(0xD8);
        out.write(buildJpegApp1(buildXmp(tags)));

        while (true) {
            int b0 = in.read();
            if (b0 < 0) return; // degenerate but nothing left to copy
            if (b0 != 0xFF) {
                // Outside a marker (should not happen before SOS, but be
                // tolerant): copy this byte and the rest verbatim.
                out.write(b0);
                copyRest(in, out, buf);
                return;
            }
            int marker = in.read();
            if (marker < 0) { out.write(b0); return; }
            if (marker == 0xFF) {           // fill byte before marker
                out.write(0xFF);
                continue;
            }

            if (isStandaloneMarker(marker)) {
                out.write(0xFF);
                out.write(marker);
                continue;
            }

            if (marker == 0xDA) {           // SOS: copy everything verbatim
                out.write(0xFF);
                out.write(marker);
                copyRest(in, out, buf);
                return;
            }

            int hi = in.read(), lo = in.read();
            if (hi < 0 || lo < 0) throw new EOFException("Truncated JPEG segment");
            int segLen = ((hi & 0xFF) << 8) | (lo & 0xFF);
            if (segLen < 2) throw new IOException("Bad JPEG segment length");
            int payload = segLen - 2;

            // Probe the payload head so we can detect an XMP APP1 segment
            // without a pushback stream: bytes we read are held in `probe`
            // and echoed back out when the segment turns out to be kept.
            int probeLen = Math.min(payload, marker == 0xE1 ? XMP_MAGIC.length : 0);
            byte[] probe = new byte[probeLen];
            if (probeLen > 0) readFully(in, probe);

            if (marker == 0xE1 && probeLen == XMP_MAGIC.length && eq(probe, XMP_MAGIC)) {
                // Existing XMP segment -> drop it; our fresh one was already
                // written right after the SOI. IMPORTANT: the FF E1 marker
                // bytes must NOT be emitted — emitting them would plant a
                // phantom segment and desync the whole marker stream.
                skipFully(in, payload - probeLen);
                continue;
            }

            out.write(0xFF);
            out.write(marker);
            out.write(hi);
            out.write(lo);
            if (probeLen > 0) out.write(probe);
            copyBytes(in, out, buf, payload - probeLen);
        }
    }

    // ── PNG ───────────────────────────────────────────────────────────────────

    private static final byte[] PNG_SIGNATURE =
        {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND = {0x49, 0x45, 0x4E, 0x44};
    private static final byte[] PNG_ITXT = {0x69, 0x54, 0x58, 0x74};
    private static final byte[] PNG_XMP_KEYWORD =
        "XML:com.adobe.xmp".getBytes(StandardCharsets.UTF_8);

    private static byte[] buildPngITXtChunk(byte[] xmpBytes) throws IOException {
        byte[] keyword = "XML:com.adobe.xmp\0".getBytes(StandardCharsets.UTF_8);
        byte[] flags   = {0x00, 0x00, 0x00, 0x00};

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(keyword);
        data.write(flags);
        data.write(xmpBytes);
        byte[] chunkData = data.toByteArray();
        int len = chunkData.length;

        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write((len >> 24) & 0xFF);
        chunk.write((len >> 16) & 0xFF);
        chunk.write((len >> 8)  & 0xFF);
        chunk.write(len & 0xFF);
        chunk.write(PNG_ITXT);
        chunk.write(chunkData);

        CRC32 crc = new CRC32();
        crc.update(PNG_ITXT);
        crc.update(chunkData);
        long crcVal = crc.getValue();
        chunk.write((int) ((crcVal >> 24) & 0xFF));
        chunk.write((int) ((crcVal >> 16) & 0xFF));
        chunk.write((int) ((crcVal >> 8)  & 0xFF));
        chunk.write((int) (crcVal & 0xFF));
        return chunk.toByteArray();
    }

    private static void writePngStream(InputStream in, OutputStream out,
                                       byte[] buf, List<String> tags) throws IOException {
        byte[] sig = new byte[8];
        readFully(in, sig);
        for (int i = 0; i < 8; i++) {
            if (sig[i] != PNG_SIGNATURE[i]) throw new IOException("Not a valid PNG");
        }
        out.write(sig);

        byte[] iTXtChunk = buildPngITXtChunk(buildXmp(tags));
        boolean injected = false;
        byte[] header = new byte[8];

        while (true) {
            int got = readUpTo(in, header, 8);
            if (got == 0) return;                       // EOF without IEND
            if (got < 8) { out.write(header, 0, got); copyRest(in, out, buf); return; }

            long chunkLen = ((long) (header[0] & 0xFF) << 24)
                          | ((long) (header[1] & 0xFF) << 16)
                          | ((long) (header[2] & 0xFF) << 8)
                          | ((long) (header[3] & 0xFF));
            if (chunkLen > Integer.MAX_VALUE - 12) throw new IOException("Bad PNG chunk");
            byte[] type = {header[4], header[5], header[6], header[7]};
            long bodyPlusCrc = chunkLen + 4;

            boolean isIend = eq(type, PNG_IEND);
            if (isIend && !injected) {
                out.write(iTXtChunk);
                injected = true;
            }

            // Probe the chunk payload head to recognise our XMP iTXt chunk
            // without a pushback stream; probed bytes are echoed back out
            // for chunks that are kept.
            boolean isITXt = eq(type, PNG_ITXT);
            int probeLen = (isITXt && chunkLen >= PNG_XMP_KEYWORD.length)
                ? PNG_XMP_KEYWORD.length : 0;
            byte[] probe = new byte[probeLen];
            if (probeLen > 0) readFully(in, probe);

            boolean isXmpITXt = isITXt && probeLen == PNG_XMP_KEYWORD.length
                && eq(probe, PNG_XMP_KEYWORD);

            if (isXmpITXt) {
                skipFully(in, bodyPlusCrc - probeLen); // drop stale XMP chunk
            } else {
                out.write(header, 0, 8);
                if (probeLen > 0) out.write(probe);
                copyBytes(in, out, buf, bodyPlusCrc - probeLen);
            }

            if (isIend) { copyRest(in, out, buf); return; }
        }
    }

    private static boolean eq(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static void readFully(InputStream in, byte[] dst) throws IOException {
        int offset = 0;
        while (offset < dst.length) {
            int n = in.read(dst, offset, dst.length - offset);
            if (n < 0) throw new EOFException("Unexpected EOF");
            offset += n;
        }
    }

    private static int readUpTo(InputStream in, byte[] dst, int max) throws IOException {
        int offset = 0;
        while (offset < max) {
            int n = in.read(dst, offset, max - offset);
            if (n < 0) break;
            offset += n;
        }
        return offset;
    }

    // ── MP4 ───────────────────────────────────────────────────────────────────

    private static final byte[] XMP_UUID = {
        (byte)0xBE, (byte)0x7A, (byte)0xCF, (byte)0xCB,
        (byte)0x97, (byte)0xA9, (byte)0x42, (byte)0xE8,
        (byte)0x9C, (byte)0x71, (byte)0x99, (byte)0x94,
        (byte)0x91, (byte)0xE3, (byte)0xAF, (byte)0xAC
    };
    private static final byte[] MP4_UUID_TYPE = {0x75, 0x75, 0x69, 0x64}; // "uuid"

    private static long readU32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
             | ((long) (b[off + 1] & 0xFF) << 16)
             | ((long) (b[off + 2] & 0xFF) << 8)
             | ((long) (b[off + 3] & 0xFF));
    }

    private static void writeU32(byte[] b, int off, long v) {
        b[off]     = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8)  & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static void writeMp4Stream(InputStream in, OutputStream out,
                                       byte[] buf, List<String> tags) throws IOException {
        byte[] header  = new byte[8];   // size(4) + type(4)
        byte[] uuidBuf = new byte[16];
        boolean wroteAny = false;

        while (true) {
            int got = readUpTo(in, header, 8);
            if (got == 0) break;                      // clean EOF: append ours below
            if (got < 8) { out.write(header, 0, got); copyRest(in, out, buf); break; }

            long size = readU32(header, 0);
            int headerLen = 8;
            byte[] lsHeader = null;                    // 64-bit largesize, if used
            if (size == 1) {
                lsHeader = new byte[8];
                readFully(in, lsHeader);
                long hi = readU32(lsHeader, 0);
                long lo = readU32(lsHeader, 4);
                size = (hi << 32) | lo;
                headerLen = 16;
            }

            boolean toEof = (size == 0);
            if (!toEof && size < headerLen) throw new IOException("Bad MP4 atom");

            boolean isUuidType = header[4] == 0x75 && header[5] == 0x75
                              && header[6] == 0x69 && header[7] == 0x64;

            long body = toEof ? -1 : size - headerLen;

            if (isUuidType && !toEof && body >= 16) {
                readFully(in, uuidBuf);
                if (eq(uuidBuf, XMP_UUID)) {
                    skipFully(in, body - 16);          // drop stale XMP atom
                    continue;
                }
                // Not an XMP uuid atom: replay header + uuid, then copy rest
                out.write(header, 0, 8);
                if (lsHeader != null) out.write(lsHeader);
                out.write(uuidBuf);
                copyBytes(in, out, buf, body - 16);
                wroteAny = true;
                continue;
            }

            out.write(header, 0, 8);
            if (lsHeader != null) out.write(lsHeader);
            if (toEof) {
                copyRest(in, out, buf);
                wroteAny = true;
                break;
            }
            copyBytes(in, out, buf, body);
            wroteAny = true;
        }

        if (!wroteAny) throw new IOException("Empty MP4");

        byte[] xmpBytes = buildXmp(tags);
        long atomSize = 8 + 16 + xmpBytes.length;
        if (atomSize > 0xFFFFFFFFL - 24) throw new IOException("XMP too large");
        byte[] head = new byte[8 + 16];
        writeU32(head, 0, atomSize);
        head[4] = MP4_UUID_TYPE[0]; head[5] = MP4_UUID_TYPE[1];
        head[6] = MP4_UUID_TYPE[2]; head[7] = MP4_UUID_TYPE[3];
        System.arraycopy(XMP_UUID, 0, head, 8, 16);
        out.write(head);
        out.write(xmpBytes);
    }

    // ── Strip metadata ────────────────────────────────────────────────────────

    /**
     * Strip JPEG metadata (APP1 EXIF/XMP, APP13 IPTC, APP2 ICC, COM comments)
     * in one streaming pass. If keepOrientation is set, a pre-pass locates the
     * EXIF orientation byte (reading only segment headers + the EXIF payload)
     * and re-injects a minimal EXIF APP1 right after the SOI.
     */
    public static boolean stripJpegMetadata(String filePath, boolean keepOrientation) {
        File file = new File(filePath);
        if (!file.exists() || !file.canWrite()) return false;

        byte[] orientationSegment = null;
        if (keepOrientation) {
            try {
                orientationSegment = findExifOrientationSegment(file);
            } catch (Exception e) {
                Log.w(TAG, "Orientation pre-scan failed: " + e.getMessage());
            }
        }

        final byte[] orientSeg = orientationSegment;
        return transform(file, new StreamTransform() {
            @Override
            public void run(InputStream in, OutputStream out, byte[] buf) throws IOException {
                stripJpegStream(in, out, buf, orientSeg);
            }
        });
    }

    private static void stripJpegStream(InputStream in, OutputStream out,
                                        byte[] buf, byte[] orientationSegment) throws IOException {
        int soi0 = in.read();
        int soi1 = in.read();
        if (soi0 != 0xFF || soi1 != 0xD8) throw new IOException("Not a valid JPEG");

        out.write(0xFF);
        out.write(0xD8);
        if (orientationSegment != null) out.write(orientationSegment);

        while (true) {
            int b0 = in.read();
            if (b0 < 0) return;
            if (b0 != 0xFF) { out.write(b0); copyRest(in, out, buf); return; }
            int marker = in.read();
            if (marker < 0) { out.write(b0); return; }
            if (marker == 0xFF) { out.write(0xFF); continue; }

            if (isStandaloneMarker(marker)) {
                out.write(0xFF); out.write(marker);
                continue;
            }

            if (marker == 0xDA) {           // SOS + scan data: verbatim
                out.write(0xFF); out.write(marker);
                copyRest(in, out, buf);
                return;
            }

            int hi = in.read(), lo = in.read();
            if (hi < 0 || lo < 0) throw new EOFException("Truncated JPEG segment");
            int segLen = ((hi & 0xFF) << 8) | (lo & 0xFF);
            if (segLen < 2) throw new IOException("Bad JPEG segment length");
            int payload = segLen - 2;

            // Keep structural segments (SOF0..SOF15, DHT, DQT, DRI, APPn that
            // are not EXIF/XMP/IPTC/ICC). Strip APP1, APP13, APP2, COM.
            boolean strip = marker == 0xE1 || marker == 0xED
                         || marker == 0xE2 || marker == 0xFE;
            if (strip) {
                skipFully(in, payload);
            } else {
                out.write(0xFF); out.write(marker);
                out.write(hi);   out.write(lo);
                copyBytes(in, out, buf, payload);
            }
        }
    }

    /** Strip a PNG down to critical chunks (IHDR, PLTE, IDAT, IEND). */
    public static boolean stripPngMetadata(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.canWrite()) return false;
        return transform(file, new StreamTransform() {
            @Override
            public void run(InputStream in, OutputStream out, byte[] buf) throws IOException {
                stripPngStream(in, out, buf);
            }
        });
    }

    private static final byte[] PNG_IHDR = {0x49, 0x48, 0x44, 0x52};
    private static final byte[] PNG_PLTE = {0x50, 0x4C, 0x54, 0x45};
    private static final byte[] PNG_IDAT = {0x49, 0x44, 0x41, 0x54};

    private static void stripPngStream(InputStream in, OutputStream out,
                                       byte[] buf) throws IOException {
        byte[] sig = new byte[8];
        readFully(in, sig);
        for (int i = 0; i < 8; i++) {
            if (sig[i] != PNG_SIGNATURE[i]) throw new IOException("Not a valid PNG");
        }
        out.write(sig);
        byte[] header = new byte[8];
        while (true) {
            int got = readUpTo(in, header, 8);
            if (got == 0) return;
            if (got < 8) { out.write(header, 0, got); copyRest(in, out, buf); return; }

            long chunkLen = ((long) (header[0] & 0xFF) << 24)
                          | ((long) (header[1] & 0xFF) << 16)
                          | ((long) (header[2] & 0xFF) << 8)
                          | ((long) (header[3] & 0xFF));
            if (chunkLen > Integer.MAX_VALUE - 12) throw new IOException("Bad PNG chunk");
            byte[] type = {header[4], header[5], header[6], header[7]};
            long bodyPlusCrc = chunkLen + 4;

            boolean keep = eq(type, PNG_IHDR) || eq(type, PNG_PLTE)
                        || eq(type, PNG_IDAT) || eq(type, PNG_IEND);
            if (keep) {
                out.write(header, 0, 8);
                copyBytes(in, out, buf, bodyPlusCrc);
            } else {
                skipFully(in, bodyPlusCrc);
            }
            if (eq(type, PNG_IEND)) { copyRest(in, out, buf); return; }
        }
    }

    // ── EXIF orientation (pre-scan reads < 128 KB) ────────────────────────────

    private static byte[] findExifOrientationSegment(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.read() != 0xFF || raf.read() != 0xD8) return null;
            while (true) {
                int marker;
                do { marker = raf.read(); } while (marker == 0xFF); // skip fill bytes
                if (marker < 0) return null;
                // Note: after the FF-skip loop, `marker` IS the segment code
                // (E1, DB, C0, …) — not a prefix. Do not read twice here.
                if (isStandaloneMarker(marker)) continue;
                if (marker == 0xDA) return null;              // SOS: no more headers
                int hi = raf.read(), lo = raf.read();
                if (hi < 0 || lo < 0) return null;
                int segLen = ((hi & 0xFF) << 8) | (lo & 0xFF);
                if (segLen < 2) return null;
                int payload = segLen - 2;
                if (marker == 0xE1 && payload >= 6 && payload <= MAX_JPEG_SEGMENT) {
                    byte[] seg = new byte[payload];
                    raf.readFully(seg);
                    String header = new String(seg, 0, 6, StandardCharsets.US_ASCII);
                    if ("Exif\0\0".equals(header)) {
                        return extractOrientationFromExif(seg);
                    }
                } else {
                    long skipped = raf.skipBytes(payload);
                    if (skipped < payload) return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** seg = APP1 payload starting with "Exif\0\0". */
    private static byte[] extractOrientationFromExif(byte[] seg) {
        try {
            int tiff = 6; // after Exif\0\0
            if (seg.length < tiff + 8) return null;
            boolean bigEndian;
            if (seg[tiff] == 'M' && seg[tiff + 1] == 'M') bigEndian = true;
            else if (seg[tiff] == 'I' && seg[tiff + 1] == 'I') bigEndian = false;
            else return null;

            // Offset to IFD0 is a 4-byte value (reading only 2 bytes yields 0
            // for every standards-compliant EXIF header).
            long ifd0 = readU32Endian(seg, tiff + 4, bigEndian);
            int entryPos = tiff + (int) ifd0;
            if (entryPos + 2 > seg.length) return null;
            int count = readU16(seg, entryPos, bigEndian);
            for (int i = 0; i < count; i++) {
                int ePos = entryPos + 2 + i * 12;
                if (ePos + 12 > seg.length) break;
                int tag = readU16(seg, ePos, bigEndian);
                if (tag == 0x0112) {
                    int orientation = readU16(seg, ePos + 8, bigEndian);
                    if (orientation >= 1 && orientation <= 8) {
                        return buildMinimalExifOrientation((byte) orientation, bigEndian);
                    }
                    return null;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int readU16(byte[] d, int off, boolean bigEndian) {
        if (bigEndian) return ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static long readU32Endian(byte[] d, int off, boolean bigEndian) {
        if (bigEndian) {
            return ((long) (d[off] & 0xFF) << 24)
                 | ((long) (d[off + 1] & 0xFF) << 16)
                 | ((long) (d[off + 2] & 0xFF) << 8)
                 | ((long) (d[off + 3] & 0xFF));
        }
        return ((long) (d[off] & 0xFF))
             | ((long) (d[off + 1] & 0xFF) << 8)
             | ((long) (d[off + 2] & 0xFF) << 16)
             | ((long) (d[off + 3] & 0xFF) << 24);
    }

    /** Minimal EXIF APP1 segment containing only the Orientation tag. */
    private static byte[] buildMinimalExifOrientation(byte orientation, boolean bigEndian) {
        try {
            ByteArrayOutputStream exif = new ByteArrayOutputStream();
            exif.write("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
            if (bigEndian) { exif.write('M'); exif.write('M'); }
            else           { exif.write('I'); exif.write('I'); }
            if (bigEndian) { exif.write(0x00); exif.write(0x2A); }
            else           { exif.write(0x2A); exif.write(0x00); }
            if (bigEndian) { exif.write(0x00); exif.write(0x00); exif.write(0x00); exif.write(0x08); }
            else           { exif.write(0x08); exif.write(0x00); exif.write(0x00); exif.write(0x00); }
            if (bigEndian) { exif.write(0x00); exif.write(0x01); }
            else           { exif.write(0x01); exif.write(0x00); }
            if (bigEndian) { exif.write(0x01); exif.write(0x12); }
            else           { exif.write(0x12); exif.write(0x01); }
            if (bigEndian) { exif.write(0x00); exif.write(0x03); }
            else           { exif.write(0x03); exif.write(0x00); }
            if (bigEndian) { exif.write(0x00); exif.write(0x00); exif.write(0x00); exif.write(0x01); }
            else           { exif.write(0x01); exif.write(0x00); exif.write(0x00); exif.write(0x00); }
            if (bigEndian) { exif.write(0x00); exif.write(orientation); exif.write(0x00); exif.write(0x00); }
            else           { exif.write(orientation); exif.write(0x00); exif.write(0x00); exif.write(0x00); }
            exif.write(0x00); exif.write(0x00); exif.write(0x00); exif.write(0x00);

            byte[] exifData = exif.toByteArray();
            int totalLen = 2 + exifData.length;
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
}
