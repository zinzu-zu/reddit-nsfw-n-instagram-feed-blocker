#!/bin/bash
# Installs a newer signed version of the add-on on a Mac that's already locked.
# Put the new signed file next to README.md as reddit-nsfw-blocker.xpi, then:
#   sudo bash update.sh
# Firefox notices the newer version in the locked folder and swaps it in on
# its next start. Needs the admin password, like any change to the lock.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$(dirname "$HERE")/reddit-nsfw-blocker.xpi"
LIB="/Library/Application Support/RedditNSFWBlocker"

if [[ $EUID -ne 0 ]]; then
  echo "Run this with sudo:  sudo bash \"$0\""; exit 1
fi
if [[ ! -f "$SRC" ]]; then
  echo "Put the new signed reddit-nsfw-blocker.xpi next to README.md first."; exit 1
fi
if [[ ! -d "$LIB" ]]; then
  echo "The lock isn't installed yet. Run lockdown.sh instead."; exit 1
fi

cp "$SRC" "$LIB/reddit-nsfw-blocker.xpi"
chown root:wheel "$LIB/reddit-nsfw-blocker.xpi"
chmod 644 "$LIB/reddit-nsfw-blocker.xpi"
echo "Updated. Quit Firefox with Cmd+Q and open it again."
