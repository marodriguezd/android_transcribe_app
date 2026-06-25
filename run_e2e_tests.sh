#!/bin/bash
set -e

# Offline Voice Input Android Optimizations - E2E Test Runner
echo "============================================="
echo "Building host native library via Cargo..."
echo "============================================="
cargo build --release

# Absolute path to built library directory
LIB_DIR="$(pwd)/target/release"
echo "Host native library built successfully at: ${LIB_DIR}"

# Run Robolectric/JUnit E2E Test Suite
echo "============================================="
echo "Running E2E tests via Gradle..."
echo "============================================="
./gradlew testDebugUnitTest --info
