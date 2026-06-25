# Scope: E2E Testing Track

## Architecture
- The test suite must be requirement-driven and opaque-box.
- It should verify the Android app features and native integration without depending on specific internal implementations.
- It will consist of a 4-tier test case hierarchy (Tiers 1-4) plus Tier 5 (Adversarial Coverage Hardening).
- Verification can run either on a running emulator/device (using `adb` or Instrumentation tests) or via a simulated local runner (JVM/Robolectric or custom integration test harness) that exercises the application's Java/Kotlin classes and native JNI bindings.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Test Environment Exploration | Explore available emulator, adb, JVM testing capabilities, and test runners. | None | DONE |
| 2 | Design E2E Test Suite (Tiers 1-4) | Enumerate features, design test cases, boundary cases, combinations, and application scenarios. Create TEST_INFRA.md. | M1 | DONE |
| 3 | Implement E2E Test Harness & Cases | Write test runner script, Java/Kotlin test code, mock audio generator, and any auxiliary test assets. | M2 | DONE |
| 4 | Run and Validate E2E Test Suite | Run tests, fix failures, and achieve 100% pass rate. | M3 | DONE |
| 5 | Verify & Publish E2E Test Suite | Produce TEST_READY.md and report success to the parent. | M4 | DONE |

## Interface Contracts
- None (Test track doesn't expose public interfaces to implementation modules, but consumes Android API and JNI).
