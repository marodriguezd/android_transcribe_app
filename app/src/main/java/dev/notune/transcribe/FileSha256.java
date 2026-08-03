package dev.notune.transcribe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Incremental SHA-256 helper used to verify a downloaded model file before it
 * is activated (P0.3). Mirrors the release build's {@code checkModels} gate:
 * debug's runtime download must expose the same observable guarantee — a
 * truncated or corrupted GGUF never becomes the active model.
 *
 * <p>Pure-JVM friendly (no Android framework classes) so the hash logic is
 * covered by the Guantelete unit-test gate.</p>
 */
public final class FileSha256 {

    private FileSha256() {
        // Utility class
    }

    /** Returns the lowercase hex SHA-256 of {@code file}. */
    public static String sha256Hex(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return sha256Hex(in);
        }
    }

    /** Reads {@code in} fully and returns its lowercase hex SHA-256. */
    public static String sha256Hex(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
