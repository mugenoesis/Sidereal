#!/usr/bin/env bash
# Takes photos one at a time and logs every change in the camera's shooting state, to catch a camera that hangs mid-shot.
#
#   tools/shoot_probe.sh <label> [shutter] [iso] [manualfocus] [count]
#   tools/shoot_probe.sh panasonic-32mm-fast SHUTTER_SPEED_1_60 ISO_800 manual 3
#   tools/shoot_probe.sh panasonic-32mm-auto            # leave exposure and focus as they are
#
# Writes docs/lens-tests/<label>-shoot-<time>.txt. If the camera hangs, power-cycle it before the next attempt.
set -u
LABEL="${1:?usage: tools/shoot_probe.sh <label> [shutter] [iso] [manual] [count]}"
SHUTTER="${2:-}"; ISO="${3:-ISO_800}"; FOCUS="${4:-}"; COUNT="${5:-1}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG=io.github.mugenoesis.sidereal
OUT="${OUT:-docs/lens-tests}"; mkdir -p "$OUT"
REPORT="$OUT/$LABEL-shoot-$(date +%Y%m%d-%H%M).txt"
ARGS=(--es tag "$LABEL" --es count "$COUNT")
[ -n "$SHUTTER" ] && ARGS+=(--es shutter "$SHUTTER" --es iso "$ISO")
[ "$FOCUS" = "manual" ] && ARGS+=(--es focus manual)
"$ADB" logcat -c
"$ADB" shell am broadcast -n $PKG/.debug.DebugCommandReceiver -a $PKG.DEBUG --es cmd shoot_probe "${ARGS[@]}" >/dev/null
for _ in $(seq 1 $((50 * COUNT + 20))); do
  "$ADB" logcat -d -s SiderealDebug | grep -qE "RESULT shoot_probe|shot[0-9]+ RESULT (STUCK|refused)" && break
  sleep 1
done
"$ADB" logcat -d -s SiderealDebug | grep SHOOTPROBE | sed -E 's/^.{19}//; s/ +[0-9]+ +[0-9]+ I SiderealDebug: / /' | tee "$REPORT"
echo "Report: $REPORT"
