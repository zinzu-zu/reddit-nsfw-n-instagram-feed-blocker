#!/usr/bin/env bash
# Builds Watch Limit into a signed APK, without Android Studio or Gradle.
#
# Needs a JDK (17 or newer), curl, unzip and zip, on Linux or macOS. The first run
# downloads the build tools into .tools/ and checks each one against a pinned SHA-256.
#
# Signing: set KEYSTORE to a PKCS12 file and KEYSTORE_PASS to its password (KEY_ALIAS
# defaults to "watchlimit"). Android only installs an update over the app if it's signed
# with the same key, so keep using the key the installed copy was signed with. Without
# KEYSTORE, the script makes a new key in .tools/, which only suits a first install.
#
# To compile against Google's own SDK instead of the downloaded android.jar, set
# ANDROID_JAR to <sdk>/platforms/android-36/android.jar.
#
# Output: build/WatchLimit-<version>.apk
set -euo pipefail

cd "$(dirname "$0")"
TOOLS="$PWD/.tools"
OUT="$PWD/build"
MIN_SDK=29
MAVEN=https://repo1.maven.org/maven2

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# fetch URL SHA256 DEST
fetch() {
  if [ -f "$3" ] && [ "$(sha256 "$3")" = "$2" ]; then return; fi
  echo "Downloading $(basename "$3")..."
  curl -fsSL --retry 3 -o "$3.part" "$1"
  local got
  got=$(sha256 "$3.part")
  if [ "$got" != "$2" ]; then
    rm -f "$3.part"
    echo "Checksum mismatch for $1 (got $got)." >&2
    exit 1
  fi
  mv "$3.part" "$3"
}

mkdir -p "$TOOLS"
fetch "$MAVEN/org/apktool/apktool-lib/3.0.3/apktool-lib-3.0.3.jar" \
  983773879fd89ede2cd938858e3efce2a90ac1123f6a5140e9d949dcf4464e3e "$TOOLS/apktool-lib-3.0.3.jar"
fetch "$MAVEN/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar" \
  9637078c0016244e4be0941836295365a7e2e5b164c59cb7885783c40460bfee "$TOOLS/apksig-2.3.0.jar"
fetch "$MAVEN/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar" \
  1e4b645628e3bdb097b5331d669e177ef235a551582a8c646dbe36865e541907 "$TOOLS/dalvik-dx-16.0.1.jar"
if [ -z "${ANDROID_JAR:-}" ]; then
  # Android 16 (API 36) android.jar, including resources, from github.com/Reginer/aosp-android-jar.
  fetch "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-36/android.jar" \
    231e29e345e85c7107bde3e08ece052c067ebbe9d72256f036b9a126e4dde0f6 "$TOOLS/android-36.jar"
  ANDROID_JAR="$TOOLS/android-36.jar"
fi

# aapt2 comes from inside the apktool library jar.
case "$(uname -s)" in
  Linux) AAPT2_IN_JAR=prebuilt/linux/aapt2 ;;
  Darwin) AAPT2_IN_JAR=prebuilt/macosx/aapt2 ;;
  *) echo "Only Linux and macOS are supported." >&2; exit 1 ;;
esac
AAPT2="$TOOLS/aapt2-$(uname -s)"
if [ ! -x "$AAPT2" ]; then
  unzip -p "$TOOLS/apktool-lib-3.0.3.jar" "$AAPT2_IN_JAR" > "$AAPT2"
  chmod +x "$AAPT2"
fi

VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' src/main/AndroidManifest.xml | head -n 1)
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/test-classes" "$OUT/tools"

echo "Running the unit tests..."
javac -d "$OUT/test-classes" \
  $(find src/main/java/io/github/zinzuzu/watchlimit/core src/test/java -name '*.java')
java -cp "$OUT/test-classes" io.github.zinzuzu.watchlimit.core.CoreTest

echo "Compiling resources..."
"$AAPT2" compile --dir src/main/res -o "$OUT/resources.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest src/main/AndroidManifest.xml \
  --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/resources.zip"

echo "Compiling the app..."
# Java 8 bytecode, because that's what dx converts.
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -Xlint:-options -encoding UTF-8 \
  -d "$OUT/classes" $(find src/main/java "$OUT/gen" -name '*.java')
java -cp "$TOOLS/dalvik-dx-16.0.1.jar" com.android.dx.command.Main --dex \
  --min-sdk-version=$MIN_SDK --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -X unsigned.apk classes.dex)

echo "Signing..."
if [ -z "${KEYSTORE:-}" ]; then
  KEYSTORE="$TOOLS/local-key.p12"
  KEYSTORE_PASS="${KEYSTORE_PASS:-watchlimit}"
  if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$KEYSTORE_PASS" \
      -alias watchlimit -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Watch Limit"
  fi
  echo "No KEYSTORE given, so this build is signed with $KEYSTORE."
fi
: "${KEYSTORE_PASS:?Set KEYSTORE_PASS to the password of $KEYSTORE}"
export KEYSTORE_PASS
javac -cp "$TOOLS/apksig-2.3.0.jar" -d "$OUT/tools" tools/SignApk.java
# This apksig version predates Java modules and uses JDK-internal certificate classes.
java --add-exports java.base/sun.security.x509=ALL-UNNAMED \
  --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
  --add-exports java.base/sun.security.util=ALL-UNNAMED \
  -cp "$TOOLS/apksig-2.3.0.jar:$OUT/tools" SignApk \
  "$KEYSTORE" "${KEY_ALIAS:-watchlimit}" "$OUT/unsigned.apk" "$OUT/WatchLimit-$VERSION.apk"
