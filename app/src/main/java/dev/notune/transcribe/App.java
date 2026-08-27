package dev.notune.transcribe;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    private static final String TAG = "App";
    private static final String LANGUAGE_FILE = "model_language";
    private static final String DEVICE_LANGUAGE_FILE = "device_language";

    // Completes when the one-time legacy→marker migration finishes in this
    // process. PostProcessSettingsActivity awaits it (with a timeout) before
    // letting the user edit settings, so the migration can never overwrite
    // fresh user changes with stale legacy values (race found in review,
    // 2026-08-06). Each process (main and ":ime") has its own latch, which is
    // exactly right: the migration is per-process and guarded by a file lock.
    private static final CountDownLatch PP_MIGRATION_LATCH = new CountDownLatch(1);
    // Ceiling so a hung Keystore can never block the settings screen; the
    // typical migration is <10 ms, so 1 s is generous while keeping the worst
    // case on the UI thread short (2nd review round, 2026-08-06).
    private static final long PP_MIGRATION_WAIT_MS = 1000;

    @Override
    public void onCreate() {
        super.onCreate();
        ThemePrefs.apply(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
        applyDeviceLanguageIfUnset();
        // Migrate post-processing settings from SharedPreferences to marker
        // files once. Runs in the main and ":ime" processes; the sentinel
        // makes it idempotent. Off the UI thread (O6): App.onCreate runs on
        // the main thread in both processes, and the migration can touch
        // Android Keystore (legacy encrypted API key), which is not instant.
        // All settings are read lazily from marker files afterwards, so a few
        // milliseconds of delay is invisible to every consumer.
        new Thread(() -> {
            try {
                SettingsManager.migrateIfNeeded(this);
            } catch (Exception t) {
                // Never let the background migration kill the process (review
                // finding, 2026-08-06): an unexpected RuntimeException (e.g.
                // ClassCastException while reading a legacy pref) would
                // otherwise crash the app from this thread. The migration is
                // best-effort — settings are read lazily from markers, so a
                // failed migration only means the legacy values stay unmoved.
                // Errors (OOM, ThreadDeath) are deliberately not caught so a
                // genuinely fatal condition still surfaces to the system.
                Log.e(TAG, "Post-processing migration failed", t);
            } finally {
                PP_MIGRATION_LATCH.countDown();
            }
        }, "pp-migration").start();
    }

    /**
     * Blocks (bounded) until the post-processing legacy→marker migration has
     * finished in this process, so a surface that both reads and writes PP
     * settings cannot race it. No-op after the first launch (the latch is
     * already at zero) and never blocks longer than the timeout.
     */
    public static void awaitPostProcessMigration() {
        try {
            PP_MIGRATION_LATCH.await(PP_MIGRATION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        try {
            PostProcessor.nativeTrimMemory(level);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {
        }
    }
}
