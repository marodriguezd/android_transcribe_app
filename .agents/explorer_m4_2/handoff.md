# Handoff Report — Native Rust JNI ↔ Java Integration Strategy for FloatingOverlayService

**Agent**: Explorer 2 (JNI & Lifecycle Integration Specialist)  
**Working Directory**: `/root/GitHub/android_transcribe_app/.agents/explorer_m4_2`  
**Target File to Implement in M4**: `app/src/main/java/dev/notune/transcribe/FloatingOverlayService.java`  
**Status**: Investigation Complete — Implementation Strategy Designed  

---

## 1. Observation

Direct code observations from inspecting the codebase:

1. **JNI C Function Declarations in `src/floating.rs`**:
   - File: `/root/GitHub/android_transcribe_app/src/floating.rs` (lines 11–62):
     ```rust
     #[no_mangle]
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_initNative(
         env: JNIEnv, _class: JClass, service: JObject,
     ) { ... }

     #[no_mangle]
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_cleanupNative(
         _env: JNIEnv, _class: JClass,
     ) { ... }

     #[no_mangle]
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_startRecording(
         env: JNIEnv, _class: JClass, auto_stop: jni::sys::jboolean, session_id: jni::sys::jint,
     ) { ... }

     #[no_mangle]
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_stopRecording(
         env: JNIEnv, _class: JClass,
     ) { ... }

     #[no_mangle]
     pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_cancelRecording(
         env: JNIEnv, _class: JClass,
     ) { ... }
     ```
   - Global native state: `FLOATING_STATE` is defined at `src/floating.rs:9` as `static FLOATING_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));`.
   - `initNative` calls `voice_session::init_session(env, service)` which creates a global JNI ref (`GlobalRef`) to the `service` Java object and spawns an engine loading background thread.

2. **JNI Callback Delivery Mechanisms in `src/jni_util.rs`**:
   - File: `/root/GitHub/android_transcribe_app/src/jni_util.rs` (lines 24–120):
     - `notify_status_with_session(env, obj, msg, session_id)` calls Java method `onStatusUpdate(String, int)` signature `(Ljava/lang/String;I)V`.
     - `notify_status(env, obj, msg)` calls Java method `onStatusUpdate(String)` signature `(Ljava/lang/String;)V`.
     - `notify_level_with_session(env, obj, level, session_id)` calls Java method `onAudioLevel(float, int)` signature `(FI)V`.
     - `notify_partial_with_session(env, obj, text, session_id)` calls Java method `onPartialText(String, int)` signature `(Ljava/lang/String;I)V`.
     - `notify_text_with_session(env, obj, text, session_id)` calls Java method `onTextTranscribed(String, int)` signature `(Ljava/lang/String;I)V`.
     - `notify_auto_stop_with_session(env, obj, session_id)` calls Java method `onAutoStop(int)` signature `(I)V`.

3. **Existing Surface Implementations (`RecognizeActivity.java` & `RustInputMethodService.java`)**:
   - `RecognizeActivity.java` (lines 25–32, 147–249, 277–281) and `RustInputMethodService.java` (lines 39–46, 551–556, 574–767):
     - Both load native libraries in `static {}` block:
       ```java
       static {
           try {
               System.loadLibrary("c++_shared");
               System.loadLibrary("android_transcribe_app");
           } catch (UnsatisfiedLinkError e) {
               Log.e(TAG, "Failed to load native libraries", e);
           }
       }
       ```
     - Both maintain `private int currentSessionId = 0;` and increment it (`++currentSessionId`) when starting a new recording or cancelling.
     - Both check `if (sessionId != currentSessionId) return;` on the main UI thread inside every callback.
     - `RustInputMethodService` uses `new Handler(Looper.getMainLooper())` to post all callbacks to the main thread before touching UI views.

4. **AI Post-Processing & Marker Integration**:
   - `SettingsManager.java` (lines 48, 115–125):
     - `isPostProcessEnabled()` checks presence of marker file `pp_enabled` in `context.getFilesDir()`.
     - `setPostProcessEnabled(boolean)` creates or deletes `pp_enabled` via `MarkerFileHelper`.
   - `PostProcessor.java` (lines 78–119, 210–389):
     - Initialized with `new PostProcessor(settings, mainHandler, validator, owner)`.
     - `owner` is passed as `this` (`FloatingOverlayService`). `PostProcessor.cancelAllFor(this)` cancels in-flight OkHttp calls owned by this service without affecting other surfaces.
     - `validator` (`() -> processingSessionId == currentSessionId && !isDestroyed`) ensures late HTTP callbacks are ignored if the service is destroyed or the session changed.
     - On network/parsing failure, `onError(String)` returns, allowing caller to deliver the raw transcript as fallback.

5. **Accessibility Service Auto-Paste Helper**:
   - `FloatingDictationAccessibilityService.java` (lines 108–114):
     - Public static method `FloatingDictationAccessibilityService.pasteText(Context context, CharSequence text)` returns `boolean`.
     - Priority: `ACTION_PASTE` via ClipboardManager -> `ACTION_SET_TEXT` -> Fallback clipboard write + Toast notification.

---

## 2. Logic Chain

1. **Step 1 — Mapping Native Signatures to Java Declarations**:
   - To match `src/floating.rs`, `FloatingOverlayService.java` must declare private native methods with exact identifier and signature matching:
     ```java
     private native void initNative(FloatingOverlayService service);
     private native void cleanupNative();
     private native void startRecording(boolean autoStop, int sessionId);
     private native void stopRecording();
     private native void cancelRecording();
     ```
   - When `initNative(this)` is called during `onCreate()`, Rust receives the Java reference to `FloatingOverlayService` and stores it in `FLOATING_STATE`.

2. **Step 2 — Required Public Java Callbacks on `FloatingOverlayService`**:
   - Because Rust calls Java methods via JNI method reflection (`env.call_method`), `FloatingOverlayService.java` MUST expose public implementations for all expected callback signatures:
     1. `public void onStatusUpdate(String status, int sessionId)`
     2. `public void onStatusUpdate(String status)`
     3. `public void onAudioLevel(float level, int sessionId)`
     4. `public void onAudioLevel(float level)`
     5. `public void onPartialText(String text, int sessionId)`
     6. `public void onTextTranscribed(String text, int sessionId)`
     7. `public void onAutoStop(int sessionId)`

3. **Step 3 — Thread-Safety & Main Thread UI Dispatching**:
   - Rust invokes JNI callbacks from background threads (`cpal` audio loop, engine worker thread, auto-stop monitor thread).
   - In Android, modifying `WindowManager` views (such as floating bubble icons, status text, mic level indicators, or streaming partial text views) from a background thread throws a `CalledFromWrongThreadException`.
   - Solution: `FloatingOverlayService` creates `private final Handler mainHandler = new Handler(Looper.getMainLooper());`. Every JNI callback immediately dispatches its work to the main thread via `mainHandler.post(() -> { ... })`.

4. **Step 4 — Session ID Generation & Stale Callback Filtering**:
   - Rapid user interactions (e.g. tapping cancel and immediately starting a new recording) can cause trailing callbacks from the old recording thread to arrive after the new recording starts.
   - Solution:
     - `FloatingOverlayService` maintains `private int currentSessionId = 0;`.
     - When starting recording: `startRecording(isAutoStopEnabled(), ++currentSessionId);`.
     - When cancelling or stopping: `currentSessionId++; try { cancelRecording(); } catch (Throwable ignored) {}`.
     - Inside every main-thread callback runnable:
       `if (sessionId != currentSessionId) return;`
     - This guarantees that callbacks from outdated sessions are discarded instantly without mutating UI state.

5. **Step 5 — Post-Processing Flow & Auto-Paste Integration**:
   - When transcription finishes, Rust calls `onTextTranscribed(rawText, sessionId)`.
   - On the main thread, after validating `sessionId == currentSessionId`:
     - If `rawText` is empty, reset UI state and return.
     - Check `SettingsManager settings = new SettingsManager(this);`.
     - If `settings.isPostProcessEnabled()`:
       - Update UI status to `R.string.ime_refining` ("Refinando con IA...").
       - Capture `final int processingSessionId = sessionId;`.
       - Execute `PostProcessor`:
         ```java
         new PostProcessor(settings, mainHandler,
                 () -> processingSessionId == currentSessionId && !isDestroyed,
                 this)
                 .process(rawText, new PostProcessor.PostProcessCallback() {
                     @Override
                     public void onSuccess(String refinedText) {
                         if (processingSessionId != currentSessionId) return;
                         String out = (refinedText != null && !refinedText.trim().isEmpty())
                                 ? refinedText : rawText;
                         commitAndPasteResult(out);
                     }

                     @Override
                     public void onError(String error) {
                         if (processingSessionId != currentSessionId) return;
                         Log.w(TAG, "Post-processing failed, delivering raw text: " + error);
                         commitAndPasteResult(rawText);
                     }
                 });
         ```
     - If `!settings.isPostProcessEnabled()`:
       - Directly call `commitAndPasteResult(rawText);`.
   - `commitAndPasteResult(String text)` calls:
     `FloatingDictationAccessibilityService.pasteText(getApplicationContext(), text);`
     and resets overlay panel state to collapsed bubble.

6. **Step 6 — AI Fix Toggle Switch in Overlay UI**:
   - The expanded panel UI includes an AI Fix toggle (`MaterialSwitch` / `CompoundButton`).
   - `ppToggle.setChecked(SettingsManager.isPostProcessEnabled(this));`
   - `ppToggle.setOnCheckedChangeListener((btn, isChecked) -> { SettingsManager.setPostProcessEnabled(this, isChecked); if (!isChecked) PostProcessor.cancelAllFor(this); });`
   - Dynamically creates or deletes `pp_enabled` marker file in `getFilesDir()`.

---

## 3. Caveats

1. **Microphone Permission (`RECORD_AUDIO`)**:
   - Service must check `checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PERMISSION_GRANTED` before calling `startRecording()`. If missing, UI status must display `R.string.rec_mic_permission_required` and prompt user to open app settings.
2. **Audio Focus Pausing**:
   - To prevent system audio (music, podcasts) from bleeding into dictation, `FloatingOverlayService` should utilize `AudioFocusPauser` during recording: `audioPauser.request(this);` on start and `audioPauser.abandon(this);` on stop/cancel.
3. **Service Teardown Lifecycle (`onDestroy`)**:
   - In `onDestroy()`, `currentSessionId++` must be executed first to invalidate pending runnables, followed by `cancelRecording()`, `PostProcessor.cancelAllFor(this)`, `cleanupNative()`, and removing overlay views from `WindowManager`.
4. **Accessibility Service Absence / Unbound State**:
   - If `FloatingDictationAccessibilityService` is not enabled by user in Android Accessibility Settings, `pasteText()` automatically falls back to copying text to `ClipboardManager` and displaying a Toast notification (`Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()`).

---

## 4. Conclusion

The complete JNI & lifecycle integration contract for `FloatingOverlayService.java` is fully specified:

### Java Class Structural Contract (`FloatingOverlayService.java`)
```java
package dev.notune.transcribe;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

public class FloatingOverlayService extends Service {
    private static final String TAG = "FloatingOverlay";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private Handler mainHandler;
    private int currentSessionId = 0;
    private boolean isRecording = false;
    private boolean isDestroyed = false;

    // WindowManager UI elements (bubble & expanded panel)
    private WindowManager windowManager;
    // ... UI view handles ...

    // Native Method Declarations matching src/floating.rs
    private native void initNative(FloatingOverlayService service);
    private native void cleanupNative();
    private native void startRecording(boolean autoStop, int sessionId);
    private native void stopRecording();
    private native void cancelRecording();

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        try {
            initNative(this);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initNative", t);
        }
        // Initialize WindowManager & Views...
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        currentSessionId++;
        try { cancelRecording(); } catch (Throwable ignored) {}
        PostProcessor.cancelAllFor(this);
        try { cleanupNative(); } catch (Throwable ignored) {}
        // Remove WindowManager views...
        super.onDestroy();
    }

    // --- JNI Callbacks from Rust ---

    public void onAutoStop(int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            if (isRecording) {
                stopRecordingAction();
            }
        });
    }

    public void onStatusUpdate(String status, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            updateStatusUI(status);
        });
    }

    public void onStatusUpdate(String status) {
        mainHandler.post(() -> updateStatusUI(status));
    }

    public void onAudioLevel(float level, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            updateMicLevelUI(level);
        });
    }

    public void onAudioLevel(float level) {
        onAudioLevel(level, currentSessionId);
    }

    public void onPartialText(String text, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            if (isRecording && text != null) {
                updatePartialTextUI(text);
            }
        });
    }

    public void onTextTranscribed(String text, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            if (text == null || text.trim().isEmpty()) {
                resetOverlayState();
                return;
            }
            processAndCommitText(text, sessionId);
        });
    }

    private void processAndCommitText(String rawText, int sessionId) {
        SettingsManager settings = new SettingsManager(this);
        if (settings.isPostProcessEnabled()) {
            updateStatusUI(getString(R.string.ime_refining));
            final int processingSessionId = sessionId;
            new PostProcessor(settings, mainHandler,
                    () -> processingSessionId == currentSessionId && !isDestroyed,
                    this)
                    .process(rawText, new PostProcessor.PostProcessCallback() {
                        @Override
                        public void onSuccess(String refinedText) {
                            if (processingSessionId != currentSessionId) return;
                            String out = (refinedText != null && !refinedText.trim().isEmpty())
                                    ? refinedText : rawText;
                            deliverResult(out);
                        }

                        @Override
                        public void onError(String error) {
                            if (processingSessionId != currentSessionId) return;
                            Log.w(TAG, "Post-processing failed, using raw ASR text: " + error);
                            deliverResult(rawText);
                        }
                    });
        } else {
            deliverResult(rawText);
        }
    }

    private void deliverResult(String text) {
        FloatingDictationAccessibilityService.pasteText(getApplicationContext(), text);
        resetOverlayState();
    }

    private boolean isAutoStopEnabled() {
        return new java.io.File(getFilesDir(), "auto_stop").exists();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
```

---

## 5. Verification Method

To verify the integration strategy:

1. **JNI Signature Alignment**:
   - Verify that all native functions in `src/floating.rs` (`Java_dev_notune_transcribe_FloatingOverlayService_*`) map 1-to-1 with Java native declarations.
2. **JNI Callback Verification**:
   - Confirm public visibility of all 7 callback methods (`onStatusUpdate`, `onAudioLevel`, `onPartialText`, `onTextTranscribed`, `onAutoStop`) on `FloatingOverlayService.java`.
3. **Session Invalidation Verification**:
   - Verify `currentSessionId` incrementing on `startRecording` / `cancelRecording` and matching `if (sessionId != currentSessionId) return;` check in every callback handler.
4. **PostProcessor & Marker Verification**:
   - Confirm `isPostProcessEnabled()` marker check, `PostProcessor.cancelAllFor(this)` owner cancellation, and fallback delivery of `rawText` when network errors occur.
5. **Auto-Paste Delivery Verification**:
   - Confirm `FloatingDictationAccessibilityService.pasteText(context, text)` call upon completion.
