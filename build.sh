#!/usr/bin/env bash
set -euo pipefail

# Environment Configuration
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-/home/marodriguezd/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/home/marodriguezd/jdk-21/jdk-21.0.2+13}

# Ensure we are in the project root
cd "$(dirname "$0")"

echo "--- Preparing build environment ---"

# Check if Java exists
if ! [ -x "$JAVA_HOME/bin/java" ]; then
    echo "Error: JAVA_HOME is not correctly set or java executable not found at $JAVA_HOME/bin/java"
    exit 1
fi

# Determine build type (default to Release if no arguments provided, or use first arg)
BUILD_TYPE="Release"
GRADLE_TASK="assembleRelease"

if [[ "${1:-}" == "debug" ]]; then
    BUILD_TYPE="Debug"
    GRADLE_TASK="assembleDebug"
    shift
fi

echo "--- Building $BUILD_TYPE APK ---"

# 1. Download/Verify models (Parakeet)
# 2. Build Rust native code (cargo-ndk)
# 3. Assemble Android APK
./gradlew downloadModels cargoNdkBuild "$GRADLE_TASK" "$@"

echo ""
echo "--- Build Successful ---"
if [[ "$BUILD_TYPE" == "Release" ]]; then
    echo "APK Location: app/build/outputs/apk/release/app-release.apk"
else
    echo "APK Location: app/build/outputs/apk/debug/app-debug.apk"
fi
