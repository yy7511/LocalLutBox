#!/usr/bin/env bash
# Builds the LocalLutBox APK with plain SDK tools (no Gradle / AGP).
# Pipeline: aapt2 compile/link -> javac -> R8 (shrink + obfuscate) ->
#           aapt2 optimize (resource name collapse) -> zip dex -> zipalign -> apksigner
set -euo pipefail

SDK=${SDK:-/opt/android-sdk}
BT="${BT:-$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)}"
PLATFORM="$SDK/platforms/android-34/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build"
GEN="$OUT/gen"
CLS="$OUT/classes"
DEX="$OUT/dex"
APK_LINKED="$OUT/app-linked.apk"
APK_OPT="$OUT/app-opt.apk"
APK_ALIGNED="$OUT/app-aligned.apk"
APK_FINAL="$ROOT/LocalLutBox-1.1.0.apk"
VERSION_CODE=11
VERSION_NAME=1.1.0
# Signing key lives outside the repository on purpose (public repo).
KS=${KS:-$HOME/.lutbox/locallutbox.keystore}
KS_PASS=${KS_PASS:-lutroom}
KEY_ALIAS=${KEY_ALIAS:-locallutbox}

rm -rf "$OUT"
mkdir -p "$GEN" "$CLS" "$DEX"

echo "[1/8] aapt2 compile"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[2/8] aapt2 link"
"$BT/aapt2" link \
  -o "$APK_LINKED" \
  -I "$PLATFORM" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$GEN" \
  --min-sdk-version 24 \
  --target-sdk-version 33 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[3/8] javac"
find "$SRC/java" "$GEN" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -Xlint:-options -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$CLS" \
  @"$OUT/sources.txt"

echo "[4/8] R8 (shrink, optimize, obfuscate)"
find "$CLS" -name '*.class' > "$OUT/classes.txt"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 \
  --release \
  --min-api 24 \
  --lib "$PLATFORM" \
  --pg-conf "$ROOT/tools/keep.pro" \
  --pg-map-output "$OUT/mapping.txt" \
  --output "$DEX" \
  @"$OUT/classes.txt"

echo "[5/8] aapt2 optimize (resource name obfuscation)"
"$BT/aapt2" optimize \
  --collapse-resource-names \
  --shorten-resource-paths \
  --enable-sparse-encoding \
  --save-obfuscation-map "$OUT/res-map.txt" \
  -o "$APK_OPT" "$APK_LINKED"

echo "[6/8] package dex"
python3 - "$APK_OPT" "$DEX/classes.dex" "$OUT/app-packaged.apk" <<'PY'
import sys, zipfile
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, 'classes.dex')
PY

echo "[7/8] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/app-packaged.apk" "$APK_ALIGNED"

echo "[8/8] sign"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=LocalLutBox, OU=Mobile, O=yy7511, L=Shanghai, ST=Shanghai, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK_FINAL" "$APK_ALIGNED"
"$BT/apksigner" verify --print-certs "$APK_FINAL" | head -4

ls -lh "$APK_FINAL"
echo "OK: $APK_FINAL"

echo "[publish] GitHub public repo"
"$ROOT/tools/publish.sh" || echo "publish: skipped (not logged in or push failed)"
