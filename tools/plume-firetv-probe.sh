#!/bin/sh
# plume-firetv-probe.sh: collect what Plume needs to know about how a Fire TV (or any Android TV) handles the Home
# button, into one text file you can paste into https://github.com/aradhyas8/Plume_Launcher/issues/87
#
# It installs nothing and changes no setting on the TV. It reads settings, listens to the system log and the
# remote's key events while you press a few buttons, and opens the Accessibility settings page once to see
# whether it exists. It clears the TV's log buffers once before it listens (harmless: the log is only a
# short-lived buffer), so that the lines it keeps all come from your button presses. Run it from a computer on the
# same network.
#
# Usage:   ./plume-firetv-probe.sh [options] [TV_IP[:PORT]]
#   -l LABEL  name for this run, e.g. "stock" or "with-plume" (goes in the file name; default: run)
#   -n        no prompts: wait 8 seconds at each step instead of waiting for you to press Enter
#   -h        this help
#
# On the TV: Settings > My Fire TV > Developer options > ADB debugging ON (if Developer options is missing:
# Settings > My Fire TV > About, then click the device name 7 times). The first connection shows
# "Allow USB debugging?" on the TV: tick "Always allow" and accept.
#
# Privacy: the text file keeps only the lines about the Home button and the launcher, and IP addresses, MAC
# addresses and e-mail addresses are replaced. Read it before you post it. The device serial number is never read.
# The script also saves a screenshot of the Accessibility settings page (accessibility-page.png). It is not
# filtered: it can show your account name or a profile name. Look at it first, and post it only if it is clean.

LABEL=run NOPROMPT=0
while getopts "l:nh" o; do
  case $o in
    l) LABEL=$OPTARG ;;
    n) NOPROMPT=1 ;;
    *) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  esac
done
shift $((OPTIND - 1))

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || die "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"

if [ -n "$1" ]; then
  case $1 in *:*) D=$1 ;; *) D=$1:5555 ;; esac
  adb connect "$D" >/dev/null 2>&1
else
  D=$(adb devices | awk 'NR>1 && $2!="" {print $1}')
  [ "$(printf '%s\n' "$D" | grep -c .)" -eq 1 ] || die "give the TV address (./plume-firetv-probe.sh 192.168.1.20), or connect exactly one device first"
fi
tries=0
while [ "$(adb -s "$D" get-state 2>/dev/null)" != device ]; do
  tries=$((tries + 1))
  [ $tries -gt 30 ] && die "the TV did not answer. Is ADB debugging on? If the TV shows 'Allow USB debugging?', accept it and run this again."
  [ $tries -eq 1 ] && echo "Waiting for the TV... accept 'Allow USB debugging?' on the screen if it shows up."
  sleep 2
done

tv() { adb -s "$D" shell "$@" 2>&1 | tr -d '\r'; }
redact() { sed -E 's/([0-9]{1,3}\.){3}[0-9]{1,3}/x.x.x.x/g; s/([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/xx:xx:xx:xx:xx:xx/g; s/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/email-removed/g'; }

STAMP=$(date +%Y%m%d-%H%M%S)
MODEL=$(tv getprop ro.product.model | tr -c 'A-Za-z0-9\n' '_')
DIR="plume-probe-$MODEL-$LABEL-$STAMP"
mkdir -p "$DIR" || die "cannot create $DIR"
OUT="$DIR/probe.txt"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/plume-probe.XXXXXX") || die "cannot create a temporary directory"
LOGPID= EVPID=
cleanup() { [ -n "$LOGPID" ] && kill "$LOGPID" 2>/dev/null; [ -n "$EVPID" ] && kill "$EVPID" 2>/dev/null; rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

section() { printf '\n===== %s =====\n' "$*" | tee -a "$OUT"; }
note()    { printf '%s\n' "$*" | redact | tee -a "$OUT"; }
run()     { printf '$ %s\n' "$*" >>"$OUT"; "$@" 2>&1 | tr -d '\r' | redact | tee -a "$OUT"; }
devrun()  { printf '$ adb shell %s\n' "$*" >>"$OUT"; adb -s "$D" shell "$@" 2>&1 | tr -d '\r' | redact | tee -a "$OUT"; }
devgrep() { pat=$1; shift; printf '$ adb shell %s | grep -E "%s"\n' "$*" "$pat" >>"$OUT"; tv "$@" | grep -E "$pat" | redact | tee -a "$OUT"; }

wait_step() { # $1 = what to do
  printf '\n>>> %s\n' "$1"
  if [ $NOPROMPT -eq 1 ]; then sleep 8; else printf '    (press Enter here when done) '; read -r _ </dev/tty || sleep 8; fi
}

section "Plume Fire TV probe ($LABEL), $(date '+%Y-%m-%d %H:%M')"
note "Script version: 1"

# --- 1. device -----------------------------------------------------------------------------------
section "1. Device and software"
for p in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.build.version.release \
         ro.build.version.sdk ro.build.version.security_patch ro.build.version.incremental ro.build.display.id \
         ro.build.version.name ro.build.fireos.version ro.build.characteristics; do
  printf '%s = %s\n' "$p" "$(tv getprop $p)" | tee -a "$OUT"
done
note "--- launcher and Home-related packages"
devgrep "amazon.tv.launcher|amazon.tahoe|firehomestarter|amazon.tv.quicksettings|tv.settings|launcherx|dev.plume|projectivy|wolf|homeonfire|toolicious" pm list packages
devgrep "versionName|versionCode" dumpsys package com.amazon.tv.launcher

# --- 2. ADB from the TV itself ------------------------------------------------------------------
section "2. ADB reachable from the TV itself (loopback)"
for p in service.adb.tcp.port persist.adb.tcp.port; do printf '%s = %s\n' "$p" "$(tv getprop $p)" | tee -a "$OUT"; done
devrun settings get global adb_enabled
devrun settings get global adb_wifi_enabled
note "Bytes the TV's own adbd answers to a connection from the TV itself (0 = it hangs up; 24+ = it talks):"
printf '$ echo -n | toybox nc -w 2 127.0.0.1 5555 | wc -c\n' >>"$OUT"
tv "echo -n | toybox nc -w 2 127.0.0.1 5555 | wc -c" | tee -a "$OUT"

# --- 3. accessibility ----------------------------------------------------------------------------
section "3. Accessibility"
devrun settings get secure enabled_accessibility_services
devrun settings get secure accessibility_enabled
note "--- Does the Accessibility settings page exist and show anything? (a screenshot is saved next to this file)"
devrun am start -a android.settings.ACCESSIBILITY_SETTINGS
sleep 3
devgrep "mCurrentFocus|mFocusedApp" dumpsys window
adb -s "$D" exec-out screencap -p >"$DIR/accessibility-page.png" 2>/dev/null && note "screenshot: $DIR/accessibility-page.png (look at it before you share it; it can show your account name)"
tv input keyevent KEYCODE_BACK >/dev/null
sleep 1
devgrep "Bound services|Enabled services" dumpsys accessibility

# --- 4. Home role ---------------------------------------------------------------------------------
section "4. Who handles Home"
devrun cmd role get-role-holders --user 0 android.app.role.HOME
devrun cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME

# --- 5. what the Home button does ----------------------------------------------------------------
section "5. What the remote's buttons do"
note "Open any app first (e.g. Settings or a video app) so that Home has somewhere to leave from."
wait_step "On the TV, open an app (not the home screen). Then come back here."

adb -s "$D" logcat -b all -c >/dev/null 2>&1
adb -s "$D" logcat -b all -v time >"$TMP/log.txt" 2>/dev/null &
LOGPID=$!
adb -s "$D" shell getevent -lt >"$TMP/ev.txt" 2>/dev/null &
EVPID=$!
sleep 1

watch() { # $1 = label; samples the foreground activity for 6 seconds, on the TV, 4 times a second
  printf '\n--- %s: focused window every 0.25 s (uptime seconds, activity)\n' "$1" >>"$OUT"
  printf 'MARK %s %s\n' "$1" "$(tv date '+%m-%d %H:%M:%S')" >>"$TMP/marks.txt"
  adb -s "$D" shell 'i=0; while [ $i -lt 24 ]; do echo "$(cut -d" " -f1 /proc/uptime) $(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed "s/^ *//")"; i=$((i+1)); sleep 0.25; done' 2>&1 \
    | tr -d '\r' | awk '{ line=$0; sub(/^[^ ]+ /,"",line); if (line!=prev) print; prev=line }' | redact | tee -a "$OUT"
}

step() { # $1 = label, $2 = instruction
  wait_step "$2"
  watch "$1" &
  W=$!
  sleep 0.5
  printf '    >>> PRESS NOW on the remote: %s  (you have 5 seconds)\n' "$1"
  wait $W
}

step "HOME short press" "Get ready to press HOME on the REMOTE (short press, one time). Press Enter, then press the button."
step "HOME long press"  "Get ready to LONG-press HOME on the remote (hold about 2 seconds). Press Enter, then do it."
step "HOME double press" "Get ready to press HOME TWICE quickly. Press Enter, then do it."
step "Netflix/Prime/other app button" "Get ready to press one of the coloured app buttons on the remote (Netflix, Prime Video...). Press Enter, then do it."
step "BACK" "Get ready to press BACK once (as a reference). Press Enter, then do it."

sleep 1
kill "$LOGPID" "$EVPID" 2>/dev/null; LOGPID= EVPID=

section "6. Keys the remote sent to the TV (kernel level; Home missing here means the remote never sent it)"
grep -E "KEY_" "$TMP/ev.txt" | grep -E "DOWN|UP" | head -80 | redact | tee -a "$OUT"

section "7. System log lines about Home (filtered)"
cat "$TMP/marks.txt" 2>/dev/null | tee -a "$OUT"
grep -E 'cat=\[android\.intent\.category\.HOME\]|HomeActivity|HudActivity|quicksettings|KeyPolicy|handleShortPressOnHome|handleLongPressOnHome|KeyEventDispatcher|AccessibilityManagerService|Background activity start|app-switch|Displayed .*(launcher|tahoe)' "$TMP/log.txt" \
  | head -300 | redact | tee -a "$OUT"

# --- 8. Plume, if present -------------------------------------------------------------------------
if tv pm list packages dev.plume | grep -q "package:dev.plume"; then
  section "8. Plume on this TV"
  devgrep "versionName|installerPackageName|packageSource|WRITE_SECURE_SETTINGS" dumpsys package dev.plume
  devrun appops get dev.plume ACCESS_RESTRICTED_SETTINGS
  devgrep "Bound services|Enabled services" dumpsys accessibility
fi

section "Done"
note "Please post the file $OUT (and the screenshot if it shows only the page, not your account) at"
note "https://github.com/aradhyas8/Plume_Launcher/issues/87 . Thank you!"
