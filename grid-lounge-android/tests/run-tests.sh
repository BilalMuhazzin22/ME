#!/usr/bin/env bash
# Runs all automated tests for Grid Gaming Lounge.
#   Web/UI suite : node + Playwright (Chromium)          -> tests/web/full.test.js
#   Android suite: Maven + Robolectric on Android 7/9/14  -> tests/native
# Needs SDK_DIR with android-all.jar (see build.sh) and a built app (./build.sh) for build/unsigned.apk + build/gen/R.java.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${SDK_DIR:?set SDK_DIR}"
echo "== Web/UI suite"; node tests/web/full.test.js | tail -3
echo "== Android suite"
W=$(mktemp -d); cp -r tests/native/. "$W/"; mkdir -p "$W/src/main/java/com/gridgaming/lounge" "$W/src/test/resources/com/android/tools"
cp src/com/gridgaming/lounge/*.java build/gen/com/gridgaming/lounge/R.java "$W/src/main/java/com/gridgaming/lounge/"
printf 'android_merged_manifest=%s\nandroid_merged_assets=%s\nandroid_resource_apk=%s\nandroid_custom_package=com.gridgaming.lounge\n' \
  "$PWD/AndroidManifest.xml" "$PWD/assets" "$PWD/build/unsigned.apk" > "$W/src/test/resources/com/android/tools/test_config.properties"
(cd "$W" && mvn -q -B test) && echo "Android suite: all passed"
