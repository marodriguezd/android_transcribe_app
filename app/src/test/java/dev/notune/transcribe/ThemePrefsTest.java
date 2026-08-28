package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Pure JVM unit test for ThemePrefs marker file persistence contract.
 */
public class ThemePrefsTest {
    private File tempDirectory;

    @Before
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("aura-theme-test").toFile();
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
    public void testThemeModeDefaultsAndPersistence() {
        // Default when missing: -1 (MODE_NIGHT_FOLLOW_SYSTEM)
        int defaultMode = MarkerFileHelper.readIntFromFile(tempDirectory, "theme_mode", -1);
        assertEquals(-1, defaultMode);

        // Persist Light mode (1: MODE_NIGHT_NO)
        MarkerFileHelper.writeIntToFile(tempDirectory, "theme_mode", 1);
        assertEquals(1, MarkerFileHelper.readIntFromFile(tempDirectory, "theme_mode", -1));

        // Persist Dark mode (2: MODE_NIGHT_YES)
        MarkerFileHelper.writeIntToFile(tempDirectory, "theme_mode", 2);
        assertEquals(2, MarkerFileHelper.readIntFromFile(tempDirectory, "theme_mode", -1));
    }
}
