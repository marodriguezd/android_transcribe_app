# Handoff Report — Explorer Survey 1: JNI & Audio Session Architecture

## 1. Observation

Direct code analysis of `android_transcribe_app` native Rust & Java surfaces revealed the following facts:

### A. Java Native Library Loading & Surface Declarations
- **Library Loading**: All native-backed surfaces load native libraries in a `static` initializer block (e.g. `RecognizeActivity.java:25-32`, `RustInputMethodService.java:39-46`, `VoiceRecognitionService.java:36-43`, `LiveSubtitleService.java:44-51`):
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
- **Native Method Declarations**:
  - `RecognizeActivity.java:276-281` & `RustInputMethodService.java:551-556`:
    ```java
    private native void initNative(RecognizeActivity activity); // or RustInputMethodService
    private native void cleanupNative();
    private native void startRecording(boolean autoStop, int sessionId);
    private native void stopRecording();
    private native void cancelRecording();
    ```
  - `VoiceRecognitionService.java:258-262`:
    ```java
    private native void initNative(VoiceRecognitionService service);
    private native void startListening(VoiceRecognitionService service, int sessionId);
    private native void stopListening();
    private native void cancelNative();
    private native void destroyNative();
    ```
  - `LiveSubtitleService.java:720-723`:
    ```java
    private native void initNative(LiveSubtitleService service);
    private native void cleanupNative();
    private native void pushAudio(float[] data, int length);
    ```

### B. Standardized JNI Export Symbol Format & Helpers
- Native C-ABI symbols are exported using standard JNI naming conventions (`src/recognize.rs:11-62`, `src/ime.rs:10-61`):
  - `Java_dev_notune_transcribe_RecognizeActivity_initNative`
  - `Java_dev_notune_transcribe_RecognizeActivity_cleanupNative`
  - `Java_dev_notune_transcribe_RecognizeActivity_startRecording`
  - `Java_dev_notune_transcribe_RecognizeActivity_stopRecording`
  - `Java_dev_notune_transcribe_RecognizeActivity_cancelRecording`
- **JNI Callback Helpers** (`src/jni_util.rs:1-121`):
  - `notify_status(env, obj, msg)` -> `onStatusUpdate(String)` (`(Ljava/lang/String;)V`)
  - `notify_status_with_session(env, obj, msg, sessionId)` -> `onStatusUpdate(String, int)` (`(Ljava/lang/String;I)V`)
  - `notify_text_with_session(env, obj, text, sessionId)` -> `onTextTranscribed(String, int)` (`(Ljava/lang/String;I)V`)
  - `notify_partial_with_session(env, obj, text, sessionId)` -> `onPartialText(String, int)` (`(Ljava/lang/String;I)V`)
  - `notify_level_with_session(env, obj, level, sessionId)` -> `onAudioLevel(float, int)` (`(FI)V`)
  - `notify_auto_stop_with_session(env, obj, sessionId)` -> `onAutoStop(int)` (`(I)V`)

### C. Session Management & Auto-Stop Endpointing (`src/voice_session.rs`)
- `VoiceSessionState` (`src/voice_session.rs:43-66`) encapsulates:
  - `stream: Option<SendStream>` (`cpal::Stream` wrapper)
  - `audio_buffer: Arc<Mutex<Vec<f32>>>` (16 kHz mono float PCM)
  - `jvm: Arc<jni::JavaVM>` & `target_ref: GlobalRef` (attached Java surface instance)
  - `session_id: i32` (generation token from Java)
  - `session_active: Arc<AtomicBool>` & `session_cancelled: Arc<AtomicBool>`
  - `stream_cmd_tx` & `streaming_active` (for real-time streaming models like Nemotron)
- **Voice Capture & Audio Level**: `start_recording` (`src/voice_session.rs:111-354`) uses `cpal` to open microphone input, computes RMS volume every 50ms, and calls `onAudioLevel(level, sessionId)` on Java.
- **Auto-Stop Monitor**: Silence endpointing (2 seconds trailing silence after speech, `src/voice_session.rs:17`) or hard cap timeout (5 minutes, `src/voice_session.rs:28`) triggers `notify_auto_stop_with_session(env, target_ref, session_id)`, calling Java `onAutoStop(int sessionId)`.
- **Transcription Completion**: `stop_recording` (`src/voice_session.rs:497-593`) stops audio stream, runs `engine::transcribe_shared`, updates status to `"Ready"`, and fires `onTextTranscribed(text, sessionId)`.
- **Cancellation**: `cancel_recording` (`src/voice_session.rs:595-617`) sets `session_cancelled = true`, drops the stream, clears the buffer, and signals `StreamCmd::Cancel`.

### D. Process Isolation
- Manifest specifies `android:process=":ime"` for `RustInputMethodService`.
- Main process runs `RecognizeActivity`, `VoiceRecognitionService`, `LiveSubtitleService`, `MainActivity`.
- Each process maintains its own Rust static state singleton (`RECOG_STATE` vs `IME_STATE`).

---

## 2. Logic Chain

1. **Reusability of `voice_session.rs`**:
   - Both `RecognizeActivity` and `RustInputMethodService` use identical native mechanics by delegating JNI calls directly to `voice_session::{init_session, start_recording, stop_recording, cancel_recording}`.
   - `FloatingOverlayService` is also a Java surface running in the main process requiring microphone capture, live RMS levels (`MicLevelView`), streaming partial hypotheses (`onPartialText`), silence auto-stop (`onAutoStop`), and final transcription delivery (`onTextTranscribed`).
   - Therefore, `FloatingOverlayService` can reuse `voice_session.rs` without any modifications to audio engine core logic.

2. **Creation of `src/floating.rs`**:
   - To bind `FloatingOverlayService` to `voice_session.rs`, a new thin JNI bridge module `src/floating.rs` is required (parallel to `src/recognize.rs` and `src/ime.rs`).
   - `src/floating.rs` will maintain a static singleton `FLOATING_STATE: Lazy<Mutex<Option<VoiceSessionState>>>` and export the JNI functions targeting `dev.notune.transcribe.FloatingOverlayService`.
   - `src/lib.rs` must declare `pub mod floating;`.

3. **Java Callback Contract for `FloatingOverlayService`**:
   - `FloatingOverlayService` must implement:
     - `public void onStatusUpdate(String status, int sessionId)`
     - `public void onStatusUpdate(String status)`
     - `public void onAudioLevel(float level, int sessionId)`
     - `public void onPartialText(String text, int sessionId)`
     - `public void onTextTranscribed(String text, int sessionId)`
     - `public void onAutoStop(int sessionId)`
   - When `onTextTranscribed` fires, `FloatingOverlayService` will inspect `SettingsManager.isPostProcessEnabled()`. If true, it calls `PostProcessor.process(text, callback)` for AI refinement before updating the overlay UI and performing auto-paste via `FloatingDictationAccessibilityService`.

4. **Session Invalidation & Thread Safety**:
   - `FloatingOverlayService` must maintain `private int currentSessionId = 0;` incremented on `startRecording`, `stopRecording`, `cancelRecording`, and service destruction.
   - All Java callbacks posted to `mainHandler` must check `if (sessionId != currentSessionId) return;` to avoid race conditions and stale transcriptions.

---

## 3. Caveats

- **Audio Focus**: Like `RecognizeActivity` and `RustInputMethodService`, `FloatingOverlayService` should use `AudioFocusPauser` when the `pause_audio` marker file exists to pause background audio playback during capture.
- **Permissions**: `RECORD_AUDIO` must be granted before calling `startRecording`. `SYSTEM_ALERT_WINDOW` permission (`Settings.canDrawOverlays(context)`) must be granted for window manager overlay creation.
- **Process Context**: `FloatingOverlayService` will run in the main application process, sharing native library instance and engine model memory with `RecognizeActivity` and `VoiceRecognitionService`.

---

## 4. Conclusion

The required architecture for `FloatingOverlayService` JNI integration is fully determined:

1. **Rust Native JNI Bridge**: Add `src/floating.rs` and update `src/lib.rs`.
   - Module `src/floating.rs` contains static `FLOATING_STATE` and exports JNI methods:
     - `Java_dev_notune_transcribe_FloatingOverlayService_initNative`
     - `Java_dev_notune_transcribe_FloatingOverlayService_cleanupNative`
     - `Java_dev_notune_transcribe_FloatingOverlayService_startRecording`
     - `Java_dev_notune_transcribe_FloatingOverlayService_stopRecording`
     - `Java_dev_notune_transcribe_FloatingOverlayService_cancelRecording`

2. **Java Service Pattern (`FloatingOverlayService`)**:
   - Inherits `android.app.Service` (Foreground Service with `SYSTEM_ALERT_WINDOW`).
   - Loads native libraries in static initializer (`System.loadLibrary("c++_shared"); System.loadLibrary("android_transcribe_app");`).
   - Maintains `currentSessionId` token for session invalidation.
   - Implements callback methods: `onStatusUpdate`, `onAudioLevel`, `onPartialText`, `onTextTranscribed`, `onAutoStop`.
   - Integrates with `PostProcessor` for optional LLM post-processing.
   - Communicates with `FloatingDictationAccessibilityService` to auto-paste text into the active focused field.

---

## 5. Verification Method

To verify this survey report independently:
1. **Source Inspection**:
   - Inspect `src/voice_session.rs` lines 43-105, 111-354, 497-617.
   - Inspect `src/recognize.rs` lines 1-63 and `src/ime.rs` lines 1-62 to verify the thin JNI bridge pattern.
   - Inspect `src/jni_util.rs` lines 1-121 to confirm callback method names and signatures.
   - Inspect `RecognizeActivity.java` lines 147-249 and `RustInputMethodService.java` lines 559-767 to confirm Java-side callback signatures and post-processor integration.
2. **Build Test**:
   - Run `./gradlew testDebugUnitTest` to verify JVM unit tests pass (34/34 tests green).
   - Run `python3 scripts/check_translations.py` to confirm translation string key integrity.
