#!/usr/bin/env bash
# Builds Grove Vault without Gradle, using only open tools:
#   aapt2 (resources), javac (Java 8 bytecode), dx (dex), uber-apk-signer (signing).
# Android Studio users can instead open this folder and use the included Gradle files.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-$ROOT/../tools}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/ap/android-34/android.jar}"
AAPT2="${AAPT2:-$TOOLS/prebuilt/linux/aapt2_64}"
DX_JAR="${DX_JAR:-$(ls "$TOOLS"/d2j/*/lib/dx-*.jar 2>/dev/null | head -1)}"
SIGNER="${SIGNER:-$TOOLS/uber.jar}"
KEYSTORE="${KEYSTORE:-$ROOT/release.keystore}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"

SRC="$ROOT/app/src/main"
OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/signed"

# The Gradle-style manifest has no package attribute (namespace lives in build.gradle.kts); add it for aapt2.
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.grove.vault">#' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "> aapt2 compile"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
echo "> aapt2 link"
"$AAPT2" link -A app/src/main/assets -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o "$OUT/unsigned.apk" "$OUT/res.zip"

echo "> javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "> dx"
java -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -qj unsigned.apk classes.dex)

echo "> sign"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass grove-local -keypass grove-local -alias grove \
    -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Grove Vault, O=Grove, C=MY" >/dev/null 2>&1
fi
java -jar "$SIGNER" -a "$OUT/unsigned.apk" -o "$OUT/signed" --ks "$KEYSTORE" --ksAlias grove \
  --ksPass grove-local --ksKeyPass grove-local --allowResign >/dev/null
cp "$OUT"/signed/*.apk "$OUT/GroveVault-$VERSION_NAME.apk"
echo "Built $OUT/GroveVault-$VERSION_NAME.apk"
