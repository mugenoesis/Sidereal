#!/usr/bin/env bash
# One step of a lens session: records everything the camera says (every readable CameraKey and the pushed camera
# state), takes one photo and reads the focal length, aperture and lens name from its EXIF, then deletes the photo
# from the computer (nothing but numbers is kept).
#
#   tools/lens_step.sh <label>        e.g.  tools/lens_step.sh panasonic-12-32-stowed
#
# Needs a debug build of Sidereal, connected, the camera on, the app open. The phone keeps its normal copy of the
# photo in Pictures/Sidereal like any run.
set -u
LABEL="${1:?usage: tools/lens_step.sh <label>}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG=io.github.mugenoesis.sidereal
OUT="${OUT:-docs/lens-tests}"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
REPORT="$OUT/$LABEL-step-$(date +%Y%m%d-%H%M).txt"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cmd() { "$ADB" shell am broadcast -n $PKG/.debug.DebugCommandReceiver -a $PKG.DEBUG --es cmd "$@" >/dev/null; }

echo "# $LABEL ($(date -Is))" > "$REPORT"

echo "== what the camera says" | tee -a "$REPORT"
"$ADB" logcat -c; cmd lens_probe; sleep 7
"$ADB" logcat -d -s SiderealDebug | grep LENS | sed -E 's/^.{19}//; s/ +[0-9]+ +[0-9]+ I SiderealDebug: / /' | tee -a "$REPORT"

"$ADB" logcat -c; cmd camera_dump --es tag "$LABEL"
for _ in $(seq 1 150); do "$ADB" logcat -d -s SiderealDebug | grep -q "RESULT camera_dump" && break; sleep 2; done
"$ADB" logcat -d -s SiderealDebug | grep -E "CAMKEY|CAMSTATE" | sed -E 's/^.{19}//; s/ +[0-9]+ +[0-9]+ I SiderealDebug: //' > "$TMP/dump.txt"
cp "$TMP/dump.txt" "$OUT/$LABEL-dump-$(date +%Y%m%d-%H%M).txt"
echo "keys answered: $(grep -E 'summary' "$TMP/dump.txt" | sed 's/.*summary: //')" | tee -a "$REPORT"
grep -E "CAMKEY $LABEL (HAS_ERROR|LENS_|IS_ADJUSTABLE_APERTURE|APERTURE |FOCUS_RING_VALUE_UPPER|FOCUS_MODE|FOCUS_STATUS)" "$TMP/dump.txt" \
  | sed "s/CAMKEY $LABEL //" | grep -v "FAIL" | tee -a "$REPORT"

if [ -n "${SKIP_PHOTO:-}" ]; then
  echo "(photo skipped: SKIP_PHOTO set - program mode can hang the camera on some lenses at some zooms)" | tee -a "$REPORT"
  echo "Report: $REPORT"
  exit 0
fi
echo "== one photo, and what its EXIF says about the lens" | tee -a "$REPORT"
"$ADB" logcat -c; cmd series_run --es mode INTERVALOMETER --es ints "frames:1,intervalSec:5,settleMs:500"
for _ in $(seq 1 60); do "$ADB" logcat -d -s SiderealDebug | grep -q "SERIES finished" && break; sleep 2; done
"$ADB" logcat -d -s SiderealDebug | grep "SERIES finished" | sed -E 's/.*message=//' | tee -a "$REPORT"
LATEST=$("$ADB" shell "ls -t /sdcard/Pictures/Sidereal/*/*.jpg 2>/dev/null | head -1" | tr -d '\r')
if [ -n "$LATEST" ]; then
  "$ADB" pull "$LATEST" "$TMP/photo.jpg" >/dev/null 2>&1 && python3 "$HERE/exif_info.py" "$TMP/photo.jpg" | tee -a "$REPORT"
else
  echo "(no photo found)" | tee -a "$REPORT"
fi
echo "Report: $REPORT"
