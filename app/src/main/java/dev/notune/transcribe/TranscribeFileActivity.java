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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
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
    // Cache prefix for materialized audio files. The decoder APIs (MediaCodec /
    // MediaExtractor + our manual RIFF reader) want either an InputStream or a
    // raw file path. On Android 16+ scoped storage the original URI may refuse
    // both ContentResolver.openInputStream and MediaExtractor.setDataSource, so
    // we copy the stream into the app's private cache dir once and read from
    // there for both decoding paths. Old copies are pruned at every materialize.
    private static final String CACHE_PREFIX = "tfa_audio_";
    private static final long CACHE_MAX_AGE_MS = 60_000; // 1 minute
    // Age-only pruning is not enough: a user who rapid-shares dozens of audio
    // files in under 60s leaves a CACHE_MAX_FILES-sized working set in cacheDir,
    // each potentially up to MAX_AUDIO_FILE_SIZE. Bound it to a small number so
    // a heavy share session does not OOM low-RAM devices.
    private static final int CACHE_MAX_FILES = 5;

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
    // Track the audio URI currently bound. Updated from both onCreate (initial
    // intent) and onNewIntent (re-share under launchMode="singleTask"). Always
    // read fresh inside startDecodeAndTranscribe's worker thread so a new
    // share that arrives between engine status updates isn't lost.
    private volatile Uri audioUri = null;
    // engineReady becomes true the first time Rust emits "Ready" via
    // onStatusUpdate. Once set, subsequent onNewIntent calls for rapid
    // re-sharing can immediately restart the decode pipeline without waiting
    // for a fresh model load (the engine singleton never tears down between
    // share intents within the same process).
    private volatile boolean engineReady = false;
    // Monotonic generation counter incremented by every startDecodeAndTranscribe.
    // Each worker thread snapshots its own gen at launch and bails on
    // transcribeAudio() if a newer gen exists, so a rapid re-share of B
    // after A is being decoded will not consume Rust inference time on the
    // stale A copy. Without this, two threads can both reach
    // transcribeAudio, queue both jobs in the Rust engine Mutex, and
    // deliver A's stale result via onTextTranscribed *after* B's — which
    // would show A's text on screen and copy A's text to the clipboard.
    private final java.util.concurrent.atomic.AtomicInteger decodeGen =
            new java.util.concurrent.atomic.AtomicInteger(0);
    // Set true once onDestroy() runs. Short-circuits the worker thread before
    // transcribeAudio() and skips the new-PostProcessor OkHttp round-trip in
    // onTextTranscribed, so any work that captures `this` does not run after
    // the Activity is gone. The isFinishing()/isDestroyed() guard inside the
    // runOnUiThread lambdas is the second line of defense; this flag is the
    // cheap up-front gate so we don't even pay the cost of dictionary apply
    // or okhttp pool handoff once the Activity is finishing.
    private volatile boolean destroyed = false;

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

        Uri initialUri = extractAudioUri(getIntent());
        if (initialUri == null) {
            statusText.setText(getString(R.string.transcribe_error_no_audio));
            progressBar.setVisibility(View.GONE);
            return;
        }
        this.audioUri = initialUri;

        statusText.setText(getString(R.string.transcribe_loading_model));
        initNative(this);
    }

    /**
     * Handle re-sharing of an audio file while TFA is already running.
     * Under {@code launchMode="singleTask"}, the system delivers a second
     * share intent to {@code onNewIntent} instead of creating a new Activity,
     * so {@link #audioUri} is updated here rather than in {@link #onCreate}.
     * Without {@code setIntent(intent)}, {@link #getIntent()} would keep
     * returning the original intent for the rest of the Activity's lifetime,
     * silently dropping every subsequent share.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        Uri nextUri = extractAudioUri(intent);
        if (nextUri == null) {
            Log.w(TAG, "onNewIntent: no audio URI in new intent");
            return;
        }
        Log.i(TAG, "onNewIntent: new audio URI " + nextUri);
        this.audioUri = nextUri;
        runOnUiThread(() -> statusText.setText(getString(R.string.transcribe_loading_model)));

        if (engineReady) {
            // Engine singleton is already loaded, so skip the model-load
            // wait and restart decode+transcribe for the new file. Any
            // in-flight decode of a previous file will still finish, but
            // its UI updates are harmless: the next completion just
            // overwrites the earlier one. Engine inference is serialized
            // via the Rust Mutex<EngineWrapper>, so total time scales with
            // the number of pending shares — acceptable since rapid
            // multi-share from one user is an exceptional corner case.
            Log.i(TAG, "onNewIntent: engine already ready; restarting decode");
            startDecodeAndTranscribe();
        } else {
            Log.i(TAG, "onNewIntent: deferring until engine becomes ready");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        // Best-effort cleanup. The Rust GLOBAL_ENGINE singleton
        // (Lazy<Mutex<EngineWrapper>>) is shared across TranscribeFileActivity
        // and other consumers (RecognizeActivity, IME, LiveSubtitles), so
        // this call cannot actually tear down the engine — it signals this
        // Activity's native bindings to drop their JNI references only. The
        // engine stays resident until process death or an explicit
        // switchModel from another bridge.
        try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
    }

    // Read the audio URI out of an Intent. Used from onCreate (where we
    // adopt whatever the system put on our Intent at launch) and from
    // onNewIntent (where the system gave us a *new* Intent while we were
    // already running under launchMode="singleTask"). Single source of
    // truth so both lifecycle paths apply the same SEND/EXTRA_STREAM vs
    // VIEW/DATA precedence rules.
    private static Uri extractAudioUri(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            return intent.getData();
        }
        return null;
    }

    private Uri getAudioUri() {
        return audioUri;
    }

    // Called from Rust when model is ready
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            if ("Ready".equals(status)) {
                if (!engineReady) {
                    engineReady = true;
                    Log.i(TAG, "engine ready");
                }
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
        // Fast destroyed-flag pre-check. If the Activity is gone, drop the
        // Rust callback without launching any UI updates or a PostProcessor
        // OkHttp round-trip — both would pin a destroyed Activity reference
        // until completion. The runOnUiThread lambdas inside this method
        // also early-return via isFinishing()/isDestroyed(), but those are
        // checked later in the chain; this gate avoids paying the cost of
        // the dictionary apply and the OkHttp thread pool handoff at all.
        if (destroyed) {
            Log.i(TAG, "onTextTranscribed: Activity destroyed; dropping callback");
            return;
        }
        // Snapshot the current generation BEFORE going to the UI thread. If
        // a newer share has already incremented the counter, this result is
        // from a stale decode and must not overwrite the latest UI / clipboard.
        final int resultGen = decodeGen.get();
        transcribing = false;
        Log.i(TAG, "onTextTranscribed (gen=" + resultGen + "): len=" + text.length()
                + " text=" + (text.length() > 100 ? text.substring(0, 100) + "..." : text));
        runOnUiThread(() -> {
            if (decodeGen.get() != resultGen) {
                Log.i(TAG, "onTextTranscribed: gen " + resultGen
                        + " no longer latest (" + decodeGen.get()
                        + "); dropping stale result");
                return;
            }
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
        // Snapshot the field once so a re-share arriving during the decode
        // does not change which URI this thread processes. Without the
        // snapshot, a second onNewIntent could swap audioUri between the
        // dereferences below and yield a thread that mixes up intent
        // handling or grabs the stale URI.
        Uri currentUri = audioUri;
        if (currentUri == null) {
            statusText.setText(getString(R.string.transcribe_error_no_audio_file));
            return;
        }
        transcribing = true;
        // Increment the decode generation. Any in-flight thread that has
        // already snapshotted a smaller value will see it's stale and skip
        // its transcribeAudio (native) call below — keeping the latest
        // share's inference time bounded by A's wasted CPU (Java-side
        // materialize + decode), not A's wasted Rust inference time.
        final int myGen = decodeGen.incrementAndGet();
        Log.i(TAG, "startDecodeAndTranscribe gen=" + myGen + ", uri=" + currentUri);

        new Thread(() -> {
            File audioFile = null;
            float[] samples = null;
            String usedDecoder = null;
            Exception decodeError = null;

            try {
                runOnUiThread(() -> statusText.setText("Copying audio to cache…"));
                audioFile = materializeAudio(currentUri);
                Log.i(TAG, "Materialized " + currentUri + " to " + audioFile.getAbsolutePath()
                        + " (" + audioFile.length() + " bytes)");

                // Manual WAV reader is faster and more reliable than MediaExtractor
                // for plain PCM. Catch OutOfMemoryError | Exception so a large
                // data-chunk OOM also falls through to MediaExtractor rather than
                // crashing the thread — but keep our own NPE / AssertionError /
                // StackOverflowError loud (they extend Error and would be silently
                // swallowed by a Throwable catch).
                try {
                    samples = decodeManualWav(audioFile);
                    usedDecoder = "manual-wav";
                } catch (OutOfMemoryError | Exception t) {
                    Log.w(TAG, "Manual WAV reader failed, falling back to MediaExtractor", t);
                }

                if (samples == null) {
                    // Fall back to MediaCodec for compressed / non-WAV containers
                    // (MP3, M4A, OGG, FLAC, WAVE_FORMAT_EXTENSIBLE, …).
                    try {
                        samples = decodeAudioToSamples(audioFile);
                        usedDecoder = "MediaExtractor";
                    } catch (Exception e) {
                        Log.e(TAG, "MediaExtractor decode failed", e);
                        decodeError = e;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio materialization failed", e);
                decodeError = e;
            }
            // (Cache file under getCacheDir()/tfa_audio_* is intentionally
            // left in place until cleanupOldCacheFiles() prunes it on the
            // next materialize, or Android prunes the cache dir under
            // storage pressure. Keeping it around avoids a recopy if the
            // share flow re-fires within the same 60s window.)

            if (samples == null || samples.length == 0) {
                String msg = decodeError != null
                        ? "Error: Could not decode audio: " + decodeError.getMessage()
                        : "Error: Could not decode audio file";
                showError(msg);
                return;
            }

            Log.i(TAG, "Transcribing " + samples.length + " samples (decoder=" + usedDecoder + ") for gen=" + myGen);
            runOnUiThread(() -> {
                if (decodeGen.get() != myGen) {
                    Log.i(TAG, "transcribing status: gen " + myGen + " no longer current; skipping UI update");
                    return;
                }
                statusText.setText(getString(R.string.transcribing));
            });
            // Generation gate: if a newer share arrived while we were
            // running, drop out before burning Rust inference time. The
            // newer thread will call write its own myGen, and its result
            // is the one the user wants.
            if (decodeGen.get() != myGen) {
                Log.i(TAG, "decode gen=" + myGen + " superseded; skipping transcribeAudio");
                transcribing = false;
                return;
            }
            // destroyed-flag gate: if the Activity has been torn down
            // while we were decoding (user re-shared + immediately backed
            // out, or a system-level configuration change), skip the JNI
            // infer call so the Rust onTextTranscribed callback path does
            // not run on a captured-this-of-destroyed-Activity context.
            if (destroyed) {
                Log.i(TAG, "decode gen=" + myGen + ": Activity destroyed; skipping transcribeAudio");
                return;
            }
            transcribeAudio(samples, samples.length);
        }, "TFA-decode-gen-" + myGen).start();
    }

    /**
     * Manual RIFF/WAVE reader for simple PCM (16-bit LE). Reads from a local
     * cache File produced by {@link #materializeAudio}, so the InputStream
     * path here is unambiguous and never hits scoped-storage fenceposts.
     * Supports mono and stereo (mixed to mono), resamples to
     * TARGET_SAMPLE_RATE if needed.
     * Throws IOException for non-PCM / non-16-bit / unrecognized containers so
     * the caller can fall back to MediaExtractor.
     */
    private float[] decodeManualWav(File file) throws IOException {
        if (file.length() > MAX_AUDIO_FILE_SIZE) {
            throw new IOException("Audio file " + file.getName()
                    + " is " + file.length() + " bytes, exceeds MAX_AUDIO_FILE_SIZE ("
                    + MAX_AUDIO_FILE_SIZE + ")");
        }
        try (InputStream raw = new BufferedInputStream(new FileInputStream(file));
             LEDataInputStream dis = new LEDataInputStream(raw)) {

            // --- RIFF header (12 bytes) ---
            byte[] riff = new byte[4];
            dis.readFully(riff);
            if (!"RIFF".equals(new String(riff, StandardCharsets.US_ASCII))) {
                throw new IOException("Not a RIFF file (got '" + new String(riff, StandardCharsets.US_ASCII) + "')");
            }
            long riffSize = dis.readIntULE();
            // RIFF size is the file size minus the 8-byte header ("RIFF" + the
            // size field itself). Guard against corrupted or very-small files.
            // Using unsigned long comparison so that any 32-bit value < 4
            // (including unsigned overflow from a correct-size read) is caught.
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
                long chunkSize = dis.readIntULE();
                String id = new String(chunkIdBytes, StandardCharsets.US_ASCII);

                if ("fmt ".equals(id)) {
                    if (chunkSize < 16) throw new IOException("fmt chunk too small: " + chunkSize);
                    audioFormat = dis.readShortLE() & 0xFFFF;
                    channels = dis.readShortLE() & 0xFFFF;
                    sampleRate = dis.readIntLE();
                    dis.readIntLE(); // byte rate
                    dis.readShortLE(); // block align
                    bitsPerSample = dis.readShortLE() & 0xFFFF;
                    long extraFmt = chunkSize - 16;
                    if (extraFmt > 0) dis.skipBytes((int) Math.min(extraFmt, Integer.MAX_VALUE));
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
                    // would otherwise OOM here. With the unsigned read fix,
                    // `chunkSize` round-trips honestly through the decoder for the
                    // first time — negative values from signed overflow are gone.
                    if (chunkSize > MAX_AUDIO_FILE_SIZE) {
                        throw new IOException("data chunk size " + chunkSize
                                + " bytes outside allowed range (0, " + MAX_AUDIO_FILE_SIZE + "]");
                    }
                    byte[] buf = new byte[(int) chunkSize];
                    dis.readFully(buf);
                    pcmData = buf;
                    break; // we are done
                } else {
                    // LIST, JUNK, PAD, fact, … — skip; pad byte if chunk size was odd
                    // Clamp to Integer.MAX_VALUE because skipBytes takes an int.
                    // A chunk > 2 GiB is unlikely in practice but we must not take
                    // the negative-branch of a signed comparison that would cause
                    // the chunk to not be skipped, misaligning the parser.
                    if (chunkSize > 0) {
                        int toSkip = (int) Math.min(chunkSize, Integer.MAX_VALUE);
                        dis.skipBytes(toSkip);
                    }
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
     * Open an InputStream for the given audio URI. Try the system
     * ContentResolver FIRST: it handles content:// URIs with a SAF grant
     * (the stock case for *shared* audio from other apps) and can also
     * return a stream for file:// URIs when the OS has already granted
     * access (e.g. via FileProvider). On Android 16+ scoped storage,
     * calling FileInputStream directly on /sdcard paths fails because
     * {@link File#canRead()} returns false even for files in the app's
     * own external dir — that's why ContentResolver is preferred. We
     * only fall through to a direct FileInputStream for file:// URIs
     * the app already knows it owns (its own external-files dir, where
     * Android grants access without prompting).
     */
    private InputStream openAudioStream(Uri uri) throws IOException {
        InputStream resolverStream = null;
        try {
            resolverStream = getContentResolver().openInputStream(uri);
            if (resolverStream != null) return resolverStream;
        } catch (FileNotFoundException | SecurityException e) {
            // No SAF grant or no provider for this URI — try direct file
            // access below.
            Log.d(TAG, "ContentResolver.openInputStream(" + uri + ") failed: " + e.getMessage());
        }

        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.canRead()) {
                Log.i(TAG, "Opening " + uri + " via direct FileInputStream");
                return new FileInputStream(f);
            }
        }
        throw new IOException("Cannot open input stream for uri: " + uri);
    }

    /**
     * Copy the audio referenced by {@code uri} into the activity's private
     * cache directory and return the resulting File. The cache file is then
     * the single source of truth for both decoders, which means:
     *   - MediaCodec / MediaExtractor can use the conventional
     *     {@code setDataSource(file.absolutePath)} API (which refuses
     *     /sdcard paths on Android 16+).
     *   - The manual RIFF reader reads from a regular FileInputStream with
     *     no SAF-grant ambiguity.
     * The copy enforces MAX_AUDIO_FILE_SIZE — anything larger is rejected
     * with IOException before we burn heap on a corrupt or malicious input.
     * Old cache files older than CACHE_MAX_AGE_MS are pruned before each copy
     * so the cache dir never grows unbounded over a long session.
     */
    private File materializeAudio(Uri uri) throws IOException {
        cleanupOldCacheFiles();
        String ext = pickAudioExtension(uri);
        File out = new File(getCacheDir(), CACHE_PREFIX + System.currentTimeMillis() + ext);
        try (InputStream in = openAudioStream(uri);
             FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_AUDIO_FILE_SIZE) {
                    os.close();
                    out.delete();
                    throw new IOException("Audio stream exceeds MAX_AUDIO_FILE_SIZE ("
                            + MAX_AUDIO_FILE_SIZE + " bytes) after " + total + " bytes");
                }
                os.write(buf, 0, n);
            }
        }
        Log.i(TAG, "Materialized " + uri + " -> " + out.getAbsolutePath()
                + " (" + out.length() + " bytes)");
        return out;
    }

    /**
     * Prune {@link #CACHE_PREFIX} files from getCacheDir() in two stages:
     * <ol>
     *   <li>Age-based: delete any file older than {@link #CACHE_MAX_AGE_MS}.</li>
     *   <li>Count-based: after the age prune, if more than
     *       {@link #CACHE_MAX_FILES} remain, delete oldest-first by
     *       {@link File#lastModified()} until we are at the cap.</li>
     * </ol>
     * The age prune alone is insufficient: a user who rapid-shares dozens of
     * audio files within a 60s window would keep all of them until 60s elapsed,
     * so we cap the working set at the same time. Stage 1 is cheaper than
     * Stage 2 (no sort); both stages are O(n) in the number of cache files.
     */
    private void cleanupOldCacheFiles() {
        File cache = getCacheDir();
        File[] files = cache.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        // Stage 1 — age prune.
        for (File f : files) {
            if (f.isFile()
                    && f.getName().startsWith(CACHE_PREFIX)
                    && now - f.lastModified() > CACHE_MAX_AGE_MS) {
                if (f.delete()) {
                    Log.i(TAG, "Pruned stale cache file " + f.getName());
                }
            }
        }
        // Stage 2 — count cap. Re-list because Stage 1 mutated the directory.
        File[] live = cache.listFiles();
        if (live == null) return;
        java.util.ArrayList<File> ageMatched = new java.util.ArrayList<>();
        for (File f : live) {
            if (f.isFile() && f.getName().startsWith(CACHE_PREFIX)) {
                ageMatched.add(f);
            }
        }
        if (ageMatched.size() <= CACHE_MAX_FILES) return;
        ageMatched.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toDelete = ageMatched.size() - CACHE_MAX_FILES;
        for (int i = 0; i < toDelete; i++) {
            if (ageMatched.get(i).delete()) {
                Log.i(TAG, "Pruned count-cap cache file " + ageMatched.get(i).getName());
            }
        }
    }

    /**
     * Pick a sensible filename extension for the cache copy so MediaCodec
     * can identify the container without sniffing. Falls back to ".bin" if
     * we cannot recover an audio/* mime type or a path extension. Used
     * only for the cache temp file; the file content is unchanged.
     */
    private String pickAudioExtension(Uri uri) {
        String mime = null;
        try {
            mime = getContentResolver().getType(uri);
        } catch (Exception ignored) { }
        if (mime != null && mime.startsWith("audio/")) {
            String sub = mime.substring("audio/".length());
            int slash = sub.indexOf('/');
            if (slash >= 0) sub = sub.substring(0, slash);
            if (!sub.isEmpty()) return "." + sub.toLowerCase().replace('+', 'p');
        }
        String path = uri.getPath();
        if (path != null) {
            int q = path.indexOf('?');
            if (q > 0) path = path.substring(0, q);
            int dot = path.lastIndexOf('.');
            if (dot > 0 && dot < path.length() - 1 && dot >= path.length() - 6) {
                return path.substring(dot);
            }
        }
        return ".bin";
    }

    private void showError(String message) {
        transcribing = false;
        runOnUiThread(() -> {
            statusText.setText(message);
            progressBar.setVisibility(View.GONE);
        });
    }

    /**
     * Decode audio from a local cache File to 16kHz mono float samples
     * using MediaExtractor/MediaCodec. The caller has already taken care
     * of bringing the file into getCacheDir(), so the path-based
     * MediaExtractor API works on every Android version — no need for
     * the URI-based overload that the previous implementation used as a
     * workaround for the now-broken /sdcard setDataSource call.
     *
     * Resource safety: {@link MediaExtractor} and {@link MediaCodec} are
     * allocated up front and released in the {@code finally} block, so an
     * exception from {@code dequeueInputBuffer}, {@code readSampleData},
     * {@code asShortBuffer().get()}, etc. does not strand the underlying
     * system_media_process codecs. {@code codec.stop()} may throw
     * {@link IllegalStateException} if the codec never reached the Started
     * state (e.g. setDataSource failed) — we swallow that on the cleanup
     * path because we are already heading out the door.
     */
    private float[] decodeAudioToSamples(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        boolean codecStarted = false;
        try {
            extractor.setDataSource(file.getAbsolutePath());

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

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();
            codecStarted = true;

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

            // Resample to 16kHz if needed
            float[] monoSamples = mergeChunks(allChunks, totalSamples);

            if (sampleRate != TARGET_SAMPLE_RATE) {
                Log.i(TAG, "Resampling from " + sampleRate + " to " + TARGET_SAMPLE_RATE);
                monoSamples = resample(monoSamples, sampleRate, TARGET_SAMPLE_RATE);
            }

            Log.i(TAG, "Decoded " + monoSamples.length + " samples at 16kHz");
            return monoSamples;
        } finally {
            // Always release codec + extractor on the way out — success OR
            // exception. codec.stop() before codec.release() is the safe
            // order even though we swallow any IllegalStateException; if
            // start() never reached the running state we just skip stop().
            if (codec != null) {
                if (codecStarted) {
                    try { codec.stop(); } catch (IllegalStateException ignored) { }
                }
                try { codec.release(); } catch (IllegalStateException ignored) { }
            }
            try { extractor.release(); } catch (IllegalStateException ignored) { }
        }
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

        /**
         * Read 4 bytes as an unsigned little-endian {@code long}. RIFF/WAVE
         * size fields are 32-bit unsigned integers; Java's signed {@code int}
         * cannot represent values >= 2¹⁹.  Using the signed return of
         * {@link #readIntLE()} would produce a negative value for any chunk
         * whose high bit (bit 31) is set, causing size comparisons like
         * {@code chunkSize < 0} or {@code riffSize < 4} to fire
         * incorrectly and, more critically, causing the chunk-walker to
         * skip the {@code if (chunkSize > 0)} guard and misalign every
         * subsequent read.
         */
        long readIntULE() throws IOException {
            return readIntLE() & 0xFFFFFFFFL;
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
