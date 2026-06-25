# E2E Test Infrastructure - Offline Voice Input Android Optimizations

This document outlines the testing infrastructure, hierarchical test layout, feature inventory, and execution guide for the Offline Voice Input Android application optimizations.

## E2E Test Infrastructure Overview

The test suite runs JVM-based unit tests powered by **JUnit 4** and **Robolectric**. Since the core voice typing engine utilizes a hybrid layout combining Kotlin/Java Android code with high-performance Rust (`libandroid_transcribe_app.so`), the test infrastructure passes the host-compiled native library to the Java Virtual Machine.

### Test Stack:
- **Test Runner**: Robolectric (configured to mock Android SDK 28)
- **Host Native Compilation**: Cargo (`cargo build --release` produces `libandroid_transcribe_app.so` on Linux x86_64 host)
- **JVM Library Injection**: Configured in `app/build.gradle.kts` using `java.library.path` pointing to `target/release`.

---

## 4-Tier Test Hierarchy

To ensure robust coverage under normal, boundary, integration, and user-facing scenarios, the tests are organized into four distinct tiers:

### Tier 1: Feature Coverage (>= 25 test cases)
Validates that each of the 5 key features works correctly under standard settings and configurations:
- **Feature 1: Model Initialization & Storage Management (R1)**: Verifies asset directory mapping, resource file presence, and default states.
- **Feature 2: Process & Resource Sharing (R2)**: Verifies UI and background thread initialization safety, static singleton accessibility, and same-process operation constraints.
- **Feature 3: Audio Callback JNI Decoupling (R3)**: Verifies get/set atomic audio levels, silence detection, and max capping.
- **Feature 4: CPAL Audio Format Compatibility & Resampler LPF (R4)**: Verifies CPAL sample rate selection, resampler Low-Pass Filter math, sample rate conversion ratios, and buffer queue safety.
- **Feature 5: UI & Settings Polish (R5)**: Verifies SettingsManager CRUD operations, settings toggles (e.g. post-processing), and view level targets.

### Tier 2: Boundary & Corner Cases (>= 25 test cases)
Tests error handling and system resilience when dealing with invalid inputs, missing resources, and extreme bounds:
- **Feature 1**: Handles missing files, empty asset files, negative file descriptor offsets, and excessively large file limits.
- **Feature 2**: Verifies loading idempotency (double load), unloading nonloaded engines, concurrent load synchronization, and null context handling.
- **Feature 3**: Asserts correct boundary behavior for negative audio levels, extremely large audio levels, `NaN` levels, and updates during stopped sessions.
- **Feature 4**: Evaluates unsupported sample rates (e.g. 8kHz), empty input arrays, invalid conversion ratios, and large buffer overflows.
- **Feature 5**: Asserts settings values with empty endpoints, blank prompt templates, overflow prompt lengths, and View level input range clamping.

### Tier 3: Pairwise Interaction (>= 5 test cases)
Verifies correct behavior when multiple features interact:
- **Model Init & Settings Collision**: Validates settings changes alongside model init.
- **Audio Callback & UI Smoothing**: Connects decoupled atomic JNI audio levels directly to the `MicLevelView` smoothing target.
- **Process Sharing & Storage Consistency**: Ensures main thread and background worker tasks resolve to identical files directories.
- **CPAL Format & PostProcessor**: Verifies audio formatting compatibility does not interfere with the post-processing configuration lifecycle.
- **UI State & Model Unloading**: Ensures UI components remain disabled or transition correctly when resources are cleaned up or unloaded.

### Tier 4: Real-World Scenarios (>= 5 test cases)
Simulates complete end-to-end user workflows:
- **Complete Voice Typing Success**: Standard path from listening, transcription, to refining and committing text to target inputs.
- **Adversarial Mic Permission Denied**: Verifies UI instructions and behavior when RECORD_AUDIO permission is withheld.
- **Network Failure During Post-Process Fallback**: Ensures system falls back to raw text transcription when external refinement endpoints fail.
- **Service Lifecycle with Auto-Record Config**: Simulates the IME lifecycle launching with `auto_record` configuration enabled.
- **Keyboard Switching During Recording**: Simulates a user swapping input methods midway through an active audio recording session.

---

## Feature Inventory & Requirement Map

| Req ID | Feature | Test Tier | Java Method |
|---|---|---|---|
| **R1** | Direct Asset Loading / FD | Tier 1 & 2 | `testTier1_Feature1_*`, `testTier2_Feature1_*` |
| **R2** | Process Unification / Resource Sharing | Tier 1 & 2 | `testTier1_Feature2_*`, `testTier2_Feature2_*` |
| **R3** | JNI Decoupled Audio Callback | Tier 1 & 2 | `testTier1_Feature3_*`, `testTier2_Feature3_*` |
| **R4** | CPAL Format Query & LPF Resampler | Tier 1 & 2 | `testTier1_Feature4_*`, `testTier2_Feature4_*` |
| **R5** | UI & Settings Polish | Tier 1 & 2 | `testTier1_Feature5_*`, `testTier2_Feature5_*` |
| **Cross** | Feature Interactions | Tier 3 | `testTier3_Combo_*` |
| **E2E** | User Flows | Tier 4 | `testTier4_Flow_*` |

---

## Test Execution Guide

Run E2E tests using the provided test runner script:
```bash
./run_e2e_tests.sh
```

This script:
1. Re-compiles the native Rust library for the host platform (`cargo build --release`).
2. Places the library in `target/release/`.
3. Runs the Gradle test task (`./gradlew testDebugUnitTest --info`) passing the system library path.

---

## Current Coverage Status

- **Total Test Cases**: 60 (Tier 1: 25, Tier 2: 25, Tier 3: 5, Tier 4: 5)
- **Status**: Enabled and verified in build environment.
