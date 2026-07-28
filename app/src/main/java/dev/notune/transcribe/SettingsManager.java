package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal settings store for the AI post-processing layer.
 *
 * All user-facing settings (enabled flag, provider, base URL, model and system
 * prompt) are stored as marker files in {@code filesDir()} so they can be read
 * consistently from the main process and from the ":ime" process without
 * relying on cross-process SharedPreferences semantics. The only exception is
 * the API key, which is kept in EncryptedSharedPreferences as documented in
 * the project's AGENTS.md.
 */
public class SettingsManager {
    private static final String TAG = "SettingsManager";

    // SharedPreferences file used only for the encrypted API key.
    private static final String PREFS_NAME = "transcribe_settings";
    private static final String KEY_API_KEY = "api_key";

    // Legacy keys, kept only for the one-time migration to marker files.
    private static final String LEGACY_KEY_POST_PROCESS_ENABLED = "post_process_enabled";
    private static final String LEGACY_KEY_PROVIDER = "pp_provider";
    private static final String LEGACY_KEY_API_URL = "api_url";
    private static final String LEGACY_KEY_MODEL_NAME = "model_name";
    private static final String LEGACY_KEY_SYSTEM_PROMPT = "system_prompt";

    // Marker file names in filesDir(). Presence of pp_enabled means ON.
    private static final String PP_ENABLED_FILE = "pp_enabled";
    private static final String PP_PROVIDER_FILE = "pp_provider";
    private static final String PP_API_URL_FILE = "pp_api_url";
    private static final String PP_MODEL_FILE = "pp_model";
    private static final String PP_PROMPT_FILE = "pp_prompt";

    // Sentinel that guarantees the legacy -> marker migration runs at most once.
    private static final String MIGRATION_SENTINEL = "pp_migrated";

    private static final String DEFAULT_API_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    // Cached encrypted preferences instance so every post-process call does not
    // pay the cost of rebuilding the MasterKey/EncryptedSharedPreferences.
    private static volatile SharedPreferences cachedEncryptedPrefs;

    /**
     * Provider presets: known OpenAI-compatible endpoints. The user picks a
     * provider and only fills in the API key; "custom" exposes a free-form
     * base-URL field.
     */
    public static final class Provider {
        public final String id;
        public final String label;
        public final String baseUrl;      // null for custom (user-supplied)
        public final String defaultModel;

        Provider(String id, String label, String baseUrl, String defaultModel) {
            this.id = id;
            this.label = label;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
        }
    }

    public static final Provider[] PROVIDERS = new Provider[] {
        new Provider("groq",      "Groq",       "https://api.groq.com/openai/v1",       "llama-3.3-70b-versatile"),
        new Provider("openai",    "OpenAI",     "https://api.openai.com/v1",            "gpt-4o-mini"),
        new Provider("cerebras",  "Cerebras",   "https://api.cerebras.ai/v1",           "llama-3.3-70b"),
        new Provider("openrouter","OpenRouter", "https://openrouter.ai/api/v1",         "meta-llama/llama-3.3-70b-instruct"),
        new Provider("mistral",   "Mistral",    "https://api.mistral.ai/v1",            "mistral-small-latest"),
        new Provider("together",  "Together",   "https://api.together.xyz/v1",          "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
        new Provider("ollama",    "Ollama (local)", "http://localhost:11434/v1",        "llama3.2"),
        new Provider("custom",    "Custom",     null,                                    ""),
    };

    public static Provider providerById(String id) {
        for (Provider p : PROVIDERS) {
            if (p.id.equals(id)) return p;
        }
        return PROVIDERS[PROVIDERS.length - 1]; // custom
    }

    private final SharedPreferences prefs;
    private final Context appContext;

    private static final Object PREFS_LOCK = new Object();

    public SettingsManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Context getContext() {
        return appContext;
    }

    // ----------------------------------------------------------------------
    // Toggle (marker file)
    // ----------------------------------------------------------------------

    public boolean isPostProcessEnabled() {
        return new File(appContext.getFilesDir(), PP_ENABLED_FILE).exists();
    }

    public void setPostProcessEnabled(boolean enabled) {
        File marker = new File(appContext.getFilesDir(), PP_ENABLED_FILE);
        if (enabled) {
            try {
                marker.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create pp_enabled marker", e);
            }
        } else {
            marker.delete();
        }
    }

    public static boolean isPostProcessEnabled(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), PP_ENABLED_FILE).exists();
    }

    // ----------------------------------------------------------------------
    // Provider + effective base URL (marker file)
    // ----------------------------------------------------------------------

    public String getApiUrl() {
        String value = readMarker(PP_API_URL_FILE);
        return (value == null || value.isEmpty()) ? DEFAULT_API_URL : value;
    }

    public void setApiUrl(String url) {
        writeMarker(PP_API_URL_FILE, url);
    }

    public String getProviderId() {
        String value = readMarker(PP_PROVIDER_FILE);
        return (value == null || value.isEmpty()) ? "custom" : value;
    }

    public void setProviderId(String id) {
        writeMarker(PP_PROVIDER_FILE, id);
    }

    public String getEffectiveApiUrl() {
        Provider p = providerById(getProviderId());
        if (p.baseUrl != null) return p.baseUrl;
        return getApiUrl();
    }

    // ----------------------------------------------------------------------
    // API key (encrypted SharedPreferences)
    // ----------------------------------------------------------------------

    public String getApiKey() {
        try {
            SharedPreferences enc = encryptedPrefs();
            String encrypted = enc.getString(KEY_API_KEY, "");
            if (encrypted == null || encrypted.isEmpty()) {
                // Fallback: encrypted store wiped but a plain key exists.
                String plain = prefs.getString(KEY_API_KEY, "");
                return plain != null ? plain : "";
            }
            return encrypted;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read encrypted API key", e);
            return prefs.getString(KEY_API_KEY, "");
        }
    }

    public void setApiKey(String key) {
        try {
            encryptedPrefs().edit().putString(KEY_API_KEY, key).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write encrypted API key", e);
            prefs.edit().putString(KEY_API_KEY, key).apply();
        }
    }

    private SharedPreferences encryptedPrefs() throws Exception {
        SharedPreferences prefs = cachedEncryptedPrefs;
        if (prefs == null) {
            synchronized (PREFS_LOCK) {
                prefs = cachedEncryptedPrefs;
                if (prefs == null) {
                    MasterKey masterKey = new MasterKey.Builder(appContext)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();
                    prefs = EncryptedSharedPreferences.create(
                            appContext,
                            PREFS_NAME + "_encrypted",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );
                    cachedEncryptedPrefs = prefs;
                }
            }
        }
        return prefs;
    }

    // ----------------------------------------------------------------------
    // Model name + prompt (marker files)
    // ----------------------------------------------------------------------

    public String getModelName() {
        String value = readMarker(PP_MODEL_FILE);
        return (value == null || value.isEmpty()) ? DEFAULT_MODEL : value;
    }

    public void setModelName(String model) {
        writeMarker(PP_MODEL_FILE, model);
    }

    public String getActivePromptBody() {
        String p = readMarker(PP_PROMPT_FILE);
        return (p == null || p.trim().isEmpty()) ? getDefaultPrompt() : p;
    }

    public void setActivePromptBody(String prompt) {
        writeMarker(PP_PROMPT_FILE, prompt);
    }

    public String getDefaultPrompt() {
        return appContext.getString(R.string.pp_default_prompt);
    }

    // ----------------------------------------------------------------------
    // Marker file helpers
    // ----------------------------------------------------------------------

    private String readMarker(String fileName) {
        File f = new File(appContext.getFilesDir(), fileName);
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read marker file " + fileName, e);
            return null;
        }
    }

    private void writeMarker(String fileName, String value) {
        File dir = appContext.getFilesDir();
        File f = new File(dir, fileName);
        if (value == null || value.isEmpty()) {
            f.delete();
            return;
        }
        // Write to a temp file and rename so readers never see a half-written
        // marker, even when two processes/threads write concurrently.
        File temp = new File(dir, fileName + ".tmp");
        try (FileOutputStream os = new FileOutputStream(temp)) {
            os.write(value.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
            if (!temp.renameTo(f)) {
                Log.e(TAG, "Failed to rename marker file " + fileName);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write marker file " + fileName, e);
        } finally {
            temp.delete();
        }
    }

    // ----------------------------------------------------------------------
    // One-time migration from SharedPreferences to marker files.
    //
    // Runs in every process (main and ":ime") on first launch after update.
    // Three concurrency concerns:
    //   1. Sentinel check + legacy read + marker write is a TOCTOU race:
    //      both processes can pass the sentinel check, read the legacy
    //      SharedPreferences, and queue their own marker writes.
    //   2. SharedPreferences `apply()` is async: a trailing thread can still
    //      see the stale value even after another process flushed changes.
    //   3. The IME process can lag behind the main process. If the user
    //      disables post-processing (deletes the marker) while the IME is
    //      still in its migration block, a careless migration would resurrect
    //      the marker right after the user removed it.
    //
    // Fix: serialize the whole migration with an OS-level file lock on
    // filesDir()/pp_migrated.lock, and clear the legacy SharedPreferences
    // with a synchronous `commit()` before the sentinel so any later thread
    // that re-enters migration sees no legacy values.
    // ----------------------------------------------------------------------

    public static void migrateIfNeeded(Context context) {
        Context app = context.getApplicationContext();
        File filesDir = app.getFilesDir();
        File sentinel = new File(filesDir, MIGRATION_SENTINEL);
        if (sentinel.exists()) return;

        File lockFile = new File(filesDir, MIGRATION_SENTINEL + ".lock");
        FileLock lock = null;
        try (FileOutputStream fos = new FileOutputStream(lockFile, true)) {
            FileChannel channel = fos.getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                // Another process holds the lock and is migrating. Sleep briefly
                // so it can finish; the next App.onCreate will re-check the
                // sentinel and skip migration if necessary.
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                return;
            }

            // Re-check sentinel under the lock (the holder may have completed
            // in the meantime).
            if (sentinel.exists()) return;

            SharedPreferences legacy = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            if (legacy.contains(LEGACY_KEY_POST_PROCESS_ENABLED)) {
                boolean enabled = legacy.getBoolean(LEGACY_KEY_POST_PROCESS_ENABLED, false);
                if (enabled) {
                    try {
                        new File(filesDir, PP_ENABLED_FILE).createNewFile();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to create pp_enabled marker during migration", e);
                    }
                }
            }

            if (legacy.contains(LEGACY_KEY_PROVIDER)) {
                writeMarker(filesDir, PP_PROVIDER_FILE, legacy.getString(LEGACY_KEY_PROVIDER, "custom"));
            }

            if (legacy.contains(LEGACY_KEY_API_URL)) {
                writeMarker(filesDir, PP_API_URL_FILE, legacy.getString(LEGACY_KEY_API_URL, DEFAULT_API_URL));
            }

            if (legacy.contains(LEGACY_KEY_MODEL_NAME)) {
                writeMarker(filesDir, PP_MODEL_FILE, legacy.getString(LEGACY_KEY_MODEL_NAME, DEFAULT_MODEL));
            }

            if (legacy.contains(LEGACY_KEY_SYSTEM_PROMPT)) {
                String prompt = legacy.getString(LEGACY_KEY_SYSTEM_PROMPT, "");
                if (prompt != null && !prompt.isEmpty()) {
                    writeMarker(filesDir, PP_PROMPT_FILE, prompt);
                }
            }

            // Synchronously commit the legacy-key removal so any trailing
            // process that re-enters migration after this point reads empty
            // values and does not resurrect the marker. `apply()` is not
            // enough here because other processes read SharedPreferences from
            // disk, not from a shared cache.
            try {
                SharedPreferences.Editor editor = legacy.edit();
                editor.remove(LEGACY_KEY_POST_PROCESS_ENABLED);
                editor.remove(LEGACY_KEY_PROVIDER);
                editor.remove(LEGACY_KEY_API_URL);
                editor.remove(LEGACY_KEY_MODEL_NAME);
                editor.remove(LEGACY_KEY_SYSTEM_PROMPT);
                editor.commit();
            } catch (Exception e) {
                Log.e(TAG, "Failed to clear legacy SharedPreferences", e);
            }

            // Create the sentinel LAST so a trailing process that races with
            // us and somehow acquires the lock next sees the migration as
            // complete and exits early.
            try {
                sentinel.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create migration sentinel", e);
            }
        } catch (OverlappingFileLockException e) {
            // Another thread in the same JVM already holds the lock; treat as
            // success (it will complete the migration).
            Log.d(TAG, "Migration lock held by sibling thread");
        } catch (IOException e) {
            Log.e(TAG, "Failed during migration", e);
        } finally {
            if (lock != null && lock.isValid()) {
                try { lock.release(); } catch (IOException ignored) {}
            }
        }
    }

    private static void writeMarker(File filesDir, String fileName, String value) {
        File f = new File(filesDir, fileName);
        if (value == null || value.isEmpty()) {
            f.delete();
            return;
        }
        File temp = new File(filesDir, fileName + ".tmp");
        try (FileOutputStream os = new FileOutputStream(temp)) {
            os.write(value.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
            if (!temp.renameTo(f)) {
                Log.e(TAG, "Failed to rename marker file " + fileName);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write marker file " + fileName, e);
        } finally {
            temp.delete();
        }
    }

    // ----------------------------------------------------------------------
    // Keystore pre-warm: forces EncryptedSharedPreferences/MasterKey init so
    // the first post-process call does not pay the cold-start cost.
    // ----------------------------------------------------------------------

    public static void prewarmApiKey(Context context) {
        if (!isPostProcessEnabled(context)) return;
        try {
            SettingsManager sm = new SettingsManager(context);
            sm.getApiKey(); // triggers MasterKey/EncryptedSharedPreferences init
        } catch (Exception e) {
            Log.e(TAG, "Failed to prewarm API key", e);
        }
    }
}
