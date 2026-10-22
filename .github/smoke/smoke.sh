#!/usr/bin/env bash
# Porovnanie pamäte: base.apk (0.66 pred úpravami) vs new.apk (s úpravami ľahkosti)
set -u
OUT=out; mkdir -p $OUT/shots
PKG=sk.havrila.galeria
grant() { for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO POST_NOTIFICATIONS ACCESS_MEDIA_LOCATION; do adb shell pm grant $PKG android.permission.$p 2>/dev/null; done; adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; }
launch() { adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep "${1:-8}"; }
tap_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  b=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as E
try: r=E.parse('/tmp/ui.xml').getroot()
except Exception: sys.exit()
for n in r.iter('node'):
    if sys.argv[1] in (n.get('text') or ''):
        m=re.findall(r'\d+',n.get('bounds',''))
        if len(m)==4: print((int(m[0])+int(m[2]))//2,(int(m[1])+int(m[3]))//2); break
PY
); [ -n "$b" ] && { adb shell input tap $b; sleep 3; echo "tap $1" >> $OUT/steps.txt; } || echo "NENAŠIEL $1 ($TAG)" >> $OUT/steps.txt; }
mem() { v=$(adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|Native Heap:" | tr -s ' ' | tr '\n' ' '); echo "$TAG | $1 | $v" >> $OUT/mem.txt; }
peak() { p=0; for i in $(seq 1 $1); do v=$(adb shell dumpsys meminfo $PKG | grep "TOTAL PSS" | tr -s ' ' | cut -d' ' -f4); [ -n "$v" ] && [ "$v" -gt "$p" ] && p=$v; sleep 5; done; echo "$TAG | ŠPIČKA PSS počas spracovania (KB) | $p" >> $OUT/mem.txt; }

adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0; adb shell settings put global animator_duration_scale 0
adb shell mkdir -p /sdcard/DCIM/Camera
i=0; for r in 1 2 3 4 5; do for f in photos/*; do i=$((i+1)); adb push "$f" "/sdcard/DCIM/Camera/p${i}_$(basename $f)" >/dev/null; done; done
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
for t in 1 2 3 4 5 6 7 8 9 10 11 12; do c=$(adb shell content query --uri content://media/external/images/media --projection _id 2>/dev/null | grep -c Row); [ "$c" -ge 20 ] && break; sleep 5; done
echo "MediaStore fotiek: $c" >> $OUT/steps.txt
adb shell dumpsys battery set ac 1; adb shell dumpsys battery set level 90

for TAG in base new; do
  adb logcat -c
  adb install $TAG.apk >> $OUT/steps.txt 2>&1; grant
  launch 10; mem "po_starte"
  adb exec-out screencap -p > $OUT/shots/${TAG}_start.png
  tap_text "Spustiť spracovanie"
  peak 48
  mem "po_4min"
  adb exec-out screencap -p > $OUT/shots/${TAG}_po_4min.png
  adb shell input keyevent 3; sleep 30; mem "pozadie_30s"
  adb shell am send-trim-memory $PKG UI_HIDDEN >/dev/null 2>&1; sleep 5; mem "po_trim_UI_HIDDEN"
  launch 5; adb shell input tap 944 2230; sleep 3; tap_text "Hľadať čokoľvek"; sleep 3; adb shell input text "auto"; sleep 8; mem "hladanie"
  adb shell input keyevent 3; sleep 150; mem "pozadie_2_5min_po_hladani"
  adb logcat -d > $OUT/logcat_$TAG.txt
  grep -nE "FATAL EXCEPTION|ANR in $PKG" -A12 $OUT/logcat_$TAG.txt | head -40 >> $OUT/crashes.txt
  adb uninstall $PKG >> $OUT/steps.txt 2>&1
done
{ echo "## Pamäť (KB)"; cat $OUT/mem.txt; echo "## pády"; cat $OUT/crashes.txt 2>/dev/null; echo "## kroky"; cat $OUT/steps.txt; } > $OUT/summary.md
exit 0
