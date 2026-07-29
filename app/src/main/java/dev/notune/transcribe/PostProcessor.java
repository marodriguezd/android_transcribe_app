package dev.notune.transcribe;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import android.os.Handler;

import java.io.IOException;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
 * Uses a shared OkHttpClient so TLS sessions and TCP connections can be reused
 * across transcriptions. In-flight calls are tracked so they can be cancelled
 * when the user toggles post-processing off, and the enabled flag is re-checked
 * before the refined text is delivered.
 */
public class PostProcessor {
    private static final String TAG = "PostProcessor";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * Broadcast action sent by the main process when post-processing is
     * toggled off, so the ":ime" keyboard process can cancel its own
     * in-flight OkHttp calls immediately.
     */
    public static final String CANCEL_ACTION = "dev.notune.transcribe.CANCEL_PP";

    private static volatile OkHttpClient sharedClient;
    private static final Object CLIENT_LOCK = new Object();
    private static final Set<Call> IN_FLIGHT = Collections.newSetFromMap(new ConcurrentHashMap<Call, Boolean>());
    private static final Object IN_FLIGHT_LOCK = new Object();

    private final SettingsManager settings;
    private final Handler uiHandler;
    private final BooleanSupplier validator;

    /**
     * Creates a PostProcessor that delivers callbacks on the caller's thread.
     * Kept for backward compatibility with callers that manage their own
     * UI-thread posting (e.g. fetchModels).
     */
    public PostProcessor(SettingsManager settings) {
        this(settings, null, null);
    }

    /**
     * Creates a PostProcessor that delivers callbacks on the UI thread
     * described by {@code uiHandler}. Callers can update UI directly from
     * {@code onSuccess}/{@code onError} without runOnUiThread wrappers.
     */
    public PostProcessor(SettingsManager settings, Handler uiHandler) {
        this(settings, uiHandler, null);
    }

    /**
     * Creates a PostProcessor that delivers callbacks on the UI thread only
     * while {@code validator} returns {@code true}. This prevents late
     * callbacks from running after the owning Activity or Service has been
     * destroyed. Pass a validator such as
     * {@code () -> !isFinishing() && !isDestroyed()} from an Activity, or
     * a service-owned flag for background services.
     */
    public PostProcessor(SettingsManager settings, Handler uiHandler, BooleanSupplier validator) {
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

    /** Cancel every in-flight OkHttp call created by any PostProcessor instance. */
    public static void cancelAll() {
        synchronized (IN_FLIGHT_LOCK) {
            for (Call call : IN_FLIGHT) {
                if (!call.isCanceled()) {
                    call.cancel();
                }
            }
        }
    }

    public void process(String rawText, final PostProcessCallback callback) {
        if (rawText == null || rawText.trim().isEmpty()) {
            dispatchToUi(() -> callback.onSuccess(rawText != null ? rawText : ""));
            return;
        }

        // Defensive re-check: if the toggle was disabled between the call site's
        // check and this method, deliver the raw text immediately.
        if (!settings.isPostProcessEnabled()) {
            dispatchToUi(() -> callback.onSuccess(rawText));
            return;
        }

        String apiUrl = settings.getEffectiveApiUrl() != null ? settings.getEffectiveApiUrl().trim() : "";
        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        String completionUrl = apiUrl;
        if (!completionUrl.endsWith("/chat/completions")) {
            completionUrl += "/chat/completions";
        }

        String apiKey = settings.getApiKey();
        String model = settings.getModelName();
        String systemInstruction = settings.getActivePromptBody();
        // Inject the transcript where the prompt template marks ${output}
        // (e.g. the bundled Wispr-style prompt). Without the marker the prompt
        // is used as-is and the text travels in the user message below.
        if (systemInstruction != null && systemInstruction.contains("${output}")) {
            systemInstruction = systemInstruction.replace("${output}", rawText);
        }

        try {
            JSONObject json = new JSONObject();
            json.put("model", model);

            JSONArray messages = new JSONArray();
            if (systemInstruction != null && !systemInstruction.trim().isEmpty()) {
                JSONObject sysMsg = new JSONObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemInstruction.trim());
                messages.put(sysMsg);
            }
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", rawText);
            messages.put(userMsg);
            json.put("messages", messages);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(completionUrl)
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
                    Log.e(TAG, "API call failed: " + e.getMessage());
                    dispatchToUi(() -> callback.onError(e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    IN_FLIGHT.remove(call);
                    // If the user disabled post-processing while the request was
                    // in flight, fall back to the raw transcript instead of
                    // committing the refined text.
                    if (!settings.isPostProcessEnabled()) {
                        dispatchToUi(() -> callback.onSuccess(rawText));
                        return;
                    }
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "API error: code=" + response.code());
                        dispatchToUi(() -> callback.onError("API Error " + response.code()));
                        return;
                    }
                    try {
                        String responseData = response.body().string();
                        JSONObject jsonResponse = new JSONObject(responseData);
                        JSONArray choices = jsonResponse.getJSONArray("choices");
                        if (choices.length() > 0) {
                            String resultText = choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content");
                            dispatchToUi(() -> callback.onSuccess(resultText.trim()));
                        } else {
                            dispatchToUi(() -> callback.onError("Empty response from AI"));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse API response: " + e.getMessage());
                        dispatchToUi(() -> callback.onError("Parse error: " + e.getMessage()));
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to create request: " + e.getMessage());
            dispatchToUi(() -> callback.onError(e.getMessage()));
        }
    }

    /**
     * Process post-processing with Server-Sent Events (SSE) streaming.
     * Emits token deltas in real-time via {@code callback.onToken(token)}.
     * Automatically retries up to 3 times on connection errors, and falls back to
     * raw transcript on persistent failure to prevent partial/corrupted text.
     */
    public void processStreaming(final String rawText, final StreamCallback callback) {
        processStreamingWithRetry(rawText, callback, 1);
    }

    private void processStreamingWithRetry(final String rawText, final StreamCallback callback, final int attempt) {
        if (rawText == null || rawText.trim().isEmpty()) {
            dispatchToUi(() -> callback.onSuccess(rawText != null ? rawText : ""));
            return;
        }

        if (!settings.isPostProcessEnabled()) {
            dispatchToUi(() -> callback.onSuccess(rawText));
            return;
        }

        String apiUrl = settings.getEffectiveApiUrl() != null ? settings.getEffectiveApiUrl().trim() : "";
        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        String completionUrl = apiUrl;
        if (!completionUrl.endsWith("/chat/completions")) {
            completionUrl += "/chat/completions";
        }

        String apiKey = settings.getApiKey();
        String model = settings.getModelName();
        String systemInstruction = settings.getActivePromptBody();
        if (systemInstruction != null && systemInstruction.contains("${output}")) {
            systemInstruction = systemInstruction.replace("${output}", rawText);
        }

        try {
            JSONObject json = new JSONObject();
            json.put("model", model);
            json.put("stream", true);

            JSONArray messages = new JSONArray();
            if (systemInstruction != null && !systemInstruction.trim().isEmpty()) {
                JSONObject sysMsg = new JSONObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemInstruction.trim());
                messages.put(sysMsg);
            }
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", rawText);
            messages.put(userMsg);
            json.put("messages", messages);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(completionUrl)
                    .addHeader("Accept", "text/event-stream")
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
                    Log.e(TAG, "Streaming API call failed (attempt " + attempt + "): " + e.getMessage());
                    handleStreamFailure(rawText, callback, attempt, e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    IN_FLIGHT.remove(call);
                    if (!settings.isPostProcessEnabled()) {
                        dispatchToUi(() -> callback.onSuccess(rawText));
                        return;
                    }

                    // If provider rejected stream parameter (e.g. HTTP 400), fallback to non-streaming POST once
                    if (!response.isSuccessful()) {
                        int statusCode = response.code();
                        response.close();
                        if (statusCode == 400 && attempt == 1) {
                            Log.w(TAG, "Streaming rejected (HTTP 400), falling back to non-streaming POST");
                            processNonStreamingFallback(rawText, callback);
                            return;
                        }
                        Log.e(TAG, "Streaming API error (attempt " + attempt + "): code=" + statusCode);
                        handleStreamFailure(rawText, callback, attempt, "API Error " + statusCode);
                        return;
                    }

                    try (okhttp3.ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            handleStreamFailure(rawText, callback, attempt, "Empty response body");
                            return;
                        }

                        java.io.InputStream inputStream = responseBody.byteStream();
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));

                        StringBuilder fullTextBuilder = new StringBuilder();
                        String line;
                        boolean receivedTokens = false;
                        while ((line = reader.readLine()) != null) {
                            if (!settings.isPostProcessEnabled()) {
                                dispatchToUi(() -> callback.onSuccess(rawText));
                                return;
                            }
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith(":")) {
                                continue;
                            }
                            if (line.startsWith("data:")) {
                                String dataPayload = line.substring(5).trim();
                                if ("[DONE]".equals(dataPayload)) {
                                    break;
                                }
                                try {
                                    JSONObject jsonResponse = new JSONObject(dataPayload);
                                    JSONArray choices = jsonResponse.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject choice0 = choices.getJSONObject(0);
                                        JSONObject delta = choice0.optJSONObject("delta");
                                        String token = null;
                                        if (delta != null && delta.has("content")) {
                                            token = delta.getString("content");
                                        } else if (choice0.has("text")) {
                                            token = choice0.getString("text");
                                        }

                                        if (token != null && !token.isEmpty()) {
                                            receivedTokens = true;
                                            final String tokenToEmit = token;
                                            fullTextBuilder.append(tokenToEmit);
                                            dispatchToUi(() -> callback.onToken(tokenToEmit));
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "Failed to parse SSE payload line: " + line, e);
                                }
                            }
                        }

                        final String resultStr = fullTextBuilder.toString().trim();
                        if (receivedTokens || !resultStr.isEmpty()) {
                            dispatchToUi(() -> callback.onSuccess(resultStr));
                        } else {
                            handleStreamFailure(rawText, callback, attempt, "Empty stream response");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error reading stream (attempt " + attempt + "): " + e.getMessage());
                        handleStreamFailure(rawText, callback, attempt, e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to create streaming request: " + e.getMessage());
            handleStreamFailure(rawText, callback, attempt, e.getMessage());
        }
    }

    private void handleStreamFailure(final String rawText, final StreamCallback callback, final int attempt, final String error) {
        if (attempt < 3) {
            long backoff = 150L * attempt;
            Log.i(TAG, "Retrying streaming post-processing (attempt " + (attempt + 1) + " of 3) after " + backoff + "ms");
            if (uiHandler != null) {
                uiHandler.postDelayed(() -> processStreamingWithRetry(rawText, callback, attempt + 1), backoff);
            } else {
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
                processStreamingWithRetry(rawText, callback, attempt + 1);
            }
        } else {
            Log.e(TAG, "All 3 streaming attempts failed. Delivering raw transcript fallback.");
            dispatchToUi(() -> callback.onError(error, rawText));
        }
    }

    private void processNonStreamingFallback(final String rawText, final StreamCallback callback) {
        process(rawText, new PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                callback.onSuccess(refinedText != null && !refinedText.trim().isEmpty() ? refinedText : rawText);
            }

            @Override
            public void onError(String error) {
                callback.onError(error, rawText);
            }
        });
    }

    /** Runs {@code action} on the configured UI handler, or immediately
     *  when no handler was provided. If a validator was supplied, the action
     *  is dropped when it returns false, preventing late callbacks from
     *  touching a destroyed component. */
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

    public void fetchModels(final ModelsCallback callback) {
        String baseUrl = settings.getEffectiveApiUrl() != null ? settings.getEffectiveApiUrl().trim() : "";
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
        // Register the call before enqueueing so cancelAll() cannot miss it
        // in the window between creation and tracking.
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                IN_FLIGHT.remove(call);
                dispatchToUi(() -> callback.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                IN_FLIGHT.remove(call);
                if (!response.isSuccessful()) {
                    dispatchToUi(() -> callback.onError("Error " + response.code()));
                    return;
                }
                try {
                    String data = response.body().string();
                    JSONObject json = new JSONObject(data);
                    JSONArray array = json.getJSONArray("data");
                    java.util.List<String> models = new java.util.ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        models.add(array.getJSONObject(i).getString("id"));
                    }
                    java.util.Collections.sort(models);
                    dispatchToUi(() -> callback.onSuccess(models));
                } catch (Exception e) {
                    dispatchToUi(() -> callback.onError("Parse error: " + e.getMessage()));
                }
            }
        });
    }

    public interface StreamCallback {
        void onToken(String deltaToken);
        void onSuccess(String completeText);
        void onError(String error, String rawFallbackText);
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
