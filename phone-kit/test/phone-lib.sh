# The pretend phone behind phone-kit's tests, shared by fake-adb (Android,
# OwnDroid and Feed Guard as the kit sees them over adb) and taps (the person
# at the phone). Everything about the phone is a plain file under $FAKE_PHONE:
#
#   prop/NAME                 getprop values
#   settings/NS/KEY           settings values
#   pkg/NAME/                 an installed app: system, version, code, apk,
#                             requests, perms, ops, uidops, and the flags
#                             launcher, browser, hidden, suspended, stuck
#   owner                     the device owner's package
#   deleg/NAME                its delegated scopes
#   restr-local, restr-global user restrictions OwnDroid set
#   blocked-uninstall         packages that can't be uninstalled
#   no-control                packages with user control disabled
#   od/                       OwnDroid: api-enabled, api-key, shortcuts,
#                             the API key field the person is typing in
#   role-browser              the default browser
#   plugged, authorized       the cable is in, and USB debugging is allowed
#   dns-good                  the only Private DNS address that answers
#
# Plain bash, for the tests on Linux or a Mac.

P=${FAKE_PHONE:?FAKE_PHONE isn\'t set}
OD=com.bintianqi.owndroid
FG=io.github.zinzuzu.feedguard
WL=io.github.zinzuzu.watchlimit
FF=org.mozilla.firefox

# The helpers keep to bash's own commands where they can: the kit asks the
# phone hundreds of things per setup, and each answer runs this file again.
mkd() { [ -d "$1" ] || mkdir -p "$1"; }
f() { cat "$P/$1" 2>/dev/null; }
rd() { REPLY=""; [ -f "$P/$1" ] && IFS= read -r REPLY < "$P/$1"; return 0; }   # first line, into REPLY
has() { [ -e "$P/$1" ]; }
put() { case "$1" in */*) mkd "$P/${1%/*}" ;; esac; printf '%s\n' "$2" > "$P/$1"; }
on() {
  local l
  [ -f "$P/$1" ] || return 1
  while IFS= read -r l || [ -n "$l" ]; do [ "$l" = "$2" ] && return 0; done < "$P/$1"
  return 1
}
add() { on "$1" "$2" || { case "$1" in */*) mkd "$P/${1%/*}" ;; esac; printf '%s\n' "$2" >> "$P/$1"; }; }
del() {
  local l
  [ -f "$P/$1" ] || return 0
  while IFS= read -r l || [ -n "$l" ]; do [ "$l" = "$2" ] || printf '%s\n' "$l"; done < "$P/$1" > "$P/$1.new"
  mv "$P/$1.new" "$P/$1"
}
note() { case "$1" in */*) mkd "$P/${1%/*}" ;; esac; printf '%s\n' "$2" >> "$P/$1"; }
num() { rd "$1"; echo "${REPLY:-${2:-0}}"; }
sdk() { rd prop/ro.build.version.sdk; echo "$REPLY"; }
pkgs() { local d; for d in "$P"/pkg/*; do [ -d "$d" ] && echo "${d##*/}"; done; }
installed() { [ -d "$P/pkg/$1" ]; }
setting() { if [ -f "$P/settings/$1/$2" ]; then cat "$P/settings/$1/$2"; else echo null; fi; }
setting_rd() { REPLY=null; [ ! -f "$P/settings/$1/$2" ] || IFS= read -r REPLY < "$P/settings/$1/$2"; return 0; }

# Restrictions a device owner sets go to every user or to user 0, as Android
# sorts them. Both count.
GLOBAL_RESTRICTIONS=" no_config_date_time no_safe_boot no_add_user no_network_reset no_debugging_features "
restrict_add() {
  case "$GLOBAL_RESTRICTIONS" in *" $1 "*) add restr-global "$1" ;; *) add restr-local "$1" ;; esac
  [ "$1" != no_debugging_features ] || put settings/global/adb_enabled 0
}
restrict_del() { del restr-global "$1"; del restr-local "$1"; }
restricted() { on restr-global "$1" || on restr-local "$1"; }

# An app: name system version code, then flags (launcher browser) and the
# permissions it asks for (anything with a dot).
make_app() {
  local p=$1 sys=$2 ver=$3 code=$4 x
  shift 4
  mkdir -p "$P/pkg/$p"
  put "pkg/$p/system" "$sys"
  put "pkg/$p/version" "$ver"
  put "pkg/$p/code" "$code"
  printf 'package=%s\nversion=%s\n' "$p" "$ver" > "$P/pkg/$p/apk"
  for x in "$@"; do
    case "$x" in
      launcher|browser) touch "$P/pkg/$p/$x" ;;
      *.*) add "pkg/$p/requests" "$x" ;;
    esac
  done
}

# A phone just out of a factory reset, with USB debugging on and allowed.
# SDK is the Android API level: 36 is Android 16.
new_phone() {
  local sdk=${1:-36} release
  case "$sdk" in 29) release=10 ;; 30) release=11 ;; 33) release=13 ;; 34) release=14 ;; 35) release=15 ;; 36) release=16 ;; *) release=$sdk ;; esac
  rm -rf "$P"
  mkdir -p "$P/pkg" "$P/od" "$P/taps"
  put serial 28XYZ0001
  touch "$P/plugged" "$P/authorized"
  put prop/ro.build.version.sdk "$sdk"
  put prop/ro.build.version.release "$release"
  put prop/ro.product.model "Pixel 6a"
  printf '%s\n' feature:android.hardware.telephony feature:android.software.device_admin \
    feature:android.software.managed_users feature:android.hardware.wifi > "$P/features"
  put settings/global/adb_enabled 1
  put settings/global/private_dns_mode opportunistic
  put settings/global/auto_time 1
  put settings/global/auto_time_zone 0
  put settings/secure/android_id 3f9a2c1b7d8e6f50
  put dns-good my1234.dns.nextdns.io
  make_app android 1 16 36
  make_app com.android.systemui 1 16 36
  make_app com.android.shell 1 16 36 android.permission.WRITE_SECURE_SETTINGS
  add pkg/com.android.shell/perms android.permission.WRITE_SECURE_SETTINGS
  make_app com.android.settings 1 16 36 launcher
  make_app com.google.android.gms 1 25.40.33 254033
  make_app com.google.android.dialer 1 190.0 1900 launcher
  make_app com.google.android.apps.messaging 1 20261020 2026 launcher
  make_app com.google.android.contacts 1 4.60 460 launcher
  make_app com.google.android.deskclock 1 7.12 712 launcher
  make_app com.google.android.apps.nexuslauncher 1 16 36
  make_app com.google.android.inputmethod.latin 1 15.9 159
  make_app com.google.android.documentsui 1 16 36
  make_app com.android.vending 1 48.6.20 4862 launcher
  make_app com.android.chrome 1 142.0.7444.48 744448 launcher browser
  make_app com.google.android.youtube 1 20.44.38 2044 launcher
  make_app com.google.android.gm 1 2026.10.26 20261026 launcher
  make_app com.google.android.apps.photos 1 7.53 753 launcher
  make_app com.google.android.calculator 1 8.8 88 launcher
  make_app com.google.android.apps.maps 1 25.43 2543 launcher
  make_app com.google.android.googlequicksearchbox 1 16.42 1642 launcher
}

# Feed Guard, as far as the kit can see it: black and white, and the pauses.
ESSENTIAL=" $OD $WL android com.android.systemui com.android.settings com.google.android.gms com.google.android.gsf com.google.android.webview com.android.webview com.google.android.packageinstaller com.android.packageinstaller com.google.android.permissioncontroller com.android.permissioncontroller com.google.android.apps.nexuslauncher com.google.android.documentsui com.android.documentsui com.google.android.dialer com.google.android.apps.messaging com.google.android.contacts com.google.android.deskclock com.google.android.inputmethod.latin com.android.phone com.android.server.telecom com.android.stk com.android.emergency com.google.android.apps.safetyhub com.google.android.cellbroadcastreceiver com.android.cellbroadcastreceiver "
NOT_A_BROWSER=" $FF com.android.vending com.google.android.googlequicksearchbox "

feed_app() {
  case "$1" in
    com.instagram.barcelona) return 1 ;;
    com.instander.android|org.quantumbadger.redreader|com.onelouder.baconreader|o.o.joey) return 0 ;;
    *[Ii]nstagram*|*[Rr]eddit*) return 0 ;;
  esac
  return 1
}

fg_running() {
  installed "$FG" || return 1
  setting_rd secure accessibility_enabled
  [ "$REPLY" = 1 ] || return 1
  setting_rd secure enabled_accessibility_services
  case ":$REPLY:" in
    *":$FG/$FG.GuardService:"*|*":$FG/.GuardService:"*) return 0 ;;
  esac
  return 1
}

# Feed Guard's managed configuration as OwnDroid imported it, four lines:
# grayscale, allowed_apps, blocked_apps, only_listed_apps (empty if unset).
fg_config() {
  if [ ! -f "$P/pkg/$FG/config" ]; then printf '\n\n\n\n'; return; fi
  tr '{' '\n' < "$P/pkg/$FG/config" | awk -F'"' '
    $2 == "id" {
      if ($6 == "vString") v[$4] = $8
      else if ($6 == "vBool") { b = $7; gsub(/[^a-z]/, "", b); v[$4] = b }
    }
    END { print v["grayscale"]; print v["allowed_apps"]; print v["blocked_apps"]; print v["only_listed_apps"] }'
}

fg_tick() {
  local gray allowed blocked list d p want browserlike
  fg_running || return 0
  { IFS= read -r gray; IFS= read -r allowed; IFS= read -r blocked; IFS= read -r list; } <<CONFIG
$(fg_config)
CONFIG
  if on "pkg/$FG/perms" android.permission.WRITE_SECURE_SETTINGS; then
    if [ "${gray:-true}" = true ]; then
      put settings/secure/accessibility_display_daltonizer_enabled 1
      put settings/secure/accessibility_display_daltonizer 0
      [ ! -e "$P/fg-gave-colour" ] || rm -f "$P/fg-gave-colour"
    elif ! has fg-gave-colour; then
      put settings/secure/accessibility_display_daltonizer_enabled 0
      : > "$P/fg-gave-colour"
    fi
  fi
  on "deleg/$FG" delegation-package-access || return 0
  allowed=" ${allowed//[,;]/ } "
  blocked=" ${blocked//[,;]/ } "
  for d in "$P"/pkg/*; do
    p=${d##*/}
    case "$ESSENTIAL $FG com.android.vending $allowed" in *" $p "*) continue ;; esac
    [ -e "$d/hidden" ] && continue
    browserlike=0
    if [ -e "$d/browser" ] || feed_app "$p"; then browserlike=1; fi
    case "$blocked" in *" $p "*) browserlike=1 ;; esac
    want=0
    if [ $browserlike = 1 ]; then case "$NOT_A_BROWSER" in *" $p "*) ;; *) want=1 ;; esac; fi
    if [ "$list" = true ] && [ $want = 0 ]; then
      if [ $browserlike = 1 ] || [ -e "$d/launcher" ]; then want=1
      else rd "pkg/$p/system"; [ "$REPLY" = 1 ] || want=1; fi
    fi
    if [ $want = 1 ]; then
      [ -e "$d/suspended" ] || { : > "$d/suspended"; add fg-paused "$p"; }
    elif [ -e "$d/suspended" ] && on fg-paused "$p"; then
      rm -f "$d/suspended"; del fg-paused "$p"
    fi
  done
}
