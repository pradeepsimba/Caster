#!/usr/bin/env bash
set -e

for jdk in "$HOME/.gradle/jdks/"*25*; do
    if [ -x "$jdk/bin/java" ]; then
        export JAVA_HOME="$jdk"
        break
    fi
done

chmod +x gradlew && ./gradlew assembleRelease && echo "Output APK: $PWD/$(ls app/build/outputs/apk/release/*.apk)"
