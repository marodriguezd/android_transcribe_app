#!/usr/bin/env bash
set -euo pipefail

export ANDROID_SDK_ROOT=/home/marodriguezd/Android/Sdk
export JAVA_HOME=/home/marodriguezd/jdk-21/jdk-21.0.2+13

cd "$(dirname "$0")"
./gradlew assembleDebug "$@"
