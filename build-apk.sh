#!/usr/bin/env bash
# Builds output/WiFi-TV-Receiver.apk on a machine with JDK 17+, the Android SDK and Gradle 8.7+.
set -euo pipefail
cd "$(dirname "$0")/android"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK folder (Android Studio > SDK Manager shows it)}"
G=gradle; [ -x ./gradlew ] && G=./gradlew
$G assembleDebug
mkdir -p ../output
cp app/build/outputs/apk/debug/app-debug.apk ../output/WiFi-TV-Receiver.apk
echo "APK ready: $(cd ../output && pwd)/WiFi-TV-Receiver.apk"
