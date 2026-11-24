#!/usr/bin/env bash
# Builds GridGamingLounge.apk without Gradle.
# Needs: Ubuntu packages aapt apksigner zipalign dalvik-exchange, a JDK, and
# SDK_DIR containing android-all.jar (org.robolectric:android-all:14-robolectric-10818077 from Maven Central).
# KEYSTORE / KS_PASS: the release key. ALWAYS reuse the same keystore, or the phone will refuse the update.
set -euo pipefail
cd "$(dirname "$0")"
: "${SDK_DIR:?set SDK_DIR}" "${KEYSTORE:?set KEYSTORE}" "${KS_PASS:?set KS_PASS}"
if [ ! -f "$SDK_DIR/framework.zip" ]; then
  tmp=$(mktemp -d); (cd "$tmp" && unzip -oq "$SDK_DIR/android-all.jar" AndroidManifest.xml resources.arsc && zip -q "$SDK_DIR/framework.zip" AndroidManifest.xml resources.arsc); rm -rf "$tmp"
fi
rm -rf build && mkdir -p build/gen build/classes
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK_DIR/framework.zip"
javac -nowarn --release 8 -cp "$SDK_DIR/android-all.jar" -d build/classes build/gen/com/gridgaming/lounge/R.java src/com/gridgaming/lounge/*.java
dalvik-exchange --dex --min-sdk-version=24 --output=build/classes.dex build/classes
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$SDK_DIR/framework.zip" -0 arsc -F build/unsigned.apk
(cd build && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-key-alias gridlounge --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --min-sdk-version 24 --out build/GridGamingLounge.apk build/aligned.apk
apksigner verify build/GridGamingLounge.apk && echo "Built build/GridGamingLounge.apk"
