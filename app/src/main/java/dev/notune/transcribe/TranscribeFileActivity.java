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
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TranscribeFileActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";
    private static final int TARGET_SAMPLE_RATE = 16000;
    // Peak heap in decodeManualWav is byte[chunkSize] + short[chunkSize/2] +
    // float[chunkSize/2] ≈ 5× chunkSize. 16 MB ≈ 8 min of mono 16 kHz
    // 16-bit speech (16 000 × 2 × 500 ≈ 16 MB) and keeps peak heap well
    // under Android per-process limits on devices with low RAM.
    private static final long MAX_AUDIO_FILE_SIZE = 16 * 1024 * 1024; // 16 MB

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private volatile boolean transcribing = false;

    private TextView statusText;
    private ProgressBar progressBar;
    private View progressArea;
    private ScrollView resultArea;
    private TextView resultText;
    private Button copyButton;

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
                Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
            }
        });

        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText(getString(R.string.transcribe_error_no_audio));
            progressBar.setVisibility(View.GONE);
            return;
        }

        statusText.setText(getString(R.string.transcribe_loading_model));
        initNative(this);
    }

    @Override
    protected void onDestroy() {
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
                if (transcribing) return;
                statusText.setText(getString(R.string.transcribe_decoding_audio));
                startDecodeAndTranscribe();
            } else {
                statusText.setText(status);
                if (isErrorStatus(status)) {
                    transcribing = false;
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    // Called from Rust with transcription result
    public void onTextTranscribed(String text) {
        transcribing = false;
        Log.i(TAG, "onTextTranscribed: len=" + text.length() + " text=" + (text.length() > 100 ? text.substring(0, 100) + "..." : text));
        runOnUiThread(() -> {
            String lang = getResources().getConfiguration().locale.getLanguage();
            String filtered = WordCorrector.filterTranscriptionOutput(text, lang);
            String processed = new SettingsManager(getApplicationContext()).applyDictionary(filtered);
            
            // Hide progress, show result
            progressArea.setVisibility(View.GONE);
            resultArea.setVisibility(View.VISIBLE);
            copyButton.setVisibility(View.VISIBLE);

            resultText.setText(processed);

            // Auto-copy to clipboard
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Transcription", processed);
            clipboard.setPrimaryClip(clip);

            Toast.makeText(this, R.string.transcription_copied, Toast.LENGTH_LONG).show();

            // If post-processing (LLM cleanup via Groq) is enabled, refine the
            // displayed text asynchronously. Mirrors the wiring in
            // RecognizeActivity.onTextTranscribed line 152 and
            // RustInputMethodService.onTextTranscribed line 418 — brings TFA into
            // feature parity for share-intent transcripts. On PostProcessor error
            // we keep the applyDictionary-cleaned text and surface the failure in
            // the status line so the user is never left with no output.
            // ApplicationContext (not Activity) per AGENTS.md v0.8.7 leak pattern.
            SettingsManager sm = new SettingsManager(getApplicationContext());
            if (sm.isPostProcessEnabled()) {
                statusText.setText("Post-processing…");
                new PostProcessor(sm).process(processed, new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refined) {
                        Log.i(TAG, "PostProcessor refined len=" + refined.length());
                        runOnUiThread(() -> {
                            // Skip UI mutation if the activity has been torn down
                            // while the OkHttp callback was in flight.
                            if (isFinishing() || isDestroyed()) return;
                            resultText.setText(refined);
                            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cb.setPrimaryClip(ClipData.newPlainText("Transcription", refined));
                        });
                    }
                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "PostProcessor failed (keeping applyDictionary text): " + error);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            statusText.setText("Post-process error: " + error);
                        });
                    }
                });
            }
        });
    }

    private void startDecodeAndTranscribe() {
        transcribing = true;
        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText(getString(R.string.transcribe_error_no_audio_file));
            return;
        }

        new Thread(() -> {
            float[] samples = null;
            String usedDecoder = null;
            Exception mediaError = null;

            // Manual WAV reader is faster and more reliable than MediaExtractor on
            // Android 16+ where MediaExtractor.setDataSource(file://Uri) frequently fails.
            // Catch OutOfMemoryError | Exception so a large data-chunk OOM also falls
            // through to MediaExtractor rather than crashing the thread — but keep our
            // own NPE / AssertionError / StackOverflowError loud (they extend Error and
            // would be silently swallowed by a Throwable catch).
            try {
                samples = decodeManualWav(audioUri);
                usedDecoder = "manual-wav";
            } catch (OutOfMemoryError | Exception t) {
                Log.w(TAG, "Manual WAV reader failed, falling back to MediaExtractor", t);
            }

            // Fall back to MediaExtractor for compressed / non-WAV containers (MP3, M4A, OGG, …).
            if (samples == null) {
                try {
                    samples = decodeAudioToSamples(audioUri);
                    usedDecoder = "MediaExtractor";
                } catch (Exception e) {
                    Log.e(TAG, "MediaExtractor decode failed", e);
                    mediaError = e;
                }
            }

            if (samples == null || samples.length == 0) {
                String msg = mediaError != null
                        ? "Error: Could not decode audio: " + mediaError.getMessage()
                        : "Error: Could not decode audio file";
                showError(msg);
                return;
            }

            Log.i(TAG, "Transcribing " + samples.length + " samples (decoder=" + usedDecoder + ")");
            runOnUiThread(() -> statusText.setText(getString(R.string.transcribing)));
            transcribeAudio(samples, samples.length);
        }).start();
    }

    /**
     * Manual RIFF/WAVE reader for simple PCM (16-bit LE). Tries this BEFORE
     * MediaExtractor because on Android 16+ MediaExtractor refuses file:// URIs
     * even for the app's own external files dir. Supports mono and stereo
     * (mixed to mono), resamples to TARGET_SAMPLE_RATE if needed.
     * Throws IOException for non-PCM / non-16-bit / unrecognized containers so
     * the caller can fall back to MediaExtractor.
     */
    private float[] decodeManualWav(Uri uri) throws IOException {
        try (InputStream raw = openAudioStream(uri);
             LEDataInputStream dis = new LEDataInputStream(new BufferedInputStream(raw))) {

            // --- RIFF header (12 bytes) ---
            byte[] riff = new byte[4];
            dis.readFully(riff);
            if (!"RIFF".equals(new String(riff, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a RIFF file (got '" + new String(riff, StandardCharsets.US_ASCII) + "')");
            }
            int riffSize = dis.readIntLE();
            if (riffSize < 4) throw new IOException("RIFF size too small: " + riffSize);
            byte[] wave = new byte[4];
            dis.readFully(wave);
            if (!"WAVE".equals(new String(wave, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a WAVE file");
            }

            int sampleRate = -1;
            int channels = -1;
            int bitsPerSample = -1;
            int audioFormat = -1;
            byte[] pcmData = null;

            // --- Walk chunks ---
            while (true) {
                byte[] chunkIdBytes = new byte[4];
                try {
                    dis.readFully(chunkIdBytes);
                } catch (EOFException eof) {
                    throw new IOException("Unexpected EOF in chunk header");
                }
                int chunkSize = dis.readIntLE();
                String id = new String(chunkIdBytes, StandardCharsets.US_ASCII);

                if ("fmt ".equals(id)) {
                    if (chunkSize < 16) throw new IOException("fmt chunk too small: " + chunkSize);
                    audioFormat = dis.readShortLE() & 0xFFFF;
                    channels = dis.readShortLE() & 0xFFFF;
                    sampleRate = dis.readIntLE();
                    dis.readIntLE(); // byte rate
                    dis.readShortLE(); // block align
                    bitsPerSample = dis.readShortLE() & 0xFFFF;
                    int extraFmt = chunkSize - 16;
                    if (extraFmt > 0) dis.skipBytes(extraFmt);
                } else if ("data".equals(id)) {
                    if (audioFormat < 0) throw new IOException("data chunk before fmt chunk");
                    if (audioFormat != 1) {
                        // WAVE_FORMAT_EXTENSIBLE (0xFFFE) would require parsing the
                        // SubFormat GUID + cbSize extension to know the real
                        // container bitsPerSample; let MediaCodec handle it.
                        throw new IOException("Manual reader only supports PCM (format=1); got format=" + audioFormat);
                    }
                    if (bitsPerSample != 16) {
                        throw new IOException("Manual reader only supports 16-bit PCM; got " + bitsPerSample + "-bit");
                    }
                    if (channels != 1 && channels != 2) {
                        throw new IOException("Manual reader supports 1 or 2 channels; got " + channels);
                    }
                    // Guard against a maliciously-large or corrupt chunkSize that
                    // would otherwise OOM here. With the LE read fix, `chunkSize`
                    // round-trips honestly through the decoder for the first time.
                    if (chunkSize < 0 || chunkSize > MAX_AUDIO_FILE_SIZE) {
                        throw new IOException("data chunk size " + chunkSize
                                + " bytes outside allowed range (0, " + MAX_AUDIO_FILE_SIZE + "]");
                    }
                    byte[] buf = new byte[chunkSize];
                    dis.readFully(buf);
                    pcmData = buf;
                    break; // we are done
                } else {
                    // LIST, JUNK, PAD, fact, … — skip; pad byte if chunk size was odd
                    if (chunkSize > 0) dis.skipBytes(chunkSize);
                    if ((chunkSize & 1) == 1) dis.skipBytes(1);
                }
            }

            if (pcmData == null) throw new IOException("No data chunk found");

            // --- PCM samples → float[] mono (single ByteBuffer view instead of byte-by-byte) ---
            short[] rawShorts = new short[pcmData.length / 2];
            ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(rawShorts);
            int frameCount = rawShorts.length / channels;
            float[] mono = new float[frameCount];
            if (channels == 1) {
                for (int i = 0; i < frameCount; i++) mono[i] = rawShorts[i] / 32768.0f;
            } else { // channels == 2: average L+R
                for (int i = 0; i < frameCount; i++) {
                    mono[i] = ((int) rawShorts[2 * i] + (int) rawShorts[2 * i + 1]) / 65536.0f;
                }
            }

            if (sampleRate != TARGET_SAMPLE_RATE) {
                Log.i(TAG, "Resampling " + frameCount + " samples from " + sampleRate
                        + " to " + TARGET_SAMPLE_RATE + " (manual reader)");
                mono = resample(mono, sampleRate, TARGET_SAMPLE_RATE);
            }

            Log.i(TAG, "Manual WAV decoded: " + mono.length + " samples @ " + sampleRate
                    + "Hz, " + channels + "ch, " + bitsPerSample + "-bit");
            return mono;
        }
    }

    /**
     * Open an InputStream for the given audio URI. For file:// URIs, prefer a
     * direct FileInputStream (file-system path) which bypasses the storage access
     * framework: ContentResolver.openInputStream() returns EACCES on Android 16
     * even for files in the app's own external files dir. For non-file schemes
     * or unreadable paths, the caller falls back to MediaCodec via
     * decodeAudioToSamples (which itself prefers the raw path for setDataSource).
     */
    private InputStream openAudioStream(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.canRead()) {
                Log.i(TAG, "Opening " + uri + " via direct FileInputStream");
                return new FileInputStream(f);
            }
            // Don't fall through to ContentResolver for file:// — it would EACCES
            // on Android 16+; let the outer startDecodeAndTranscribe catch and
            // route to MediaCodec (raw path) instead.
            throw new IOException("Cannot read file: " + uri);
        }
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("null stream for " + uri);
        return is;
    }

    private void showError(String message) {
        transcribing = false;
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
        // On Android 16+ the storage access framework refuses file:// URIs;
        // pass the raw path to MediaExtractor to bypass the EACCES check.
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            extractor.setDataSource(uri.getPath());
        } else {
            extractor.setDataSource(this, uri, null);
        }

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

        // Anti-aliasing pre-filter: moving average of window size floor(ratio)
        int filterSpan = (int) Math.max(1, Math.floor(ratio));
        if (filterSpan > 1) {
            float[] filtered = new float[input.length];
            float sum = 0;
            // Initialize moving average
            for (int i = 0; i < filterSpan; i++) {
                sum += input[i];
            }
            filtered[0] = sum / filterSpan;
            for (int i = 1; i < input.length; i++) {
                sum -= input[i - 1];
                if (i + filterSpan - 1 < input.length) {
                    sum += input[i + filterSpan - 1];
                }
                filtered[i] = sum / filterSpan;
            }
            input = filtered;
        }

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

    // Native methods
    private native void initNative(TranscribeFileActivity activity);
    private native void cleanupNative();
    private native void transcribeAudio(float[] samples, int length);

    private static boolean isErrorStatus(String status) {
        if (status == null) return false;
        String lower = status.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("error") || lower.contains("fail");
    }

    /**
     * Little-endian DataInputStream. RIFF/WAVE integers are LE, unlike the
     * default {@link java.io.DataInputStream} which reads BE. Provides only the
     * primitive methods we need (readFully, readIntLE, readShortLE, skipBytes).
     */
    private static final class LEDataInputStream implements AutoCloseable {
        private final InputStream in;
        private final byte[] tmp4 = new byte[4];
        private final byte[] tmp2 = new byte[2];

        LEDataInputStream(InputStream in) {
            this.in = in;
        }

        void readFully(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
        }

        int readIntLE() throws IOException {
            readFully(tmp4);
            return (tmp4[0] & 0xFF)
                    | ((tmp4[1] & 0xFF) << 8)
                    | ((tmp4[2] & 0xFF) << 16)
                    | ((tmp4[3] & 0xFF) << 24);
        }

        int readShortLE() throws IOException {
            readFully(tmp2);
            return (tmp2[0] & 0xFF) | ((tmp2[1] & 0xFF) << 8);
        }

        @SuppressWarnings("UnusedReturnValue")
        int skipBytes(int n) throws IOException {
            long remaining = n;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    int b = in.read();
                    if (b < 0) break;
                    skipped = 1;
                }
                remaining -= skipped;
            }
            return n - (int) remaining;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
