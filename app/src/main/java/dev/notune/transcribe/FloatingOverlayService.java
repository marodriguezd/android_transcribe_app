package dev.notune.transcribe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
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
 *
 * Crash-hardening (2026-08-12): the service must NEVER crash on startup. A crash here used to
 * produce a restart loop ("crash constantes"): with START_STICKY the system restarts a service
 * that crashed in onCreate, which crashed again. The two classic startup failures are
 * (a) startForeground() with the manifest's "microphone" FGS type while RECORD_AUDIO is not
 * granted (SecurityException), and (b) WindowManager.addView() while the overlay permission is
 * missing/revoked (BadTokenException/SecurityException). Both are now checked up-front and every
 * fallible setup step is wrapped, degrading to a graceful stopSelf() + guidance notification
 * instead of an uncaught exception.
 */
public class FloatingOverlayService extends Service {

    private static final String TAG = "FloatingOverlayService";
    private static final String CHANNEL_ID = "floating_dictation_channel";
    private static final int NOTIFICATION_ID = 404;
    private static final int SETUP_ERROR_NOTIFICATION_ID = 405;

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
    private TextView mHintText;

    // Bubble position (window offset) kept while the panel is expanded, so the
    // bubble returns exactly where the user left it after collapsing.
    private int mBubbleX = 20;
    private int mBubbleY = 200;

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

        // The manifest declares foregroundServiceType="microphone", so
        // startForeground() REQUIRES RECORD_AUDIO on API 31+. Without the
        // check below, startForeground() throws SecurityException, onCreate
        // crashes, and START_STICKY restarts the service into a crash loop.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; refusing to start floating service");
            notifySetupError(getString(R.string.floating_need_mic_body));
            stopSelf();
            return;
        }

        // addView() on a TYPE_APPLICATION_OVERLAY window throws
        // BadTokenException/SecurityException without the overlay permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; refusing to start floating service");
            notifySetupError(getString(R.string.floating_overlay_permission_msg));
            stopSelf();
            return;
        }

        try {
            initNative(this);
        } catch (Throwable t) {
            // A dead native bridge means every later recording call would throw
            // UnsatisfiedLinkError mid-use. Fail fast and gracefully instead of
            // running a service that can only crash later.
            Log.e(TAG, "Error in initNative", t);
            notifySetupError(getString(R.string.floating_start_failed_body));
            stopSelf();
            return;
        }

        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            // SecurityException (FGS type enforcement), MissingForegroundServiceTypeException,
            // or any other unexpected failure must never crash the process into a restart loop.
            Log.e(TAG, "Failed to start foreground service", t);
            notifySetupError(getString(R.string.floating_start_failed_body));
            stopSelf();
            return;
        }

        try {
            setupOverlayView();
        } catch (Throwable t) {
            // WindowManager.addView() failing (e.g. permission revoked between the check and
            // the add) is a setup failure, not a crash.
            Log.e(TAG, "Failed to attach overlay view", t);
            notifySetupError(getString(R.string.floating_start_failed_body));
            stopSelf();
        }
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

    /**
     * Shows a non-foreground guidance notification when the service cannot start
     * (missing permission, overlay attach failure). Tapping it reopens the main
     * activity so the user can grant what is missing. Never throws.
     */
    private void notifySetupError(String body) {
        try {
            createNotificationChannel();
            Intent contentIntent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    0,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.floating_service_name))
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_mic)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(SETUP_ERROR_NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to show setup error notification", t);
        }
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
        mHintText = mOverlayView.findViewById(R.id.floating_hint);

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
                // The service may be torn down while a drag gesture is in flight;
                // never touch the window manager after destruction.
                if (mIsDestroyed) {
                    return true;
                }
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
                        // Keep the bubble position in sync for the panel expand/collapse
                        // window resize (dragging only happens on the bubble itself).
                        mBubbleX = mParams.x;
                        mBubbleY = mParams.y;
                        if (mWindowManager != null && mOverlayView != null) {
                            try {
                                mWindowManager.updateViewLayout(mOverlayView, mParams);
                            } catch (Throwable t) {
                                // View may already be detached (service stopping); ignore.
                                Log.w(TAG, "updateViewLayout failed during drag", t);
                            }
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
                new SettingsManager(FloatingOverlayService.this).setPostProcessEnabled(isChecked);
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
        // Full-width panel, IME style: stretch the window to near-screen width
        // and anchor it below the status bar, centered horizontally.
        resizeWindow(true);
    }

    private void collapsePanel() {
        mPanelRoot.setVisibility(View.GONE);
        mBubbleRoot.setVisibility(View.VISIBLE);
        // Back to the small draggable bubble at the saved position.
        resizeWindow(false);
    }

    /**
     * Switches the overlay window between the small bubble (wrap content at the
     * dragged position) and the full-width dictation panel (near-screen width,
     * top-anchored). Only the width/gravity/offset change; height stays wrap.
     */
    private void resizeWindow(boolean expanded) {
        if (mWindowManager == null || mOverlayView == null || mParams == null) {
            return;
        }
        try {
            if (expanded) {
                float density = getResources().getDisplayMetrics().density;
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int margin = (int) (16 * density);
                int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
                int statusBar = resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
                mParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                mParams.width = screenWidth - margin * 2;
                mParams.x = 0;
                mParams.y = statusBar + (int) (12 * density);
            } else {
                mParams.gravity = Gravity.TOP | Gravity.START;
                mParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
                mParams.x = mBubbleX;
                mParams.y = mBubbleY;
            }
            mWindowManager.updateViewLayout(mOverlayView, mParams);
        } catch (Throwable t) {
            // Never crash on a cosmetic resize failure; the panel still shows
            // with its previous size.
            Log.w(TAG, "resizeWindow failed", t);
        }
    }

    private void startRecordingSession() {
        mIsRecording = true;
        mResultPending = false;
        mLastRawTranscript = null;
        mTranscribedResult = null;
        mInsertButton.setVisibility(View.GONE);
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mHintText != null) mHintText.setText(R.string.ime_tap_to_stop);
        mStatusText.setText(getString(R.string.floating_status_listening));
        mCancelButton.setVisibility(View.VISIBLE);
        startRecording(new File(getFilesDir(), "auto_stop").exists(), ++mCurrentSessionId);
    }

    private void stopRecordingSession() {
        mIsRecording = false;
        mResultPending = true;
        mStatusText.setText(getString(R.string.floating_status_transcribing));
        if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
        if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
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
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
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
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
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
            try {
                // removeView() on a detached view throws IllegalArgumentException
                // (e.g. addView() never succeeded in a failed setup); the
                // isAttachedToWindow() check + try/catch keeps teardown crash-free.
                if (mOverlayView.isAttachedToWindow()) {
                    mWindowManager.removeView(mOverlayView);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to remove overlay view on destroy", t);
            }
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
                if (mProgress != null) mProgress.setVisibility(View.GONE);
                if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
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
                if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
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
