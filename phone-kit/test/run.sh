#!/bin/bash
# Tests for phone-kit, with no phone, network or Mac: fake-adb plays the phone
# (Android, OwnDroid 8.4 and Feed Guard), and taps plays the person tapping on
# it wherever the kit waits. curl and owndroid-key are fakes too.
#   bash test/run.sh
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
KIT="$HERE/phone-kit"
T="$HERE/test"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export FAKE_PHONE="$WORK/phone"
export PHONE_KIT_ADB="$T/fake-adb"
export PHONE_KIT_CONF="$WORK/my-phone.conf"
export PHONE_KIT_APKS="$WORK/apks"
export PHONE_KIT_STATE="$WORK/state"
export PHONE_KIT_NAP=0
export PHONE_KIT_TEST_ON_PAUSE="$T/taps"
unset ANDROID_SERIAL PHONE_KIT_TEST_ONCE
export FAKE_LOG="$WORK/log"
BIN="$WORK/bin"
mkdir -p "$BIN" "$FAKE_LOG"

# GitHub's release API for OwnDroid v8.4, and the download.
cat > "$BIN/curl" <<'EOF'
#!/bin/bash
out="" url=""
while [ $# -gt 0 ]; do
  case "$1" in -o) out=$2; shift 2 ;; --max-time) shift 2 ;; -*) shift ;; *) url=$1; shift ;; esac
done
echo "$url" >> "$FAKE_LOG/curl"
[ -z "${FAKE_OFFLINE:-}" ] || exit 6
base=https://github.com/BinTianqi/OwnDroid/releases/download/v8.4
case "$url" in
  https://api.github.com/repos/BinTianqi/OwnDroid/releases/tags/v8.4)
    cat <<JSON
{
  "tag_name": "v8.4",
  "assets": [
    { "name": "OwnDroid-v8.4.apk", "browser_download_url": "$base/OwnDroid-v8.4.apk" },
    { "name": "OwnDroid-v8.4-testkey.apk", "browser_download_url": "$base/OwnDroid-v8.4-testkey.apk" }
  ]
}
JSON
    ;;
  "$base/OwnDroid-v8.4.apk") printf 'package=com.bintianqi.owndroid\nversion=8.4\ncode=40\nflags=launcher\n' > "$out" ;;
  https://dl.google.com/android/repository/platform-tools-latest-darwin.zip)
    # Android's platform tools: an adb that is the pretend phone's.
    python3 - "$out" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1], "w")
i = zipfile.ZipInfo("platform-tools/adb")
i.external_attr = 0o755 << 16
z.writestr(i, '#!/bin/bash\nexec "$FAKE_ADB" "$@"\n')
z.close()
PY
    ;;
  *) exit 22 ;;
esac
EOF

# macOS's open: TextEdit, where the person fills in the settings file.
cat > "$BIN/open" <<'EOF'
#!/bin/bash
echo "$*" >> "$FAKE_LOG/open"
[ "$1" != -e ] || [ -z "${FAKE_TEXTEDIT:-}" ] || printf '%s\n' "$FAKE_TEXTEDIT" > "$2"
EOF
export FAKE_ADB="$T/fake-adb"

# owndroid-key: FAKE_KEY is none (no key yet), sealed, or broken.
mkdir -p "$WORK/keybin"
cat > "$WORK/keybin/owndroid-key" <<'EOF'
#!/bin/bash
echo "$*" >> "$FAKE_LOG/owndroid-key"
case "$1" in
  status)
    case "${FAKE_KEY:-sealed}" in
      none) echo "There's no key yet. Start with: owndroid-key seal --open \"YYYY-MM-DD HH:MM\" --every 30d" ;;
      sealed) echo "The key is sealed until Tue 1 Dec 2026 18:00."; echo "It opens for 24 hours once every 30 days." ;;
      broken) echo "owndroid-key: Can't reach the key service. Run sudo bash install.sh again." >&2; exit 1 ;;
    esac ;;
  seal) echo "owndroid-key: sealing ($*)"; [ -z "${FAKE_SEAL_FAILS:-}" ] || exit 1 ;;
esac
EOF
chmod +x "$BIN"/* "$WORK/keybin/owndroid-key"
export PATH="$BIN:$WORK/keybin:$PATH"
NO_KEY_PATH="$BIN:$(printf '%s' "${PATH#"$BIN:$WORK/keybin:"}")"

. "$T/phone-lib.sh"

passed=0 failed=0
check() {  # name, then a command that must succeed
  local name=$1; shift
  if "$@"; then passed=$((passed + 1)); else failed=$((failed + 1)); echo "FAIL: $name"; fi
}
OUT="$WORK/out"
not() { ! "$@"; }
said() { grep -qF -- "$1" "$OUT"; }
not_said() { ! grep -qF -- "$1" "$OUT"; }
said_n() { [ "$(grep -cF -- "$1" "$OUT")" -eq "$2" ]; }
is() { [ "$1" = "$2" ] || { echo "  got \"$1\", wanted \"$2\"" >&2; return 1; }; }
rc_is() { is "$RC" "$1"; }
tapped() { grep -qx -- "$1" "$P/taps.log" 2>/dev/null; }
not_tapped() { ! tapped "$1"; }
taps_were() { is "$(tr '\n' ' ' < "$P/taps.log" 2>/dev/null)" "$1"; }

TIMEOUT=""
command -v timeout >/dev/null 2>&1 && TIMEOUT="timeout 120"

# Runs the kit: the answers typed (printf format, one per line), then its
# arguments. Keeps what it printed in $OUT and its exit code in $RC.
kit() {
  local answers=$1; shift
  # shellcheck disable=SC2059
  printf "$answers" | $TIMEOUT "$KIT" "$@" > "$OUT" 2>&1
  RC=$?
}
Y40='y\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\n'
setup() { kit "${1:-$Y40}" setup; }

conf() { printf '%s\n' "$@" > "$PHONE_KIT_CONF"; }
BASE_CONF="NEXTDNS_HOST=my1234.dns.nextdns.io
APP_LIST=com.whatsapp, org.telegram.messenger
HIDE_APPS=com.android.chrome org.mozilla.focus io.nextdns.NextDNS"

apk() {  # file, package, version, code, then more lines
  local file=$1 pkg=$2 ver=$3 code=$4
  shift 4
  mkdir -p "$(dirname "$PHONE_KIT_APKS/$file")"
  { printf 'package=%s\nversion=%s\ncode=%s\nflags=launcher\n' "$pkg" "$ver" "$code"; printf '%s\n' "$@"; } > "$PHONE_KIT_APKS/$file"
}

fresh() {  # SDK level: a new phone, the usual APKs and settings, no state
  new_phone "${1:-36}"
  rm -rf "$PHONE_KIT_APKS" "$PHONE_KIT_STATE" "$FAKE_LOG"
  mkdir -p "$FAKE_LOG"
  apk OwnDroid-v8.4.apk com.bintianqi.owndroid 8.4 40
  apk FeedGuard-0.2.0.apk "$FG" 0.2.0 2 "requests=android.permission.WRITE_SECURE_SETTINGS android.permission.POST_NOTIFICATIONS"
  apk WatchLimit-1.0.apk "$WL" 1.0 1 "requests=android.permission.RECEIVE_BOOT_COMPLETED"
  printf '%s\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
}

state_dir() { ls -d "$PHONE_KIT_STATE"/*/ 2>/dev/null | head -n 1; }
installs_of() { grep -c "^$1 " "$P/installs" 2>/dev/null || true; }
hidden_on() { [ -e "$P/pkg/$1/hidden" ]; }
paused() { [ -e "$P/pkg/$1/suspended" ]; }
deleg_on() { on "deleg/$1" delegation-package-access; }
op_is() { is "$(awk -v o="$3" '$1 == o { print $2 }' "$P/pkg/$1/$2" 2>/dev/null)" "$4"; }
config_has() { grep -qF -- "$1" "$P/pkg/$FG/config"; }
pushed_has() { grep -qF -- "$1" "$P/fs/sdcard/Download/feed-guard-settings.json"; }
restricted_all() { local r; for r in "$@"; do restricted "$r" || { echo "  $r isn't on" >&2; return 1; }; done; }
not_restricted() { ! restricted "$1"; }
gone() { [ "$(setting global adb_enabled)" = 0 ]; }

echo "--- The settings file ---"

fresh
rm -f "$PHONE_KIT_CONF"
kit "" setup
check "no settings file: says so" said "isn't there"
check "no settings file: exit 1" rc_is 1

EXAMPLE_CONF="$HERE/my-phone.conf"
check "the kit isn't run with the example as its settings yet" test ! -e "$EXAMPLE_CONF"
PHONE_KIT_CONF="$EXAMPLE_CONF" kit "" setup
check "first run: settings made from the example" test -f "$EXAMPLE_CONF"
check "first run: says to edit them" said "Put in your NextDNS address"
check "first run: exit 1" rc_is 1
PHONE_KIT_CONF="$EXAMPLE_CONF" kit "" setup
check "the example's address is refused" said "NEXTDNS_HOST is still the example"
rm -f "$EXAMPLE_CONF"
check "the phone wasn't touched by any of that" test ! -e "$P/shell.log"

conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "COLOUR=yes"
kit "" setup
check "unknown setting: named, with its line" said "my-phone.conf, line 2: there's no setting called COLOUR"
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "just words"
kit "" setup
check "a line without =: refused" said "line 2: expected NAME=value"
conf "NEXTDNS_HOST="
kit "" setup
check "empty NextDNS address: refused" said "NEXTDNS_HOST is empty"
conf "NEXTDNS_HOST=https://dns.nextdns.io/abc123"
kit "" setup
check "a URL for the address: refused" said "isn't an address like abc123.dns.nextdns.io"
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "APP_LIST=com.whatsapp, WhatsApp"
kit "" setup
check "an app name instead of a package: refused" said 'APP_LIST has "WhatsApp"'
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "APP_LIST=com.whatsapp com.android.chrome"
kit "" setup
check "Chrome on the list: refused" said "APP_LIST has com.android.chrome, a browser other than Firefox"
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "APP_LIST=com.kiwibrowser.browser"
kit "" setup
check "any *browser* on the list: refused" said "a browser other than Firefox"
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "APP_LIST=com.whatsapp, com.instagram.android"
kit "" setup
check "Instagram on the list: refused" said "APP_LIST has com.instagram.android, an Instagram or Reddit app"
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "APP_LIST=com.reddit.frontpage"
kit "" setup
check "Reddit on the list: refused" said "an Instagram or Reddit app"
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "BLACK_AND_WHITE=maybe"
kit "" setup
check "yes or no only" said 'BLACK_AND_WHITE takes yes or no, not "maybe"'
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "ADDON_PAGE=http://addons.mozilla.org/x"
kit "" setup
check "the add-on page must be https" said "ADDON_PAGE has to start with https://"
conf "NEXTDNS_HOST=my1234.dns.nextdns.io" "ONLY_LISTED_APPS=yes"
kit "" setup
check "list mode needs a list" said "ONLY_LISTED_APPS=yes with an empty APP_LIST"
check "none of those touched the phone" test ! -e "$P/shell.log"

# Comments, quotes, Windows line ends and odd separators are fine.
printf '%s\r\n' "# my phone" "NEXTDNS_HOST = \"my1234.dns.nextdns.io\"  # mine" \
  "APP_LIST=com.whatsapp,org.telegram.messenger;com.instagram.barcelona" "BLACK_AND_WHITE=Yes" > "$PHONE_KIT_CONF"
PHONE_KIT_TEST_ONCE=1 kit "" check
check "comments, quotes and CRLF: read" said "Phone: Pixel 6a, Android 16."
check "Threads may be on the list" not_said "an Instagram or Reddit app"

echo "--- Finding the phone ---"

fresh
rm -f "$P/plugged"
echo 1 > "$P/taps/skip-wait-phone"
PHONE_KIT_TEST_ONCE=1 kit "" setup
check "no phone: how to switch USB debugging on" said "tap Build number 7 times"
check "no phone: exit 1" rc_is 1
rm -f "$P/plugged" "$P/authorized"
touch "$P/plugged"
echo 1 > "$P/taps/skip-wait-phone"
PHONE_KIT_TEST_ONCE=1 kit "" setup
check "not allowed yet: says to tap Allow" said 'tap Allow on "Allow USB debugging?"'
fresh
rm -f "$P/plugged"
kit "" check
check "check doesn't wait for a phone" said "No phone found"
check "check without a phone: exit 1" rc_is 1
fresh
rm -f "$P/plugged"
setup
check "setup waits until the phone is there" said "1/15  OwnDroid as the device owner"
check "and then goes on" said "15/15"

fresh
touch "$P/second-phone"
kit "" setup
check "two phones: asks to pick one" said "More than one phone is plugged in"
ANDROID_SERIAL=28XYZ0001 PHONE_KIT_TEST_ONCE=1 kit "" check
check "ANDROID_SERIAL picks the phone" said "Phone: Pixel 6a"

fresh 28
kit "" setup
check "Android 9 is too old" said "The kit needs Android 10 or newer"
fresh
grep -v device_admin "$P/features" > "$P/f" && mv "$P/f" "$P/features"
kit "" setup
check "no device admin feature: says the lock can't go on" said "can't have a device owner"

echo "--- The APKs ---"

fresh
rm -f "$PHONE_KIT_APKS/FeedGuard-0.2.0.apk"
kit "" setup
check "no Feed Guard APK: says which one" said "Feed Guard's APK (FeedGuard-0.2.0.apk or newer) isn't in"
fresh
apk FeedGuard-0.1.1.apk "$FG" 0.1.1 1
kit "" setup
check "two Feed Guard APKs: refused" said "more than one Feed Guard APK"
fresh
apk OwnDroid-v8.4-testkey.apk com.bintianqi.owndroid 8.4 40
kit "" setup
check "the testkey build is left out" not_said "more than one OwnDroid APK"
check "and the regular one goes on" is "$(installs_of com.bintianqi.owndroid)" 1

fresh
rm -f "$PHONE_KIT_APKS"/OwnDroid*
setup
check "no OwnDroid APK: downloaded from the release" test -f "$PHONE_KIT_APKS/OwnDroid-v8.4.apk"
check "the testkey asset isn't downloaded" test ! -e "$PHONE_KIT_APKS/OwnDroid-v8.4-testkey.apk"
check "its fingerprint is kept" test -f "$PHONE_KIT_APKS/OwnDroid.apk.sha256"
check "and said" said "Kept OwnDroid's fingerprint"
new_phone 36
rm -rf "$PHONE_KIT_STATE"
printf 'package=com.bintianqi.owndroid\nversion=8.4\ncode=40\nflags=launcher\n# changed\n' > "$PHONE_KIT_APKS/OwnDroid-v8.4.apk"
kit "" setup
check "a different OwnDroid APK later: refused" said "its fingerprint changed"
check "before touching the phone" not_said "1/15"
fresh
rm -f "$PHONE_KIT_APKS"/OwnDroid*
FAKE_OFFLINE=1 kit "" setup
check "can't download: says where to get it" said "Get the regular OwnDroid v8.4 APK (not the testkey one)"

echo "--- A whole setup, through OwnDroid's API (Android 16) ---"

fresh 36
setup
check "exit 0" rc_is 0
check "all 15 steps" said "15/15  OwnDroid's code"
check "the pauses, in order" taps_were "firefox apps api-field api-apply delegations import import-check watch-limit api-off nextdns addon instagram debug key "
check "OwnDroid is the device owner" is "$(f owner)" "$OD"
check "OwnDroid, Feed Guard and Watch Limit installed once each" \
  is "$(installs_of "$OD") $(installs_of "$FG") $(installs_of "$WL")" "1 1 1"
check "Feed Guard has the secure settings permission" on "pkg/$FG/perms" android.permission.WRITE_SECURE_SETTINGS
check "and notifications" on "pkg/$FG/perms" android.permission.POST_NOTIFICATIONS
check "both accessibility services on" \
  is "$(setting secure enabled_accessibility_services)" "$FG/$FG.GuardService:$WL/$WL.WatchService"
check "accessibility on" is "$(setting secure accessibility_enabled)" 1
check "Feed Guard: not paused when unused" op_is "$FG" uidops AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
check "Watch Limit: not paused when unused" op_is "$WL" uidops AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
check "both delegated" deleg_on "$FG"
check "both delegated (2)" deleg_on "$WL"
check "Private DNS" is "$(setting global private_dns_mode) $(setting global private_dns_specifier)" "hostname my1234.dns.nextdns.io"
check "automatic time zone switched on" is "$(setting global auto_time_zone)" 1
check "Firefox is the browser" is "$(f role-browser)" "$FF"
check "the restrictions" restricted_all disallow_config_private_dns no_config_vpn no_network_reset no_add_user \
  no_user_switch no_add_private_profile no_config_date_time no_safe_boot
check "default apps left alone without list mode" not_restricted disallow_config_default_apps
check "Debug features last" restricted no_debugging_features
check "the probe restriction is back off" not_restricted no_set_user_icon
check "Chrome hidden" hidden_on com.android.chrome
check "Feed Guard paused Chrome" paused com.android.chrome
check "uninstall blocked: Feed Guard, Watch Limit, Firefox" \
  is "$(sort "$P/blocked-uninstall" | tr '\n' ' ')" "$FG $WL $FF "
check "user control off: the same three" is "$(sort "$P/no-control" | tr '\n' ' ')" "$FG $WL $FF "
check "picture-in-picture off for YouTube" op_is com.google.android.youtube ops PICTURE_IN_PICTURE deny
check "said with its name" said "Picture-in-picture is off for YouTube."
check "OwnDroid's API off at the end" is "$(f od/api-enabled)" 0
check "Shortcuts off" is "$(f od/shortcuts)" 0
check "the restrictions went through the API" grep -q "ADD_USER_RESTRICTION no_safe_boot" "$P/od/api.log"
check "the API never set Debug features" not grep -q no_debugging_features "$P/od/api.log"
check "the setup key is gone from the Mac" test ! -e "$(state_dir)api-key"
check "Feed Guard's settings, pushed" pushed_has '{"id":"allowed_apps","vString":"com.whatsapp, org.telegram.messenger"}'
check "and imported" config_has '{"id":"only_listed_apps","vBool":false}'
check "black and white" is "$(setting secure accessibility_display_daltonizer_enabled)$(setting secure accessibility_display_daltonizer)" 10
check "the settings file was scanned for the file picker" grep -q "file:///sdcard/Download/feed-guard-settings.json" "$P/scanned"
check "Firefox opened the NextDNS test" grep -q "^$FF https://test.nextdns.io" "$P/opened"
check "and instagram.com" grep -q "^$FF https://www.instagram.com/" "$P/opened"
check "the leftover in the package report's later parts wasn't touched" not said "com.example.leftover"
check "the audit had no ✗" not_said "✗"
check "nothing to check by hand on Android 16" not_said "check it in OwnDroid"
check "owndroid-key made a key for a new phone" grep -qx "seal --new-phone" "$FAKE_LOG/owndroid-key"
check "and the kit ends after it" said "The phone is set up."
check "the whole setup: exit 0" rc_is 0
check "after checking its status" grep -qx "status" "$FAKE_LOG/owndroid-key"
check "the API key was typed into the field" is "$(wc -c < "$P/od/field")" 24

echo "--- Stopping and going on ---"

fresh 36
kit 'y\ny\ny\n' setup
check "stdin ends: stops" said "Stopped. Run phone-kit setup again to go on where it stopped."
check "stdin ends: exit 1" rc_is 1
check "it got as far as the API key" tapped api-apply
setup
check "run again: finishes" rc_is 0
check "step 1 already done" said "✓ OwnDroid is the device owner."
check "nothing installed twice" is "$(installs_of "$OD") $(installs_of "$FG") $(installs_of "$WL")" "1 1 1"
check "the app list asked about once" is "$(grep -c '^apps$' "$P/taps.log")" 1
check "the same API key both times" is "$(sort -u "$P/od/api.log" | grep -c 'ADD_USER_RESTRICTION no_safe_boot')" 1

fresh 36
touch "$P/taps/unplug-at-delegations"
setup
check "cable out: says so" said "Lost the phone. Plug it in again and unlock it"
check "cable out: exit 1" rc_is 1
check "cable out: nothing after it ran" not_said "8/15"
touch "$P/plugged"
setup
check "cable back in: finishes" rc_is 0
check "delegations then done" deleg_on "$FG"

fresh 36
setup 'y\ny\ny\ny\ny\ny\n'
check "stopped part way" rc_is 1
new_phone 36                       # the factory reset: Android makes a new ID
put settings/secure/android_id 0000aaaa1111bbbb
setup
check "after a factory reset the kit starts afresh" tapped apps
check "two folders of state, one per reset" is "$(ls "$PHONE_KIT_STATE" | wc -l | tr -d ' ')" 2

echo "--- What stops the setup ---"

fresh 36
put accounts 1
setup
check "an account on the phone: stops at step 1" said "The phone has 1 account on it"
check "with how to remove it" said "Passwords, passkeys & accounts"
check "OwnDroid not installed then" not installed "$OD"
check "exit 1" rc_is 1

fresh 36
put users 2
setup
check "a private space: stops" said "more than one user"

fresh 36
make_app com.example.mdm 0 1.0 1
put owner com.example.mdm
setup
check "another device owner: says only a reset removes it" said "com.example.mdm is this phone's device owner"

fresh 36
put fail-install/$OD "INSTALL_FAILED_INSUFFICIENT_STORAGE"
setup
check "an install that fails: Android's reason" said "INSTALL_FAILED_INSUFFICIENT_STORAGE"

fresh 36
conf "NEXTDNS_HOST=typo99.dns.nextdns.io" "APP_LIST=com.whatsapp"
setup
check "a NextDNS address that doesn't answer: stops" said "couldn't look up names through typo99.dns.nextdns.io"
check "Private DNS back on Automatic" is "$(setting global private_dns_mode)" opportunistic
check "nothing locked" not_restricted disallow_config_private_dns

fresh 36
make_app com.example.tasker 0 6.3 63 launcher android.permission.WRITE_SECURE_SETTINGS
add pkg/com.example.tasker/perms android.permission.WRITE_SECURE_SETTINGS
setup
check "another app with the secure settings permission: taken" said "Took it from: com.example.tasker."
check "it's gone" not on pkg/com.example.tasker/perms android.permission.WRITE_SECURE_SETTINGS
check "the shell keeps its own" on pkg/com.android.shell/perms android.permission.WRITE_SECURE_SETTINGS

fresh 36
make_app com.example.stuck 0 1.0 1 android.permission.WRITE_SECURE_SETTINGS
add pkg/com.example.stuck/perms android.permission.WRITE_SECURE_SETTINGS
touch "$P/pkg/com.example.stuck/stuck"
setup
check "one that keeps it: stops" said "Couldn't take it from: com.example.stuck."

echo "--- The apps on the phone ---"

fresh 36
make_app "$FG" 0 0.2.0 2
cp "$PHONE_KIT_APKS/FeedGuard-0.2.0.apk" "$P/pkg/$FG/apk"
setup
check "the same Feed Guard already there: not installed again" is "$(installs_of "$FG")" 0
fresh 36
make_app "$FG" 0 0.3.0 3 android.permission.WRITE_SECURE_SETTINGS android.permission.POST_NOTIFICATIONS
setup
check "a newer Feed Guard on the phone stays" said "The phone has a newer Feed Guard (0.3.0), so that one stays."
check "and setup goes on" rc_is 0
fresh 36
apk extra/Firefox-145.apk "$FF" 145.0.1 2016100000 "flags=browser"
apk extra/WhatsApp.apk com.whatsapp 2.26.1 26
setup
check "apks/extra: Firefox and WhatsApp installed" is "$(installs_of "$FF") $(installs_of com.whatsapp)" "1 1"
check "so no Play Store pause for Firefox" not_tapped firefox
check "Telegram still missing, asked about" said "aren't on the phone yet: org.telegram.messenger"
new_phone 36
put settings/secure/android_id 77aa77aa77aa77aa
setup
check "on the next phone they're installed again" is "$(installs_of "$FF")" 1
put settings/global/adb_enabled 1
rm -f "$P/restr-global.new"; del restr-global no_debugging_features
setup
check "but not twice on the same phone" is "$(installs_of "$FF")" 1

fresh 36
put no-role 1
echo 0 > "$P/taps/skip-browser"
setup
check "the browser role refused: asks for the tap" tapped browser
check "and goes on once it's done" rc_is 0

echo "--- OwnDroid's API and Shortcuts ---"

fresh 36
touch "$P/taps/forget-shortcuts"
setup
check "Shortcuts left on: asked again" tapped shortcuts-off
check "and off in the end" is "$(f od/shortcuts)" 0

fresh 36
echo 1 > "$P/taps/skip-api-apply"
setup
check "Apply not tapped: the key asked for again" is "$(grep -c '^api-field$' "$P/taps.log")" 2
check "then it works" rc_is 0

fresh 36
echo 9 > "$P/taps/skip-api-apply"
setup
check "the API never answers: offers taps" tapped api-taps
check "says to put the API back off" said "Then put the API back off"
check "the locks become taps" tapped locks
check "and setup finishes" rc_is 0
check "the restrictions are on" restricted_all disallow_config_private_dns no_safe_boot no_add_private_profile

fresh 36
echo "HIDE com.android.chrome" > "$P/od/api-ignores"
setup
check "something the API didn't set: a tap" said "OwnDroid's API didn't set these, so they're taps."
check "listed" said "Applications › Chrome › Hide"
check "Chrome hidden after the tap" hidden_on com.android.chrome

fresh 36
echo 1 > "$P/taps/skip-api-off"
setup
check "API left on: asked again" is "$(grep -c '^api-off$' "$P/taps.log")" 2
check "finishes once it's off" rc_is 0
check "and it's off" is "$(f od/api-enabled)" 0

fresh 36
echo 1 > "$P/taps/forget-shortcuts"
echo 0 > "$P/od/shortcuts"
setup
check "Shortcuts already off: never asked" not_tapped shortcuts-off

echo "--- By hand: USE_OWNDROID_API=no ---"

fresh 36
printf '%s\nUSE_OWNDROID_API=no\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
setup
check "taps: no API key asked for" not_tapped api-field
check "Shortcuts asked off" tapped shortcuts-off
check "the locks listed for tapping" tapped locks
check "with Debug features left for the end" said "Leave Debug features off until the very end."
check "listed by their names in OwnDroid" said "User restriction › Network › Configure private DNS: on"
check "the API was never used" test ! -e "$P/od/api.log"
check "no API key made" test ! -e "$(state_dir)api-key"
check "done" rc_is 0
check "and locked" restricted_all disallow_config_private_dns no_config_vpn no_debugging_features

fresh 36
printf '%s\nUSE_OWNDROID_API=no\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
echo 1 > "$P/taps/skip-locks"
setup
check "taps not done: asked again" is "$(grep -c '^locks$' "$P/taps.log")" 2
check "saying some aren't on" said "Some aren't on yet."

echo "--- Only apps on the list ---"

fresh 36
make_app com.whatsapp 0 2.26.1 26 launcher
make_app org.telegram.messenger 0 12.1 121 launcher
make_app com.example.game 0 1.0 1 launcher
printf '%s\nONLY_LISTED_APPS=yes\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
setup
check "Firefox goes on the list" pushed_has '"vString":"org.mozilla.firefox, com.whatsapp, org.telegram.messenger"'
check "list mode on" config_has '{"id":"only_listed_apps","vBool":true}'
check "the game is paused" paused com.example.game
check "Gmail is paused" paused com.google.android.gm
check "WhatsApp isn't" not paused com.whatsapp
check "Firefox isn't" not paused "$FF"
check "the dialer isn't" not paused com.google.android.dialer
check "default apps locked along with the list" restricted disallow_config_default_apps
check "the audit saw the list working" said "Only apps on your list open: the rest are paused"
check "done" rc_is 0

fresh 36
make_app com.whatsapp 0 2.26.1 26 launcher
printf '%s\nONLY_LISTED_APPS=yes\nLOCK_DEFAULT_APPS=no\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
touch "$P/taps/no-default-apps"
setup
check "LOCK_DEFAULT_APPS=no wins" not_restricted disallow_config_default_apps
check "done (2)" rc_is 0

fresh 36
make_app com.whatsapp 0 2.26.1 26 launcher
printf '%s\nONLY_LISTED_APPS=yes\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
echo 1 > "$P/taps/skip-import"
setup
check "import not done: seen by nothing being paused" said "Feed Guard hasn't paused any app outside your list"
check "asked to import again" tapped import-again
check "imported the second time" config_has only_listed_apps
check "done (3)" rc_is 0

fresh 36
make_app com.whatsapp 0 2.26.1 26 launcher
printf '%s\nONLY_LISTED_APPS=yes\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
echo 9 > "$P/taps/skip-import"
# Five yeses to the steps before, then Enter, yes it worked, no don't import
# again, and yes to the rest.
setup "y\ny\ny\ny\ny\n\ny\nn\n$Y40"
check "going on without it: the audit asks" said "check in OwnDroid › Applications › Feed Guard › Managed configuration"
check "as a question, not a stop" tapped audit-hand
check "done (4)" rc_is 0

fresh 36
printf '%s\nBLACK_AND_WHITE=no\nCOLOR_APPS=none\nBLOCK_APPS=com.example.game com.example.chat\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
setup
check "colour: grayscale off in the settings" config_has '{"id":"grayscale","vBool":false}'
check "COLOR_APPS=none goes in as none" config_has '{"id":"color_apps","vString":"none"}'
check "the extra apps to pause" config_has '{"id":"blocked_apps","vString":"com.example.game, com.example.chat"}'
check "the screen got its colour back" is "$(setting secure accessibility_display_daltonizer_enabled)" 0
check "done (5)" rc_is 0

fresh 36
printf '%s\nBLACK_AND_WHITE=no\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
echo 1 > "$P/taps/skip-import"
setup
check "colour wanted but still grey: import again" said "The screen is still black and white"

echo "--- Older Android ---"

fresh 34
printf '%s\nONLY_LISTED_APPS=yes\n' "$BASE_CONF" > "$PHONE_KIT_CONF"
make_app com.whatsapp 0 2.26.1 26 launcher
setup
check "Android 14: no private space restriction" not_restricted no_add_private_profile
check "default apps locked" restricted disallow_config_default_apps
check "uninstall blocks can't be read: checked by hand" said "Android 34 doesn't report this, so check it in OwnDroid: Applications › Feed Guard › Block uninstall: on"
check "asked once" is "$(grep -c '^audit-hand$' "$P/taps.log")" 1
check "but still set through the API" grep -q "BLOCK_UNINSTALL $FG" "$P/od/api.log"
check "done" rc_is 0

fresh 34
touch "$P/engine-14"
setup
check "Android 14 with them in its report: nothing by hand" not_tapped audit-hand

fresh 29
setup
check "Android 10: done" rc_is 0
check "no auto-revoke on Android 10" test ! -e "$P/pkg/$FG/uidops"
check "no notification grant on Android 10" not on "pkg/$FG/perms" android.permission.POST_NOTIFICATIONS
check "no default apps or private space restriction" not_restricted no_add_private_profile
check "checked by hand" tapped audit-hand

echo "--- The check at the end, and check ---"

fresh 36
echo 1 > "$P/taps/skip-debug"
PHONE_KIT_NAP=0 setup
check "Debug features never switched on: stops" said "still reachable over USB"
check "the API key stays until then" test -f "$(state_dir)api-key"
kit "" check
check "check: all in place" said "Everything is in place."
check "check: exit 0" rc_is 0
check "check: Debug features still to do" said "○ Debug features"
touch "$P/pkg/$FF/suspended"
kit "" check
check "check: a paused Firefox" said "✗ Firefox is paused"
check "check: exit 1" rc_is 1
rm -f "$P/pkg/$FF/suspended"
restrict_del no_config_vpn
kit "" check
check "check: a restriction gone" said "✗ Missing: User restriction › Network › Configure VPN: on"
put od/api-enabled 1
kit "" check
check "check: the API back on" said "✗ OwnDroid's API still answers the setup key"
put od/api-enabled 0
setup
check "setup puts the restriction back" restricted no_config_vpn
check "and finishes" rc_is 0

fresh 36
kit "" check
check "check on a new phone: lists what's missing" said "✗ OwnDroid isn't the device owner"
check "and changes nothing" test ! -e "$P/owner"
check "exit 1" rc_is 1

fresh 36
echo 1 > "$P/taps/skip-delegations"
kit 'y\ny\ny\ny\ny\n' setup
make_app com.example.tasker 0 6.3 63 android.permission.WRITE_SECURE_SETTINGS
add pkg/com.example.tasker/perms android.permission.WRITE_SECURE_SETTINGS
kit "" check
check "check: an app with the secure settings permission" said "✗ These apps have the secure settings permission"

echo "--- OwnDroid's code ---"

fresh 36
FAKE_KEY=none setup
check "no key yet: how to make the first one" said 'owndroid-key seal --open "YYYY-MM-DD HH:MM" --every 30d'
check "and it's done, apart from the code" said "The phone is set up, apart from OwnDroid's code."
check "no key yet: exit 0" rc_is 0
check "nothing sealed" not grep -q "^seal" "$FAKE_LOG/owndroid-key"
fresh 36
FAKE_SEAL_FAILS=1 setup
check "owndroid-key fails: says the code isn't set" said "owndroid-key didn't finish, so OwnDroid has no code from this Mac yet."
check "and what to run" said "Run it when you're ready: owndroid-key seal --new-phone"
check "and the setup still ends" rc_is 0
check "apart from the code" said "The phone is set up, apart from OwnDroid's code."
fresh 36
FAKE_KEY=broken setup
check "owndroid-key broken: its message" said "Can't reach the key service"
check "then what to run" said "run: owndroid-key seal --new-phone"
fresh 36
PATH="$NO_KEY_PATH" setup
check "owndroid-key not installed: says so" said "owndroid-key isn't installed here"
check "done anyway" rc_is 0
fresh 36
setup 'y\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\nn\n'
check "no to the new key: says when to" said "Run it when you're ready: owndroid-key seal --new-phone"
check "nothing sealed (2)" not grep -q "^seal" "$FAKE_LOG/owndroid-key"

echo "--- Set up a phone.command ---"

# A copy of the kit, so the download lands in a scratch folder.
COPY="$WORK/kitcopy"
mkdir -p "$COPY"
cp "$KIT" "$HERE/my-phone.conf.example" "$HERE/Set up a phone.command" "$COPY/"
CLICK="$COPY/Set up a phone.command"
click() {  # the answers typed, as for kit()
  # shellcheck disable=SC2059
  printf "$1" | $TIMEOUT "$CLICK" > "$OUT" 2>&1
  RC=$?
}
export FAKE_TEXTEDIT="$BASE_CONF"

fresh 36
rm -f "$PHONE_KIT_CONF"
click "\n$Y40"
check "click: makes the settings file" test -f "$PHONE_KIT_CONF"
check "click: opens it in TextEdit" grep -qxF -- "-e $PHONE_KIT_CONF" "$FAKE_LOG/open"
check "click: then the whole setup" said "15/15  OwnDroid's code"
check "click: done" said "The phone is set up."
check "click: the window waits before closing" said "Press Enter to close this window."
check "click: exit 0" rc_is 0
check "click: nothing downloaded" not grep -qs dl.google.com "$FAKE_LOG/curl"

fresh 36
conf "NEXTDNS_HOST=abc123.dns.nextdns.io"
click "s\n\n$Y40"
check "click, example settings: the kit says so" said "NEXTDNS_HOST is still the example"
check "click: s opens the settings" grep -qxF -- "-e $PHONE_KIT_CONF" "$FAKE_LOG/open"
check "click: then Enter goes on and finishes" said "The phone is set up."
check "click, after the fix: exit 0" rc_is 0

fresh 36
touch "$P/taps/unplug-at-api-field"
click "\n$Y40$Y40"
check "click, cable out: Enter to go on" said "Plug it in again and unlock it, then press Enter in this window:"
check "click, cable out: no command to type" not_said "run phone-kit setup again"
check "click, cable out: goes on and finishes" said "The phone is set up."

fresh 36
printf 'y\ny\ny\n' | PHONE_KIT_AGAIN="press Enter in this window" $TIMEOUT "$KIT" setup > "$OUT" 2>&1
check "stopped from the window: Enter, not a command" said "Stopped. Press Enter in this window to go on where it stopped."

fresh 36
( unset PHONE_KIT_ADB
  printf "y\n$Y40" | PATH="$BIN:$WORK/keybin:/usr/bin:/bin" PHONE_KIT_ADB_PLACES="" $TIMEOUT "$CLICK" > "$OUT" 2>&1 )
RC=$?
check "no adb: offers to get it" said "This Mac doesn't have adb"
check "no adb: downloads Android's platform tools" grep -qx "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" "$FAKE_LOG/curl"
check "no adb: into the kit's folder" test -x "$COPY/platform-tools/adb"
check "no adb: then sets the phone up with it" said "The phone is set up."
check "no adb: exit 0" rc_is 0

fresh 36
( unset PHONE_KIT_ADB
  printf "y\n$Y40" | PATH="$BIN:$WORK/keybin:/usr/bin:/bin" PHONE_KIT_ADB_PLACES="" $TIMEOUT "$CLICK" > "$OUT" 2>&1 )
check "adb downloaded before: used again" not grep -qs dl.google.com "$FAKE_LOG/curl"
rm -rf "$COPY/platform-tools"

fresh 36
( unset PHONE_KIT_ADB
  printf 'n\n\n' | PATH="$BIN:$WORK/keybin:/usr/bin:/bin" PHONE_KIT_ADB_PLACES="" $TIMEOUT "$CLICK" > "$OUT" 2>&1 )
RC=$?
check "no adb, no download: says how to get it" said "brew install android-platform-tools"
check "no adb, no download: exit 1" rc_is 1
check "no adb, no download: the phone untouched" not has owner

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
