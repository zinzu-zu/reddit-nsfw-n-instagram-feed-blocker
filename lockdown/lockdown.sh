#!/bin/bash
# Reddit NSFW Blocker lock-down for macOS.
# Run from Terminal:  sudo bash lockdown.sh
# Builds one configuration profile that force-installs the extension in
# Firefox (and Edge, if published there), blocks Reddit in the other Chromium
# browsers, and turns off private windows and extra profiles. DNS settings
# are left alone.
# A system profile can only be removed with an administrator password.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
LIB="/Library/Application Support/RedditNSFWBlocker"
OUT="$HERE/RedditNSFWBlocker.mobileconfig"
FF_ID="reddit-nsfw-blocker@self-hosted"

if [[ $EUID -ne 0 && -z "${DRY_RUN:-}" ]]; then
  echo "Run this with sudo:  sudo bash \"$0\""; exit 1
fi

# --- Firefox: listed add-on on addons.mozilla.org, or a self-signed .xpi ---
read -r -p "Firefox add-on slug on addons.mozilla.org (Enter to use reddit-nsfw-blocker.xpi from this folder): " SLUG
if [[ -n "$SLUG" && ! "$SLUG" =~ ^[A-Za-z0-9_-]{1,100}$ ]]; then
  echo "That doesn't look like an add-on name (letters, numbers, - and _ only)."; exit 1
fi
if [[ -n "$SLUG" ]]; then
  FF_URL="https://addons.mozilla.org/firefox/downloads/latest/$SLUG/latest.xpi"
elif [[ -f "$ROOT/reddit-nsfw-blocker.xpi" ]]; then
  if [[ -z "${DRY_RUN:-}" ]]; then
    mkdir -p "$LIB"; cp "$ROOT/reddit-nsfw-blocker.xpi" "$LIB/"; chown -R root:wheel "$LIB"; chmod -R go-w "$LIB"
  fi
  FF_URL="file:///Library/Application%20Support/RedditNSFWBlocker/reddit-nsfw-blocker.xpi"
else
  echo "No slug and no reddit-nsfw-blocker.xpi next to README.md. See step 1 in the README."; exit 1
fi

# --- Chromium browsers ---
# Chrome, Brave and Chromium only force-install extensions from the paid Chrome
# Web Store on a Mac that isn't MDM-managed, so they get Reddit blocked outright
# instead. Edge can force-install from Microsoft's free Edge Add-ons store.
read -r -p "Edge Add-ons extension ID (Enter to skip and block Reddit in Edge too): " EDGE_ID
read -r -p "Also block Instagram in the Chromium browsers (Chrome, Brave, and Edge unless it has the extension), so its feed can only be opened in Firefox? [Y/n]: " IG
IG_ENTRY="<string>instagram.com</string>"
[[ "$IG" =~ ^[Nn] ]] && IG_ENTRY=""
BLOCK_REDDIT="<key>URLBlocklist</key><array><string>reddit.com</string><string>redd.it</string><string>redditmedia.com</string>$IG_ENTRY</array>"
if [[ -n "$EDGE_ID" && ! "$EDGE_ID" =~ ^[a-p]{32}$ ]]; then
  echo "That doesn't look like an extension ID (32 letters a-p)."; exit 1
fi
if [[ -n "$EDGE_ID" ]]; then
  EDGE_EXTRA="<key>ExtensionInstallForcelist</key><array><string>$EDGE_ID;https://edge.microsoft.com/extensionwebstorebase/v1/crx</string></array>"
else
  EDGE_EXTRA="$BLOCK_REDDIT"
fi

u() { uuidgen; }

chromium_payload() { # domain, private-key, add-profile-key, extra
  cat <<P
    <dict>
      <key>PayloadType</key><string>$1</string>
      <key>PayloadIdentifier</key><string>local.redditnsfwblocker.$1</string>
      <key>PayloadUUID</key><string>$(u)</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>$2</key><integer>1</integer>
      <key>BrowserGuestModeEnabled</key><false/>
      <key>$3</key><false/>
      $4
    </dict>
P
}

cat > "$OUT" <<EOF2
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>PayloadDisplayName</key><string>Reddit NSFW Blocker</string>
  <key>PayloadDescription</key><string>Keeps the Reddit NSFW Blocker installed in every browser.</string>
  <key>PayloadIdentifier</key><string>local.redditnsfwblocker</string>
  <key>PayloadUUID</key><string>$(u)</string>
  <key>PayloadType</key><string>Configuration</string>
  <key>PayloadScope</key><string>System</string>
  <key>PayloadVersion</key><integer>1</integer>
  <key>PayloadContent</key>
  <array>
    <dict>
      <key>PayloadType</key><string>org.mozilla.firefox</string>
      <key>PayloadIdentifier</key><string>local.redditnsfwblocker.firefox</string>
      <key>PayloadUUID</key><string>$(u)</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>EnterprisePoliciesEnabled</key><true/>
      <key>ExtensionSettings</key>
      <dict>
        <key>$FF_ID</key>
        <dict>
          <key>installation_mode</key><string>force_installed</string>
          <key>install_url</key><string>$FF_URL</string>
          <key>private_browsing</key><true/>
        </dict>
      </dict>
      <key>DisablePrivateBrowsing</key><true/>
      <key>DisableSafeMode</key><true/>
      <key>BlockAboutProfiles</key><true/>
      <key>BlockAboutConfig</key><true/>
    </dict>
$(chromium_payload com.google.Chrome IncognitoModeAvailability BrowserAddPersonEnabled "$BLOCK_REDDIT")
$(chromium_payload org.chromium.Chromium IncognitoModeAvailability BrowserAddPersonEnabled "$BLOCK_REDDIT")
$(chromium_payload com.brave.Browser IncognitoModeAvailability BrowserAddPersonEnabled "<key>TorDisabled</key><true/>$BLOCK_REDDIT")
$(chromium_payload com.microsoft.Edge InPrivateModeAvailability BrowserAddProfileEnabled "$EDGE_EXTRA")
  </array>
</dict>
</plist>
EOF2

if command -v plutil >/dev/null; then plutil -lint "$OUT"; fi
echo
echo "Profile written to $OUT"
echo "Opening it now. Then go to System Settings > General > Device Management"
echo "(or Privacy & Security > Profiles), click Reddit NSFW Blocker, and Install."
if [[ -z "${DRY_RUN:-}" ]]; then open "$OUT"; fi
