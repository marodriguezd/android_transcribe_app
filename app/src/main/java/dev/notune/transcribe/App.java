package dev.notune.transcribe;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Applies the saved dark-mode choice before any activity is created, and enables
 * Material You dynamic color on Android 12+. Runs in every process (including the
 * ":ime" keyboard process).
 *
 * Also defaults the transcription language to the device's current language on
 * first run: an empty language left the model with no hint, so it defaulted to
 * English for every input language. With this, speech is transcribed in the
 * phone's system language by default, and the user can still override it in
 * ModelsActivity's language dropdown.
 */
public class App extends Application {
    private static final String LANGUAGE_FILE = "model_language";

    @Override
    public void onCreate() {
        super.onCreate();
        ThemePrefs.apply(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
        applyDeviceLanguageIfUnset();
        // Migrate post-processing settings from SharedPreferences to marker
        // files once. Runs in the main and ":ime" processes; the sentinel
        // makes it idempotent.
        SettingsManager.migrateIfNeeded(this);
    }

    /// Writes the device's current language (BCP-47 tag, e.g. "es-ES",
    /// "en-US", "fr-FR") as the transcription language the first time the app
    /// runs, when no language has been chosen yet.
    private void applyDeviceLanguageIfUnset() {
        File f = new File(getFilesDir(), LANGUAGE_FILE);
        if (f.exists()) return;
        writeConfig(LANGUAGE_FILE, Locale.getDefault().toLanguageTag());
    }

    private void writeConfig(String name, String value) {
        File f = new File(getFilesDir(), name);
        if (value == null || value.isEmpty()) {
            f.delete();
            return;
        }
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Non-fatal: the model will fall back to its default language.
        }
    }
}
