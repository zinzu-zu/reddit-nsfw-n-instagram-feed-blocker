#!/usr/bin/env bash
# Builds and signs Feed Guard without Gradle.
#   KEYSTORE=/path/feed-guard.p12 KS_PASS=... bash build.sh
# Tools: aapt, zipalign and apksigner (apt), a JDK, and the two downloads in
# fetch-tools.sh. Output: build/feed-guard.apk
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$HERE/.tools/android.jar}"
# aapt (v1) can't read newer android.jar resource tables, so resources link
# against Android 6's. Everything the manifest and resources use exists there.
AAPT_JAR="${AAPT_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
D8_JAR="${D8_JAR:-$HERE/.tools/r8lib.jar}"
: "${KEYSTORE:?Set KEYSTORE to the signing keystore (.p12)}"
: "${KS_PASS:?Set KS_PASS to the keystore password}"
export KS_PASS
OUT="$HERE/build"
MIN_SDK=26
TARGET_SDK=35

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 1. Resources and manifest -> resources.arsc + binary manifest, and R.java.
aapt package -f -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$AAPT_JAR" \
  -J "$OUT/gen" -F "$OUT/unaligned.apk" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK"

# 2. Java -> class files (Java 8 language level; D8 handles lambdas).
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

# 3. Class files -> classes.dex
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"

# 4. Add the dex, align, sign, verify.
(cd "$OUT/dex" && aapt add "$OUT/unaligned.apk" classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass env:KS_PASS --out "$OUT/feed-guard.apk" "$OUT/aligned.apk"
apksigner verify --verbose --print-certs "$OUT/feed-guard.apk" | grep -E "Verified using|Signer #1 certificate SHA-256" || true
echo "Built $OUT/feed-guard.apk"
