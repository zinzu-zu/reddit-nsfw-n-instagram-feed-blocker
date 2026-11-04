#!/bin/bash
# Installs owndroid-key as a root service. An administrator runs it once:
#   sudo bash install.sh                 builds tle with Go (brew install go first)
#   sudo bash install.sh ~/go/bin/tle    or uses a tle you built yourself
# Running it again updates the script and keeps the sealed key.
set -eu
LABEL=io.github.zinzuzu.owndroid-key
DEST="${OWNDROID_KEY_DEST:-}"     # only for trying the installer out in a scratch folder
ROOT="$DEST/Library/Application Support/OwnDroidKey"
HERE="$(cd "$(dirname "$0")" && pwd)"

[ "$(id -u)" -eq 0 ] || { echo "This needs an administrator: sudo bash install.sh" >&2; exit 1; }

mkdir -p "$ROOT/bin" "$ROOT/sealed"
chown root:wheel "$ROOT" "$ROOT/bin" "$ROOT/sealed"
chmod 755 "$ROOT" "$ROOT/bin"
chmod 700 "$ROOT/sealed"

# tle goes in a folder only root can change, so the service never runs
# anything your account could swap out.
if [ $# -ge 1 ]; then
  [ -x "$1" ] || { echo "No tle at $1" >&2; exit 1; }
  install -o root -g wheel -m 755 "$1" "$ROOT/bin/tle"
elif [ ! -x "$ROOT/bin/tle" ]; then
  GO=""
  for g in "$(command -v go 2>/dev/null || true)" /opt/homebrew/bin/go /usr/local/bin/go /usr/local/go/bin/go; do
    if [ -n "$g" ] && [ -x "$g" ]; then GO=$g; break; fi
  done
  if [ -z "$GO" ]; then
    echo "Go is missing. Install it with: brew install go" >&2
    echo "(or build tle yourself and pass its path: sudo bash install.sh ~/go/bin/tle)" >&2
    exit 1
  fi
  # The version owndroid-key was tested with: it reads tle's error messages.
  echo "Building tle..."
  BUILD=$(mktemp -d)
  env HOME="$BUILD" GOPATH="$BUILD/go" GOCACHE="$BUILD/cache" GOFLAGS=-modcacherw GOBIN="$ROOT/bin" \
    "$GO" install github.com/drand/tlock/cmd/tle@v1.2.0
  rm -rf "$BUILD"
  chown root:wheel "$ROOT/bin/tle"
  chmod 755 "$ROOT/bin/tle"
fi

install -o root -g wheel -m 755 "$HERE/owndroid-key" "$ROOT/owndroid-key"
tmutil addexclusion "$ROOT/sealed" 2>/dev/null || true
mkdir -p "$DEST/Library/Logs" "$DEST/Library/LaunchDaemons"
touch "$DEST/Library/Logs/owndroid-key.log"
chmod 644 "$DEST/Library/Logs/owndroid-key.log"

# One daemon seals the key again after each window, the other answers your
# account on its socket. Right after a bootout launchd can still be busy, so
# loading retries once.
for label in "$LABEL" "$LABEL.service"; do
  install -o root -g wheel -m 644 "$HERE/$label.plist" "$DEST/Library/LaunchDaemons/$label.plist"
  launchctl bootout "system/$label" 2>/dev/null || true
  launchctl bootstrap system "$DEST/Library/LaunchDaemons/$label.plist" 2>/dev/null \
    || { sleep 2; launchctl bootstrap system "$DEST/Library/LaunchDaemons/$label.plist"; }
done

mkdir -p "$DEST/usr/local/bin"
ln -sf "$ROOT/owndroid-key" "$DEST/usr/local/bin/owndroid-key"

echo "Installed. From your own account, run:  owndroid-key status"
ME=$(stat -f %Su /dev/console 2>/dev/null || true)
if [ -n "$ME" ] && [ "$ME" != root ] && id -Gn "$ME" 2>/dev/null | tr ' ' '\n' | grep -qx admin; then
  echo "Note: $ME is an administrator, so with sudo it can still copy the sealed key."
  echo "The key is only out of reach from a Standard account."
fi
