#!/usr/bin/env bash
# Runs the off-phone tests: address bar reading, marker, cover decision, app rules,
# colour rules, the accessibility services list and signature pins.
# Needs only a JDK (8 or newer).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
SRC="$HERE/src/io/github/zinzuzu/feedguard"
javac -d "$OUT" \
  "$SRC/UrlText.java" "$SRC/MarkerText.java" "$SRC/CoverDecision.java" "$SRC/AppRules.java" \
  "$SRC/ColorRules.java" "$SRC/ServiceList.java" "$SRC/Pins.java" \
  "$HERE/test/io/github/zinzuzu/feedguard/LogicTest.java"
java -cp "$OUT" io.github.zinzuzu.feedguard.LogicTest
