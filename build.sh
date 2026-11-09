#!/bin/bash
# 无 Gradle 打包：javac -> d8 -> aapt -> zipalign -> apksigner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK_JAR="${SDK_JAR:-$ROOT/tools/platform36/android-36/android.jar}"
R8_JAR="${R8_JAR:-$ROOT/tools/r8.jar}"
AAPT="${AAPT:-aapt}"
OUT="$ROOT/build"
SRC="$ROOT/src/main"
KS="$ROOT/keystore.jks"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 生成 R.java"
"$AAPT" package -f -m -J "$OUT/gen" -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -I "$SDK_JAR"

echo "== javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -source 8 -target 8 -classpath "$SDK_JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -v -E 'bootstrap class path|^警告|^Note:' || true
test -f "$OUT/classes/com/dsh/dydl/MainActivity.class"

echo "== d8 -> classes.dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 21 --lib "$SDK_JAR" \
  --output "$OUT/dex" @"$OUT/classes.txt"
ls -l "$OUT/dex/classes.dex"

echo "== aapt 打包资源与清单"
"$AAPT" package -f -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -I "$SDK_JAR" -F "$OUT/unsigned.apk"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
( cd "$OUT" && "$AAPT" add unsigned.apk classes.dex )

echo "== zipalign"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
  echo "== 生成签名密钥"
  keytool -genkeypair -keystore "$KS" -alias dsh -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass dshdydl -keypass dshdydl -dname "CN=DSH Douyin Downloader, O=DSH" >/dev/null 2>&1
fi

echo "== apksigner"
apksigner sign --ks "$KS" --ks-pass pass:dshdydl --key-pass pass:dshdydl \
  --out "$OUT/douyin-dl.apk" "$OUT/aligned.apk"
apksigner verify --print-certs "$OUT/douyin-dl.apk" | head -3
ls -lh "$OUT/douyin-dl.apk"
echo "OK -> $OUT/douyin-dl.apk"
