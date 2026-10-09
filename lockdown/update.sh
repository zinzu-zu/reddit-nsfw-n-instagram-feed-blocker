#!/bin/bash
# Installs a newer signed version of the add-on on a Mac that's already locked.
#   sudo bash update.sh /path/to/signed.xpi
# Tip: type "sudo bash ", drag update.sh into Terminal, type a space, then drag
# the signed .xpi in too. Without a path, it uses reddit-nsfw-blocker.xpi next
# to README.md, or the only .xpi there if it has another name.
# Firefox notices the newer version in the locked folder and swaps it in on
# its next start. Needs the admin password, like any change to the lock.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
LIB="/Library/Application Support/RedditNSFWBlocker"

if [[ $EUID -ne 0 ]]; then
  echo "Run this with sudo:  sudo bash \"$0\" /path/to/signed.xpi"; exit 1
fi
if [[ ! -d "$LIB" ]]; then
  echo "The lock isn't installed yet. Run lockdown.sh instead."; exit 1
fi

SRC="${1:-}"
if [[ -z "$SRC" ]]; then
  if [[ -f "$ROOT/reddit-nsfw-blocker.xpi" ]]; then
    SRC="$ROOT/reddit-nsfw-blocker.xpi"
  else
    shopt -s nullglob
    found=("$ROOT"/*.xpi "$ROOT"/*.xpi.zip)
    if [[ ${#found[@]} -eq 1 ]]; then
      SRC="${found[0]}"
    else
      echo "Couldn't find the signed add-on next to README.md (${#found[@]} .xpi files there)."
      echo "Pass it directly:  sudo bash \"$0\" /path/to/signed.xpi"
      echo "Files in $ROOT:"; ls -1 "$ROOT"
      exit 1
    fi
  fi
fi
if [[ ! -f "$SRC" ]]; then
  echo "No file at: $SRC"; exit 1
fi

# A signed add-on carries Mozilla's signature files; an unsigned zip doesn't.
if ! unzip -l "$SRC" 2>/dev/null | grep -q "META-INF/"; then
  echo "This file isn't signed by Mozilla: $SRC"
  echo "Download the signed .xpi from your add-on's version page instead."; exit 1
fi
VERSION="$(unzip -p "$SRC" manifest.json 2>/dev/null | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' | head -n1)"

cp "$SRC" "$LIB/reddit-nsfw-blocker.xpi"
chown root:wheel "$LIB/reddit-nsfw-blocker.xpi"
chmod 644 "$LIB/reddit-nsfw-blocker.xpi"
echo "Installed version ${VERSION:-unknown} from $SRC"
echo "Quit Firefox with Cmd+Q and open it again."
