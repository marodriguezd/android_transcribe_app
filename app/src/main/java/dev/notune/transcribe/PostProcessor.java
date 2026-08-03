package dev.notune.transcribe;

import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI post-processing over an OpenAI-compatible /chat/completions endpoint.
 *
 * <p>The speech model owns the real-time experience: its partial hypotheses
 * remain visual-only and its final transcript arrives through
 * {@code onTextTranscribed}. Post-processing deliberately uses one complete
 * response request after that final transcript is available. This keeps the
 * editor atomic: it receives either the refined transcript once, or the raw
 * transcript once if the optional network step fails.</p>
 *
 * <p>A shared OkHttpClient reuses connections across dictations. In-flight
 * calls are tracked so they can be cancelled when the feature is disabled or
 * an owning Activity/Service is destroyed.</p>
 */
public class PostProcessor {
    private static final String TAG = "PostProcessor";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Broadcast action used to cancel calls in the isolated IME process. */
    public static final String CANCEL_ACTION = "dev.notune.transcribe.CANCEL_PP";

    /**
     * Timeouts for the shared production client: 30 s to reach a provider
     * (DNS + TLS on mobile networks) and 60 s to read/write the complete
     * non-streaming response, which for long transcripts on slow links can
     * legitimately take a while. Kept as named constants so the applied
     * values are asserted by the plain-JVM tests (the wall-clock durations
     * themselves are too long to wait out inside the harness).
     */
    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int WRITE_TIMEOUT_SECONDS = 60;

    private static volatile OkHttpClient sharedClient;
    private static final Object CLIENT_LOCK = new Object();

    private final PostProcessorSettings settings;
    private final Handler uiHandler;
    private final BooleanSupplier validator;
    /**
     * The surface (Activity/Service) that owns this processor's calls.
     * Cancellation is scoped to the owner so destroying one surface never
     * cancels another surface's in-flight post-processing (P0.1).
     */
    private final Object owner;

    /** Creates a processor that delivers callbacks on the caller's thread. */
    public PostProcessor(SettingsManager settings) {
        this(settings, null, null, null);
    }

    /** Creates a processor that delivers callbacks on {@code uiHandler}. */
    public PostProcessor(SettingsManager settings, Handler uiHandler) {
        this(settings, uiHandler, null, null);
    }

    /**
     * Creates a processor that delivers callbacks on {@code uiHandler} only
     * while {@code validator} returns true.
     */
    public PostProcessor(SettingsManager settings, Handler uiHandler,
                         BooleanSupplier validator) {
        this(settings, uiHandler, validator, null);
    }

    /**
     * Creates a processor whose in-flight calls are owned by {@code owner}:
     * {@link #cancelAllFor(Object)} only cancels that owner's calls, so a
     * destroyed Activity/Service cannot interrupt another surface's request.
     */
    public PostProcessor(SettingsManager settings, Handler uiHandler,
                         BooleanSupplier validator, Object owner) {
        this((PostProcessorSettings) settings, uiHandler, validator, owner);
    }

    /**
     * Package-private seam for plain-JVM tests (P1.3): accepts any
     * {@link PostProcessorSettings} implementation, so the payload/fallback
     * contract of {@link #process} can be verified against a controlled
     * HTTP server without an Android Context.
     */
    PostProcessor(PostProcessorSettings settings, Handler uiHandler,
                  BooleanSupplier validator, Object owner) {
        this.settings = settings;
        this.uiHandler = uiHandler;
        this.validator = validator;
        this.owner = owner;
    }

    /**
     * The settings surface {@link PostProcessor} reads. Implemented by
     * {@link SettingsManager} in production; a fake implements it in the
     * plain-JVM HTTP tests.
     */
    interface PostProcessorSettings {
        boolean isPostProcessEnabled();

        String getEffectiveApiUrl();

        String getApiKey();

        String getModelName();

        String getActivePromptBody();
    }

    private static OkHttpClient getSharedClient() {
        OkHttpClient client = sharedClient;
        if (client == null) {
            synchronized (CLIENT_LOCK) {
                client = sharedClient;
                if (client == null) {
                    client = buildProductionClient();
                    sharedClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Builds the production client: generous timeouts for real dictations
     * (30 s to connect, 60 s read/write for the complete response) and a
     * small connection pool so consecutive dictations reuse connections.
     *
     * <p>Package-private so the plain-JVM tests can assert the exact applied
     * values — the guarantee users actually get on device — without having
     * to wait for the 30 s/60 s wall-clock timeouts themselves.</p>
     */
    static OkHttpClient buildProductionClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
    }

    /**
     * Test-only seam (plain-JVM harness): installs a client with test-scaled
     * timeouts so {@link #process} exercises the real OkHttp timeout path
     * (socket read timeout → {@code onFailure} → onError) without waiting for
     * the production 30 s/60 s values. Not for production use — restore with
     * {@link #resetSharedClientForTests()} (or leave null to recreate the
     * production client lazily).
     */
    static void setSharedClientForTests(OkHttpClient client) {
        sharedClient = client;
    }

    /** Test-only seam: restores lazy production-client creation. */
    static void resetSharedClientForTests() {
        sharedClient = null;
    }

    /**
     * Cancels every in-flight OkHttp call created by any processor instance.
     * Reserved for real process-global shutdown events: the IME service being
     * destroyed and the "post-processing disabled" toggle, which also
     * broadcasts to the ":ime" process.
     */
    public static void cancelAll() {
        CallRegistry.cancelAll();
    }

    /**
     * Cancels only the in-flight calls owned by {@code owner} (by identity).
     * Used by surfaces on destroy/cancel so one surface's teardown never
     * cancels a legitimate request from another surface.
     */
    public static void cancelAllFor(Object owner) {
        CallRegistry.cancelAllForOwner(owner);
    }

    /**
     * Sends exactly one non-streaming completion request for the final ASR
     * transcript. The callback receives either the complete refined response
     * or an error so each caller can deliver the raw transcript as fallback.
     */
    public void process(final String rawText, final PostProcessCallback callback) {
        if (rawText == null || rawText.trim().isEmpty()) {
            dispatchToUi(() -> callback.onSuccess(rawText != null ? rawText : ""));
            return;
        }

        // Re-check the marker here, not only at each caller, because the toggle
        // can change between receiving the ASR result and creating this call.
        if (!settings.isPostProcessEnabled()) {
            dispatchToUi(() -> callback.onSuccess(rawText));
            return;
        }

        final String completionUrl = buildCompletionUrl(settings.getEffectiveApiUrl());
        final String apiKey = settings.getApiKey();
        final String model = settings.getModelName();
        final String systemInstruction = settings.getActivePromptBody();
        final boolean injected = systemInstruction != null
                && systemInstruction.contains("${output}");
        final String requestSystemInstruction = injected
                ? systemInstruction.replace("${output}", rawText)
                : systemInstruction;

        try {
            JSONObject json = new JSONObject();
            json.put("model", model);
            // Be explicit: this is the single complete-response path. Some
            // OpenAI-compatible providers otherwise apply their own default.
            json.put("stream", false);

            JSONArray messages = new JSONArray();
            if (requestSystemInstruction != null
                    && !requestSystemInstruction.trim().isEmpty()) {
                JSONObject systemMessage = new JSONObject();
                systemMessage.put("role", "system");
                systemMessage.put("content", requestSystemInstruction.trim());
                messages.put(systemMessage);
            }

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            // A ${output} prompt already contains the transcript in the system
            // message. Do not send it a second time: duplicate input makes some
            // providers echo the transcript instead of editing it.
            userMessage.put("content", injected
                    ? "Apply the instructions to the transcript above."
                    : rawText);
            messages.put(userMessage);
            json.put("messages", messages);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(completionUrl)
                    .addHeader("Accept", "application/json")
                    .post(body);
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }

            Call call = getSharedClient().newCall(requestBuilder.build());
            CallRegistry.register(call, owner);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    CallRegistry.unregister(call);
                    String message = e.getMessage() != null
                            ? e.getMessage() : "Post-processing request failed";
                    Log.e(TAG, "API call failed: " + message);
                    dispatchToUi(() -> callback.onError(message));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    CallRegistry.unregister(call);
                    // Always close responses, including HTTP errors and parse
                    // failures, so repeated dictations do not exhaust OkHttp.
                    try (Response responseResource = response) {
                        // If the user disabled post-processing while the request
                        // was in flight, the raw transcript wins.
                        if (!settings.isPostProcessEnabled()) {
                            dispatchToUi(() -> callback.onSuccess(rawText));
                            return;
                        }

                        if (!responseResource.isSuccessful()) {
                            String error = "API Error " + responseResource.code();
                            Log.e(TAG, error);
                            dispatchToUi(() -> callback.onError(error));
                            return;
                        }

                        try {
                            if (responseResource.body() == null) {
                                dispatchToUi(() -> callback.onError("Empty response body"));
                                return;
                            }
                            String responseData = responseResource.body().string();
                            JSONObject responseJson = new JSONObject(responseData);
                            JSONArray choices = responseJson.getJSONArray("choices");
                            if (choices.length() == 0) {
                                dispatchToUi(() -> callback.onError("Empty response from AI"));
                                return;
                            }

                            String resultText = choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                    .trim();
                            if (resultText.isEmpty()) {
                                dispatchToUi(() -> callback.onError("Empty response from AI"));
                            } else {
                                // Re-check on the UI thread immediately before delivery.
                                // If the user disabled PP after the HTTP thread parsed the
                                // response, the raw ASR transcript still wins.
                                dispatchToUi(() -> callback.onSuccess(
                                        settings.isPostProcessEnabled() ? resultText : rawText));
                            }
                        } catch (Exception e) {
                            String error = "Parse error: " + e.getMessage();
                            Log.e(TAG, "Failed to parse API response: " + e.getMessage());
                            dispatchToUi(() -> callback.onError(error));
                        }
                    }
                }
            });
        } catch (Exception e) {
            String error = e.getMessage() != null
                    ? e.getMessage() : "Failed to create post-processing request";
            Log.e(TAG, error);
            dispatchToUi(() -> callback.onError(error));
        }
    }

    private static String buildCompletionUrl(String configuredUrl) {
        String apiUrl = configuredUrl != null ? configuredUrl.trim() : "";
        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        if (!apiUrl.endsWith("/chat/completions")) {
            apiUrl += "/chat/completions";
        }
        return apiUrl;
    }

    /** Runs {@code action} on the configured handler, if the owner is valid. */
    private void dispatchToUi(Runnable action) {
        if (uiHandler != null) {
            uiHandler.post(() -> {
                if (validator != null && !validator.getAsBoolean()) return;
                action.run();
            });
        } else {
            if (validator != null && !validator.getAsBoolean()) return;
            action.run();
        }
    }

    /** Fetches model IDs from the provider's OpenAI-compatible /models endpoint. */
    public void fetchModels(final ModelsCallback callback) {
        String baseUrl = settings.getEffectiveApiUrl() != null
                ? settings.getEffectiveApiUrl().trim() : "";
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String modelsUrl = baseUrl;
        if (!modelsUrl.endsWith("/models")) {
            modelsUrl += "/models";
        }

        String apiKey = settings.getApiKey();
        Request.Builder requestBuilder = new Request.Builder().url(modelsUrl).get();
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        Call call = getSharedClient().newCall(requestBuilder.build());
        CallRegistry.register(call, owner);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                CallRegistry.unregister(call);
                String message = e.getMessage() != null ? e.getMessage() : "Request failed";
                dispatchToUi(() -> callback.onError(message));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                CallRegistry.unregister(call);
                try (Response responseResource = response) {
                    if (!responseResource.isSuccessful()) {
                        dispatchToUi(() -> callback.onError(
                                "Error " + responseResource.code()));
                        return;
                    }
                    try {
                        if (responseResource.body() == null) {
                            dispatchToUi(() -> callback.onError("Empty response body"));
                            return;
                        }
                        String data = responseResource.body().string();
                        JSONObject json = new JSONObject(data);
                        JSONArray array = json.getJSONArray("data");
                        java.util.List<String> models = new java.util.ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            models.add(array.getJSONObject(i).getString("id"));
                        }
                        Collections.sort(models);
                        dispatchToUi(() -> callback.onSuccess(models));
                    } catch (Exception e) {
                        dispatchToUi(() -> callback.onError("Parse error: " + e.getMessage()));
                    }
                }
            }
        });
    }

    public interface PostProcessCallback {
        void onSuccess(String refinedText);
        void onError(String error);
    }

    public interface ModelsCallback {
        void onSuccess(java.util.List<String> models);
        void onError(String error);
    }

    /**
     * Registry of in-flight OkHttp calls and their owning surface.
     *
     * <p>Calls are keyed in a concurrent map so cancellation can be scoped:
     * {@link #cancelAll()} is the process-global shutdown path (feature
     * disabled, IME death), while {@link #cancelAllForOwner(Object)} only
     * cancels one owner's calls by identity — destroying the popup or the
     * settings screen must never interrupt a dictation from another surface.</p>
     *
     * <p>Kept as a separate, package-visible unit so the isolation semantics
     * are covered by plain-JVM tests (see CallRegistryTest) without touching
     * Android framework classes.</p>
     */
    static final class CallRegistry {
        private static final Map<Call, Object> OWNERS = new ConcurrentHashMap<>();

        private CallRegistry() {
        }

        /** Sentinel for ownerless calls: still globally cancellable, never
         *  matched by an owner-scoped cancel (ConcurrentHashMap forbids null
         *  values, and legacy constructors pass a null owner). */
        private static final Object NO_OWNER = new Object();

        static void register(Call call, Object owner) {
            if (call != null) OWNERS.put(call, owner != null ? owner : NO_OWNER);
        }

        static void unregister(Call call) {
            if (call != null) OWNERS.remove(call);
        }

        /** Cancels every registered call (global shutdown). */
        static void cancelAll() {
            for (Call call : OWNERS.keySet()) {
                if (!call.isCanceled()) call.cancel();
            }
        }

        /**
         * Cancels only the calls registered with exactly {@code owner}
         * (identity comparison — surfaces pass {@code this}).
         */
        static void cancelAllForOwner(Object owner) {
            if (owner == null) return;
            for (Map.Entry<Call, Object> entry : OWNERS.entrySet()) {
                if (entry.getValue() == owner) {
                    Call call = entry.getKey();
                    if (!call.isCanceled()) call.cancel();
                }
            }
        }
    }
}
