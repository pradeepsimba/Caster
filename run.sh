#!/usr/bin/env bash
# Installs the debug app on a device you choose, then starts it.
# Pass --watch (or -w) to automatically rebuild, reinstall, and restart when source files change.
# If the build fails with "has no checksum information" (a damaged Gradle cache), run these once, then run this again:
#   ./gradlew --stop && rm -rf ~/.gradle/caches/*/transforms && ./gradlew clean
cd "$(dirname "$0")" || exit 1

WATCH=0
for arg in "$@"; do
    case "$arg" in
        --watch|-w) WATCH=1 ;;
        *) echo "Unknown option: $arg"; exit 1 ;;
    esac
done

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

chmod +x gradlew

build_and_launch() {
    echo "Building debug APK..."
    ./gradlew assembleDebug || return 1

    echo "Installing to device automatically..."
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    if [ ! -f "$APK_PATH" ]; then
        echo "APK not found at $APK_PATH"
        return 1
    fi

    # Push APK to device temp storage for fast, silent local installation (bypasses wireless streaming hangs & prompts)
    TMP_APK="/data/local/tmp/app-debug.apk"
    adb -s "$SERIAL" push "$APK_PATH" "$TMP_APK" || return 1

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
        adb -s "$SERIAL" install -r -t -d -g "$APK_PATH" || return 1
    fi
    echo "App successfully installed!"

    adb -s "$SERIAL" logcat -c
    adb -s "$SERIAL" shell am start -n mano.algo.caster/.MainActivity || return 1
}

tail_logs() {
    # Errors-only log of the app (Ctrl+C stops it). Waits for the app process to start.
    local PID=""
    for i in 1 2 3 4 5 6 7 8 9 10; do
        PID=$(adb -s "$SERIAL" shell pidof mano.algo.caster | tr -d '\r' | awk '{print $1}')
        [ -n "$PID" ] && break
        sleep 1
    done
    if [ -z "$PID" ]; then
        echo "The app did not start; showing errors from the whole device instead."
        adb -s "$SERIAL" logcat '*:E'
        return
    fi
    echo "Errors from mano.algo.caster (pid $PID). Ctrl+C to stop."
    # If the app is restarted (a crash, or the system ends it), follow the new process too.
    while true; do
        adb -s "$SERIAL" logcat --pid="$PID" '*:E'
        sleep 1
        local NEW
        NEW=$(adb -s "$SERIAL" shell pidof mano.algo.caster | tr -d '\r' | awk '{print $1}')
        [ -z "$NEW" ] && { echo "The app is no longer running."; break; }
        PID="$NEW"
    done
}

if [ "$WATCH" -eq 0 ]; then
    build_and_launch || exit 1
    tail_logs
    exit 0
fi

snapshot() {
    find app/src docs app/build.gradle.kts build.gradle.kts gradle/libs.versions.toml \
        -type f -printf '%T@ %s %p\n' 2>/dev/null | sort
}

LOG_PID=""
stop_logs() {
    if [ -n "$LOG_PID" ]; then
        kill "$LOG_PID" 2>/dev/null
        wait "$LOG_PID" 2>/dev/null || true
        LOG_PID=""
    fi
}
trap 'stop_logs; exit 0' INT TERM EXIT

LAST_SNAP=$(snapshot)
if build_and_launch; then
    tail_logs &
    LOG_PID=$!
else
    echo "Build/install failed; watching for changes..."
fi

echo "Watching app/src and docs for changes... (Ctrl+C to stop)"
while true; do
    sleep 1
    CUR_SNAP=$(snapshot)
    if [ "$CUR_SNAP" != "$LAST_SNAP" ]; then
        sleep 0.3
        LAST_SNAP=$(snapshot)
        echo ""
        echo "Change detected — rebuilding and restarting..."
        stop_logs
        if build_and_launch; then
            tail_logs &
            LOG_PID=$!
        else
            echo "Build/install failed; watching for changes..."
        fi
    fi
done
