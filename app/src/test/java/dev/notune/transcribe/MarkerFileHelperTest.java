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

public class MarkerFileHelperTest {
    private File tempDirectory;

    @Before
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("transcribe-marker-test").toFile();
    }

    @After
    public void tearDown() {
        if (tempDirectory != null && tempDirectory.exists()) {
            for (File file : tempDirectory.listFiles()) {
                file.delete();
            }
            tempDirectory.delete();
        }
    }

    @Test
    public void testWriteStringAndReadString() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "test_marker.txt", "hello_world");
        String value = MarkerFileHelper.readStringFromFile(tempDirectory, "test_marker.txt", "default");
        assertEquals("hello_world", value);
    }

    @Test
    public void testReadStringMissingFileReturnsDefault() {
        String value = MarkerFileHelper.readStringFromFile(tempDirectory, "non_existent.txt", "fallback");
        assertEquals("fallback", value);
    }

    @Test
    public void testWriteIntAndReadInt() {
        MarkerFileHelper.writeIntToFile(tempDirectory, "test_int.txt", 42);
        int value = MarkerFileHelper.readIntFromFile(tempDirectory, "test_int.txt", 0);
        assertEquals(42, value);
    }

    @Test
    public void testWriteNullOrEmptyDeletesFile() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "delete_me.txt", "data");
        File file = new File(tempDirectory, "delete_me.txt");
        assertTrue(file.exists());

        MarkerFileHelper.writeStringToFile(tempDirectory, "delete_me.txt", "");
        assertFalse(file.exists());
    }
}
