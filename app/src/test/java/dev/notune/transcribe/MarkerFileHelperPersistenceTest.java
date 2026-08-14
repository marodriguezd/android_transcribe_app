package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM persistence coverage for {@link MarkerFileHelper} that does NOT
 * require a {@code Context}/Robolectric. This is the operational test harness
 * the Guantelete gate (AGENTS.md §3 "Validación y estilo") relies on:
 * `testDebugUnitTest` runs these directly on the JVM so they execute fast in CI
 * without an emulator.
 */
public class MarkerFileHelperPersistenceTest {
    private File tempDirectory;

    @Before
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("transcribe-persist-test").toFile();
    }

    @After
    public void tearDown() {
        if (tempDirectory != null && tempDirectory.exists()) {
            File[] kids = tempDirectory.listFiles();
            if (kids != null) {
                for (File k : kids) k.delete();
            }
            tempDirectory.delete();
        }
    }

    @Test
    public void readStringTrimsWhitespace() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "ws.txt", "  hello  \n");
        assertEquals("hello", MarkerFileHelper.readStringFromFile(tempDirectory, "ws.txt", ""));
    }

    @Test
    public void readIntReturnsDefaultOnGarbage() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "bad.txt", "not-a-number");
        assertEquals(99, MarkerFileHelper.readIntFromFile(tempDirectory, "bad.txt", 99));
    }

    @Test
    public void readIntReturnsDefaultWhenMissing() {
        assertEquals(99, MarkerFileHelper.readIntFromFile(tempDirectory, "absent.txt", 99));
    }

    @Test
    public void writeIntRoundTripNegative() {
        MarkerFileHelper.writeIntToFile(tempDirectory, "neg.txt", -7);
        assertEquals(-7, MarkerFileHelper.readIntFromFile(tempDirectory, "neg.txt", 0));
    }

    @Test
    public void writeStringOverwritesExisting() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "ow.txt", "old");
        MarkerFileHelper.writeStringToFile(tempDirectory, "ow.txt", "new");
        assertEquals("new", MarkerFileHelper.readStringFromFile(tempDirectory, "ow.txt", ""));
    }

    @Test
    public void writeEmptyDeletesFile() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "del.txt", "data");
        assertTrue(new File(tempDirectory, "del.txt").exists());
        MarkerFileHelper.writeStringToFile(tempDirectory, "del.txt", "");
        assertFalse(new File(tempDirectory, "del.txt").exists());
    }

    @Test
    public void readStringMissingReturnsDefault() {
        assertEquals("fallback", MarkerFileHelper.readStringFromFile(tempDirectory, "nope.txt", "fallback"));
    }

    @Test
    public void hardwareBackendPersistenceRoundTrip() {
        assertEquals("cpu", MarkerFileHelper.readStringFromFile(tempDirectory, "hardware_backend", "cpu"));
        MarkerFileHelper.writeStringToFile(tempDirectory, "hardware_backend", "npu");
        assertEquals("npu", MarkerFileHelper.readStringFromFile(tempDirectory, "hardware_backend", "cpu"));
        MarkerFileHelper.writeStringToFile(tempDirectory, "hardware_backend", "gpu");
        assertEquals("gpu", MarkerFileHelper.readStringFromFile(tempDirectory, "hardware_backend", "cpu"));
    }

    @Test
    public void streamLatencyPersistenceRoundTrip() {
        assertEquals("13", MarkerFileHelper.readStringFromFile(tempDirectory, "stream_context_right", "13"));
        MarkerFileHelper.writeStringToFile(tempDirectory, "stream_context_right", "0");
        assertEquals("0", MarkerFileHelper.readStringFromFile(tempDirectory, "stream_context_right", "13"));
    }
}
