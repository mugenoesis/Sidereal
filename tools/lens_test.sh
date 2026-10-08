#!/usr/bin/env bash
# Runs the lens checks that can be automated on a phone with a debug build of Sidereal installed, connected over adb,
# with the camera on and the app open and connected. Writes a text report you can paste into docs/lens-tests/.
#
#   tools/lens_test.sh <label> [yaw] [pitch]
#   tools/lens_test.sh olympus-45mm-f1.8 40 -3
#
# Point the camera at a well-lit scene with detail at the distance you care about first (aim it with yaw/pitch if the
# app's gimbal angles suit; otherwise aim by hand and omit them). The report holds only numbers and lens names.
set -u
LABEL="${1:?usage: tools/lens_test.sh <label> [yaw] [pitch]}"
YAW="${2:-}"
PITCH="${3:--3}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG=io.github.mugenoesis.sidereal
OUT="${OUT:-lens-tests}"
mkdir -p "$OUT"
REPORT="$OUT/$LABEL-$(date +%Y%m%d-%H%M).txt"

cmd() { "$ADB" shell am broadcast -n $PKG/.debug.DebugCommandReceiver -a $PKG.DEBUG --es cmd "$@" >/dev/null; }
wait_for() { # <logcat pattern> <seconds>
  for _ in $(seq 1 "$2"); do "$ADB" logcat -d -s SiderealDebug | grep -q "$1" && return 0; sleep 1; done; return 1; }
logs() { "$ADB" logcat -d -s SiderealDebug | grep -E "$1" | sed -E 's/^.{19}//; s/ +[0-9]+ +[0-9]+ I SiderealDebug: / /'; }

{
  echo "# Lens test: $LABEL  ($(date -Is))"
  echo "app version: $("$ADB" shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ')"
  echo
} > "$REPORT"

if [ -n "$YAW" ]; then cmd gimbal_to --es pitch "$PITCH" --es yaw "$YAW"; sleep 8; fi

echo "== 1. what the camera reports about the lens" | tee -a "$REPORT"
"$ADB" logcat -c; cmd lens_probe; sleep 8
logs "LENS" | tee -a "$REPORT"

echo "== 2. aperture: which values the camera accepts (widest first)" | tee -a "$REPORT"
"$ADB" logcat -c
cmd aperture_probe --es names "F_1_DOT_4,F_1_DOT_7,F_1_DOT_8,F_2,F_2_DOT_8,F_3_DOT_5,F_4,F_5_DOT_6,F_8,F_11"
sleep 24
logs "APERTURE" | tee -a "$REPORT"
cmd reset_camera; sleep 3

echo "== 3. sharpness over the whole focus ring (ground truth for autofocus)" | tee -a "$REPORT"
"$ADB" logcat -c; cmd af_curve --es tag "$LABEL" --es step 50
wait_for "AFNOISE" 120 || echo "(timed out)" | tee -a "$REPORT"
logs "AFCURVE|AFNOISE" | tee -a "$REPORT"

echo "== 4. software autofocus from blurred starts (time to lock, where it locked)" | tee -a "$REPORT"
"$ADB" logcat -c; cmd afc_trials --es tag "$LABEL" --es starts "0,700,1400,2000"
wait_for "RESULT afc_trials" 240 || echo "(timed out)" | tee -a "$REPORT"
"$ADB" logcat -d | grep -E "AFTRIAL |AFC using" | sed -E 's/^.{19}//; s/ +[0-9]+ +[0-9]+ [A-Z] / /' | tee -a "$REPORT"

echo
echo "Report: $REPORT"
echo "Still to do by hand (see docs/lens-test-plan.md): panorama + stitch fov scale, ramp aperture, face tracking, zoom behaviour."
