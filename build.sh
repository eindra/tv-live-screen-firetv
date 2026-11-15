#!/bin/sh
# Build Live Screen without Gradle: aapt2 -> javac -> d8, the NDK for the NDI glue, then zipalign -> apksigner.
# Needs JDK 17, the Android SDK (build-tools 34, platform 33, an NDK) and NDI's SDK for Android.
#
#   ./build.sh                          # builds build/livescreen.apk
#   DEVICE=192.168.0.50 ./build.sh install   # also installs and opens it (the address is remembered)
set -eu

JAVA_HOME="${JAVA_HOME:-$HOME/jdk17/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"  # d8 and apksigner look for java on the PATH
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-33/android.jar"
NDK="${NDK:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
NDI="${NDI_SDK:-$HOME/NDI SDK for Android}"
HOME_DIR="$HOME/.livescreen"
ABIS="arm64-v8a armeabi-v7a"  # Fire TV runs most apps as 32-bit, Google TV as 64-bit

[ -d "$NDK" ] || { echo "No NDK in $SDK/ndk (sdkmanager 'ndk;<version>')"; exit 1; }
[ -f "$NDI/include/Processing.NDI.Lib.h" ] || { echo "NDI SDK for Android not found at $NDI (set NDI_SDK)"; exit 1; }

cd "$(dirname "$0")"
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "Resources..."
"$BT/aapt2" compile --dir res -o "$OUT/res/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --java "$OUT/gen" "$OUT/res/res.zip" --auto-add-overlay

echo "Java..."
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -encoding UTF-8 \
    -bootclasspath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "Dex..."
"$BT/d8" --release --min-api 25 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)

echo "Native..."
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
for abi in $ABIS; do
    case $abi in
        arm64-v8a) clang="$CC/aarch64-linux-android25-clang" ;;
        armeabi-v7a) clang="$CC/armv7a-linux-androideabi25-clang" ;;
    esac
    lib="$NDI/lib/$abi"
    [ -f "$lib/libndi.so" ] || { echo "  skipping $abi (no $lib/libndi.so)"; continue; }
    mkdir -p "$OUT/lib/$abi"
    "$clang" -O2 -fPIC -shared -Wall -I"$NDI/include" jni/ndiview.c -L"$lib" -lndi -landroid -llog \
        -o "$OUT/lib/$abi/libndiview.so"
    cp "$lib/libndi.so" "$OUT/lib/$abi/"
done
(cd "$OUT" && zip -q -r unsigned.apk lib)

echo "Sign..."
KS="$HOME_DIR/release.keystore"
if [ ! -f "$KS" ]; then
    mkdir -p "$HOME_DIR"
    PASS=$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 20)
    echo "$PASS" > "$HOME_DIR/keystore-pass"
    chmod 600 "$HOME_DIR/keystore-pass"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
        -alias livescreen -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Live Screen" >/dev/null 2>&1
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$HOME_DIR/keystore-pass" --out "$OUT/livescreen.apk" "$OUT/aligned.apk"
echo "Built $OUT/livescreen.apk ($(du -h "$OUT/livescreen.apk" | cut -f1))"

if [ "${1:-}" = "install" ]; then
    if [ -n "${DEVICE:-}" ]; then mkdir -p "$HOME_DIR"; echo "$DEVICE" > "$HOME_DIR/device"; fi
    DEV=$(cat "$HOME_DIR/device" 2>/dev/null || true)
    [ -n "$DEV" ] || { echo "Set DEVICE=<the stick's IP address>"; exit 1; }
    case $DEV in *:*) ;; *) DEV="$DEV:5555" ;; esac
    ADB="$SDK/platform-tools/adb"
    "$ADB" connect "$DEV" >/dev/null
    "$ADB" -s "$DEV" install -r "$OUT/livescreen.apk"
    "$ADB" -s "$DEV" shell am start -n com.moments.livescreen/.MainActivity >/dev/null
    echo "Installed on $DEV"
fi
