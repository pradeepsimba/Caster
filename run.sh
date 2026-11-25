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

chmod +x gradlew && ANDROID_SERIAL="$SERIAL" ./gradlew installDebug || exit 1

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
