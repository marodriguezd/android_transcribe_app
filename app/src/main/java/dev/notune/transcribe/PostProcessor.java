package dev.notune.transcribe;

import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
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

    private static volatile OkHttpClient sharedClient;
    private static final Object CLIENT_LOCK = new Object();
    private static final Set<Call> IN_FLIGHT = Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>());
    private static final Object IN_FLIGHT_LOCK = new Object();

    private final SettingsManager settings;
    private final Handler uiHandler;
    private final BooleanSupplier validator;

    /** Creates a processor that delivers callbacks on the caller's thread. */
    public PostProcessor(SettingsManager settings) {
        this(settings, null, null);
    }

    /** Creates a processor that delivers callbacks on {@code uiHandler}. */
    public PostProcessor(SettingsManager settings, Handler uiHandler) {
        this(settings, uiHandler, null);
    }

    /**
     * Creates a processor that delivers callbacks on {@code uiHandler} only
     * while {@code validator} returns true.
     */
    public PostProcessor(SettingsManager settings, Handler uiHandler,
                         BooleanSupplier validator) {
        this.settings = settings;
        this.uiHandler = uiHandler;
        this.validator = validator;
    }

    private static OkHttpClient getSharedClient() {
        OkHttpClient client = sharedClient;
        if (client == null) {
            synchronized (CLIENT_LOCK) {
                client = sharedClient;
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .writeTimeout(60, TimeUnit.SECONDS)
                            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                            .build();
                    sharedClient = client;
                }
            }
        }
        return client;
    }

    /** Cancel every in-flight OkHttp call created by any processor instance. */
    public static void cancelAll() {
        synchronized (IN_FLIGHT_LOCK) {
            for (Call call : IN_FLIGHT) {
                if (!call.isCanceled()) {
                    call.cancel();
                }
            }
        }
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
            synchronized (IN_FLIGHT_LOCK) {
                IN_FLIGHT.add(call);
            }
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    IN_FLIGHT.remove(call);
                    String message = e.getMessage() != null
                            ? e.getMessage() : "Post-processing request failed";
                    Log.e(TAG, "API call failed: " + message);
                    dispatchToUi(() -> callback.onError(message));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    IN_FLIGHT.remove(call);
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
        synchronized (IN_FLIGHT_LOCK) {
            IN_FLIGHT.add(call);
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                IN_FLIGHT.remove(call);
                String message = e.getMessage() != null ? e.getMessage() : "Request failed";
                dispatchToUi(() -> callback.onError(message));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                IN_FLIGHT.remove(call);
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
}
