#!/bin/bash
# End-to-end test with the real tle, against a stand-in drand relay
# (test/fakedrand) instead of the internet. It runs on the real clock and
# moves "drand time" forward through the relay's offset file. Needs Go, to
# build the relay and tle (unless you pass the path to a tle).
#   bash test/real-tle.sh [path/to/tle]
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$HERE/owndroid-key"
WORK="$(mktemp -d)"
RELAY=""
trap '[ -n "$RELAY" ] && kill "$RELAY" 2>/dev/null; rm -rf "$WORK"' EXIT
GO=$(command -v go || echo /usr/local/go/bin/go)
[ -x "$GO" ] || { echo "This test needs Go."; exit 1; }

( cd "$HERE/test/fakedrand" && "$GO" build -o "$WORK/fakedrand" . ) || exit 1
REAL_TLE=${1:-}
if [ -z "$REAL_TLE" ]; then
  GOBIN="$WORK" "$GO" install github.com/drand/tlock/cmd/tle@v1.2.0 || exit 1
  REAL_TLE="$WORK/tle"
fi

PORT=$((20000 + RANDOM % 20000))
echo 0 > "$WORK/offset"
"$WORK/fakedrand" "127.0.0.1:$PORT" "$WORK/offset" > "$WORK/relay.txt" 2> "$WORK/relay.log" &
RELAY=$!
for i in $(seq 1 50); do [ -s "$WORK/relay.txt" ] && break; sleep 0.1; done
read -r HASH GENESIS < "$WORK/relay.txt"

export OWNDROID_KEY_ROOT="$WORK/root"
export OWNDROID_KEY_DIRECT="$SCRIPT"
export OWNDROID_KEY_LOG="$WORK/owndroid-key.log"
export OWNDROID_KEY_SOCKET="$WORK/none.sock"
mkdir -p "$OWNDROID_KEY_ROOT/bin"
# The root side runs plain "tle": point it at the stand-in relay. While
# $WORK/noseal exists, sealing fails as on a full disk.
cat > "$OWNDROID_KEY_ROOT/bin/tle" <<EOF
#!/bin/bash
for a in "\$@"; do
  if [ "\$a" = -e ] && [ -e "$WORK/noseal" ]; then echo "write: no space left on device" >&2; exit 1; fi
done
exec "$REAL_TLE" -n "http://127.0.0.1:$PORT" -c "$HASH" "\$@"
EOF
chmod +x "$OWNDROID_KEY_ROOT/bin/tle"

passed=0 failed=0
check() {
  local name=$1; shift
  if "$@"; then passed=$((passed + 1)); else failed=$((failed + 1)); echo "FAIL: $name"; fi
}
has() { grep -q -- "$2" "$1" 2>/dev/null; }
eq() { [ "$1" = "$2" ] || { echo "  expected [$2], got [$1]"; return 1; }; }
near() { [ $(( $1 - $2 )) -ge -1 ] && [ $(( $1 - $2 )) -le 1 ] || { echo "  expected about [$2], got [$1]"; return 1; }; }
ok() { "$@"; }

K="$OWNDROID_KEY_ROOT/sealed/key.tle"
E="$OWNDROID_KEY_ROOT/sealed/end.tle"
round_of() { LC_ALL=C grep -a -m 1 '^-> tlock ' "$1" | awk '{print $3}'; }
cur() { echo $(( ($(date +%s) - GENESIS) / 3 + 1 )); }
publish_to() { echo $(( $1 - $(cur) + 2 )) > "$WORK/offset"; }   # rounds up to $1 are out
# The code shown now, or at the edge of a 30-second step, the one either side.
shows_code_for() {
  local t c
  t=$(date +%s)
  for c in $(bash "$SCRIPT" _totp "$2" $((t - 30))) $(bash "$SCRIPT" _totp "$2" "$t") $(bash "$SCRIPT" _totp "$2" $((t + 30))); do
    grep -q "Code: $c" "$1" && return 0
  done
  return 1
}

SECRET_A=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP
SECRET_B=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
HOUR=3600

# --- Seal, with a first date 15 minutes away ------------------------------
T=$(( ($(date +%s) + 15 * 60) / 60 * 60 ))
printf 'y\n%s\n%s\ny\ny\n' "$SECRET_A" "$SECRET_A" \
  | bash "$SCRIPT" seal --open "$(date -d "@$T" '+%Y-%m-%d %H:%M')" --every 2h --window 1h > "$WORK/out" 2>&1
check "seal works with the real tle" eq $? 0
R=$(round_of "$K")
check "key sealed to the first date" near "$R" $(( (T - GENESIS) / 3 + 1 ))
check "end marker an hour later" eq "$(round_of "$E")" $((R + HOUR / 3))
check "sealed file is root-only" eq "$(stat -c %a "$K")" "600"

bash "$SCRIPT" status > "$WORK/out" 2>&1
check "status: sealed" has "$WORK/out" "sealed until"
bash "$SCRIPT" code > "$WORK/out" 2>&1
check "no code while sealed" eq $? 1

# --- The date comes ----------------------------------------------------
publish_to "$R"
bash "$SCRIPT" code > "$WORK/out" 2>&1
check "code once drand reaches the date" eq $? 0
check "the right code" shows_code_for "$WORK/out" "$SECRET_A"

# --- The window ends on a full disk: codes stop anyway ------------------
publish_to $((R + HOUR / 3))
touch "$WORK/noseal"
bash "$SCRIPT" relock
check "full disk: key not sealed again" eq "$(round_of "$K")" "$R"
bash "$SCRIPT" code > "$WORK/out" 2>&1
check "full disk: no code after the window" eq $? 1
check "full disk: says the window is over" has "$WORK/out" "The window is over"
rm -f "$WORK/noseal"

# --- The window ends ---------------------------------------------------
bash "$SCRIPT" relock
check "sealed again for the next date" eq "$(round_of "$K")" $((R + 2 * HOUR / 3))
check "end marker follows" eq "$(round_of "$E")" $((R + 3 * HOUR / 3))
bash "$SCRIPT" code > "$WORK/out" 2>&1
check "no code after the window" eq $? 1

# --- Next window: a new key --------------------------------------------
publish_to $((R + 2 * HOUR / 3))
bash "$SCRIPT" code > "$WORK/out" 2>&1
check "the same key opens again" shows_code_for "$WORK/out" "$SECRET_A"
printf 'y\n%s\n%s\ny\ny\n' "$SECRET_B" "$SECRET_B" | bash "$SCRIPT" seal > "$WORK/out" 2>&1
check "new key while open" eq $? 0
check "new key sealed to the next date" eq "$(round_of "$K")" $((R + 4 * HOUR / 3))
bash "$SCRIPT" code > "$WORK/out" 2>&1
check "new key sealed straight away" eq $? 1
publish_to $((R + 4 * HOUR / 3))
bash "$SCRIPT" code > "$WORK/out" 2>&1
check "new key opens with its own codes" shows_code_for "$WORK/out" "$SECRET_B"

# --- drand out of reach ------------------------------------------------
kill "$RELAY"; wait "$RELAY" 2>/dev/null; RELAY=""
BEFORE=$(round_of "$K")
bash "$SCRIPT" relock
check "offline: key left alone" eq "$(round_of "$K")" "$BEFORE"
bash "$SCRIPT" code > "$WORK/out" 2>&1
check "offline: code fails" eq $? 1
check "offline: blames the network" has "$WORK/out" "Couldn't reach drand"
check "offline: no half-written files" ok test ! -e "$K.new" -a ! -e "$E.new"

echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
