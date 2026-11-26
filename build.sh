#!/usr/bin/env bash
# Build the mic-recorder APK without Android Studio.
# Needs: JDK 11+, Android build-tools 30.0.3, and the android-28 platform.
#
#   export BT=/path/to/build-tools/30.0.3
#   export PLATFORM=/path/to/platforms/android-28/android.jar
#   export KEYSTORE=/path/to/keystore.jks KS_PASS=yourpassword
#   ./build.sh
set -euo pipefail

BT="${BT:-$HOME/android-sdk/build-tools/30.0.3}"
PLATFORM="${PLATFORM:-$HOME/android-sdk/platforms/android-28/android.jar}"
KEYSTORE="${KEYSTORE:-keystore.jks}"
KS_PASS="${KS_PASS:-changeit}"
OUT="${OUT:-mic-recorder.apk}"

rm -rf build
mkdir -p build/gen build/classes build/dex

# Launcher icons are generated, not committed — regenerate if missing.
if [ ! -f res/mipmap-mdpi/ic_launcher.png ]; then
    python3 gen_icon.py
fi

"$BT/aapt2" compile --dir res -o build/compiled.zip
"$BT/aapt2" link -o build/app-unaligned.apk -I "$PLATFORM" \
    --manifest AndroidManifest.xml --java build/gen build/compiled.zip

javac --release 8 -encoding UTF-8 -cp "$PLATFORM" -d build/classes \
    $(find build/gen src -name '*.java')

java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$PLATFORM" --min-api 24 --output build/dex \
    $(find build/classes -name '*.class')

(cd build/dex && zip -q ../app-unaligned.apk classes.dex)
"$BT/zipalign" -f 4 build/app-unaligned.apk build/app-aligned.apk
java -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --out "$OUT" build/app-aligned.apk
java -jar "$BT/lib/apksigner.jar" verify "$OUT"
echo "Built $OUT"
