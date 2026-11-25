export ADB_MDNS_OPENSCREEN=1   # adb's own mDNS finder: needs no Bonjour service on Windows
adb kill-server && \
adb start-server && \
for i in 1 2 3 4 5 6 7 8; do   # the phone needs a few seconds to show up after the server starts
    ADDRS=$(adb mdns services | tr -d '\r' | awk '/_adb-tls-connect\._tcp/ {print $NF}')
    [ -n "$ADDRS" ] && break
    sleep 1
done
for ADDR in $ADDRS; do
    adb connect "$ADDR"
done
adb devices
