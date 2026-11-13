#!/usr/bin/env bash
# Builds the portable Windows version of Grove Vault on Linux:
#   1. compiles core + desktop sources into GroveVault.jar
#   2. jlinks a small Windows Java runtime from a Windows Temurin JDK (same version as the Linux jlink)
#   3. cross-compiles the native GroveVault.exe launcher with zig (pip install ziglang)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-$ROOT/../tools}"
JDK="${JDK:-$TOOLS/jdk/jdk-21.0.8+9}"                 # Linux Temurin 21.0.8+9
WINJDK="${WINJDK:-$TOOLS/jdk/win/jdk-21.0.8+9}"       # Windows Temurin 21.0.8+9 (for jmods)
VERSION="${VERSION_NAME:-1.3.0}"
OUT="$ROOT/build/windows"
PKG="$OUT/GroveVault"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$PKG/app"

"$JDK/bin/javac" -nowarn --release 17 -encoding UTF-8 -d "$OUT/classes" \
  $(find "$ROOT/app/src/main/java/org/grove/vault/core" -name '*.java') $(find "$ROOT/desktop/src" -name '*.java')
printf 'Main-Class: org.grove.desktop.GroveDesktop\nImplementation-Title: Grove Vault\nImplementation-Version: %s\n' "$VERSION" > "$OUT/manifest.txt"
"$JDK/bin/jar" cfm "$PKG/app/GroveVault.jar" "$OUT/manifest.txt" -C "$OUT/classes" .

"$JDK/bin/jlink" --module-path "$WINJDK/jmods" \
  --add-modules java.base,java.desktop,java.xml,java.datatransfer,jdk.crypto.ec,jdk.localedata --include-locales=en,ms \
  --strip-debug --no-header-files --no-man-pages --compress=zip-9 --output "$PKG/runtime"

(cd "$ROOT/desktop/windows" && python3 -m ziglang cc -target x86_64-windows-gnu -O2 -municode -Wl,--subsystem,windows \
  launcher.c launcher.rc -o "$PKG/GroveVault.exe")
(cd "$ROOT/desktop/windows" && python3 -m ziglang cc -target x86_64-windows-gnu -O2 helper.c -lsetupapi -lhid -lcrypt32 -o "$PKG/GroveHelper.exe")
cp "$ROOT/desktop/windows/README.txt" "$ROOT/LICENSE" "$PKG/"
(cd "$OUT" && rm -f "GroveVault-$VERSION-windows-x64.zip" && zip -qr9 "GroveVault-$VERSION-windows-x64.zip" GroveVault)
echo "Built $OUT/GroveVault-$VERSION-windows-x64.zip"
