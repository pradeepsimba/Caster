chmod +x gradlew && ./gradlew installDebug && adb shell am start -n mano.algo.caster/.MainActivity
# If the build fails with "has no checksum information" (a damaged Gradle cache), run these once, then run this again:
#   ./gradlew --stop && rm -rf ~/.gradle/caches/*/transforms && ./gradlew clean
