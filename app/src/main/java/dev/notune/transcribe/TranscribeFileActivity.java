package dev.notune.transcribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import dev.notune.transcribe.BuildConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TranscribeFileActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";
    private static final int TARGET_SAMPLE_RATE = 16000;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusText;
    private ProgressBar progressBar;
    private View progressArea;
    private ScrollView resultArea;
    private TextView resultText;
    private Button copyButton;

    // Monotonic operation-id source, shared across Activity instances so a
    // recreated Activity can never collide with a stale native worker's id.
    private static final AtomicInteger NEXT_OP = new AtomicInteger(1);
    // The operation id of the decode currently owned by this Activity; a
    // destroyed/recreated Activity bumps it to invalidate late callbacks.
    private int currentOpId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transcribe_file_activity);

        statusText = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progress_bar);
        progressArea = findViewById(R.id.progress_area);
        resultArea = findViewById(R.id.result_area);
        resultText = findViewById(R.id.txt_result);
        copyButton = findViewById(R.id.btn_copy);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

        copyButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (!text.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Transcription", text);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, getString(R.string.file_copied), Toast.LENGTH_SHORT).show();
            }
        });

        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText(getString(R.string.file_error_no_audio_received));
            progressBar.setVisibility(View.GONE);
            return;
        }

        statusText.setText(getString(R.string.file_loading_model));
        initNative(this);
    }

    @Override
    protected void onDestroy() {
        // Invalidate any in-flight decode callbacks (P1.1): a late native
        // worker must not update a destroyed/recreated Activity.
        currentOpId = NEXT_OP.incrementAndGet();
        // Cancel this Activity's in-flight post-processing call (owner-scoped,
        // P0.1) so a late callback cannot update a finishing UI — without
        // cancelling another surface's legitimate request.
        PostProcessor.cancelAllFor(this);
        super.onDestroy();
        try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
    }

    private Uri getAudioUri() {
        Intent intent = getIntent();
        if (intent == null) return null;

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            return intent.getData();
        }
        return null;
    }

    // Called from Rust when model is ready
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            if ("Ready".equals(status)) {
                statusText.setText(getString(R.string.file_decoding_audio));
                startDecodeAndTranscribe();
            } else {
                statusText.setText(status);
                if (status != null && status.startsWith("Error")) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    // Called from Rust with the transcription result of a specific decode
    // operation. The opId guard (P1.1) drops callbacks from a worker that
    // finishes after this Activity was destroyed or a new decode started.
    public void onTextTranscribed(String text, int opId) {
        runOnUiThread(() -> {
            if (opId != currentOpId || isFinishing() || isDestroyed()) return;
            SettingsManager settings = new SettingsManager(this);
            if (settings.isPostProcessEnabled()) {
                statusText.setText(getString(R.string.file_refining));
                // Owned by this Activity so its teardown only cancels its own
                // in-flight call, never another surface's (P0.1).
                new PostProcessor(settings, new Handler(Looper.getMainLooper()),
                        () -> !isFinishing() && !isDestroyed(), this)
                        .process(text, new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        String out = (refinedText != null && !refinedText.trim().isEmpty())
                                ? refinedText : text;
                        showResult(out);
                    }

                    @Override
                    public void onError(String error) {
                        // Privacy (v0.1.24): the error string can carry
                        // provider details; the transcript itself is never
                        // logged in release builds.
                        if (BuildConfig.DEBUG) {
                            Log.w(TAG, "Post-process failed, showing raw text: " + error);
                        }
                        showResult(text);
                    }
                });
            } else {
                showResult(text);
            }
        });
    }

    private void showResult(String text) {
        // Hide progress, show result
        progressArea.setVisibility(View.GONE);
        resultArea.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);

        resultText.setText(text);

        // Auto-copy to clipboard
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Transcription", text);
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, getString(R.string.file_transcription_copied), Toast.LENGTH_LONG).show();
    }

    private void startDecodeAndTranscribe() {
        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText(getString(R.string.file_error_no_audio));
            return;
        }

        // Every decode gets a fresh unique operation id (static counter, so a
        // recreated Activity can never accept a stale worker's callbacks).
        final int opId = NEXT_OP.incrementAndGet();
        currentOpId = opId;

        new Thread(() -> {
            try {
                float[] samples = decodeAudioToSamples(audioUri);
                if (samples == null || samples.length == 0) {
                    showError(getString(R.string.file_error_decode));
                    return;
                }

                runOnUiThread(() -> statusText.setText(getString(R.string.file_transcribing)));
                transcribeAudio(samples, samples.length, opId);

            } catch (Exception e) {
                Log.e(TAG, "Error decoding audio", e);
                showError(getString(R.string.file_error_format, e.getMessage()));
            }
        }).start();
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
            progressBar.setVisibility(View.GONE);
        });
    }

    /**
     * Decode audio from a Uri to 16kHz mono float samples using MediaExtractor/MediaCodec.
     */
    private float[] decodeAudioToSamples(Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(this, uri, null);

        // Find audio track
        int audioTrackIndex = -1;
        MediaFormat inputFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                inputFormat = format;
                break;
            }
        }

        if (audioTrackIndex < 0 || inputFormat == null) {
            Log.e(TAG, "No audio track found");
            return null;
        }

        extractor.selectTrack(audioTrackIndex);
        String mime = inputFormat.getString(MediaFormat.KEY_MIME);
        int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        Log.i(TAG, "Audio: mime=" + mime + " rate=" + sampleRate + " channels=" + channelCount);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(inputFormat, null, null, 0);
        codec.start();

        List<float[]> allChunks = new ArrayList<>();
        int totalSamples = 0;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        long timeoutUs = 10000;

        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                int inputBufferIndex = codec.dequeueInputBuffer(timeoutUs);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                    int bytesRead = extractor.readSampleData(inputBuffer, 0);
                    if (bytesRead < 0) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        codec.queueInputBuffer(inputBufferIndex, 0, bytesRead,
                                presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            // Drain output
            int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs);
            if (outputBufferIndex >= 0) {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }

                ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                    // Decoded PCM is 16-bit signed. Convert to mono float.
                    ShortBuffer shortBuf = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    int shortCount = shortBuf.remaining();
                    int monoCount = shortCount / channelCount;

                    float[] chunk = new float[monoCount];
                    for (int i = 0; i < monoCount; i++) {
                        if (channelCount == 1) {
                            chunk[i] = shortBuf.get() / 32768.0f;
                        } else {
                            // Mix channels to mono
                            float sum = 0;
                            for (int c = 0; c < channelCount; c++) {
                                sum += shortBuf.get() / 32768.0f;
                            }
                            chunk[i] = sum / channelCount;
                        }
                    }

                    allChunks.add(chunk);
                    totalSamples += monoCount;
                }

                codec.releaseOutputBuffer(outputBufferIndex, false);
            }
        }

        codec.stop();
        codec.release();
        extractor.release();

        // Resample to 16kHz if needed
        float[] monoSamples = mergeChunks(allChunks, totalSamples);

        if (sampleRate != TARGET_SAMPLE_RATE) {
            Log.i(TAG, "Resampling from " + sampleRate + " to " + TARGET_SAMPLE_RATE);
            monoSamples = resample(monoSamples, sampleRate, TARGET_SAMPLE_RATE);
        }

        Log.i(TAG, "Decoded " + monoSamples.length + " samples at 16kHz");
        return monoSamples;
    }

    private float[] mergeChunks(List<float[]> chunks, int totalSamples) {
        float[] result = new float[totalSamples];
        int offset = 0;
        for (float[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    /**
     * Simple linear interpolation resampling.
     */
    private float[] resample(float[] input, int fromRate, int toRate) {
        double ratio = (double) fromRate / toRate;
        int outputLength = (int) (input.length / ratio);
        float[] output = new float[outputLength];

        for (int i = 0; i < outputLength; i++) {
            double srcIndex = i * ratio;
            int idx = (int) srcIndex;
            double frac = srcIndex - idx;

            if (idx + 1 < input.length) {
                output[i] = (float) (input[idx] * (1.0 - frac) + input[idx + 1] * frac);
            } else if (idx < input.length) {
                output[i] = input[idx];
            }
        }

        return output;
    }

    // Decode-scoped status ("Transcribing...", decode errors). Ignored when
    // the operation no longer belongs to this Activity instance.
    public void onStatusUpdate(String status, int opId) {
        runOnUiThread(() -> {
            if (opId != currentOpId || isFinishing() || isDestroyed()) return;
            statusText.setText(status);
            if (status != null && status.startsWith("Error")) {
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    // Native methods
    private native void initNative(TranscribeFileActivity activity);
    private native void cleanupNative();
    private native void transcribeAudio(float[] samples, int length, int opId);
}
