# Original User Request

## 2026-08-12T21:25:08Z

This is a single self-contained feature; keep it small and focused.

Implement edge snapping, smooth magnetic docking, and an inactive semi-transparent collapsed/docked state for the floating dictation bubble (`FloatingOverlayService.java`) in `android_transcribe_app`.

Working directory: /root/GitHub/android_transcribe_app
Integrity mode: demo

## Requirements

### R1. Edge Snapping and Smooth Docking Animation
- In `FloatingOverlayService.java`, when the user finishes dragging the floating bubble icon (`ACTION_UP` / `ACTION_CANCEL`), calculate the distance to the left and right screen boundaries.
- Smoothly animate the floating bubble position (`WindowManager.LayoutParams.x`) to snap to the nearest lateral edge (left or right).
- Ensure orientation changes and screen boundary metrics (via `DisplayMetrics` / `WindowMetrics`) are accurately handled.

### R2. Inactive Edge Collapsed / Semi-Transparent State
- After snapping to an edge, transition the bubble to a docked edge state after 2-3 seconds of inactivity.
- In the docked state, adjust opacity (e.g. alpha ~0.5) and partially peek from the screen margin so it stays unobtrusive.
- Tapping or touching the docked bubble restores full opacity and opens/toggles the dictation overlay seamlessly.

### R3. Quality & Regression Safeguards
- Preserve all existing floating overlay features (expanding overlay box, status indicator, AI Fix toggle, streaming text, cancel/paste actions).
- Guarantee main UI thread WindowManager safety without crashes, WindowManager leak errors, or exceptions during service destruction (`onDestroy`).

## Acceptance Criteria

### Edge Docking & Behavior
- [ ] Releasing the floating bubble smoothly animates it to the nearest edge (left or right).
- [ ] Inactive bubble dims to semi-transparent and docks at the lateral margin after inactivity.
- [ ] Interacting with the docked bubble revives full visibility and opens/toggles dictation as expected.
- [ ] Unit tests pass (`./gradlew testDebugUnitTest`).
- [ ] Android build compiles cleanly without errors.

## 2026-08-27T17:24:49Z

This is a single self-contained fix; keep it small and focused.

Stabilize and refactor `android_transcribe_app` on branch `feat/audio-ci-refactor` to achieve 100% green CI/CD builds, robust Bluetooth & external microphone routing (FUTO Keyboard style), and flawless offline speech-to-text dictation delivered via Telegram APK.

Working directory: /data/data/com.termux/files/home/android_transcribe_app
Integrity mode: development

## Requirements

### R1. Bluetooth & External Audio Input Dynamic Routing
Enable seamless automatic switching and manual selection between Bluetooth SCO/BLE headsets, USB mics, wired headsets, and the device's internal mic across all recording surfaces (`RustInputMethodService`, `RecognizeActivity`, `FloatingOverlayService`).

### R2. Pure-JVM Test Suite & Decoupled Architecture
Ensure 100% decoupling between pure business logic / post-processing and Android framework APIs so that unit tests (`./gradlew testDebugUnitTest`) pass reliably on GitHub Actions runners without requiring Android framework mocks or keystore initialization.

### R3. CI/CD Hard Gate Pipeline & Telegram APK Delivery
All build steps (Rust fmt, 247 translation check, JVM tests, latency benchmarks, NDK compilation, APK packaging) must pass cleanly in GitHub Actions (`.github/workflows/debug_telegram.yml`) on `feat/audio-ci-refactor` and deliver the resulting debug APK directly to the configured Telegram chat.

## Acceptance Criteria

### Audio Routing & UI
- [ ] 3-way input mode selection (*Auto*, *Bluetooth Only*, *Builtin Only*) persisted via `SettingsManager`.
- [ ] Active audio communication mode acquired before recording begins and released immediately upon termination across IME, popup, and bubble.

### CI/CD & Build Integrity
- [ ] `check_translations.py` reports 100% parity across all 6 locales (247 strings).
- [ ] `testDebugUnitTest` passes 100% on plain JVM.
- [ ] GitHub Actions generates and uploads `android_transcribe_app_v0.1.36-debug.apk` and sends it via Telegram bot without errors.

