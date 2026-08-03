package dev.notune.transcribe;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

import java.io.File;
import java.util.Locale;

/**
 * Applies the saved dark-mode choice before any activity is created, and enables
 * Material You dynamic color on Android 12+. Runs in every process (including the
 * ":ime" keyboard process).
 *
 * Also defaults the transcription language to automatic detection on first run:
 * the bundled Nemotron 3.5 ASR model detects the spoken language natively across
 * 40 language-locales. The device's current language is kept in a separate
 * marker (`device_language`) used by the engine as the fallback hint for models
 * without native detection (e.g. Canary) — the old device-locale default.
 */
public class App extends Application {
    private static final String LANGUAGE_FILE = "model_language";
    private static final String DEVICE_LANGUAGE_FILE = "device_language";

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

    /// Writes "auto" as the transcription language the first time the app
    /// runs, when no language has been chosen yet (the bundled model detects
    /// the language natively). The device locale (BCP-47 tag, e.g. "es-ES",
    /// "en-US", "fr-FR") goes to `device_language`, the engine's fallback
    /// hint for models without native detection.
    private void applyDeviceLanguageIfUnset() {
        File f = new File(getFilesDir(), LANGUAGE_FILE);
        if (f.exists()) return;
        writeConfig(LANGUAGE_FILE, "auto");
        writeConfig(DEVICE_LANGUAGE_FILE, Locale.getDefault().toLanguageTag());
    }

    private void writeConfig(String name, String value) {
        // Atomic temp+rename write so the main process and ":ime" never read
        // a partially-written language marker (P1.2). Non-fatal on failure:
        // the model will fall back to its default language.
        MarkerFileHelper.writeString(this, name, value);
    }
}
