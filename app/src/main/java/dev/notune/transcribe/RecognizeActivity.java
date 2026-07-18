package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.pm.PackageManager;

import java.util.ArrayList;

public class RecognizeActivity extends Activity {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView status;
    private boolean isRecording = false;
    private MicLevelView micLevel;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;

    private final android.os.Handler pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                float level = getAudioLevelNative();
                micLevel.setLevel(level);
                pollHandler.postDelayed(this, 50);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recognize_activity);

        // Keep the screen awake for the lifetime of this recording screen so it
        // never sleeps mid-capture and cuts the recording short.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        micLevel = findViewById(R.id.mic_level);
        status = findViewById(R.id.txt_status);

        findViewById(R.id.btn_close).setOnClickListener(v -> {
            // discard current recording
            if (isRecording) {
                isRecording = false;
                pollHandler.removeCallbacks(pollRunnable);
                cancelRecording();   // new native method
            }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // Tap anywhere (or on mic) to stop
        findViewById(R.id.root).setOnClickListener(v -> {
            if (isRecording) {
                isRecording = false;
                pollHandler.removeCallbacks(pollRunnable);
                status.setText(getString(R.string.ime_processing));
                stopRecording();
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
            }
        });

        // Permission check
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText(getString(R.string.recog_mic_permission_required));
            return;
        }

        initNative(this);
        isRecording = true;
        status.setText(getString(R.string.recog_listening));
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording();
        pollHandler.post(pollRunnable);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isRecording", isRecording);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        isRecording = savedInstanceState.getBoolean("isRecording", false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacks(pollRunnable);
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
    }

    // Called from Rust
    public void onStatusUpdate(String s) {
        final String shown;
        if ("Ready".equals(s)) {
            shown = getString(R.string.recog_ready);
        } else if ("Listening...".equals(s)) {
            shown = getString(R.string.recog_listening);
        } else {
            shown = s;
        }

        runOnUiThread(() -> status.setText(shown));
    }

    // Called from Rust – keep same method name as IME for code reuse
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            String lang = getResources().getConfiguration().locale.getLanguage();
            String filtered = WordCorrector.filterTranscriptionOutput(text, lang);
            String processed = new SettingsManager(RecognizeActivity.this).applyDictionary(filtered);
            SettingsManager sm = new SettingsManager(RecognizeActivity.this);
            if (sm.isPostProcessEnabled()) {
                new PostProcessor(sm).process(processed, new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        runOnUiThread(() -> {
                            ArrayList<String> results = new ArrayList<>();
                            results.add(refinedText);
                            Intent data = new Intent();
                            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
                            setResult(Activity.RESULT_OK, data);
                            finish();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Post-process error: " + error);
                        ArrayList<String> results = new ArrayList<>();
                        results.add(processed);
                        Intent data = new Intent();
                        data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
                        setResult(Activity.RESULT_OK, data);
                        finish();
                    }
                });
            } else {
                ArrayList<String> results = new ArrayList<>();
                results.add(processed);
                Intent data = new Intent();
                data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
                setResult(Activity.RESULT_OK, data);
                finish();
            }
        });
    }

    private boolean isPauseAudioEnabled() {
        return new SettingsManager(this).isPauseAudio();
    }

    // Native methods
    private native void initNative(RecognizeActivity activity);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();
    private native float getAudioLevelNative();
}
