#!/bin/bash
# Tests for owndroid-key, without a network or a Mac: a fake tle and a fake
# clock. The fake drand publishes round n at second 3 * (n - 1), and FAKE_NOW
# is the time. The root side runs directly (OWNDROID_KEY_DIRECT) instead of
# through launchd, except at the end, where it answers on a real socket.
#   bash test/run.sh
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$HERE/owndroid-key"
WORK="$(mktemp -d)"
SERVER=""
trap '[ -n "$SERVER" ] && kill "$SERVER" 2>/dev/null; rm -rf "$WORK"' EXIT
export OWNDROID_KEY_ROOT="$WORK/root"
export OWNDROID_KEY_LOG="$WORK/owndroid-key.log"
export OWNDROID_KEY_DIRECT="$SCRIPT"
export OWNDROID_KEY_SOCKET="$WORK/none.sock"
export FAKE_LOG="$WORK/log"
FAKE="$WORK/bin"                   # tools for your account: no tle here
RBIN="$OWNDROID_KEY_ROOT/bin"      # tools for the root side
mkdir -p "$FAKE" "$RBIN" "$FAKE_LOG"

REAL_DATE="$(command -v date)"
cat > "$FAKE/date" <<EOF
#!/bin/bash
if [ "\$#" -eq 1 ] && [ "\$1" = "+%s" ]; then echo "\$FAKE_NOW"; exit 0; fi
exec "$REAL_DATE" "\$@"
EOF
cp "$FAKE/date" "$RBIN/date"

cat > "$RBIN/tle" <<'EOF'
#!/bin/bash
dec=0 enc=0 round="" dur="" force=0 out="" in=""
while [ $# -gt 0 ]; do
  case "$1" in
    -d) dec=1 ;;
    -e) enc=1 ;;
    -r) round=$2; shift ;;
    -D) dur=$2; shift ;;
    -f) force=1 ;;
    -o) out=$2; shift ;;
    -*) echo "fake tle: unknown flag $1" >&2; exit 2 ;;
    *) in=$1 ;;
  esac
  shift
done
# Like tle 1.2: exactly one of -e and -d.
[ $((dec + enc)) -eq 1 ] || { echo "only one of -m/--metadata, -d/--decrypt or -e/--encrypt must be passed" >&2; exit 1; }
cur=$((FAKE_NOW / 3 + 1))
if [ "$dec" = 1 ]; then
  r=$(grep -a -m 1 '^-> tlock ' "$in" | awk '{print $3}')
  if [ "${FAKE_OFFLINE:-0}" = 1 ] || [ "$r" -gt "$cur" ]; then
    echo "too early to decrypt: expected round $r > $cur current round" >&2
    exit 1
  fi
  tail -n +3 "$in" | base64 -d
  exit 0
fi
if [ "${FAKE_OFFLINE:-0}" = 1 ]; then echo "fake tle: can't reach drand" >&2; exit 1; fi
# A full disk: like tle, the output file is made before anything is written.
if [ "${FAKE_NOSEAL:-0}" = 1 ]; then [ -n "$out" ] && : > "$out"; echo "write $out: no space left on device" >&2; exit 1; fi
if [ -n "$dur" ]; then
  round=$(( (FAKE_NOW + ${dur%s}) / 3 + 1 ))
fi
if [ "$force" = 0 ] && [ "$round" -lt "$cur" ]; then echo "round $round is in the past" >&2; exit 1; fi
data=$(base64)
printf 'age-encryption.org/v1\n-> tlock %s 52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971\n%s\n' "$round" "$data" > "$out"
EOF

cat > "$RBIN/tmutil" <<'EOF'
#!/bin/bash
echo "$*" >> "$FAKE_LOG/tmutil.log"
EOF
chmod +x "$FAKE"/* "$RBIN"/*
export PATH="$FAKE:$PATH"

passed=0 failed=0
check() {  # name, then a command that must succeed
  local name=$1; shift
  if "$@"; then passed=$((passed + 1)); else failed=$((failed + 1)); echo "FAIL: $name"; fi
}
has() { grep -q -- "$2" "$1" 2>/dev/null; }
hasnt() { ! grep -q -- "$2" "$1" 2>/dev/null; }
eq() { [ "$1" = "$2" ] || { echo "  expected [$2], got [$1]"; return 1; }; }
ok() { "$@"; }

DATA="$OWNDROID_KEY_ROOT/sealed"
K="$DATA/key.tle"
E="$DATA/end.tle"
S="$DATA/schedule"
round_of() { grep -a -m 1 '^-> tlock ' "$1" | awk '{print $3}'; }
get() { sed -n "s/^$1=//p" "$S"; }
contents() { tail -n +3 "$1" | base64 -d; }
code_for() { bash "$SCRIPT" _totp "$1" "$FAKE_NOW"; }
serve() { printf '%s\n' "$1" | bash "$SCRIPT" serve; }

SECRET_A=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP
SECRET_B=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
SECRET_C=MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U
DAY=86400
export FAKE_NOW=1800000000   # some day in 2027
fmt() { "$REAL_DATE" -d "@$1" '+%Y-%m-%d %H:%M'; }

# --- Codes are RFC 6238 TOTP, like OwnDroid's ------------------------------
for v in 59:287082 1111111109:081804 1111111111:050471 1234567890:005924 2000000000:279037; do
  check "TOTP at ${v%%:*}" eq "$(bash "$SCRIPT" _totp "$SECRET_B" "${v%%:*}")" "${v##*:}"
done

# --- Before any key ------------------------------------------------------
bash "$SCRIPT" help 2>/dev/null; check "help works" eq $? 0
bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "code without a key fails" eq $rc 1
check "code without a key says so" has "$WORK/out" "no key yet"
bash "$SCRIPT" seal --every 7d < /dev/null > "$WORK/out" 2>&1
check "seal needs a first date" has "$WORK/out" "Say when the key first opens"
bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW + 2 * DAY)))" < /dev/null > "$WORK/out" 2>&1
check "seal needs an interval" has "$WORK/out" "Say how often"
bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW + 2 * DAY)))" --every 12h < /dev/null > "$WORK/out" 2>&1
check "interval must be longer than the window" has "$WORK/out" "has to be longer than the window"
bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW - DAY)))" --every 7d < /dev/null > "$WORK/out" 2>&1
check "first date must be ahead" has "$WORK/out" "at least 10 minutes from now"
bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW + 400 * DAY)))" --every 7d < /dev/null > "$WORK/out" 2>&1
check "first date within a year" has "$WORK/out" "within a year"
bash "$SCRIPT" seal --open "1st of May" --every 7d < /dev/null > "$WORK/out" 2>&1
check "bad date format" has "$WORK/out" "Write the date like this"

# --- The root side only answers what it knows -----------------------------
check "unknown request" eq "$(serve 'cat /etc/passwd')" "error unknown request"
check "status without a key" eq "$(serve status)" "none"
check "seal: not a key" eq "$(serve "seal abc1 - 604800 86400")" "error That isn't a key."
check "seal: odd schedule" eq "$(serve "seal $SECRET_A - 86400 86400")" "error That schedule doesn't work."
check "seal: odd date" eq "$(serve "seal $SECRET_A tomorrow 604800 86400")" "error That date doesn't work."
check "seal: a new key needs a date" eq "$(serve "seal $SECRET_A - 604800 86400")" "none"
check "seal: a date in the past" eq "$(serve "seal $SECRET_A $((FAKE_NOW - 5)) 604800 86400")" "refused date"
check "seal: nothing sealed yet" ok test ! -e "$K"

# --- The date is said back first, and a no stops everything ---------------
printf 'n\n' | bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW + 2 * DAY)))" --every 7d > "$WORK/out" 2>&1; rc=$?
check "date not confirmed stops" eq $rc 1
check "date said back" has "$WORK/out" "The key will open on"
check "date not confirmed: nothing sealed" ok test ! -e "$K"
check "date not confirmed: no secret asked" hasnt "$WORK/out" "Secret:"

# --- A wrong answer at the test unlock stops before sealing --------------
printf 'y\n%s\n%s\nn\nn\n' "$SECRET_A" "$SECRET_A" \
  | bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW + 2 * DAY)))" --every 7d > "$WORK/out" 2>&1; rc=$?
check "refused code stops" eq $rc 1
check "refused code: nothing sealed" ok test ! -e "$K"
check "refused code: explains the test password" has "$WORK/out" "password test"

# --- First seal ----------------------------------------------------------
OPEN0=$(( (FAKE_NOW + 2 * DAY) / 60 * 60 ))
{ printf 'y\n'                                            # the date is right
  printf 'abc1\nabc1\n'                                   # not base32
  printf '%s\n%s\n' "$SECRET_A" "${SECRET_A}X"            # mismatch
  printf '%s\n%s\n' "jbsw y3dp ehpk 3pxp jbsw y3dp ehpk 3pxp" "$SECRET_A"   # spaces, lower case
  printf '\ny\ny\n'; } \
  | bash "$SCRIPT" seal --open "$(fmt "$OPEN0")" --every 7d > "$WORK/out" 2>&1; rc=$?
check "first seal succeeds" eq $rc 0
check "rejects characters outside base32" has "$WORK/out" "only has the letters"
check "rejects a mismatch" has "$WORK/out" "didn't match"
check "shows a code for the test unlock" has "$WORK/out" "Code: $(code_for "$SECRET_A")"
check "asks to remove the test password" has "$WORK/out" "Remove the test password"
check "never prints the secret" hasnt "$WORK/out" "$SECRET_A"
check "key holds the secret" eq "$(contents "$K")" "$SECRET_A"
R0=$((OPEN0 / 3 + 1))
check "key sealed to the first date" eq "$(round_of "$K")" "$R0"
check "end marker sealed to the end of the window" eq "$(round_of "$E")" "$((R0 + DAY / 3))"
check "end marker holds no secret" eq "$(contents "$E")" "window end"
check "schedule: date" eq "$(get OPEN_AT)" "$OPEN0"
check "schedule: interval" eq "$(get EVERY)" "$((7 * DAY))"
check "schedule: window" eq "$(get WINDOW)" "$DAY"
check "schedule holds no secret" hasnt "$S" "$SECRET_A"
check "sealed folder is root-only" eq "$(stat -c %a "$DATA")" "700"
check "key file is root-only" eq "$(stat -c %a "$K")" "600"
check "folder kept out of Time Machine" has "$FAKE_LOG/tmutil.log" "addexclusion"
check "no leftover files" ok test ! -e "$K.new" -a ! -e "$E.new" -a ! -e "$DATA/.lock"
check "sealing is logged" has "$OWNDROID_KEY_LOG" "sealed a new key"

# --- Sealed --------------------------------------------------------------
bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "code while sealed fails" eq $rc 1
check "code while sealed says until when" has "$WORK/out" "sealed until"
check "code while sealed shows no code" hasnt "$WORK/out" "Code:"
printf 'y\n%s\n%s\ny\ny\n' "$SECRET_B" "$SECRET_B" | bash "$SCRIPT" seal > "$WORK/out" 2>&1; rc=$?
check "no new key while sealed" eq $rc 1
check "no new key while sealed: explains" has "$WORK/out" "can only be set up while the current one is open"
check "no new key while sealed: key unchanged" eq "$(contents "$K")" "$SECRET_A"
check "root side refuses a new key while sealed" eq "$(serve "seal $SECRET_B - 604800 86400")" "refused sealed $OPEN0"
check "root side refuses to begin while sealed" eq "$(serve begin)" "sealed $OPEN0"
check "root side gives no code while sealed" eq "$(serve code)" "sealed $OPEN0"
bash "$SCRIPT" relock > /dev/null 2>&1
check "relock while sealed changes nothing" eq "$(round_of "$K")" "$R0"

# --- The window opens ----------------------------------------------------
FAKE_NOW=$((OPEN0 + 3600))
bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "code while open works" eq $rc 0
check "code while open shows the code" has "$WORK/out" "Code: $(code_for "$SECRET_A")"
check "code says until when" has "$WORK/out" "open until"
check "code never prints the secret" hasnt "$WORK/out" "$SECRET_A"
check "code leaves no lock behind" ok test ! -e "$DATA/.lock"
check "the root side's answer has only the code" eq "$(serve code | awk '{print $1, $2}')" "code $(code_for "$SECRET_A")"
bash "$SCRIPT" status > "$WORK/out" 2>&1
check "status while open" has "$WORK/out" "open until"

# --- The window ends -----------------------------------------------------
FAKE_NOW=$((OPEN0 + DAY + 60))
bash "$SCRIPT" relock > /dev/null 2>&1
R1=$((R0 + 7 * DAY / 3))
check "relock: sealed to the next date" eq "$(round_of "$K")" "$R1"
check "relock: end marker follows" eq "$(round_of "$E")" "$((R1 + DAY / 3))"
check "relock: same secret" eq "$(contents "$K")" "$SECRET_A"
check "relock: schedule moves on" eq "$(get OPEN_AT)" "$((OPEN0 + 7 * DAY))"
check "relock: logged" has "$OWNDROID_KEY_LOG" "sealed again"
bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "code after the window fails" eq $rc 1

# --- A missed relock: any request closes the window first -----------------
FAKE_NOW=$((OPEN0 + 7 * DAY + DAY + 60))
bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "code after a missed relock fails" eq $rc 1
check "code ran the relock" eq "$(round_of "$K")" "$((R1 + 7 * DAY / 3))"

# --- Mac off for a month: skips windows that have passed -----------------
AT=$(get OPEN_AT)
FAKE_NOW=$((AT + 30 * DAY + 3600))
bash "$SCRIPT" relock > /dev/null 2>&1
NR=$(round_of "$K")
CUR=$((FAKE_NOW / 3 + 1))
check "after a month off: window not already over" ok test $((NR + DAY / 3)) -gt "$CUR"
check "after a month off: no further than one interval ahead" ok test "$NR" -le $((CUR + 7 * DAY / 3))
check "after a month off: still the same cycle" eq $(( (NR - R0) % (7 * DAY / 3) )) 0
check "after a month off: schedule matches the key" eq "$(( ($(get OPEN_AT) - OPEN0) / 3 ))" "$((NR - R0))"

# --- Offline: nothing changes ---------------------------------------------
AT=$(get OPEN_AT)
FAKE_NOW=$((AT + DAY + 60))
BEFORE=$(round_of "$K")
FAKE_OFFLINE=1 bash "$SCRIPT" relock > /dev/null 2>&1
check "offline relock leaves the key" eq "$(round_of "$K")" "$BEFORE"
FAKE_OFFLINE=1 bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "offline code fails" eq $rc 1
check "offline code blames the network" has "$WORK/out" "Couldn't reach drand"
bash "$SCRIPT" relock > /dev/null 2>&1
check "back online: relock catches up" ok test "$(round_of "$K")" -gt "$BEFORE"

# --- A missing end marker is made again ----------------------------------
rm -f "$E"
bash "$SCRIPT" relock > /dev/null 2>&1
check "end marker made again" eq "$(round_of "$E")" "$(( $(round_of "$K") + DAY / 3 ))"

# --- New key while open ---------------------------------------------------
AT=$(get OPEN_AT)
RK=$(round_of "$K")
FAKE_NOW=$((AT + 7200))
printf 'y\n%s\n%s\ny\ny\n' "$SECRET_B" "$SECRET_B" | bash "$SCRIPT" seal > "$WORK/out" 2>&1; rc=$?
check "new key while open succeeds" eq $rc 0
check "new key: shows the old key's code to get in" has "$WORK/out" "Code: $(code_for "$SECRET_A")"
check "new key: asks to remove the old one first" has "$WORK/out" "Remove TOTP, and confirm. Then tap Set up TOTP"
check "new key: test code uses the new secret" has "$WORK/out" "Code: $(code_for "$SECRET_B")"
check "new key: sealed to the next date" eq "$(round_of "$K")" "$((RK + 7 * DAY / 3))"
check "new key: holds the new secret" eq "$(contents "$K")" "$SECRET_B"
check "new key: schedule moves on" eq "$(get OPEN_AT)" "$((AT + 7 * DAY))"
check "new key: hold cleared" ok test ! -e "$DATA/rekey"
bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "new key: sealed straight away" eq $rc 1

# --- A new key started in the window may still be sealed after it ---------
AT=$(get OPEN_AT)
RK=$(round_of "$K")
FAKE_NOW=$((AT + DAY - 600))                  # 10 minutes before the window ends
check "begin while open" eq "$(serve begin)" "ok"
FAKE_NOW=$((AT + DAY + 900))                  # the window ended meanwhile
bash "$SCRIPT" relock > /dev/null 2>&1
check "window closed while setting up: old key sealed again" eq "$(round_of "$K")" "$((RK + 7 * DAY / 3))"
check "the new key is still taken" eq "$(serve "seal $SECRET_C - $((7 * DAY)) $DAY")" "sealed $((AT + 7 * DAY))"
check "the new key opens on the next date, not one later" eq "$(round_of "$K")" "$((RK + 7 * DAY / 3))"
check "the new key holds the new secret" eq "$(contents "$K")" "$SECRET_C"
check "only once" eq "$(serve "seal $SECRET_A - $((7 * DAY)) $DAY")" "refused sealed $((AT + 7 * DAY))"

# --- ...but not hours later ------------------------------------------------
AT=$(get OPEN_AT)
FAKE_NOW=$((AT + 3600))
check "begin again" eq "$(serve begin)" "ok"
FAKE_NOW=$((AT + DAY + 3 * 3600))             # 3 hours after the window
check "a stale hold doesn't count" eq "$(serve "seal $SECRET_A - $((7 * DAY)) $DAY")" "refused sealed $((AT + 7 * DAY))"
check "key unchanged" eq "$(contents "$K")" "$SECRET_C"

# --- status ---------------------------------------------------------------
bash "$SCRIPT" status > "$WORK/out" 2>&1
check "status: sealed until" has "$WORK/out" "sealed until"
check "status: schedule" has "$WORK/out" "24 hours once every 7 days"
check "status leaves no lock behind" ok test ! -e "$DATA/.lock"

check "no error files left behind" eq "$(ls -A "$DATA" | grep -c tle-error)" "0"

# --- Sealing again fails at the end of a window: codes stop anyway ---------
AT=$(get OPEN_AT)
RK=$(round_of "$K")
FAKE_NOW=$((AT + 3600))
check "full disk: code while open" eq "$(serve code | awk '{print $1, $2}')" "code $(code_for "$SECRET_C")"
FAKE_NOW=$((AT + DAY + 60))                              # the window is over...
FAKE_NOSEAL=1 bash "$SCRIPT" relock > /dev/null 2>&1    # ...and the disk is full
check "full disk: key not sealed again" eq "$(round_of "$K")" "$RK"
check "full disk: no half-written files" ok test ! -e "$K.new" -a ! -e "$E.new" -a ! -e "$S.new"
check "full disk: logged" has "$OWNDROID_KEY_LOG" "no space left"
FAKE_NOSEAL=1 bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "full disk: no code after the window" eq $rc 1
check "full disk: says the window is over" has "$WORK/out" "The window is over"
check "full disk: shows no code" hasnt "$WORK/out" "Code:"
check "full disk: root side gives no code" eq "$(FAKE_NOSEAL=1 serve code | cut -c1-25)" "error The window is over,"
check "full disk: no new key begun" eq "$(FAKE_NOSEAL=1 serve begin | cut -c1-25)" "error The window is over,"
FAKE_NOSEAL=1 bash "$SCRIPT" status > "$WORK/out" 2>&1
check "full disk: status says so" has "$WORK/out" "The window is over"
bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "disk freed: sealed again on the next request" eq "$(round_of "$K")" "$((RK + 7 * DAY / 3))"
check "disk freed: still no code" eq $rc 1

# --- An end marker that doesn't match the key, and can't be made again -----
AT=$(get OPEN_AT)
FAKE_NOW=$((AT + 3600))
printf 'window end' | "$RBIN/tle" -e -f -r $(( $(round_of "$K") + 2 * DAY / 3 )) -o "$E"   # two days long
check "odd end marker: no code" eq "$(FAKE_NOSEAL=1 serve code | cut -c1-28)" "error The key's files aren't"
bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
check "odd end marker: made again, then a code" eq $rc 0
check "odd end marker: matches the key again" eq "$(round_of "$E")" "$(( $(round_of "$K") + DAY / 3 ))"

# --- A new key begun in a window that then can't be sealed again ----------
RK=$(round_of "$K")
rm -f "$DATA/rekey"
FAKE_NOW=$((AT + DAY + 300))
check "full disk, nothing begun: new key refused" eq "$(FAKE_NOSEAL=1 serve "seal $SECRET_A - $((7 * DAY)) $DAY")" "refused sealed $AT"
FAKE_NOW=$((AT + DAY - 600))
check "full disk: begin in the window" eq "$(serve begin)" "ok"
FAKE_NOW=$((AT + DAY + 300))
check "full disk, new key begun: tried" eq "$(FAKE_NOSEAL=1 serve "seal $SECRET_A - $((7 * DAY)) $DAY" | cut -c1-11)" "error write"
check "full disk, new key begun: old key kept" eq "$(contents "$K")" "$SECRET_C"
check "disk freed: the new key is taken" eq "$(serve "seal $SECRET_A - $((7 * DAY)) $DAY")" "sealed $((AT + 7 * DAY))"
check "disk freed: the new key holds the new secret" eq "$(contents "$K")" "$SECRET_A"
check "disk freed: the new key opens on the next date" eq "$(round_of "$K")" "$((RK + 7 * DAY / 3))"
check "still no error files left behind" eq "$(ls -A "$DATA" | grep -c tle-error)" "0"

# --- Another account can't read the root side's files ---------------------
# (Only when the tests run as root and the nobody account exists.)
if [ "$(id -u)" = 0 ] && id nobody >/dev/null 2>&1; then
  chmod 755 "$WORK" "$OWNDROID_KEY_ROOT"
  check "another account can't read the key" ok su nobody -s /bin/sh -c "! cat '$K' 2>/dev/null"
  check "another account can't list the folder" ok su nobody -s /bin/sh -c "! ls '$DATA' 2>/dev/null"
  printf 'status\n' | su nobody -s /bin/bash -c "OWNDROID_KEY_ROOT='$OWNDROID_KEY_ROOT' bash '$SCRIPT' serve" > "$WORK/out" 2>/dev/null
  check "the root side doesn't work from another account" has "$WORK/out" "isn't set up right"
  chmod 700 "$WORK"
fi

# --- Ctrl-D never leaves a question looping --------------------------------
export OWNDROID_KEY_ROOT="$WORK/root2"
mkdir -p "$OWNDROID_KEY_ROOT/bin" && cp "$RBIN"/* "$OWNDROID_KEY_ROOT/bin/"
K2="$OWNDROID_KEY_ROOT/sealed/key.tle"
printf 'y\n%s\n%s\ny\n' "$SECRET_A" "$SECRET_A" \
  | timeout 20 bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW + 2 * DAY)))" --every 30d > "$WORK/out" 2>&1; rc=$?
check "Ctrl-D at the last check stops" eq $rc 1
check "Ctrl-D at the last check: key sealed" ok test -s "$K2"
check "Ctrl-D at the last check: says what to check" has "$WORK/out" "Stopped before the last check"
check "30 days said as days" has "$WORK/out" "once every 30 days"
rm -rf "$OWNDROID_KEY_ROOT/sealed"
printf 'y\n%s\n%s\ny\n' "$SECRET_A" "$SECRET_A" \
  | FAKE_OFFLINE=1 timeout 20 bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW + 2 * DAY)))" --every 30d > "$WORK/out" 2>&1; rc=$?
check "offline sealing, then Ctrl-D, stops" eq $rc 1
check "offline sealing: nothing sealed" ok test ! -e "$K2"
check "offline sealing: the test password still works" has "$WORK/out" "still opens with the password test"

# --- Through a socket, like launchd's inetd mode -------------------------
# A small server hands each connection to "owndroid-key serve" as its stdin
# and stdout, and your side talks to it with nc -U.
if command -v python3 >/dev/null 2>&1 && command -v nc >/dev/null 2>&1; then
  rm -rf "$OWNDROID_KEY_ROOT/sealed"
  export OWNDROID_KEY_SOCKET="$WORK/key.sock"
  python3 - "$OWNDROID_KEY_SOCKET" "$SCRIPT" <<'EOF' &
import socket, subprocess, sys
path, script = sys.argv[1], sys.argv[2]
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.bind(path)
s.listen(5)
while True:
    c, _ = s.accept()
    subprocess.Popen(['bash', script, 'serve'], stdin=c.fileno(), stdout=c.fileno(), stderr=c.fileno())
    c.close()
EOF
  SERVER=$!
  for i in 1 2 3 4 5 6 7 8 9 10; do [ -S "$OWNDROID_KEY_SOCKET" ] && break; sleep 0.2; done
  unset OWNDROID_KEY_DIRECT
  printf 'y\n%s\n%s\ny\ny\n' "$SECRET_A" "$SECRET_A" \
    | timeout 30 bash "$SCRIPT" seal --open "$(fmt $((FAKE_NOW + 2 * DAY)))" --every 30d > "$WORK/out" 2>&1; rc=$?
  check "socket: seal" eq $rc 0
  timeout 30 bash "$SCRIPT" status > "$WORK/out" 2>&1
  check "socket: status" has "$WORK/out" "sealed until"
  timeout 30 bash "$SCRIPT" code > "$WORK/out" 2>&1; rc=$?
  check "socket: no code while sealed" eq $rc 1
  OWNDROID_KEY_SOCKET="$WORK/missing.sock" bash "$SCRIPT" status > "$WORK/out" 2>&1
  check "no socket: says the service isn't running" has "$WORK/out" "isn't running"
fi

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
