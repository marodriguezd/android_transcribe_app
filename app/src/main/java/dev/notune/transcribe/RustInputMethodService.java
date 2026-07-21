package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import java.io.File;

public class RustInputMethodService extends InputMethodService {
    
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

    private TextView statusView;
    private TextView hintView;
    private View recordContainer;
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private View inputView;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    private SettingsManager settingsManager;
    private PostProcessor postProcessor;
    private volatile boolean destroyed = false;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Transcribed text waiting to be committed because no editor was focused
    // when transcription finished. This happens on long transcribes where the
    // target field (e.g. a web field in Firefox/Gemini) drops focus while we
    // process audio. Flushed from onStartInputView once a field is focused
    // again so the text is never lost.
    private String pendingCommitText = null;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service onCreate");
        settingsManager = new SettingsManager(this);
        postProcessor = new PostProcessor(settingsManager);
        // Subscribe to cross-component engine-state events. The Rust engine
        // only fires notify_status on the activity passed to its JNI entry,
        // so emit paths from MainActivity (e.g. switching model) never reach
        // the IME without this relay. Registration BEFORE initNative is
        // launched so the listener catches the very first Loading… state
        // when the engine reload happens.
        EngineStateBroadcaster.addListener(this::onEngineStateChanged);
        new Thread(() -> {
            try {
                if (!destroyed) initNative(RustInputMethodService.this);
            } catch (Exception e) {
                Log.e(TAG, "Error in initNative", e);
            }
        }).start();
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            android.view.ContextThemeWrapper ctx = new android.view.ContextThemeWrapper(this, R.style.AppTheme);
            View view = android.view.LayoutInflater.from(ctx).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Handle window insets for avoiding navigation bar overlap
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                int originalPaddingBottom = v.getPaddingTop();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), originalPaddingBottom + paddingBottom);
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micIcon.setColorFilter(ContextCompat.getColor(this, R.color.mic_active));
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);
            
            com.google.android.material.materialswitch.MaterialSwitch aiSwitch = view.findViewById(R.id.ime_post_process_switch);
            if (settingsManager.getApiKey() == null || settingsManager.getApiKey().trim().isEmpty()) {
                aiSwitch.setVisibility(android.view.View.GONE);
            } else {
                aiSwitch.setVisibility(android.view.View.VISIBLE);
                aiSwitch.setChecked(settingsManager.isPostProcessEnabled());
                aiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    settingsManager.setPostProcessEnabled(isChecked);
                });
            }

            switchKeyboardButton.setOnClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    stopRecording();
                    updateRecordButtonUI(false);
                } else {
                    switchToPreviousInputMethod();
                }
            });

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(" ", 1);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                        }
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.commitText(" ", 1);
                        }
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;

                // Check microphone permission
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (statusView != null) statusView.setText(getString(R.string.ime_no_mic_permission));
                    if (hintView != null) hintView.setText(getString(R.string.ime_open_app_for_permission));
                    return;
                }

                if (isRecording) {
                    stopRecording();
                    if (pauseAudioActive) {
                        audioPauser.abandon(this);
                        pauseAudioActive = false;
                    }
                    updateRecordButtonUI(false);
                } else {
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    applyHotwords();
                    startRecording();
                    updateRecordButtonUI(true);
                }
            });

            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (!isRecording && settingsManager.isAutoRecord()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                applyHotwords();
                startRecording();
                updateRecordButtonUI(true);
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        if (isRecording) {
            try {
                cancelRecording();
            } catch (Throwable t) {
                Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                try { stopRecording(); } catch (Throwable ignored) { }
            }
            updateRecordButtonUI(false);
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputActive = true;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        // A field is focused and the input connection is live again — commit any
        // text that finished transcribing while nothing was focused.
        flushPendingText();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        inputActive = false;
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        // Keep the screen awake while recording so it never sleeps mid-capture
        // and cuts the recording short. Cleared automatically once we stop.
        if (inputView != null) {
            inputView.setKeepScreenOn(recording);
        }
        if (recording) {
            micIcon.setColorFilter(ContextCompat.getColor(this, R.color.mic_error));
            statusView.setText(getString(R.string.ime_listening));
            hintView.setText(getString(R.string.ime_tap_to_stop));
        } else {
            micIcon.setColorFilter(ContextCompat.getColor(this, R.color.mic_active));
            statusView.setText(getString(R.string.ime_processing));
            hintView.setText(getString(R.string.ime_tap_to_record));
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        super.onDestroy();
        // Unsubscribe so a queued mainHandler.post from a recent
        // EngineStateBroadcaster.setState doesn't bounce through a
        // destroyed service (CopyOnWriteArrayList iteration is safe but
        // accessing destroyed views would touch a null inputView etc.).
        EngineStateBroadcaster.removeListener(this::onEngineStateChanged);
        if (mainHandler != null) {
            mainHandler.removeCallbacks(backspaceRepeatRunnable);
            mainHandler.removeCallbacks(spaceRepeatRunnable);
        }
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();
    private native void setHotwords(String[] words);

    private void applyHotwords() {
        // Hotwords are no longer sent to the ASR engine to prevent hallucination.
        // They are instead processed in PostProcessor.java as dictionary replacements and LLM hints.
    }

    // Called from Rust (e.g. IME's own initNative path, future direct
    // transcription status pushes). Publishes to the cross-component
    // broadcaster which fires our local listener AND any other
    // subscriber. The local listener is the single source of truth for
    // updating UI / lastStatus / pendingSwitchBack / pauseAudioActive.
    public void onStatusUpdate(String status) {
        EngineStateBroadcaster.setState(status);
    }

    /**
     * Listener callback fired by {@link EngineStateBroadcaster}. Centralises
     * the IME's reaction to engine-state changes (loading, transcribing,
     * error, ready). Called on the main thread (the broadcaster marshals
     * via its internal Handler on the main looper).
     */
    private void onEngineStateChanged(String status) {
        Log.d(TAG, "engine state: " + status);
        lastStatus = status;
        updateUiState();
        if (pendingSwitchBack && EngineStateBroadcaster.isError(status)) {
            pendingSwitchBack = false;
            switchToPreviousInputMethod();
        }
        if (pauseAudioActive && EngineStateBroadcaster.isError(status)) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    private void updateUiState() {
        if (lastStatus == null) lastStatus = "";
        boolean isLoading = EngineStateBroadcaster.isLoading(lastStatus);
        boolean isTranscribing = EngineStateBroadcaster.isTranscribing(lastStatus);
        boolean isError = EngineStateBroadcaster.isError(lastStatus);
        boolean isReady = EngineStateBroadcaster.isReady(lastStatus);

        // Don't show internal loading states to the user as-is. When we
        // are loading, surface the raw engine status (e.g. "Initializing
        // fastest engine..." or the pre-fire "Switching model…") with the
        // "Status: " prefix MainActivity adds stripped away so the IME
        // text is identical to the engine message.
        String displayStatus = EngineStateBroadcaster.stripStatusPrefix(lastStatus);
        if (statusView != null && !isRecording) {
            if (isError) {
                statusView.setText(lastStatus);
            } else if (isLoading) {
                statusView.setText(displayStatus);
            } else if (isTranscribing) {
                statusView.setText(getString(R.string.ime_processing));
            } else {
                statusView.setText(getString(R.string.ime_tap_to_record));
            }
        }

        // Progress spinner is visible during any transitional state
        // (loading/init/switch/restart/wait) so the user has an at-a-glance
        // cue that the engine is warming up.
        if (progressBar != null) {
            progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        }

        // Disable the record button for ANY non-tappable state — including
        // loading — so the user can't queue audio samples while the engine
        // is mid-switch and produce a transcription that drops on the
        // floor after the model finishes (or worse, against a half-loaded
        // model).
        if (recordContainer != null) {
            boolean disable = isLoading || isTranscribing || isError;
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.5f : 1.0f);
        }

        if (hintView != null && !isRecording) {
            hintView.setText(getString(R.string.ime_tap_to_record));
        }
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            String lang = getResources().getConfiguration().locale.getLanguage();
            String filtered = WordCorrector.filterTranscriptionOutput(text, lang);
            String processed = settingsManager.applyDictionary(filtered);
            if (settingsManager.isPostProcessEnabled()) {
                if (statusView != null) statusView.setText(getString(R.string.status_refining));
                postProcessor.process(processed, new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        mainHandler.post(() -> finalizeTranscription(refinedText));
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Post-process error: " + error);
                        // Fallback to dictionary-processed text
                        mainHandler.post(() -> finalizeTranscription(processed));
                    }
                });
            } else {
                finalizeTranscription(processed);
            }
        });
    }

    private void finalizeTranscription(String text) {
        String committed = text + " ";
        InputConnection ic = getCurrentInputConnection();
        if (inputActive && ic != null) {
            commitTranscribedText(ic, committed);
        } else {
            // No editor is focused right now (common on long transcribes where
            // a web field in Firefox/Gemini dropped focus while we processed
            // audio). Committing now would be silently dropped, so defer the
            // text until a field is focused again instead of losing it.
            pendingCommitText = committed;
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        updateRecordButtonUI(false);
        if (statusView != null) statusView.setText(getString(R.string.ime_tap_to_record));
        if (pendingSwitchBack) {
            pendingSwitchBack = false;
            switchToPreviousInputMethod();
        }
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private void commitTranscribedText(InputConnection ic, String committed) {
        ic.commitText(committed, 1);

        if (!pendingSwitchBack && settingsManager.isSelectTranscription()) {
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (et != null) {
                int end = et.selectionStart;
                int start = end - committed.length();
                if (start >= 0) {
                    ic.setSelection(start, end);
                }
            }
        }
    }

    // Commits text that finished transcribing while no field was focused. Called
    // from onStartInputView when an editor (and a live input connection) is
    // available again.
    private void flushPendingText() {
        if (pendingCommitText == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitTranscribedText(ic, pendingCommitText);
            pendingCommitText = null;
        }
    }
    public void onAudioLevel(float level) { }

    private boolean isPauseAudioEnabled() {
        return settingsManager.isPauseAudio();
    }
}

