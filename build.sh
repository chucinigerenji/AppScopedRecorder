#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# 无 Gradle 构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 纯框架 API，无任何第三方依赖（不需要 AndroidX / Shizuku）。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
T="$HOME/apkbuild/tools"
OUT="$ROOT/out"
AJ="$T/android.jar"
R8="$T/r8.jar"

KS="$T/apprecorder.keystore"
KSPASS="android"
ALIAS="apprecorder"

APPNAME="应用定向录屏"
VERSION="v1.1"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 1/6 编译资源 (aapt2 compile)"
aapt2 compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "==> 2/6 链接资源与清单 (aapt2 link)"
aapt2 link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 2 \
  --version-name 1.1 \
  "$OUT/res.zip"

echo "==> 3/6 编译 Java (javac)"
find "$ROOT/java" "$OUT/gen" -name '*.java' -print0 > "$OUT/src0"
# 注意：不能用 -bootclasspath android.jar —— SDK stub 的 LambdaMetafactory 缺少
# metafactory 方法，会让所有 lambda 编译失败。用 -cp 提供 android.*，java.* 走 JDK，
# D8 会在 --lib android.jar 下正确脱糖。
xargs -0 javac -encoding UTF-8 -nowarn -source 8 -target 8 \
  -cp "$AJ" \
  -d "$OUT/classes" < "$OUT/src0" || { echo "!! javac 失败"; exit 1; }

if [ ! -f "$OUT/classes/com/dsh/apprecorder/MainActivity.class" ]; then
  echo "!! javac 没有产出 class"; exit 1
fi

echo "==> 4/6 生成 dex (d8)"
find "$OUT/classes" -name '*.class' -print0 > "$OUT/cls0"
xargs -0 java -cp "$R8" com.android.tools.r8.D8 \
  --release --min-api 29 \
  --lib "$AJ" \
  --output "$OUT/dex" < "$OUT/cls0" || { echo "!! d8 失败"; exit 1; }

echo "      dex: $(ls "$OUT/dex" | tr '\n' ' ')"
du -sh "$OUT/dex"

echo "==> 5/6 打包 + 对齐"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" *.dex )
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/6 签名 (apksigner)"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 12000 \
    -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=AppRecorder, OU=SelfMade, O=DSH, L=CN, C=CN" >/dev/null 2>&1
fi

APK="$ROOT/${APPNAME}-${VERSION}.apk"
[ -f "$APK" ] && rm -f "$APK"
apksigner sign \
  --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"

apksigner verify --print-certs "$APK" | head -3
echo
echo "==> 完成: $APK"
ls -la "$APK"
