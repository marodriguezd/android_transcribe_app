package dev.notune.transcribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;

import androidx.core.app.NotificationCompat;

import java.io.File;

/**
 * Foreground service managing the floating dictation bubble overlay (Whisperflow style).
 * Displays a draggable floating bubble icon on screen, which expands into a dictation control
 * panel for speech recognition, AI Fix post-processing toggle, real-time partial hypothesis display,
 * and direct insertion into active text fields via Accessibility.
 */
public class FloatingOverlayService extends Service {

    private static final String TAG = "FloatingOverlayService";
    private static final String CHANNEL_ID = "floating_dictation_channel";
    private static final int NOTIFICATION_ID = 404;

    public static final String ACTION_START = "dev.notune.transcribe.action.START_FLOATING";
    public static final String ACTION_STOP = "dev.notune.transcribe.action.STOP_FLOATING";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private WindowManager mWindowManager;
    private View mOverlayView;
    private WindowManager.LayoutParams mParams;

    private View mBubbleRoot;
    private MicLevelView mBubbleMicLevel;
    private View mPanelRoot;

    private TextView mStatusText;
    private ProgressBar mProgress;
    private MaterialSwitch mPpToggle;
    private Button mCancelButton;
    private ImageView mCloseButton;
    private ScrollView mPartialScroll;
    private TextView mPartialText;

    private View mRecordContainer;
    private MicLevelView mMicLevelView;
    private Button mInsertButton;

    private Handler mMainHandler;
    private boolean mIsRecording = false;
    private boolean mResultPending = false;
    private int mCurrentSessionId = 0;
    private String mLastStatus = "Ready";
    private String mLastRawTranscript = null;
    private String mTranscribedResult = null;
    private boolean mIsDestroyed = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(Looper.getMainLooper());
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            initNative(this);
        } catch (Throwable t) {
            Log.e(TAG, "Error in initNative", t);
        }
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        setupOverlayView();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.floating_service_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.floating_service_description));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.floating_service_name))
                .setContentText(getString(R.string.floating_service_description))
                .setSmallIcon(R.drawable.ic_mic)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void setupOverlayView() {
        Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
        Context themed = new android.view.ContextThemeWrapper(night, R.style.AppTheme);
        mOverlayView = LayoutInflater.from(themed).inflate(R.layout.overlay_floating_dictation, null);

        mBubbleRoot = mOverlayView.findViewById(R.id.floating_bubble_root);
        mBubbleMicLevel = mOverlayView.findViewById(R.id.floating_bubble_mic_level);
        mPanelRoot = mOverlayView.findViewById(R.id.floating_panel_root);

        mStatusText = mOverlayView.findViewById(R.id.floating_status_text);
        mProgress = mOverlayView.findViewById(R.id.floating_progress);
        mPpToggle = mOverlayView.findViewById(R.id.floating_pp_toggle);
        mCancelButton = mOverlayView.findViewById(R.id.floating_cancel_button);
        mCloseButton = mOverlayView.findViewById(R.id.floating_close_button);
        mPartialScroll = mOverlayView.findViewById(R.id.floating_partial_scroll);
        mPartialText = mOverlayView.findViewById(R.id.floating_partial_text);

        mRecordContainer = mOverlayView.findViewById(R.id.floating_record_container);
        mMicLevelView = mOverlayView.findViewById(R.id.floating_mic_level);
        mInsertButton = mOverlayView.findViewById(R.id.floating_insert_button);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.x = 20;
        mParams.y = 200;

        setupGestureAndListeners();
        mWindowManager.addView(mOverlayView, mParams);
    }

    private void setupGestureAndListeners() {
        // Drag bubble vs tap to toggle panel
        mBubbleRoot.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mParams.x;
                        initialY = mParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false;
                        }
                        mParams.x = initialX + dx;
                        mParams.y = initialY + dy;
                        if (mWindowManager != null && mOverlayView != null) {
                            mWindowManager.updateViewLayout(mOverlayView, mParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            togglePanel();
                        }
                        return true;
                }
                return false;
            }
        });

        mCloseButton.setOnClickListener(v -> collapsePanel());

        if (mPpToggle != null) {
            mPpToggle.setChecked(SettingsManager.isPostProcessEnabled(this));
            mPpToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SettingsManager.setPostProcessEnabled(FloatingOverlayService.this, isChecked);
                if (!isChecked) {
                    if (!mIsRecording && mStatusText != null
                            && getString(R.string.ime_refining).equals(mStatusText.getText().toString())) {
                        PostProcessor.cancelAllFor(FloatingOverlayService.this);
                        if (mLastRawTranscript != null && !mLastRawTranscript.trim().isEmpty()) {
                            deliverFinalText(mLastRawTranscript);
                        }
                    }
                }
            });
        }

        mCancelButton.setOnClickListener(v -> cancelCurrentTranscription());

        mRecordContainer.setOnClickListener(v -> {
            if (mIsRecording) {
                stopRecordingSession();
            } else {
                startRecordingSession();
            }
        });

        mInsertButton.setOnClickListener(v -> {
            if (mTranscribedResult != null && !mTranscribedResult.trim().isEmpty()) {
                FloatingDictationAccessibilityService.pasteText(this, mTranscribedResult);
                mTranscribedResult = null;
                mInsertButton.setVisibility(View.GONE);
                collapsePanel();
            }
        });
    }

    private void togglePanel() {
        if (mPanelRoot.getVisibility() == View.VISIBLE) {
            collapsePanel();
        } else {
            expandPanel();
        }
    }

    private void expandPanel() {
        mBubbleRoot.setVisibility(View.GONE);
        mPanelRoot.setVisibility(View.VISIBLE);
        if (mPpToggle != null) {
            mPpToggle.setChecked(SettingsManager.isPostProcessEnabled(this));
        }
    }

    private void collapsePanel() {
        mPanelRoot.setVisibility(View.GONE);
        mBubbleRoot.setVisibility(View.VISIBLE);
    }

    private void startRecordingSession() {
        mIsRecording = true;
        mResultPending = false;
        mLastRawTranscript = null;
        mTranscribedResult = null;
        mInsertButton.setVisibility(View.GONE);
        mStatusText.setText(getString(R.string.floating_status_listening));
        mCancelButton.setVisibility(View.VISIBLE);
        startRecording(new File(getFilesDir(), "auto_stop").exists(), ++mCurrentSessionId);
    }

    private void stopRecordingSession() {
        mIsRecording = false;
        mResultPending = true;
        mStatusText.setText(getString(R.string.floating_status_transcribing));
        stopRecording();
    }

    private void cancelCurrentTranscription() {
        mCurrentSessionId++;
        mIsRecording = false;
        mResultPending = false;
        mLastRawTranscript = null;
        mTranscribedResult = null;
        try { cancelRecording(); } catch (Throwable ignored) { }
        PostProcessor.cancelAllFor(this);
        mStatusText.setText(getString(R.string.floating_status_ready));
        mCancelButton.setVisibility(View.GONE);
        mInsertButton.setVisibility(View.GONE);
        clearPartialText();
    }

    private void clearPartialText() {
        if (mPartialText != null) mPartialText.setText("");
        if (mPartialScroll != null) mPartialScroll.setVisibility(View.GONE);
    }

    private void deliverFinalText(String text) {
        mResultPending = false;
        mLastRawTranscript = null;
        mTranscribedResult = text;
        mStatusText.setText(getString(R.string.floating_status_ready));
        mCancelButton.setVisibility(View.GONE);
        clearPartialText();

        // Attempt direct insertion via AccessibilityService first
        boolean pasted = FloatingDictationAccessibilityService.pasteText(this, text);
        if (!pasted) {
            // Accessibility not enabled or insertion unavailable: show Insert button
            mInsertButton.setVisibility(View.VISIBLE);
        } else {
            mInsertButton.setVisibility(View.GONE);
            collapsePanel();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mIsDestroyed = true;
        mCurrentSessionId++;
        try { cancelRecording(); } catch (Throwable ignored) { }
        try { cleanupNative(); } catch (Throwable ignored) { }
        PostProcessor.cancelAllFor(this);
        if (mOverlayView != null && mWindowManager != null) {
            mWindowManager.removeView(mOverlayView);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Native JNI methods
    private native void initNative(FloatingOverlayService service);
    private native void cleanupNative();
    private native void startRecording(boolean autoStop, int sessionId);
    private native void stopRecording();
    private native void cancelRecording();

    // Callbacks from Rust
    public void onAutoStop(int sessionId) {
        mMainHandler.post(() -> {
            if (sessionId != mCurrentSessionId) return;
            if (mIsRecording) {
                stopRecordingSession();
            }
        });
    }

    public void onStatusUpdate(String status) {
        onStatusUpdate(status, mCurrentSessionId);
    }

    public void onStatusUpdate(String status, int sessionId) {
        mMainHandler.post(() -> {
            if (sessionId != mCurrentSessionId) return;
            mLastStatus = status != null ? status : "";
            if (mLastStatus.startsWith("Error")) {
                mResultPending = false;
                if (mStatusText != null) mStatusText.setText(mLastStatus);
            }
        });
    }

    public void onAudioLevel(float level, int sessionId) {
        if (sessionId != mCurrentSessionId) return;
        mMainHandler.post(() -> {
            if (sessionId == mCurrentSessionId) {
                if (mBubbleMicLevel != null) mBubbleMicLevel.setLevel(level);
                if (mMicLevelView != null) mMicLevelView.setLevel(level);
            }
        });
    }

    public void onPartialText(String text, int sessionId) {
        mMainHandler.post(() -> {
            if (sessionId != mCurrentSessionId) return;
            if (mIsRecording && mPartialText != null && mPartialScroll != null
                    && text != null && !text.trim().isEmpty()) {
                mPartialText.setText(text);
                mPartialScroll.setVisibility(View.VISIBLE);
                mPartialScroll.post(() -> mPartialScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    public void onTextTranscribed(String text, int sessionId) {
        mMainHandler.post(() -> {
            if (sessionId != mCurrentSessionId) return;
            if (text == null || text.trim().isEmpty()) {
                mResultPending = false;
                mLastRawTranscript = null;
                if (mStatusText != null) mStatusText.setText(getString(R.string.floating_status_ready));
                return;
            }

            mLastRawTranscript = text;
            SettingsManager settings = new SettingsManager(this);
            if (settings.isPostProcessEnabled()) {
                mLastStatus = "Processing...";
                if (mStatusText != null) mStatusText.setText(getString(R.string.ime_refining));
                if (mCancelButton != null) mCancelButton.setVisibility(View.VISIBLE);

                final int processingSessionId = sessionId;
                new PostProcessor(settings, mMainHandler,
                        () -> processingSessionId == mCurrentSessionId && !mIsDestroyed,
                        this)
                        .process(text, new PostProcessor.PostProcessCallback() {
                            @Override
                            public void onSuccess(String refinedText) {
                                if (processingSessionId != mCurrentSessionId) return;
                                String result = refinedText != null && !refinedText.trim().isEmpty()
                                        ? refinedText : text;
                                deliverFinalText(result);
                            }

                            @Override
                            public void onError(String error) {
                                if (processingSessionId != mCurrentSessionId) return;
                                deliverFinalText(text);
                            }
                        });
            } else {
                deliverFinalText(text);
            }
        });
    }
}
