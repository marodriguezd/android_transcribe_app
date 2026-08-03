package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

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
 * All settings (enabled flag, provider, base URL, model, system prompt and
 * API key) are stored as marker files in {@code filesDir()} so they can be
 * read consistently from the main process and from the ":ime" process
 * without relying on cross-process SharedPreferences or Keystore semantics.
 * The API key is Base64-encoded for minimal obscurity; real protection comes
 * from the Android app sandbox that guards filesDir().
 */
public class SettingsManager implements PostProcessor.PostProcessorSettings {
    private static final String TAG = "SettingsManager";

    // SharedPreferences file used only for the one-time legacy migration.
    private static final String PREFS_NAME = "transcribe_settings";

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
    private static final String PP_API_KEY_FILE = "pp_api_key";

    // Sentinel that guarantees the legacy -> marker migration runs at most once.
    private static final String MIGRATION_SENTINEL = "pp_migrated";

    private static final String DEFAULT_API_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";



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

    private final Context appContext;

    public SettingsManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public Context getContext() {
        return appContext;
    }

    // ----------------------------------------------------------------------
    // Toggle (marker file)
    // ----------------------------------------------------------------------

    public boolean isPostProcessEnabled() {
        return MarkerFileHelper.exists(appContext, PP_ENABLED_FILE);
    }

    public void setPostProcessEnabled(boolean enabled) {
        MarkerFileHelper.setExists(appContext, PP_ENABLED_FILE, enabled);
    }

    public static boolean isPostProcessEnabled(Context context) {
        return MarkerFileHelper.exists(context, PP_ENABLED_FILE);
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
    // API key (marker file, Base64-encoded)
    // ----------------------------------------------------------------------

    public String getApiKey() {
        String encoded = readMarker(PP_API_KEY_FILE);
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            return new String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode API key from marker", e);
            return "";
        }
    }

    public void setApiKey(String key) {
        if (key == null || key.isEmpty()) {
            writeMarker(PP_API_KEY_FILE, null);
            return;
        }
        writeMarker(PP_API_KEY_FILE,
                Base64.encodeToString(key.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
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
        return MarkerFileHelper.readString(appContext, fileName, null);
    }

    private void writeMarker(String fileName, String value) {
        MarkerFileHelper.writeString(appContext, fileName, value);
    }

    // ----------------------------------------------------------------------
    // One-time migration from SharedPreferences to marker files.
    //
    // Runs in every process (main and ":ime") on first launch after update.
    // Concurrency concerns this protects against:
    //   1. Sentinel check + legacy read + marker write is a TOCTOU race:
    //      both processes can pass the sentinel check, read the legacy
    //      SharedPreferences, and queue their own marker writes.
    //   2. The IME process can lag behind the main process. If the user
    //      disables post-processing (deletes the marker) while the IME is
    //      still in its migration block, a careless migration would resurrect
    //      the marker right after the user removed it.
    //
    // Fix: serialize the whole migration with an OS-level file lock on
    // filesDir()/pp_migrated.lock, and clear the legacy SharedPreferences
    // with a synchronous `commit()` before the sentinel. The lock guarantees
    // that any process waiting for us will only call `getSharedPreferences()`
    // AFTER our commit() returns, so it loads the cleared values and does not
    // resurrect any marker. `apply()` would not be enough because it is async:
    // a trailing process could read the legacy file before our async write
    // flushed, see the stale value, and recreate the marker.
    // ----------------------------------------------------------------------

    public static void migrateIfNeeded(Context context) {
        Context app = context.getApplicationContext();
        File filesDir = app.getFilesDir();
        File sentinel = new File(filesDir, MIGRATION_SENTINEL);
        if (sentinel.exists()) return;

        File lockFile = new File(filesDir, MIGRATION_SENTINEL + ".lock");
        boolean migratedThisCall = false;
        FileLock lock = null;
        try (FileOutputStream fos = new FileOutputStream(lockFile, true)) {
            FileChannel channel = fos.getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                // Another process is migrating. Return immediately: the next
                // App.onCreate in this process will catch up via the sentinel
                // check. Avoid sleeping on the main thread (App.onCreate is
                // on the UI thread in both main and ":ime") because the
                // typical migration is <10ms.
                return;
            }

            // Re-check sentinel under the lock (the previous holder may have
            // completed in the meantime).
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

            // Synchronously commit the legacy-key removal (see class comment).
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

            // Create the sentinel LAST so any trailing process that races
            // with us and somehow acquires the lock next sees the migration
            // as complete and exits early.
            try {
                sentinel.createNewFile();
                migratedThisCall = true;
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
            // Drop the lockfile on a successful migration so it does not leak
            // into filesDir forever. The sentinel guards against re-running
            // the migration, so the lockfile is no longer needed.
            if (migratedThisCall) {
                // Best effort; ignore failures. Deleting after releasing the
                // lock is safe — any process still holding a stale handle will
                // simply see EOF when it eventually closes.
                lockFile.delete();
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


}
