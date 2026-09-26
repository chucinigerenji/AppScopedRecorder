#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# 构建验证器 APK（com.dsh.mediaprobe）
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
T="$HOME/apkbuild/tools"
OUT="$ROOT/out"
AJ="$T/android.jar"
R8="$T/r8.jar"
KS="$T/apprecorder.keystore"
KSPASS="android"
ALIAS="apprecorder"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

aapt2 link -o "$OUT/base.apk" -I "$AJ" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 29 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0

find "$ROOT/java" "$OUT/gen" -name '*.java' -print0 > "$OUT/src0"
xargs -0 javac -encoding UTF-8 -nowarn -source 8 -target 8 -cp "$AJ" \
  -d "$OUT/classes" < "$OUT/src0"

find "$OUT/classes" -name '*.class' -print0 > "$OUT/cls0"
xargs -0 java -cp "$R8" com.android.tools.r8.D8 --release --min-api 29 --lib "$AJ" \
  --output "$OUT/dex" < "$OUT/cls0"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" *.dex )
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$ROOT/mediaprobe.apk" "$OUT/aligned.apk"
ls -la "$ROOT/mediaprobe.apk"
