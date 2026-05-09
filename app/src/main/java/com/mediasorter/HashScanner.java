package com.mediasorter;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashScanner {

    // Read only first 64KB for lightweight partial hash
    private static final int PARTIAL_SIZE = 64 * 1024;

    public static byte[] partialHash(String filePath) {
        byte[] buffer = new byte[PARTIAL_SIZE];
        int    bytesRead;

        try (FileInputStream fis = new FileInputStream(filePath)) {
            bytesRead = fis.read(buffer, 0, PARTIAL_SIZE);
            if (bytesRead <= 0) return null;

            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(buffer, 0, bytesRead);
            return md.digest();

        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    public static String hashToHex(byte[] hash) {
        if (hash == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static boolean hashesMatch(byte[] a, byte[] b) {
        if (a == null || b == null)  return false;
        if (a.length != b.length)    return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }
}
