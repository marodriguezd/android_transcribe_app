package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Minimal settings store for the AI post-processing layer (fork addition on
 * top of upstream v0.1.18). Holds only what the post-processor needs:
 *
 *   - whether post-processing is enabled
 *   - the OpenAI-compatible base URL, API key (encrypted), and model name
 *   - a single active system prompt
 *
 * This is intentionally slim. The old fork's SettingsManager also owned
 * model-variant / language / dictionary logic tied to the ONNX/Parakeet
 * engine; v0.1.18 manages models itself (ModelsActivity), so that logic is
 * dropped here and can grow back later as separate concerns.
 */
public class SettingsManager {
    private static final String TAG = "SettingsManager";
    private static final String PREFS_NAME = "transcribe_settings";

    private static final String KEY_POST_PROCESS_ENABLED = "post_process_enabled";
    private static final String KEY_PROVIDER = "pp_provider"; // groq/openai/cerebras/... or "custom"
    private static final String KEY_API_URL = "api_url";      // OpenAI-compatible base URL
    private static final String KEY_API_KEY = "api_key";      // stored encrypted
    private static final String KEY_MODEL_NAME = "model_name";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";

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
    private static final String DEFAULT_PROMPT =
            "You are a transcription cleanup assistant. Fix punctuation, "
            + "capitalization and obvious speech-to-text errors in the user's "
            + "text. Return only the corrected text, with no commentary.";

    private final SharedPreferences prefs;
    private final Context appContext;

    public SettingsManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Context getContext() {
        return appContext;
    }

    public boolean isPostProcessEnabled() {
        return prefs.getBoolean(KEY_POST_PROCESS_ENABLED, false);
    }

    public void setPostProcessEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_POST_PROCESS_ENABLED, enabled).apply();
    }

    public String getApiUrl() {
        return prefs.getString(KEY_API_URL, DEFAULT_API_URL);
    }

    public void setApiUrl(String url) {
        prefs.edit().putString(KEY_API_URL, url).apply();
    }

    /** Selected provider preset id ("groq", "openai", ... or "custom"). */
    public String getProviderId() {
        return prefs.getString(KEY_PROVIDER, "custom");
    }

    public void setProviderId(String id) {
        prefs.edit().putString(KEY_PROVIDER, id).apply();
    }

    /**
     * Effective base URL for API calls: the provider preset's URL, or the
     * user-supplied URL when the provider is "custom".
     */
    public String getEffectiveApiUrl() {
        Provider p = providerById(getProviderId());
        if (p.baseUrl != null) return p.baseUrl;
        return getApiUrl();
    }

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
        MasterKey masterKey = new MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME + "_encrypted",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    public String getModelName() {
        return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL);
    }

    public void setModelName(String model) {
        prefs.edit().putString(KEY_MODEL_NAME, model).apply();
    }

    public String getActivePromptBody() {
        String p = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_PROMPT);
        return (p == null || p.trim().isEmpty()) ? DEFAULT_PROMPT : p;
    }

    public void setActivePromptBody(String prompt) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply();
    }

    public String getDefaultPrompt() {
        return DEFAULT_PROMPT;
    }
}
