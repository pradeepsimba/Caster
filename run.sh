#!/usr/bin/env bash
# Installs the debug app on a device you choose, then starts it.
# If the build fails with "has no checksum information" (a damaged Gradle cache), run these once, then run this again:
#   ./gradlew --stop && rm -rf ~/.gradle/caches/*/transforms && ./gradlew clean
cd "$(dirname "$0")" || exit 1

mapfile -t DEVICES < <(adb devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
    echo "No device connected. Connect one (USB or ./connect_device.sh) and run this again."
    exit 1
fi

if [ "${#DEVICES[@]}" -eq 1 ]; then
    SERIAL="${DEVICES[0]}"
else
    echo "Which device?"
    for i in "${!DEVICES[@]}"; do
        echo "  $((i + 1))) ${DEVICES[$i]}  $(adb -s "${DEVICES[$i]}" shell getprop ro.product.model | tr -d '\r')"
    done
    read -r -p "Number: " CHOICE
    if ! [[ "$CHOICE" =~ ^[0-9]+$ ]] || [ "$CHOICE" -lt 1 ] || [ "$CHOICE" -gt "${#DEVICES[@]}" ]; then
        echo "Not a valid choice."
        exit 1
    fi
    SERIAL="${DEVICES[$((CHOICE - 1))]}"
fi
echo "Using $SERIAL"

if [ -z "$ANDROID_HOME" ] && [ -d "/home/prasanth/android-sdk" ]; then
    export ANDROID_HOME="/home/prasanth/android-sdk"
fi

echo "Building debug APK..."
chmod +x gradlew
./gradlew assembleDebug || exit 1

echo "Installing to device automatically..."
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "APK not found at $APK_PATH"
    exit 1
fi

# Push APK to device temp storage for fast, silent local installation (bypasses wireless streaming hangs & prompts)
TMP_APK="/data/local/tmp/app-debug.apk"
adb -s "$SERIAL" push "$APK_PATH" "$TMP_APK" || exit 1

# Install for current user with replace, test allow, and auto-grant permissions
INSTALL_OUT=$(adb -s "$SERIAL" shell "pm install -r -t -d -g --user current $TMP_APK" 2>&1)
echo "$INSTALL_OUT"

# In case the app was uninstalled for current user but preserved in multi-user / guest profile
if ! adb -s "$SERIAL" shell pm path mano.algo.caster >/dev/null 2>&1; then
    adb -s "$SERIAL" shell pm install-existing --user current mano.algo.caster >/dev/null 2>&1 || true
fi

# Clean up temporary APK
adb -s "$SERIAL" shell rm -f "$TMP_APK"

if ! adb -s "$SERIAL" shell pm path mano.algo.caster >/dev/null 2>&1; then
    echo "Direct pm install did not complete. Retrying adb install fallback..."
    adb -s "$SERIAL" install -r -t -d -g "$APK_PATH" || exit 1
fi
echo "App successfully installed!"

adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -n mano.algo.caster/.MainActivity || exit 1

# Live log of the app only (Ctrl+C stops it). It is followed by process id, so wait a moment for the app to start.
PID=""
for i in 1 2 3 4 5 6 7 8 9 10; do
    PID=$(adb -s "$SERIAL" shell pidof mano.algo.caster | tr -d '\r' | awk '{print $1}')
    [ -n "$PID" ] && break
    sleep 1
done
if [ -z "$PID" ]; then
    echo "The app did not start; showing warnings and errors from the whole device instead."
    exec adb -s "$SERIAL" logcat '*:W'
fi
echo "Logs of mano.algo.caster (pid $PID). Ctrl+C to stop."
# If the app is restarted (a crash, or the system ends it), follow the new process too.
while true; do
    adb -s "$SERIAL" logcat --pid="$PID"
    sleep 1
    NEW=$(adb -s "$SERIAL" shell pidof mano.algo.caster | tr -d '\r' | awk '{print $1}')
    [ -z "$NEW" ] && { echo "The app is no longer running."; break; }
    PID="$NEW"
done
