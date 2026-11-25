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

chmod +x gradlew && ANDROID_SERIAL="$SERIAL" ./gradlew installDebug && adb -s "$SERIAL" shell am start -n mano.algo.caster/.MainActivity
