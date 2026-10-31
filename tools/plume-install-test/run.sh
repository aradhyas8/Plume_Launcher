#!/bin/sh
# Tests tools/plume-install.sh against a fake adb (the file "adb" next to this one), so that no TV is needed.
# Run:  sh tools/plume-install-test/run.sh      (needs python3; prints the failed checks and exits 1 if there are any)
#       TESTSH=dash sh tools/plume-install-test/run.sh   runs the script under another shell
# Each test starts from a fresh fake TV. The fake adb is described at the top of the file "adb".

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=$HERE/../plume-install.sh
WORK=$(mktemp -d) || exit 2
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/bin"
cp "$HERE/adb" "$WORK/bin/adb"
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/sleep" # the script waits for the TV; the fake TV answers at once
chmod +x "$WORK/bin/adb" "$WORK/bin/sleep"
PATH=$WORK/bin:$PATH
FAKE_STATE=$WORK/tv.json
export PATH FAKE_STATE
STATE=/data/local/tmp/plume-install.state
SVC=dev.plume/dev.plume.HomeOverride
OTHER=com.example/com.example.Other
N=0 BAD=0

# new KIND [JSON]: a fresh TV of that kind, with nothing set up.
new() {
  rm -f "$FAKE_STATE"
  unset FAKE_INIT FAKE_FAIL FAKE_FAIL_SILENT FAKE_NOALARM FAKE_HOME_BROKEN
  FAKE_KIND=$1; export FAKE_KIND
  [ -z "$2" ] || { FAKE_INIT=$2; export FAKE_INIT; }
}
# run ARGS...: the script with -y on the fake TV; its output is in $OUT and its exit status in $RC.
run() { OUT=$(${TESTSH:-sh} "$SCRIPT" -y "$@" 10.0.0.2 2>&1); RC=$?; }
# st KEY: a value from the fake TV's state.
st() { python3 -c 'import json, sys; v = json.load(open(sys.argv[1]))[sys.argv[2]]; print(v if not isinstance(v, dict) else v.get(sys.argv[3], ""), end="")' "$FAKE_STATE" "$1" "$2"; }
record() { st files $STATE; }
check() { # check "what" 'shell test'
  N=$((N + 1))
  if ! eval "$2"; then BAD=$((BAD + 1)); printf 'FAIL: %s\n    test: %s\n' "$1" "$2"; printf '%s\n' "$OUT" | sed 's/^/    | /'; fi
}
has() { printf '%s\n' "$1" | grep -qF -- "$2"; }

# ---- a TV with nothing set up -------------------------------------------------------------------------------------
new fire7
run -n
check "-n changes nothing" '[ $RC = 0 ] && [ -z "$(st enabled)" ] && [ "$(st granted)" = False ]'
check "-n says what it found" 'has "$OUT" "Fire TV (Fire OS 7, Android 9)"'

new fire7
run
check "install on Fire OS 7 works" '[ $RC = 0 ] && ! has "$OUT" FAILED'
check "install: service listed, grant given" 'has "$(st enabled)" $SVC && [ "$(st granted)" = True ]'
check "install: Plume set min_futurity" 'has "$(st global)" min_futurity=0'
check "install: the record lists service and grant" 'has "$(record)" service=1 && has "$(record)" granted=1'
run --undo
check "undo works" '[ $RC = 0 ] && ! has "$OUT" FAILED'
check "undo: service off, grant back, alarm back" '[ -z "$(st enabled)" ] && [ "$(st granted)" = False ] && ! has "$(st global)" min_futurity'
check "undo: the APK stays installed and the record is gone" '[ "$(st installed)" = True ] && [ -z "$(record)" ] && has "$OUT" "still installed"'
run --undo
check "undo twice says there is nothing to undo" '[ $RC = 0 ] && has "$OUT" "Nothing to undo"'

new fire8
run
check "Fire OS 8 install works" '[ $RC = 0 ] && has "$OUT" "Fire OS 8" && has "$(st global)" min_futurity=0'

new fire14
run
check "Fire OS 14 is named, and the alarm is not used" '[ $RC = 0 ] && has "$OUT" "Fire OS 14, Android 14" && ! has "$OUT" min_futurity=0 && has "$OUT" "about 5 seconds"'

new google
run
check "Google TV install works" '[ $RC = 0 ] && ! has "$OUT" FAILED && has "$(st enabled)" $SVC'
run --undo
check "Google TV undo works" '[ $RC = 0 ] && [ -z "$(st enabled)" ]'

# ---- a service that is on already ---------------------------------------------------------------------------------
new fire7 "{\"enabled\": \"$OTHER:$SVC\", \"granted\": true}"
run
check "an existing service is left alone" '[ $RC = 0 ] && ! has "$(record)" service=1 && ! has "$(record)" granted=1'
run --undo
check "undo leaves an existing service and its grant on" '[ $RC = 0 ] && has "$(st enabled)" $SVC && has "$(st enabled)" $OTHER && [ "$(st granted)" = True ]'
check "undo says that the service was not turned on by the script" 'has "$OUT" "Nothing to undo" && has "$OUT" "stays on"'

new fire7 "{\"enabled\": \"$OTHER\"}"
run
check "another service stays in the list after install" '[ $RC = 0 ] && has "$(st enabled)" $OTHER && has "$(st enabled)" $SVC'
run --undo
check "another service stays in the list after undo" '[ $RC = 0 ] && [ "$(st enabled)" = $OTHER ]'

new fire7 "{\"enabled\": \"$SVC\"}"
run
run --undo
check "a service that was on without a grant: the grant given by the script is taken back, the service stays" 'has "$(st enabled)" $SVC && [ "$(st granted)" = False ]'

new fire7 "{\"global\": \"min_futurity=3000,other=1\"}"
run
run --undo
check "a min_futurity the TV had comes back" '[ "$(st global)" = "min_futurity=3000,other=1" ]'

new fire7 "{\"global\": \"min_futurity=0\"}"
run
run --undo
check "a min_futurity that was 0 before is not reported as a problem" '[ $RC = 0 ] && ! has "$OUT" FAILED'

# ---- a Plume without the alarm change -----------------------------------------------------------------------------
new fire7
FAKE_NOALARM=1; export FAKE_NOALARM
run
check "no alarm change: installed, with a note and not a failure" '[ $RC = 0 ] && ! has "$OUT" FAILED && has "$OUT" "does not have the alarm change"'
run --undo
check "no alarm change: undo works" '[ $RC = 0 ] && [ -z "$(st enabled)" ]'

# ---- commands that fail -------------------------------------------------------------------------------------------
new fire7
FAKE_FAIL="pm grant"; export FAKE_FAIL
run
check "a refused grant fails the run and leaves no record" '[ $RC = 1 ] && has "$OUT" "the grant was refused" && ! has "$(record)" granted=1'

new fire7
FAKE_FAIL_SILENT="pm grant"; export FAKE_FAIL_SILENT
run
check "a grant that fails with only an exit status is caught" '[ $RC = 1 ] && has "$OUT" "the grant was refused" && [ "$(st granted)" = False ]'

new fire7
FAKE_FAIL_SILENT="settings put secure enabled_accessibility_services"; export FAKE_FAIL_SILENT
run
check "a setting the TV did not keep fails the run and leaves no service record" '[ $RC = 1 ] && has "$OUT" "did not keep the setting" && ! has "$(record)" service=1 && [ -z "$(st enabled)" ]'

new fire7
FAKE_FAIL="accessibility_enabled"; export FAKE_FAIL
run
check "accessibility_enabled that is refused is caught" '[ $RC = 1 ] && has "$OUT" "did not keep the setting"'

new fire7
FAKE_FAIL="service=1"; export FAKE_FAIL
run
check "no recovery record for the service: the service is not switched on" '[ $RC = 1 ] && has "$OUT" "recovery record" && [ -z "$(st enabled)" ]'

new fire7
run
FAKE_FAIL="pm revoke"; export FAKE_FAIL
run --undo
check "a grant that cannot be taken back fails undo and keeps the record" '[ $RC = 1 ] && has "$OUT" "could not take the grant back" && has "$(record)" granted=1'
unset FAKE_FAIL
run --undo
check "undo can be run again after a failure" '[ $RC = 0 ] && [ "$(st granted)" = False ] && [ -z "$(record)" ]'

# ---- the stock launcher -------------------------------------------------------------------------------------------
new fire7
run --disable-stock-launcher
check "stock launcher off: Plume answers Home" '[ $RC = 0 ] && has "$OUT" "Home opens Plume" && has "$(st disabled)" com.amazon.tv.launcher'
check "stock launcher off: the record names it" 'has "$(record)" stock=com.amazon.tv.launcher'
run --undo
check "undo turns the stock launcher on again" '[ $RC = 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(st enabled)" ]'

new fire7
run
check "without the option the stock launcher stays on" '[ "$(st disabled)" = "[]" ]'

new fire7
FAKE_FAIL="stock="; export FAKE_FAIL
run --disable-stock-launcher
check "no recovery record: the stock launcher is not turned off" '[ $RC = 1 ] && has "$OUT" "recovery record" && [ "$(st disabled)" = "[]" ]'

new fire7
FAKE_FAIL_SILENT="pm disable-user"; export FAKE_FAIL_SILENT
run --disable-stock-launcher
check "a launcher that cannot be turned off leaves no record" '[ $RC = 1 ] && [ "$(st disabled)" = "[]" ] && ! has "$(record)" stock='

new fire7
FAKE_HOME_BROKEN=1; export FAKE_HOME_BROKEN
run --disable-stock-launcher
check "Home does not open Plume: the stock launcher is on again" '[ $RC = 1 ] && has "$OUT" "launcher is on again" && [ "$(st disabled)" = "[]" ] && ! has "$(record)" stock='
check "Home does not open Plume: the service stays on, as asked" 'has "$(st enabled)" $SVC'

new fire7
FAKE_HOME_BROKEN=1; FAKE_FAIL="pm enable"; export FAKE_HOME_BROKEN FAKE_FAIL
run --disable-stock-launcher
check "a launcher that cannot be restored says how to do it by hand" '[ $RC = 1 ] && has "$OUT" "adb shell pm enable com.amazon.tv.launcher" && has "$(record)" stock='

new fire7
FAKE_FAIL="pm grant"; export FAKE_FAIL
run --disable-stock-launcher
check "a step that failed first: the stock launcher stays on" '[ $RC = 1 ] && [ "$(st disabled)" = "[]" ]'

new google
run --disable-stock-launcher
check "Google TV refuses to turn its launcher off" '[ $RC != 0 ] && has "$OUT" "Fire TV only" && [ -z "$(st enabled)" ]'

echo "$N checks, $BAD failed"
[ $BAD = 0 ]
