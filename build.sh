#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$PROJECT_DIR/.env"
BUILD_DIR="$PROJECT_DIR/build"
MANIFEST="$PROJECT_DIR/app/src/main/AndroidManifest.xml"
RES_DIR="$PROJECT_DIR/app/src/main/res"
BLOCK_CONFIG="$PROJECT_DIR/.block-config.json"
SOURCE_DIR="$PROJECT_DIR/app/src/main/java/io/github/chickenblocker/android"
PACKAGE_PATH="io/github/chickenblocker/android"

if [[ ! -f "$ENV_FILE" ]]; then
    printf 'Missing %s. Copy .env.example to .env and set tool paths.\n' "$ENV_FILE" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

required_vars=(JAVA_HOME ANDROID_PLATFORM_JAR AAPT2 D8 ZIPALIGN APKSIGNER)
for name in "${required_vars[@]}"; do
    if [[ -z "${!name:-}" ]]; then
        printf 'Missing required variable %s in %s\n' "$name" "$ENV_FILE" >&2
        exit 1
    fi
done

JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
JAR="$JAVA_HOME/bin/jar"
KEYTOOL="$JAVA_HOME/bin/keytool"

for tool in "$JAVAC" "$JAVA" "$JAR" "$KEYTOOL" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
    if [[ ! -x "$tool" ]]; then
        printf 'Required tool is missing or not executable: %s\n' "$tool" >&2
        exit 1
    fi
done
if [[ ! -f "$ANDROID_PLATFORM_JAR" ]]; then
    printf 'Android platform jar does not exist: %s\n' "$ANDROID_PLATFORM_JAR" >&2
    exit 1
fi
if [[ ! -f "$BLOCK_CONFIG" ]]; then
    printf 'Missing %s. Copy .block-config.example.json and configure private defaults.\n' \
        "$BLOCK_CONFIG" >&2
    exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf -- "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled" "$BUILD_DIR/generated" "$BUILD_DIR/classes" \
    "$BUILD_DIR/dex" "$BUILD_DIR/config-res/raw"
cp "$BLOCK_CONFIG" "$BUILD_DIR/config-res/raw/block_config.json"

"$AAPT2" compile \
    --dir "$RES_DIR" \
    -o "$BUILD_DIR/compiled/resources.zip"

"$AAPT2" compile \
    --dir "$BUILD_DIR/config-res" \
    -o "$BUILD_DIR/compiled/block-config.zip"

"$AAPT2" link \
    -o "$BUILD_DIR/unsigned.apk" \
    -I "$ANDROID_PLATFORM_JAR" \
    --manifest "$MANIFEST" \
    --java "$BUILD_DIR/generated" \
    --min-sdk-version 28 \
    --target-sdk-version 36 \
    --version-code 3 \
    --version-name 0.3.0 \
    --auto-add-overlay \
    "$BUILD_DIR/compiled/resources.zip" \
    "$BUILD_DIR/compiled/block-config.zip"

"$JAVAC" \
    --release 17 \
    -Xlint:deprecation \
    -classpath "$ANDROID_PLATFORM_JAR" \
    -d "$BUILD_DIR/classes" \
    "$BUILD_DIR/generated/$PACKAGE_PATH/R.java" \
    "$SOURCE_DIR"/*.java

"$JAR" cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$D8" \
    --lib "$ANDROID_PLATFORM_JAR" \
    --min-api 28 \
    --output "$BUILD_DIR/dex" \
    "$BUILD_DIR/classes.jar"
"$JAR" uf "$BUILD_DIR/unsigned.apk" -C "$BUILD_DIR/dex" classes.dex

"$ZIPALIGN" -f -p 4 \
    "$BUILD_DIR/unsigned.apk" \
    "$BUILD_DIR/aligned.apk"

KEYSTORE="$PROJECT_DIR/debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
    "$KEYTOOL" -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=ChickenBlocker Development,O=ChickenBlocker,C=US"
fi

OUTPUT_APK="$BUILD_DIR/chickenblocker-debug.apk"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUTPUT_APK" \
    "$BUILD_DIR/aligned.apk"
"$APKSIGNER" verify --verbose "$OUTPUT_APK"

printf 'Built %s\n' "$OUTPUT_APK"
