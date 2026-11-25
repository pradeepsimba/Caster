adb kill-server && \
adb start-server && \
adb mdns services | awk '/_adb-tls-connect\._tcp/ {print $NF}' | while read -r ADDR; do
    adb connect "$ADDR"
done && \
adb devices
