#!/usr/bin/env bash
# Builds a signed, installable APK — no Android Studio or Gradle needed.
#
# Toolchain (Ubuntu/Debian packages): aapt, dalvik-exchange (dx), zipalign,
# apksigner, android-sdk-platform-23, plus a JDK (javac, keytool).
#
#   ./build.sh            → dist/OmniDeck-Mobile.apk
#
# Signing: uses $OMNI_KEYSTORE (PKCS12, alias "omnideck", password
# $OMNI_KEYSTORE_PASS) or creates .keystore/omnideck.p12 on first run. Keep
# that file to publish updates that install over the previous version; it is
# git-ignored on purpose.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KEYSTORE=${OMNI_KEYSTORE:-.keystore/omnideck.p12}
KS_PASS=${OMNI_KEYSTORE_PASS:-omnideck-local}
OUT=dist/OmniDeck-Mobile.apk
MIN_SDK=23

quiet() { grep -v '^Picked up JAVA_TOOL_OPTIONS' || true; }

missing=()
for t in aapt dalvik-exchange zipalign apksigner javac keytool; do
  command -v "$t" >/dev/null 2>&1 || missing+=("$t")
done
[ -f "$ANDROID_JAR" ] || missing+=("android.jar")
if [ ${#missing[@]} -gt 0 ]; then
  echo "Installing build tools (missing: ${missing[*]})…"
  SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
  $SUDO apt-get update -qq || true
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
    aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 >/dev/null
fi

rm -rf build/gen build/classes build/apk
mkdir -p build/gen build/classes build/apk dist

echo "[1/6] Resources → R.java"
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR"

echo "[2/6] Compiling Java"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d build/classes $(find src build/gen -name '*.java') 2>&1 | quiet
[ -f build/classes/com/omnideck/mobile/MainActivity.class ] || { echo "javac failed"; exit 1; }

echo "[3/6] Dexing"
dalvik-exchange --dex --min-sdk-version=$MIN_SDK --output=build/apk/classes.dex build/classes 2>&1 | quiet
[ -s build/apk/classes.dex ] || { echo "dx failed"; exit 1; }

echo "[4/6] Packaging"
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$ANDROID_JAR" -0 ttf \
  -F build/apk/app.unaligned.apk
(cd build/apk && aapt add -f app.unaligned.apk classes.dex >/dev/null)
zipalign -f -p 4 build/apk/app.unaligned.apk build/apk/app.aligned.apk

echo "[5/6] Signing"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias omnideck -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=OmniDeck Mobile, O=OmniDeck" 2>&1 | quiet
fi
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias omnideck \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out "$OUT" build/apk/app.aligned.apk 2>&1 | quiet

echo "[6/6] Verifying"
apksigner verify --verbose "$OUT" 2>&1 | quiet | grep -E '^Verifies|scheme \(|Number of signers'
aapt dump badging "$OUT" | grep -E "^(package|sdkVersion|targetSdkVersion|uses-permission|launchable-activity|application-label):"
ls -l "$OUT" | awk '{print "APK: " $NF " (" $5 " bytes)"}'
sha256sum "$OUT"
