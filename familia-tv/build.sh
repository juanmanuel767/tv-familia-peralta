#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BUILD_TOOLS="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
DEX="$BUILD/dex"
KEYSTORE="$BUILD/debug.keystore"

for tool in aapt2 d8 zipalign apksigner; do
    if [ ! -x "$BUILD_TOOLS/$tool" ]; then
        echo "Falta $BUILD_TOOLS/$tool" >&2
        exit 1
    fi
done
if [ ! -f "$ANDROID_JAR" ]; then
    echo "Falta $ANDROID_JAR" >&2
    exit 1
fi
if ! command -v zip >/dev/null 2>&1; then
    echo "Falta el comando zip" >&2
    exit 1
fi

mkdir -p "$BUILD"
rm -rf "$CLASSES" "$DEX" "$BUILD/gen"
rm -f "$BUILD/base.apk" "$BUILD/aligned.apk" "$BUILD/familia-tv.apk" "$BUILD/familia-tv.apk.idsig" "$BUILD/res.zip"
mkdir -p "$CLASSES" "$DEX" "$BUILD/gen"

if [ ! -f "$KEYSTORE" ]; then
    echo "Creando keystore de depuración..."
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" \
        -alias familia \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Familia TV, OU=Familia, O=Familia, L=Bogota, C=CO" >/dev/null
fi

echo "1/6 Empaquetando manifiesto y assets..."
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/res" -o "$BUILD/res.zip"
"$BUILD_TOOLS/aapt2" link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    -A "$ROOT/assets" \
    --java "$BUILD/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --auto-add-overlay \
    "$BUILD/res.zip"

echo "2/6 Compilando Java..."
find "$ROOT/src" "$BUILD/gen" -name '*.java' -print0 | xargs -0 javac --release 17 -classpath "$ANDROID_JAR" -d "$CLASSES"

echo "3/6 Generando classes.dex..."
find "$CLASSES" -name '*.class' -print0 | xargs -0 "$BUILD_TOOLS/d8" \
    --min-api 26 \
    --lib "$ANDROID_JAR" \
    --output "$DEX"

echo "4/6 Añadiendo classes.dex al APK..."
zip -q -j "$BUILD/base.apk" "$DEX/classes.dex"

echo "5/6 Alineando..."
"$BUILD_TOOLS/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "6/6 Firmando..."
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias familia \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$BUILD/familia-tv.apk" \
    "$BUILD/aligned.apk"

"$BUILD_TOOLS/apksigner" verify "$BUILD/familia-tv.apk"

echo "Listo: $BUILD/familia-tv.apk"
