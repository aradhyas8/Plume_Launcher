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
#   --undo                  put back what this script changed: it turns the Home button service off, waits
#                           until Plume has put its alarm setting back, takes back the grant (only if this
#                           script gave it) and turns the stock launcher on again (only if this script turned it off)
#   -h                      this help
#
# What the steps are:
#   all TVs        give Plume the WRITE_SECURE_SETTINGS grant, turn on its Home button service, wait until
#                  the TV says the service is running
#   Fire TV        Android 9 to 11 (Fire OS 7 and 8): Plume sets the alarm timing (min_futurity) by itself when
#                  its service starts. The script checks that it did. See CONTRIBUTING.md, "alarms".
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

tv() { adb -s "$D" shell "$@" 2>&1 | tr -d '\r'; }
say() { printf '%s\n' "$*"; }
# tv() keeps the text, not the exit status: a command that failed on the TV says so in its text.
tvok() { case $(tv "$@") in *Exception*|*Error*|*error*|*Failure*|*Unknown*|*"not found"*) return 1 ;; esac; }
ok() { printf '  ok      %s\n' "$*"; }
bad() { printf '  FAILED  %s\n' "$*"; FAILED=1; }
FAILED=0

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
  case $SDK in 25) OS="Fire OS 6" ;; 28) OS="Fire OS 7" ;; 30) OS="Fire OS 8" ;; *) OS="Fire OS" ;; esac
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
  say "This will:"
  [ $LISTED = 1 ] && say "  - turn off Plume's Home button service (Plume then puts its alarm setting back)"
  tv cat $STATE 2>/dev/null | grep -q '^granted=1' && say "  - take back the $GRANT grant (this script gave it)"
  tv cat $STATE 2>/dev/null | grep -q '^stock=' && say "  - turn the stock launcher on again (this script turned it off)"
  [ $LISTED = 0 ] && ! tv cat $STATE 2>/dev/null | grep -q '=' && { say "Nothing to undo."; exit 0; }
  say
  [ $DRYRUN = 1 ] && exit 0
  if [ $YES = 0 ]; then
    printf 'Go on? [y/N] '; read ans; case $ans in y|Y|yes) ;; *) say "Nothing changed."; exit 0 ;; esac
  fi
  say "Undoing:"
  if [ $LISTED = 1 ]; then
    NEW=$(printf '%s\n' "$ENABLED" | tr ':' '\n' | grep -vE "^($SERVICE|$PKG/\.HomeOverride)\$" | paste -sd: -)
    if [ -n "$NEW" ]; then tv settings put secure enabled_accessibility_services "'$NEW'" >/dev/null
    else tv settings delete secure enabled_accessibility_services >/dev/null; tv settings put secure accessibility_enabled 0 >/dev/null; fi
    n=0; while bound && [ $n -lt 15 ]; do sleep 1; n=$((n + 1)); done
    if bound; then bad "the TV still runs Plume's service. Turn it off in Settings > Accessibility."; else ok "Home button service off"; fi
  fi
  if [ "$KIND" = fire ] && [ "$SDK" -le 30 ]; then
    n=0; while [ "$(MINFUT)" = 0 ] && [ $n -lt 10 ]; do sleep 1; n=$((n + 1)); done
    if [ "$(MINFUT)" = 0 ]; then
      bad "alarm timing is still min_futurity=0. If you did not set it yourself, remove it: adb shell settings delete global alarm_manager_constants"
    else ok "alarm timing back to the TV's own value"; fi
  fi
  if tv cat $STATE 2>/dev/null | grep -q '^granted=1'; then
    tvok pm revoke $PKG $GRANT && ok "grant taken back" || bad "could not take the grant back"
  fi
  LAUNCHER=$(tv cat $STATE 2>/dev/null | sed -n 's/^stock=//p' | head -1)
  if [ -n "$LAUNCHER" ]; then
    tvok pm enable "$LAUNCHER" && ok "stock launcher on again" || bad "could not turn $LAUNCHER on. Run: adb shell pm enable $LAUNCHER"
  fi
  [ $FAILED = 0 ] && tv rm -f $STATE >/dev/null
  [ $FAILED = 0 ] && say "Done." || say "Done, with problems (see above)."
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
STEP=0
[ -n "$APK" ] && say "  - install $APK" && STEP=1
[ $GRANTED = 0 ] && say "  - give Plume the $GRANT grant (lets it keep its Home button service on)" && STEP=1
[ $LISTED = 0 ] && say "  - switch on Plume's Home button service in Android's Accessibility settings" && STEP=1
[ $STOCK = 1 ] && say "  - turn off the Fire TV launcher ($FIRE_LAUNCHER). Home then opens Plume and not Amazon's home." && STEP=1
if [ "$KIND" = fire ] && [ "$SDK" -le 30 ]; then
  say "  - check that Plume has set min_futurity to 0 (Plume does it by itself when its service starts, so Home"
  say "    does not wait 5 seconds; it puts the old value back when the Home button goes off)"
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
  tv pm grant $PKG $GRANT >/dev/null
  if tv dumpsys package $PKG | grep -q "$GRANT: granted=true"; then ok "grant given"; tv "echo granted=1 >> $STATE"; else bad "the grant was refused. On some TVs, turn on USB debugging (Security settings) as well."; fi
fi
if [ $LISTED = 0 ]; then
  if [ -n "$ENABLED" ]; then NEW="$ENABLED:$SERVICE"; else NEW=$SERVICE; fi
  tv settings put secure enabled_accessibility_services "'$NEW'" >/dev/null
  tv settings put secure accessibility_enabled 1 >/dev/null
  ok "service switched on in settings"
fi
if [ $STOCK = 1 ]; then
  if tv pm disable-user --user 0 $FIRE_LAUNCHER | grep -qi 'new state: disabled'; then
    ok "Fire TV launcher off"; tv "echo stock=$FIRE_LAUNCHER >> $STATE"
  else bad "could not turn off $FIRE_LAUNCHER"; fi
fi
n=0; while ! bound && [ $n -lt 20 ]; do sleep 1; n=$((n + 1)); done
if bound; then ok "the TV says Plume's Home button service is running"
else bad "the TV does not show Plume's service as running. Open Plume once, or check Settings > Accessibility."; fi
if [ "$KIND" = fire ] && [ "$SDK" -le 30 ]; then
  n=0; while [ "$(MINFUT)" != 0 ] && [ $n -lt 10 ]; do sleep 1; n=$((n + 1)); done
  if [ "$(MINFUT)" = 0 ]; then ok "alarm timing set (min_futurity=0)"
  else bad "min_futurity is not 0. This version of Plume may not set it yet. Home will take about 5 seconds."; fi
fi
say
if [ $FAILED = 0 ]; then say "Done. Press Home on the remote. Undo with: ./plume-install.sh --undo"; else say "Done, with problems (see above)."; fi
exit $FAILED
