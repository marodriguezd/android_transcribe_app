package dev.notune.transcribe;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Plain-JVM coverage for {@link FileSha256}, the helper that verifies the
 * debug runtime model download before activation (P0.3). Known vectors pin the
 * implementation; the mismatch case models a truncated/corrupted download,
 * which must never be accepted as the active model.
 */
public class FileSha256Test {

    @Test
    public void knownVectorEmptyInput() throws Exception {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                FileSha256.sha256Hex(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void knownVectorAbc() throws Exception {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                FileSha256.sha256Hex(
                        new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void truncatedContentHashesDiffer() throws Exception {
        // A truncated model file (the P0.3 failure mode) must hash differently
        // from the complete file, so the download gate rejects it.
        String full = FileSha256.sha256Hex(new ByteArrayInputStream(
                "hello world".getBytes(StandardCharsets.US_ASCII)));
        String truncated = FileSha256.sha256Hex(new ByteArrayInputStream(
                "hello".getBytes(StandardCharsets.US_ASCII)));
        assertNotEquals(full, truncated);
    }

    @Test
    public void fileVariantMatchesStreamVariant() throws Exception {
        File file = File.createTempFile("sha256", ".bin");
        try {
            Files.write(file.toPath(), "content".getBytes(StandardCharsets.US_ASCII));
            assertEquals(
                    FileSha256.sha256Hex(new ByteArrayInputStream(
                            "content".getBytes(StandardCharsets.US_ASCII))),
                    FileSha256.sha256Hex(file));
        } finally {
            file.delete();
        }
    }
}

