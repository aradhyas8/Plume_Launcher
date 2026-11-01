#!/bin/sh
# plume-install.sh: set Plume up over adb for the TV it finds, and put everything back with --undo.
#
# It looks at the TV first (Fire TV and its Fire OS version, Google TV, or Android TV, and the Android version),
# tells you what it found and what it is going to change, and asks before it changes anything. Then it runs only
# the steps that belong to that kind of TV, and checks that they worked. Run it from a computer on the same
# network as the TV.
#
# Usage:   ./plume-install.sh [options] [TV_IP[:PORT]]
#   -a APK                  install this Plume APK first (adb install -r)
#   -n                      only show what was found and what would change
#   -y                      do not ask before changing
#   --disable-stock-launcher  also turn off the TV's own launcher (Fire TV only). Off unless you ask for it.
#   --undo                  put back what this script changed, and only that: it turns the stock launcher on again
#                           (if this script turned it off), turns the Home button service off (if this script
#                           turned it on) and waits until Plume has put its alarm setting back, and takes back the
#                           grant (if this script gave it). A service that was already on stays on. The APK
#                           stays installed: uninstall Plume in Settings > Apps if you want it gone.
#   -h                      this help
#
# What the steps are:
#   all TVs        give Plume the WRITE_SECURE_SETTINGS grant, turn on its Home button service, wait until
#                  the TV says the service is running
#   Fire TV        Android 9 to 11 (Fire OS 7 and 8): a Plume with the alarm change sets the alarm timing
#                  (min_futurity) by itself when its service starts. The script looks whether it did. A Plume
#                  without that change leaves it, and Home then takes about 5 seconds. See CONTRIBUTING.md, "alarms".
#   Fire TV, --disable-stock-launcher: the script first writes a recovery record on the TV, turns the launcher
#                  off, checks that Plume's service runs and that Home opens Plume, and turns the launcher on
#                  again if either check fails.
#   Google TV      Plume's service takes the Home button before Google TV sees it. Nothing else is needed.
#                  The script never turns off Google TV's launcher: that can stop the TV from starting.
#
# On the TV: turn ADB debugging on. Fire TV: Settings > My Fire TV > Developer options > ADB debugging
# (if Developer options is missing: Settings > My Fire TV > About, then click the device name 7 times).
# Google TV and Android TV: Settings > System > About, press "Android TV OS build" 7 times, then
# Settings > System > Developer options > USB debugging or Network debugging. The first connection shows
# "Allow USB debugging?" on the TV: tick "Always allow" and accept.

PKG=dev.plume
SERVICE=$PKG/$PKG.HomeOverride
GRANT=android.permission.WRITE_SECURE_SETTINGS
STATE=/data/local/tmp/plume-install.state
FIRE_LAUNCHER=com.amazon.tv.launcher

APK= DRYRUN=0 YES=0 UNDO=0 STOCK=0 ADDR=
while [ $# -gt 0 ]; do
  case $1 in
    -a) [ -n "$2" ] || { echo "-a needs the path of an APK" >&2; exit 2; }; APK=$2; shift ;;
    -n) DRYRUN=1 ;;
    -y) YES=1 ;;
    --undo) UNDO=1 ;;
    --disable-stock-launcher) STOCK=1 ;;
    -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "Unknown option: $1 (use -h)" >&2; exit 2 ;;
    *) ADDR=$1 ;;
  esac
  shift
done

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || die "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
[ -z "$APK" ] || [ -f "$APK" ] || die "no such file: $APK"

if [ -n "$ADDR" ]; then
  case $ADDR in *:*) D=$ADDR ;; *) D=$ADDR:5555 ;; esac
  adb connect "$D" >/dev/null 2>&1
else
  D=$(adb devices | awk 'NR>1 && $2!="" {print $1}')
  [ "$(printf '%s\n' "$D" | grep -c .)" -eq 1 ] || die "give the TV address (./plume-install.sh 192.168.1.20), or connect exactly one device first"
fi
tries=0
while [ "$(adb -s "$D" get-state 2>/dev/null)" != device ]; do
  tries=$((tries + 1))
  [ $tries -gt 30 ] && die "the TV did not answer. Is ADB debugging on? If the TV shows 'Allow USB debugging?', accept it and run this again."
  [ $tries -eq 1 ] && echo "Waiting for the TV... accept 'Allow USB debugging?' on the screen if it shows up."
  sleep 2
done

# tvr keeps both the text (TVOUT) and adb's exit status (TVRC). An older adb may answer 0 for a command that failed,
# so a command that failed on the TV also says so in its text.
tvr() { TVOUT=$(adb -s "$D" shell "$@" 2>&1); TVRC=$?; TVOUT=$(printf '%s' "$TVOUT" | tr -d '\r'); }
tv() { tvr "$@"; printf '%s\n' "$TVOUT"; }
tvok() { tvr "$@"; [ "$TVRC" -eq 0 ] || return 1; case $TVOUT in *Exception*|*Error*|*error*|*Failure*|*Unknown*|*"not found"*) return 1 ;; esac; }
say() { printf '%s\n' "$*"; }
ok() { printf '  ok      %s\n' "$*"; }
bad() { printf '  FAILED  %s\n' "$*"; FAILED=1; }
note() { printf '  note    %s\n' "$*"; }
FAILED=0
# The recovery record: what this script changed, kept on the TV so that --undo puts back that and nothing else.
# A line is written and read back before the change it stands for. If that fails, the change is not made.
record() { tvok "echo '$1' >> $STATE" && tv cat $STATE | grep -qx "$1"; }
unrecord() { tvok "grep -vx '$1' $STATE > $STATE.new; mv $STATE.new $STATE"; }
has() { printf '%s\n' "$STATEDATA" | grep -qx "$1"; }
disabled() { tv pm list packages -d | grep -q "package:$1\$"; }

# ---- What is this TV? ------------------------------------------------------------------------------------------
MAKER=$(tv getprop ro.product.manufacturer)
MODEL=$(tv getprop ro.product.model)
ANDROID=$(tv getprop ro.build.version.release)
SDK=$(tv getprop ro.build.version.sdk)
case $SDK in ''|*[!0-9]*) die "cannot read the Android version of this TV" ;; esac
FEATURES=$(tv pm list features)
PACKAGES=$(tv pm list packages)
if printf '%s\n' "$FEATURES" | grep -q 'amazon.hardware.fire_tv'; then
  KIND=fire
  # Amazon: Fire OS 6 is Android 7.1, 7 is Android 9, 8 is Android 10 and 11, 14 is Android 12 to 14, 16 is Android 15 and 16.
  case $SDK in 25) OS="Fire OS 6" ;; 28) OS="Fire OS 7" ;; 29|30) OS="Fire OS 8" ;; 31|32|33|34) OS="Fire OS 14" ;; 35|36) OS="Fire OS 16" ;; *) OS="Fire OS" ;; esac
  KINDNAME="Fire TV ($OS, Android $ANDROID)"
elif printf '%s\n' "$PACKAGES" | grep -q 'package:com.google.android.apps.tv.launcherx$'; then
  KIND=google
  KINDNAME="Google TV (Android $ANDROID)"
else
  KIND=android
  KINDNAME="Android TV (Android $ANDROID)"
fi
VERSION=$(tv dumpsys package $PKG | sed -n 's/^ *versionName=//p' | head -1)
INSTALLED=0
printf '%s\n' "$PACKAGES" | grep -q "package:$PKG\$" && INSTALLED=1
GRANTED=0
tv dumpsys package $PKG | grep -q "$GRANT: granted=true" && GRANTED=1
ENABLED=$(tv settings get secure enabled_accessibility_services)
[ "$ENABLED" = null ] && ENABLED=
STATEDATA=$(tv cat $STATE)
case $STATEDATA in *"No such file"*) STATEDATA= ;; esac
is_listed() { printf '%s\n' "$ENABLED" | tr ':' '\n' | grep -qE "^($SERVICE|$PKG/\.HomeOverride)\$"; }
LISTED=0
is_listed && LISTED=1
# "services:{" on Fire OS 7 (Android 9), "Bound services:{" later. Fire OS names a service by its label ("Plume: Home button").
bound() { tv dumpsys accessibility | grep -E 'Bound services|^ *services:\{' | grep -qi plume; }
MINFUT() { tv settings get global alarm_manager_constants | tr ',' '\n' | sed -n 's/^min_futurity=//p' | head -1; }

say "Found: $MAKER $MODEL, $KINDNAME"
if [ $INSTALLED = 1 ]; then say "Plume: installed${VERSION:+, version $VERSION}"; else say "Plume: not installed"; fi
[ $INSTALLED = 1 ] && say "WRITE_SECURE_SETTINGS grant: $([ $GRANTED = 1 ] && echo given || echo "not given")"
[ $INSTALLED = 1 ] && say "Home button service: $([ $LISTED = 1 ] && echo "switched on$(bound && echo ' and running')" || echo "off")"
say

[ "$SDK" -ge 26 ] || die "Plume needs Android 8 or newer. This TV has Android $ANDROID."

# ---- Undo ---------------------------------------------------------------------------------------------------------
if [ $UNDO = 1 ]; then
  U_STOCK=$(printf '%s\n' "$STATEDATA" | sed -n 's/^stock=//p' | head -1)
  U_SERVICE=0; has service=1 && U_SERVICE=1
  U_GRANT=0; has granted=1 && U_GRANT=1
  if [ -z "$U_STOCK" ] && [ $U_SERVICE = 0 ] && [ $U_GRANT = 0 ]; then
    say "Nothing to undo: this script has not changed anything on this TV."
    [ $LISTED = 1 ] && say "Plume's Home button service is on, and stays on: it was not this script that turned it on."
    exit 0
  fi
  say "This will:"
  [ -n "$U_STOCK" ] && say "  - turn the stock launcher on again ($U_STOCK; this script turned it off)"
  [ $U_SERVICE = 1 ] && say "  - turn off Plume's Home button service (this script turned it on; Plume then puts its alarm setting back)"
  [ $U_GRANT = 1 ] && say "  - take back the $GRANT grant (this script gave it)"
  [ $U_SERVICE = 0 ] && [ $LISTED = 1 ] && say "  - leave Plume's Home button service on (it was on before this script)"
  say "  - leave Plume installed (uninstall it in Settings > Apps if you want it gone)"
  say
  [ $DRYRUN = 1 ] && exit 0
  if [ $YES = 0 ]; then
    printf 'Go on? [y/N] '; read ans; case $ans in y|Y|yes) ;; *) say "Nothing changed."; exit 0 ;; esac
  fi
  say "Undoing:"
  # The launcher comes back first: with the service off and the launcher off, Home would go nowhere.
  if [ -n "$U_STOCK" ]; then
    if tvok pm enable "$U_STOCK" && ! disabled "$U_STOCK"; then ok "stock launcher on again"; unrecord "stock=$U_STOCK" || bad "could not update the record on the TV"
    else bad "could not turn $U_STOCK on. Run: adb shell pm enable $U_STOCK"; fi
  fi
  if [ $U_SERVICE = 1 ]; then
    if [ $LISTED = 1 ]; then
      NEW=$(printf '%s\n' "$ENABLED" | tr ':' '\n' | grep -vE "^($SERVICE|$PKG/\.HomeOverride)\$" | paste -sd: -)
      if [ -n "$NEW" ]; then tvok settings put secure enabled_accessibility_services "'$NEW'"
      else tvok settings delete secure enabled_accessibility_services && tvok settings put secure accessibility_enabled 0; fi
      ENABLED=$(tv settings get secure enabled_accessibility_services); [ "$ENABLED" = null ] && ENABLED=
      n=0; while bound && [ $n -lt 15 ]; do sleep 1; n=$((n + 1)); done
      if is_listed || bound; then bad "the TV still runs Plume's service. Turn it off in Settings > Accessibility."
      else ok "Home button service off"; unrecord service=1 || bad "could not update the record on the TV"; fi
    else
      ok "Home button service was already off"; unrecord service=1
    fi
    if [ "$KIND" = fire ] && [ "$SDK" -le 30 ] && ! has futurity0=1 && [ $FAILED = 0 ]; then
      n=0; while [ "$(MINFUT)" = 0 ] && [ $n -lt 10 ]; do sleep 1; n=$((n + 1)); done
      if [ "$(MINFUT)" = 0 ]; then
        bad "alarm timing is still min_futurity=0. If you did not set it yourself, remove it: adb shell settings delete global alarm_manager_constants"
      else ok "alarm timing is the TV's own value"; fi
    fi
  fi
  if [ $U_GRANT = 1 ]; then
    if tvok pm revoke $PKG $GRANT && ! tv dumpsys package $PKG | grep -q "$GRANT: granted=true"; then ok "grant taken back"; unrecord granted=1 || bad "could not update the record on the TV"
    else bad "could not take the grant back"; fi
  fi
  [ $FAILED = 0 ] && tv rm -f $STATE >/dev/null
  [ $FAILED = 0 ] && say "Done. Plume is still installed." || say "Done, with problems (see above). The record on the TV is kept, so you can run --undo again."
  exit $FAILED
fi

# ---- Install: the plan ------------------------------------------------------------------------------------------
if [ $STOCK = 1 ]; then
  [ "$KIND" = fire ] || die "--disable-stock-launcher is for a Fire TV only. On $KINDNAME the stock launcher must stay on: turning it off can stop the TV from starting."
fi
if [ $INSTALLED = 0 ] && [ -z "$APK" ]; then
  die "Plume is not installed on this TV. Give the APK with -a plume.apk, or install it first."
fi

say "This will:"
[ -n "$APK" ] && say "  - install $APK"
[ $GRANTED = 0 ] && say "  - give Plume the $GRANT grant (lets it keep its Home button service on)"
[ $LISTED = 0 ] && say "  - switch on Plume's Home button service in Android's Accessibility settings"
[ $LISTED = 1 ] && say "  - leave Plume's Home button service as it is (it is on already; --undo will leave it on)"
if [ $STOCK = 1 ]; then
  say "  - write a recovery record on the TV, then turn off the Fire TV launcher ($FIRE_LAUNCHER). Home then opens Plume and not Amazon's home."
  say "  - check that Plume's service runs and that Home opens Plume (this opens Settings and presses Home on the TV)."
  say "    If either check fails, the launcher is turned on again."
fi
if [ "$KIND" = fire ] && [ "$SDK" -le 30 ]; then
  say "  - look whether Plume has set min_futurity to 0. A Plume with the alarm change does it by itself when its"
  say "    service starts, so Home does not wait 5 seconds. A Plume without it does not, and Home takes about 5 seconds."
elif [ "$KIND" = fire ]; then
  say "  Note: this Android is newer than 11. Plume does not use its alarm here, so Home may take about 5 seconds."
fi
[ "$KIND" = google ] && say "  Note: Google TV's launcher stays on. Holding Home still opens the Google TV dashboard."
say "  - then wait until the TV says the service is running"
say
if [ $DRYRUN = 1 ]; then say "(-n: nothing was changed.)"; exit 0; fi
if [ $YES = 0 ]; then
  printf 'Go on? [y/N] '; read ans; case $ans in y|Y|yes) ;; *) say "Nothing changed."; exit 0 ;; esac
fi

# ---- Install: the steps ------------------------------------------------------------------------------------------
say "Setting up:"
if [ -n "$APK" ]; then
  if adb -s "$D" install -r "$APK" >/dev/null 2>&1; then ok "Plume installed"; else bad "could not install $APK"; fi
fi
if [ $GRANTED = 0 ]; then
  if ! record granted=1; then bad "could not write the recovery record on the TV, so the grant is not given"
  elif tvok pm grant $PKG $GRANT && tv dumpsys package $PKG | grep -q "$GRANT: granted=true"; then ok "grant given"
  else unrecord granted=1; bad "the grant was refused. On some TVs, turn on USB debugging (Security settings) as well."; fi
fi
if [ $LISTED = 0 ]; then
  FUT0=$(MINFUT)
  if [ -n "$ENABLED" ]; then NEW="$ENABLED:$SERVICE"; else NEW=$SERVICE; fi
  if ! record service=1; then bad "could not write the recovery record on the TV, so the service is not switched on"
  else
    [ "$KIND" = fire ] && [ "$SDK" -le 30 ] && [ "$FUT0" = 0 ] && { record futurity0=1 || note "could not record that min_futurity was 0 before"; }
    PUT=1
    tvok settings put secure enabled_accessibility_services "'$NEW'" || PUT=0
    tvok settings put secure accessibility_enabled 1 || PUT=0
    ENABLED=$(tv settings get secure enabled_accessibility_services); [ "$ENABLED" = null ] && ENABLED=
    if [ $PUT = 1 ] && is_listed && [ "$(tv settings get secure accessibility_enabled)" = 1 ]; then ok "service switched on in settings"
    else
      bad "the TV did not keep the setting that turns Plume's service on. Check Settings > Accessibility."
      is_listed || unrecord service=1
    fi
  fi
fi
n=0; while ! bound && [ $n -lt 20 ]; do sleep 1; n=$((n + 1)); done
if bound; then ok "the TV says Plume's Home button service is running"
else bad "the TV does not show Plume's service as running. Open Plume once, or check Settings > Accessibility."; fi
if [ "$KIND" = fire ] && [ "$SDK" -le 30 ]; then
  n=0; while [ "$(MINFUT)" != 0 ] && [ $n -lt 10 ]; do sleep 1; n=$((n + 1)); done
  if [ "$(MINFUT)" = 0 ]; then ok "alarm timing set (min_futurity=0)"
  else note "min_futurity is not 0. This Plume does not have the alarm change, so Home takes about 5 seconds. Nothing is wrong."; fi
fi

# The stock launcher goes last, and only when Plume's service runs: with the launcher off and no service, Home goes nowhere.
resumed() { tv dumpsys activity activities | grep ResumedActivity; }
home_opens_plume() {
  tv am start -a android.settings.SETTINGS >/dev/null
  n=0; while resumed | grep -q "$PKG/" && [ $n -lt 5 ]; do sleep 1; n=$((n + 1)); done
  resumed | grep -q "$PKG/" && return 1 # Plume is still in front: Settings did not open, so the test would prove nothing
  tv input keyevent KEYCODE_HOME >/dev/null
  n=0; while [ $n -lt 12 ]; do sleep 1; resumed | grep -q "$PKG/" && return 0; n=$((n + 1)); done
  return 1
}
restore_launcher() {
  if tvok pm enable $FIRE_LAUNCHER && ! disabled $FIRE_LAUNCHER; then ok "Fire TV launcher on again"; unrecord "stock=$FIRE_LAUNCHER"
  else bad "could not turn $FIRE_LAUNCHER on again. Run: adb shell pm enable $FIRE_LAUNCHER"; fi
}
if [ $STOCK = 1 ]; then
  if [ $FAILED != 0 ]; then
    bad "the Fire TV launcher stays on, because the steps above did not all work"
  elif ! record "stock=$FIRE_LAUNCHER"; then
    bad "could not write the recovery record on the TV, so the Fire TV launcher stays on"
  elif ! { tvok pm disable-user --user 0 $FIRE_LAUNCHER && disabled $FIRE_LAUNCHER; }; then
    restore_launcher; bad "could not turn off $FIRE_LAUNCHER"
  else
    ok "Fire TV launcher off"
    if bound && home_opens_plume; then ok "Home opens Plume"
    else restore_launcher; bad "Plume's service or Home did not work without the Fire TV launcher, so the launcher is on again"; fi
  fi
fi
say
if [ $FAILED = 0 ]; then say "Done. Press Home on the remote. Undo with: ./plume-install.sh --undo"; else say "Done, with problems (see above)."; fi
exit $FAILED
