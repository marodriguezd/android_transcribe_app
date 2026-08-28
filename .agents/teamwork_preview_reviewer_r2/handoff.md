# Adversarial Review & QA Handoff Report — Audio CI & Dynamic Routing (Round 2)

**Branch:** `feat/audio-ci-refactor`  
**Repository:** `android_transcribe_app`  
**Working Directory:** `/data/data/com.termux/files/home/android_transcribe_app`  
**Commit:** `b9e7173`  
**Integrity Mode:** development  
**Status:** Verification Complete / 100% Green Test Suite

---

## 1. Independent Task & Requirements Derivation

### R1. Bluetooth & External Audio Input Dynamic Routing
Seamless automatic routing (preferring Bluetooth SCO/BLE, USB audio, wired headsets, and falling back to builtin mic) as well as explicit 3-way input mode selection (*Auto*, *Bluetooth Only*, *Builtin Only*) persisted via `SettingsManager` marker files across all recording surfaces (`RustInputMethodService`, `RecognizeActivity`, `FloatingOverlayService`, and `VoiceRecognitionService`).

### R2. Pure-JVM Test Suite & Decoupled Architecture
100% decoupling between pure business logic / persistence / signal processing and Android framework APIs so that unit tests (`./gradlew testDebugUnitTest`) compile and pass without relying on framework mocks or keystore initialization.

### R3. CI/CD Hard Gate Pipeline & Telegram APK Delivery
Every build gate (`cargo fmt`, `check_translations.py` 247 strings parity across 6 locales, JVM unit tests, performance/latency benchmarks, NDK compilation, APK packaging, lint gate, checkModels) must pass cleanly in GitHub Actions (`.github/workflows/debug_telegram.yml`) on `feat/audio-ci-refactor` and deliver the debug APK via Telegram bot.

---

## 2. Defects Identified in Prior Implementation & Root Cause Analysis

1. **Defect 1: `FloatingOverlayTest` Class Initialization Crash on JVM (11 Failing Tests)**
   - *Input:* Running JVM unit tests against classes referencing `FloatingOverlayService`.
   - *Expected:* Tests execute cleanly on plain JVM without framework mocks.
   - *Actual:* `NoClassDefFoundError: Could not initialize class dev.notune.transcribe.FloatingOverlayService` across 11 test cases in `FloatingOverlayTest`.
   - *Root Cause:* `FloatingOverlayService.<clinit>` invoked `Log.e(TAG, "Failed to load native libraries", e)` inside `catch (UnsatisfiedLinkError e)`. On plain JVM with stub `android.jar`, `Log.e` throws `RuntimeException("Stub!")`, causing unhandled `ExceptionInInitializerError` that permanently breaks class loading.

2. **Defect 2: `PostProcessorTest` Latch Timeouts on Network & Parse Errors (6 Failing Tests)**
   - *Input:* Simulated HTTP errors (401 unauthorized), DNS failures, connect timeouts, read timeouts, and malformed JSON payloads in `PostProcessorTest`.
   - *Expected:* Error callbacks delivered to subscriber and `CountDownLatch.countDown()` executed.
   - *Actual:* `done.await(5, TimeUnit.SECONDS)` timed out and failed across 6 test cases (`providerErrorMessageRedactsCredentialLikeValues`, `httpErrorReportsApiError`, `dnsFailureReportsError`, `stalledResponseHitsReadTimeoutAndReportsError`, `connectTimeoutReportsError`, `malformedJsonReportsParseError`).
   - *Root Cause:* `PostProcessor.debugLog()` called `Log.d(TAG, message)` whenever `BuildConfig.DEBUG` was true. On plain JVM, `Log.d` threw `RuntimeException("Stub!")`, killing the OkHttp callback thread before `dispatchToUi(() -> callback.onError(...))` was reached.

3. **Defect 3: Fragile Native Static Initializers Across 7 Core Classes**
   - *Input:* Loading `MainActivity`, `ModelsActivity`, `RustInputMethodService`, `RecognizeActivity`, `VoiceRecognitionService`, `LiveSubtitleService`, or `TranscribeFileActivity` on JVM.
   - *Expected:* Safe graceful degradation when native `.so` is not present.
   - *Actual:* In `MainActivity` and `ModelsActivity`, `System.loadLibrary("android_transcribe_app")` was outside the `try` block; in other classes, `Log.e`/`Log.w` was unshielded against stub JVM runtime exceptions.
   - *Root Cause:* Missing defensive `Throwable` catch and safe logging wrappers in static initializers.

4. **Defect 4: Missing `isRoutingActive()` Observability in `AudioDeviceManager`**
   - *Input:* Querying active audio routing state after start/release cycles.
   - *Expected:* Public accessor method `isRoutingActive()` available and verified in unit tests.
   - *Actual:* Field was package-private with no getter.
   - *Root Cause:* Omission in initial API design.

---

## 3. Changes Implemented

- **`PostProcessor.java`:**
  - Wrapped `Log.d` inside `debugLog(String message)` with `try { Log.d(TAG, message); } catch (Throwable ignored) {}`.
  - Wrapped `Log.e` in `processOnDeviceInternal` with defensive try-catch.
- **`FloatingOverlayService.java`:**
  - Hardened `<clinit>` to catch `Throwable` and safely shield `Log.e` against stub JVM runtime exceptions.
- **`RustInputMethodService.java`, `RecognizeActivity.java`, `VoiceRecognitionService.java`, `LiveSubtitleService.java`, `MainActivity.java`, `ModelsActivity.java`, `TranscribeFileActivity.java`:**
  - Hardened `<clinit>` blocks across all classes to safely load native libraries and shield logging against JVM stub runtime exceptions.
- **`AudioDeviceManager.java`:**
  - Added public getter `isRoutingActive()` for recording state observability.
- **`AudioDeviceManagerTest.java`:**
  - Added assertion verifying `isRoutingActive() == false` following redundant releases.

---

## 4. Verification Record

- **Translation Parity Gate (`scripts/check_translations.py`):**
  - Command: `python3 scripts/check_translations.py`
  - Output: `[CHECK-TRANSLATIONS] PASS: all 6 locales complete` (247/247 strings present across DE, ES, FR, IT, PT, RU).
- **Rust Code Formatting Gate (`cargo fmt --all -- --check`):**
  - Command: `cargo fmt --all -- --check`
  - Output: 0 violations, clean exit code 0.
- **Performance & Latency Benchmark Suite (`scripts/bench_performance.py`):**
  - Command: `python3 scripts/bench_performance.py`
  - Output: All 5 optimization suites passed (RMS audio energy math, SIMD sliding split detection, 80ms live streaming cadence, bigram cosine throughput, banded Levenshtein DP).
- **Pure-JVM Unit Test Suite (JUnit 4.13.2 Runner):**
  - Tested classes:
    - `dev.notune.transcribe.AudioDeviceManagerTest`
    - `dev.notune.transcribe.CallRegistryTest`
    - `dev.notune.transcribe.FileSha256Test`
    - `dev.notune.transcribe.FloatingOverlayTest`
    - `dev.notune.transcribe.MarkerAtomicityTest`
    - `dev.notune.transcribe.MarkerFileHelperPersistenceTest`
    - `dev.notune.transcribe.MarkerFileHelperTest`
    - `dev.notune.transcribe.PostProcessorTest`
    - `dev.notune.transcribe.SourceLanguageResolverTest`
    - `dev.notune.transcribe.SubtitlePrefsTest`
    - `dev.notune.transcribe.SubtitleTranslationTargetsTest`
  - Result: `OK (94 tests)` — 100% PASS, 0 failures, 0 errors.

---

## 5. Known Issues

- `Shallow Verification`: Physical Bluetooth SCO hardware microphone switching requires live physical Android device validation with OEM vendor HALs.
- `Minor Robustness Risk`: On legacy Android 8–10 devices with customized vendor audio HALs, `startBluetoothSco()` connection is asynchronous and may take ~100–300ms to route initial audio frames from the headset microphone.

---

## 6. Remaining Risk & Next Step

- Push branch `feat/audio-ci-refactor` to trigger GitHub Actions workflow `.github/workflows/debug_telegram.yml` and verify the end-to-end CI build and Telegram bot APK delivery.
