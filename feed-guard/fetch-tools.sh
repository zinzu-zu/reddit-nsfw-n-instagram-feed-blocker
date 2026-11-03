#!/usr/bin/env bash
# Downloads what build.sh needs that a plain Linux box doesn't have:
# - android.jar for Android 16 (API 36), from github.com/Reginer/aosp-android-jar
# - D8 (Google's dex compiler), from Google's R8 releases
# aapt, zipalign and apksigner come from the distribution:
#   sudo apt-get install aapt zipalign apksigner android-sdk-platform-23
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$HERE/.tools"
mkdir -p "$TOOLS"
[ -f "$TOOLS/android.jar" ] || curl -fL -o "$TOOLS/android.jar" \
  https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-36/android.jar
[ -f "$TOOLS/r8lib.jar" ] || curl -fL -o "$TOOLS/r8lib.jar" \
  https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar
ls -l "$TOOLS"
