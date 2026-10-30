#!/bin/sh
# plume-firetv-probe.sh: collect what Plume needs to know about how a Fire TV (or any Android TV) handles the Home
# button, into one text file you can paste into https://github.com/aradhyas8/Plume_Launcher/issues/153
#
# It installs nothing and changes no setting on the TV. It reads settings, listens to the system log and the
# remote's key events while you press a few buttons, and opens the Accessibility settings page once to see
# whether it exists. It clears the TV's log buffers once before it listens (harmless: the log is only a
# short-lived buffer), so that the lines it keeps all come from your button presses. Run it from a computer on the
# same network.
#
# The file ends with a short summary: what the TV is, whether Plume sees it as a Fire TV, where each button press
# landed, and how many milliseconds went by between Home and Plume. Post that part if you post nothing else.
#
# Usage:   ./plume-firetv-probe.sh [options] [TV_IP[:PORT]]
#   -l LABEL  name for this run, e.g. "stock" or "with-plume" (goes in the file name; default: run)
#   -n        no prompts: wait 8 seconds at each step instead of waiting for you to press Enter
#   -w        also test waking the TV from standby (experimental: the TV may drop the connection while it sleeps)
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

LABEL=run NOPROMPT=0 WAKE=0
while getopts "l:nwh" o; do
  case $o in
    l) LABEL=$OPTARG ;;
    n) NOPROMPT=1 ;;
    w) WAKE=1 ;;
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
MODEL_RAW=$(tv getprop ro.product.model)
MODEL=$(printf '%s' "$MODEL_RAW" | tr -c 'A-Za-z0-9\n' '_')
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
# devrun and devgrep leave all that the TV said in $R, so that a value is read from the TV once and used from there.
devrun()  { printf '$ adb shell %s\n' "$*" >>"$OUT"; R=$(adb -s "$D" shell "$@" 2>&1 | tr -d '\r'); [ -z "$R" ] || printf '%s\n' "$R" | redact | tee -a "$OUT"; }
devgrep() { pat=$1; shift; printf '$ adb shell %s | grep -E "%s"\n' "$*" "$pat" >>"$OUT"; R=$(tv "$@"); printf '%s\n' "$R" | grep -E "$pat" | redact | tee -a "$OUT"; }

wait_step() { # $1 = what to do
  printf '\n>>> %s\n' "$1"
  if [ $NOPROMPT -eq 1 ]; then sleep 8; else printf '    (press Enter here when done) '; read -r _ </dev/tty || sleep 8; fi
}

gist() { printf '%s\n' "$*" >>"$TMP/summary.txt"; } # lines of the summary printed at the end

section "Plume Fire TV probe ($LABEL), $(date '+%Y-%m-%d %H:%M')"
note "Script version: 2"

# --- 1. device -----------------------------------------------------------------------------------
section "1. Device and software"
for p in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.build.version.release \
         ro.build.version.sdk ro.build.version.security_patch ro.build.version.incremental ro.build.display.id \
         ro.build.version.name ro.build.fireos.version ro.build.characteristics; do
  case $p in ro.product.model) v=$MODEL_RAW ;; *) v=$(tv getprop $p) ;; esac
  printf '%s = %s\n' "$p" "$v" | tee -a "$OUT"
  case $p in ro.product.brand) BRAND=$v ;; ro.build.version.release) ANDROID=$v ;; ro.build.version.sdk) SDK=$v ;; ro.build.display.id) BUILD=$v ;; esac
done
gist "TV: $BRAND $MODEL_RAW, Android $ANDROID (sdk $SDK), build $BUILD"
printf 'seconds since boot = %s\n' "$(tv cat /proc/uptime | cut -d' ' -f1)" | tee -a "$OUT"
note "--- launcher and Home-related packages"
devgrep "amazon.tv.launcher|amazon.tahoe|firehomestarter|amazon.tv.quicksettings|tv.settings|launcherx|dev.plume|projectivy|wolf|homeonfire|toolicious" pm list packages
PKGS=$R
devgrep "versionName|versionCode" dumpsys package com.amazon.tv.launcher
note "--- System features: Plume treats a TV as a Fire TV when amazon.hardware.fire_tv is listed"
devgrep "amazon|fire" pm list features
if printf '%s\n' "$R" | grep -q 'amazon.hardware.fire_tv'; then FIRE=yes; else FIRE=no; fi
note "Plume sees a Fire TV: $FIRE"
gist "Plume sees a Fire TV (amazon.hardware.fire_tv): $FIRE"

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
ENABLED=$R
devrun settings get secure accessibility_enabled
note "--- Does the Accessibility settings page exist and show anything? (a screenshot is saved next to this file)"
devrun am start -a android.settings.ACCESSIBILITY_SETTINGS
sleep 3
devgrep "mCurrentFocus|mFocusedApp" dumpsys window
adb -s "$D" exec-out screencap -p >"$DIR/accessibility-page.png" 2>/dev/null && note "screenshot: $DIR/accessibility-page.png (look at it before you share it; it can show your account name)"
tv input keyevent KEYCODE_BACK >/dev/null
sleep 1
devgrep "Bound services|Enabled services|^ *services:\{" dumpsys accessibility
ACC=$R
case $ENABLED in *dev.plume/*) PLUME_SET=yes ;; *) PLUME_SET=no ;; esac
# The list of bound services starts on a line "Bound services:{" (Android 10 and later) or "services:{" (Fire OS 7,
# Android 9) and can go on over the next lines, up to the next list ("Enabled services:{", "installed services:{"...).
BOUNDLIST=$(printf '%s\n' "$ACC" | awk '/Bound services|^ *services:\{/ { on = 1; print; next } on && /^ *[A-Za-z][A-Za-z ]*:[{[]/ { on = 0 } on')
if [ -n "$BOUNDLIST" ]; then
  # Fire OS names a bound service by its label ("Plume: Home button"), not by its package: look for the name.
  if printf '%s\n' "$BOUNDLIST" | grep -qi plume; then PLUME_BOUND=yes; else PLUME_BOUND=no; fi
else
  PLUME_BOUND=unknown # this Android's dumpsys has no line of bound services
  note "--- This Android's dumpsys has no line of bound services. First lines of what it prints instead:"
  printf '%s\n' "$ACC" | head -40 | redact | tee -a "$OUT"
fi
# "On" is what the steps below act on: bound if the TV says so; else, when it does not say, switched on in settings.
if [ "$PLUME_BOUND" = yes ] || { [ "$PLUME_BOUND" = unknown ] && [ "$PLUME_SET" = yes ]; }; then PLUME_ON=yes; else PLUME_ON=no; fi
note "Plume's Home button service: switched on in Android's settings: $PLUME_SET; bound, as dumpsys says: $PLUME_BOUND"
gist "Plume's Home button service: switched on in settings: $PLUME_SET; bound (dumpsys): $PLUME_BOUND"

# --- 4. Home role ---------------------------------------------------------------------------------
section "4. Who handles Home, and what limits launching an app after Home"
devrun cmd role get-role-holders --user 0 android.app.role.HOME
devrun cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
note "--- Every app that declares itself a home screen (a second one that answers Home, like Google TV's setup app, can matter)"
devrun cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
note "--- What is in front now"
devgrep "mResumedActivity|topResumedActivity" dumpsys activity activities
note "--- Alarm timing. Android holds an app's launch back for about 5 s after Home unless it comes from the system, as an"
note "    alarm does; but an alarm only fires at once if min_futurity is 0. 'null' means the default is in force."
devrun settings get global alarm_manager_constants
CONSTANTS=$R
devrun device_config get alarm_manager min_futurity
MINFUT=$(printf '%s\n' "$R" | head -1)
case $MINFUT in ''|*"not found"*|*inaccessible*) MINFUT="not available on this Android (it starts at 10)" ;; esac
gist "Alarm timing: alarm_manager_constants = $CONSTANTS, min_futurity = $MINFUT"

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

watch() { # $1 = label, $2 = samples (default 40, so 10 seconds); samples the foreground activity on the TV, 4 times a second
  printf '\n--- %s: focused window every 0.25 s (uptime seconds, activity)\n' "$1" >>"$OUT"
  printf 'MARK %s %s\n' "$1" "$(tv date +%H:%M:%S)" >>"$TMP/marks.txt"
  adb -s "$D" shell 'i=0; while [ $i -lt '"${2:-40}"' ]; do echo "$(cut -d" " -f1 /proc/uptime) $(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed "s/^ *//")"; i=$((i+1)); sleep 0.25; done' 2>&1 \
    | tr -d '\r' | awk '{ line=$0; sub(/^[^ ]+ /,"",line); if (line!=prev) print; prev=line }' | redact | tee -a "$OUT"
}

# The app in front, as package/activity ("com.amazon.tv.launcher/.ui.HomeActivity_vNext"); empty when the TV can't say.
front1() { tv dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' | sed -n 's/.* \([A-Za-z0-9_.]*\/[A-Za-z0-9_.$]*\)[ }].*/\1/p'; }
front() { f=$(front1); [ -n "$f" ] || { sleep 1; f=$(front1); }; printf '%s\n' "$f"; } # empty while the TV changes screen: ask again once

# Waits for a background job, and stops it when it takes much longer than it should: a TV that went to sleep
# can leave adb waiting for ever on a connection that is gone.
stop_after() { # $1 = pid, $2 = seconds
  s=0
  while kill -0 "$1" 2>/dev/null && [ $s -lt "$2" ]; do sleep 1; s=$((s + 1)); done
  if kill -0 "$1" 2>/dev/null; then
    command -v pkill >/dev/null 2>&1 && pkill -P "$1" 2>/dev/null
    kill "$1" 2>/dev/null
    note "    (stopped waiting: the TV did not answer)"
  fi
  wait "$1" 2>/dev/null
}

# Wi-Fi ADB can drop while the TV sleeps: connect again, start again what listens to the TV, and say so when the
# TV does not come back (what the script reads from the TV after this is then missing).
reconnect() {
  n=0
  until [ "$(adb -s "$D" get-state 2>/dev/null)" = device ]; do
    n=$((n + 1))
    if [ $n -gt 15 ]; then note "    The TV did not answer again after it woke up. What this script reads from the TV below is missing."; return; fi
    case $D in *:*) adb connect "$D" >/dev/null 2>&1 ;; esac
    sleep 2
  done
  kill -0 "$LOGPID" 2>/dev/null || { adb -s "$D" logcat -b all -v time >>"$TMP/log.txt" 2>/dev/null & LOGPID=$!; }
  kill -0 "$EVPID" 2>/dev/null || { adb -s "$D" shell getevent -lt >>"$TMP/ev.txt" 2>/dev/null & EVPID=$!; }
  sleep 1
}

step() { # $1 = label, $2 = instruction, $3 = samples (default 40, so 9 seconds to press), $4 = what to run when the watch is over
  wait_step "$2"
  FROM=$(front)
  watch "$1" "${3:-40}" &
  W=$!
  sleep 0.5
  printf '    >>> PRESS NOW on the remote: %s  (you have %s seconds)\n' "$1" "$(( ${3:-40} / 4 - 1 ))"
  stop_after "$W" "$(( ${3:-40} / 4 + 15 ))"
  [ -z "$4" ] || "$4"
  TO=$(front)
  note "    in front before: ${FROM:-unknown}   after: ${TO:-unknown}"
  gist "$1: ${FROM:-unknown} -> ${TO:-unknown}"
}

step "HOME short press" "Get ready to press HOME on the REMOTE (short press, one time). Press Enter, then press the button." 40
step "HOME long press"  "Get ready to LONG-press HOME on the remote (hold about 2 seconds). Press Enter, then do it."
step "HOME double press" "Get ready to press HOME TWICE quickly. Press Enter, then do it." 40
# What is in front now ($TO, after the double press) tells which step makes sense, not whether the service is on:
# Plume can be on and still not be in front.
case $TO in
  dev.plume/*|dev.plume.*/*)
    # Plume's own home is in front: the common case, and one where Plume's jump may not happen (see HomeWatch).
    step "HOME from Plume's home" "Plume is on screen. Get ready to press HOME once, then wait 10 seconds without touching anything. Press Enter, then do it." 44 ;;
  *)
    step "HOME again on Amazon's home" "Get ready to press HOME, wait 2 seconds on Amazon's own home screen without opening anything, then press HOME again. Press Enter, then do it." ;;
esac
step "Netflix/Prime/other app button" "Get ready to press one of the coloured app buttons on the remote (Netflix, Prime Video...). Press Enter, then do it."
step "BACK" "Get ready to press BACK once (as a reference). Press Enter, then do it."
if [ $WAKE -eq 1 ]; then
  step "Wake from standby" "Open an app or Amazon's home. Get ready to put the TV to sleep with the remote's power button, wait about 8 seconds, then wake it with any button. The TV may drop the connection meanwhile; that is a result too. Press Enter, then do it." 80 reconnect
fi

sleep 1
kill "$LOGPID" "$EVPID" 2>/dev/null; LOGPID= EVPID=
# A log that was started again after the TV woke up begins with the lines that the first one had kept already.
[ $WAKE -eq 0 ] || LC_ALL=C sort -u -o "$TMP/log.txt" "$TMP/log.txt"

section "6. Keys the remote sent to the TV (kernel level; Home missing here means the remote never sent it)"
grep -E "KEY_" "$TMP/ev.txt" | grep -E "DOWN|UP" | head -80 | redact | tee -a "$OUT"

section "7. System log lines about Home (filtered)"
cat "$TMP/marks.txt" 2>/dev/null | tee -a "$OUT"
# Also kept: the activity-resume events (their time stamps tell how long Home took), a launch Android refused
# ("Background activity start ... callingUidHasAnyVisibleWindow"), and a service Fire OS refused to bind (A11yAuthorizer).
grep -E 'cat=\[android\.intent\.category\.HOME\]|HomeActivity|HudActivity|quicksettings|KeyPolicy|handleShortPressOnHome|handleLongPressOnHome|KeyEventDispatcher|AccessibilityManagerService|Background activity start|callingUidHasAnyVisibleWindow|A11yAuthorizer|am_new_intent|resumed_activity|resume_activity|app-switch|Displayed .*(launcher|tahoe|plume)' "$TMP/log.txt" \
  | grep -v ' I/Vlog' | head -400 | redact | tee -a "$OUT"

# For each Home step: when Android started the Home launch, when Amazon's home came to the front, when Plume asked
# for its home, and when Plume came to the front. Android holds a launch back for about 5 s after Home.
lag() { # $1 = HH:MM:SS the step started at (on the TV's clock), $2 = HH:MM:SS the next step started at (or empty)
  awk -v s="$1" -v e="$2" '
    function ms(t,  a) { split(t, a, /[:.]/); return ((a[1] * 60 + a[2]) * 60 + a[3]) * 1000 + a[4] }
    # milliseconds since the start of the step; the clock goes round at midnight, and the log lines carry no year
    function rel(t) { return ((ms(t) - lo) % 86400000 + 86400000) % 86400000 }
    BEGIN { lo = ms(s ".000"); hi = 25000; if (e != "") { n = rel(e ".000"); if (n < hi) hi = n } }
    { t = rel($2); if (t > hi) next }
    # The system starts Home from uid 1000. A start of Plume from an app uid is Plume asking for its home.
    { own = /cmp=dev\.plume/ && !/from uid 1000([^0-9]|$)/ }
    h == "" && /cat=\[android\.intent\.category\.HOME\]/ && !own { h = t; hp = /cmp=dev\.plume/ }
    h != "" && a == "" && /(resumed_activity|resume_activity).*(amazon\.tv\.launcher|firehomestarter|firelauncher)/ { a = t }
    h != "" && q == "" && own && /START u0 .*from uid/ { q = t }
    h != "" && p == "" && /(resumed_activity|resume_activity).*dev\.plume/ { p = t }
    END {
      if (h == "") { print "no Home launch in the log"; exit }
      r = "Home launch seen" (hp ? " (Android started Plume itself: it is the home app)" : "")
      if (a != "") r = r "; Amazon home in front " (a - h) " ms later"
      if (q != "") r = r "; Plume asked to start " (q - h) " ms after Home"; else r = r "; Plume never asked to start"
      if (p != "") r = r "; Plume in front " (p - h) " ms after Home" (a != "" ? " (Amazon home visible about " (p - a) " ms)" : "")
      else r = r "; Plume did not come to the front"
      print r
    }' "$TMP/log.txt"
}
report_lag() { # $1 = a line of marks.txt, $2 = the time of the next mark (or empty)
  l=${1#MARK }; t=${l##* }; l=${l% *}
  case $l in HOME*) r=$(lag "$t" "$2"); note "$l: $r"; gist "$l: $r" ;; esac
}
section "7b. Timing, from the log (milliseconds on the TV's clock)"
if [ -s "$TMP/marks.txt" ]; then
  prev=
  while IFS= read -r m; do
    [ -n "$prev" ] && report_lag "$prev" "${m##* }"
    prev=$m
  done <"$TMP/marks.txt"
  [ -n "$prev" ] && report_lag "$prev" ""
fi

# --- 8. Plume, if present -------------------------------------------------------------------------
if printf '%s\n' "$PKGS" | grep -q '^package:dev\.plume$'; then
  section "8. Plume on this TV"
  devgrep "versionName|installerPackageName|packageSource|WRITE_SECURE_SETTINGS" dumpsys package dev.plume
  PLV=$(printf '%s\n' "$R" | grep -m1 versionName | sed 's/^ *//')
  [ -n "$PLV" ] || { sleep 1; PLV=$(tv dumpsys package dev.plume | grep -m1 versionName | sed 's/^ *//'); } # the TV answered nothing: ask once more
  devrun appops get dev.plume ACCESS_RESTRICTED_SETTINGS
  note "--- Is Plume exempt from Doze? (a line with Plume = yes)"
  devgrep "plume" dumpsys deviceidle whitelist
  gist "Plume ${PLV:-version not read} installed"
fi

# --- 9. one check by eye ----------------------------------------------------------------------------
if [ $NOPROMPT -eq 0 ] && [ "$FIRE" = yes ]; then
  section "9. One check on Amazon's Network status screen"
  note "(Asked in every run, so that the answers with Plume's Home button on and off can be compared. It is on: $PLUME_ON.)"
  note "Another Fire TV launcher (dumbtv) reported that on Fire OS 8, while an accessibility service is on, Amazon's"
  note "Network status screen shows no highlighted button, so Advanced and Run Speed Test can't be reached."
  note "On the TV: Settings > Network > your network > Network status. Is one button highlighted?"
  printf '    y = yes, n = no, anything else = skip: '
  read -r ans </dev/tty || ans=
  case $ans in
    y|Y) note "Network status: a button is highlighted."; gist "Network status screen: a button is highlighted (Plume's service on: $PLUME_ON)" ;;
    n|N) note "Network status: NO button is highlighted."; gist "Network status screen: NO button highlighted (Plume's service on: $PLUME_ON)" ;;
    *)   note "Network status: not checked." ;;
  esac
fi

section "Summary (this part is enough to post)"
note "Script version 2, run '$LABEL'"
cat "$TMP/summary.txt" 2>/dev/null | redact | tee -a "$OUT"

section "Done"
note "Please post the file $OUT (and the screenshot if it shows only the page, not your account) at"
note "https://github.com/aradhyas8/Plume_Launcher/issues/153 . Thank you!"
