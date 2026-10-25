#!/usr/bin/env bash
# End-to-end test rodinného prepojenia na dvoch emulátoroch (A = emulator-5554, B = emulator-5556).
# Sieť: server A (port v emulátore) ← adb forward tcp:18090 ← B ho vidí ako 10.0.2.2:18090; B obrátene cez 28090.
# NSD medzi emulátormi nefunguje → ladiaca ručná adresa (FamilyPrefs.manualEndpoint, len debug build).
# Háčiky: FamilyDebugReceiver (src/debug), výsledky do logcatu pod FamilyE2E.
set -u
OUT=${OUT:-out}
mkdir -p "$OUT/shots" "$OUT/db" "$OUT/logcat"
PKG=sk.havrila.galeria.debug
RCV=org.fossify.gallery.family.FamilyDebugReceiver
A=emulator-5554
B=emulator-5556
DIR=$(cd "$(dirname "$0")" && pwd)
STEPS=$OUT/steps.txt
RES=$OUT/results.md
CAM=/storage/emulated/0/DCIM/Camera
N=0
R=""

nm() { [ "$1" = "$A" ] && echo A || echo B; }
log() { echo "$(date +%T) $*" | tee -a "$STEPS"; }
result() { echo "- $*" | tee -a "$RES"; }
mark() { for s in $A $B; do adb -s $s shell log -t E2E "===== $1 =====" >/dev/null 2>&1; done; log "===== $1 ====="; }

# dbg <serial> <cmd> [arg] [arg2] [arg3] → R = výsledok príkazu
dbg() {
  local s=$1 c=$2 line="" i
  N=$((N + 1))
  local extra="--es cmd $c --es id $N"
  [ $# -ge 3 ] && extra="$extra --es arg '$3'"
  [ $# -ge 4 ] && extra="$extra --es arg2 '$4'"
  [ $# -ge 5 ] && extra="$extra --es arg3 '$5'"
  adb -s "$s" shell "am broadcast -f 32 -n $PKG/$RCV $extra" >/dev/null 2>&1
  for i in $(seq 1 "${DBG_TIMEOUT:-90}"); do
    line=$(adb -s "$s" logcat -d -s FamilyE2E:I 2>/dev/null | grep "DONE id=$N cmd=" | tail -1)
    [ -n "$line" ] && break
    sleep 1
  done
  R="${line#*cmd=$c }"
  [ -z "$line" ] && R="TIMEOUT"
  log "[$(nm "$s")] $c ${3:-} ${4:-} ${5:-} -> $R"
}

shot() { adb -s "$1" exec-out screencap -p > "$OUT/shots/$2.png" 2>/dev/null; log "[$(nm "$1")] snímka shots/$2.png"; }

dump_ui() { adb -s "$1" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb -s "$1" pull /sdcard/ui.xml "/tmp/ui_$1.xml" >/dev/null 2>&1; }
texts() { dump_ui "$1"; log "[$(nm "$1")] obrazovka: $(python3 "$DIR/ui.py" "/tmp/ui_$1.xml" --texts | cut -c1-600)"; }

# tap <serial> <text> [exact] → 0 = ťuknuté
tap() {
  local b="" i
  for i in 1 2 3 4 5; do
    dump_ui "$1"
    b=$(python3 "$DIR/ui.py" "/tmp/ui_$1.xml" "$2" "${3:-}")
    [ -n "$b" ] && break
    sleep 2
  done
  if [ -n "$b" ]; then adb -s "$1" shell input tap $b; sleep 2; log "[$(nm "$1")] ťuk „$2“"; return 0; fi
  log "[$(nm "$1")] NENAŠIEL „$2“"
  return 1
}

dbstate() {
  local s=$1 tag=$2 d="$OUT/db/$2_$(nm "$1")" f
  mkdir -p "$d"
  for f in $(adb -s "$s" shell ls /data/data/$PKG/databases/ 2>/dev/null | tr -d '\r' | grep -E '^(people|faces|family|extras)\.db(-wal)?$'); do
    adb -s "$s" exec-out cat "/data/data/$PKG/databases/$f" > "$d/$f" 2>/dev/null
  done
  python3 "$DIR/dbsum.py" "$d" > "$d/summary.txt" 2>&1
  rm -f "$d"/*.db "$d"/*.db-wal "$d"/*.db-shm
}

state() { dbg "$A" dump; RA=$R; dbg "$B" dump; RB=$R; dbstate "$A" "$1"; dbstate "$B" "$1"; }

open_pairing() { adb -s "$1" shell am start -n $PKG/org.fossify.gallery.family.PairingActivity >/dev/null 2>&1; sleep 4; }
open_main() { adb -s "$1" shell am start -n $PKG/org.fossify.gallery.activities.MainActivity >/dev/null 2>&1; sleep 3; }
open_people() { adb -s "$1" shell am start -n $PKG/org.fossify.gallery.activities.PeopleActivity >/dev/null 2>&1; sleep 4; }

# get_link <serial> <ip> <port> → LINK (QR zo snímky cez zbarimg, záloha = háčik pair_link) s prepísanou adresou
get_link() {
  local s=$1 raw=""
  shot "$s" "qr_$(nm "$s")_$N"
  raw=$(zbarimg -q --raw "$OUT/shots/qr_$(nm "$s")_$N.png" 2>/dev/null | grep '^galeriaplus://' | head -1)
  if [ -n "$raw" ]; then log "QR dekódovaný zo snímky: ${raw:0:60}…"; else
    log "QR sa zo snímky nepodarilo dekódovať → háčik pair_link"
    dbg "$s" pair_link 10.0.2.15
    raw="${R#link=}"
  fi
  LINK=$(python3 - "$raw" "$2" "$3" <<'PY'
import sys, urllib.parse as u
raw, ip, port = sys.argv[1:4]
pre, _, q = raw.partition('?')
kv = [p.split('=', 1) for p in q.split('&') if p]
kv = [(k, ip if k == 'ip' else port if k == 'port' else v) for k, v in kv]
print(pre + '?' + '&'.join(k + '=' + v for k, v in kv))
PY
)
  log "odkaz: $LINK"
}

# pair <ukazuje QR> <skenuje> <port pre skenujúceho>
pair() {
  local show=$1 scan=$2 port=$3
  open_pairing "$show"
  shot "$show" "${TAGP}_1_qr_$(nm "$show")"
  get_link "$show" 10.0.2.2 "$port"
  adb -s "$scan" shell "am start -a android.intent.action.VIEW -d '$LINK' $PKG" >/dev/null 2>&1
  sleep 4
  shot "$scan" "${TAGP}_2_dialog_$(nm "$scan")"
  texts "$scan"
  tap "$scan" "Prepojiť" exact
  sleep 6
  shot "$scan" "${TAGP}_3_po_$(nm "$scan")"
  shot "$show" "${TAGP}_3_po_$(nm "$show")"
}

crashes() {
  local s
  for s in $A $B; do
    adb -s "$s" logcat -d > "$OUT/logcat/$(nm "$s")_full.txt" 2>/dev/null
    grep -nE "FATAL EXCEPTION|ANR in|am_anr|Process: $PKG" -A15 "$OUT/logcat/$(nm "$s")_full.txt" > "$OUT/logcat/$(nm "$s")_crashes.txt"
    grep -nE "FamilyE2E|FamilyServer|FamilyAutoSync|FamilyStore|BundleApplier|BundleReceiver|E2E" \
      "$OUT/logcat/$(nm "$s")_full.txt" > "$OUT/logcat/$(nm "$s")_family.txt"
  done
}

has() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }

: > "$RES"
echo "# Výsledky e2e rodinného prepojenia" >> "$RES"

# ---------- príprava ----------
mark "príprava"
for s in $A $B; do
  adb -s "$s" root >/dev/null 2>&1
done
sleep 5
for s in $A $B; do
  adb -s "$s" wait-for-device
  adb -s "$s" shell settings put global window_animation_scale 0
  adb -s "$s" shell settings put global transition_animation_scale 0
  adb -s "$s" shell settings put global animator_duration_scale 0
  adb -s "$s" shell settings put system screen_off_timeout 2147483647
  adb -s "$s" shell svc power stayon true
  adb -s "$s" shell input keyevent 82
  adb -s "$s" shell dumpsys battery set ac 1
  adb -s "$s" shell dumpsys battery set level 90
  adb -s "$s" shell mkdir -p $CAM
  for f in obama.jpg obama2.jpg biden.jpg; do adb -s "$s" push "photos/$f" "$CAM/$f" >/dev/null; done
  adb -s "$s" shell ip -4 addr 2>/dev/null | grep inet | tr -s ' ' >> "$STEPS"
done
adb -s $A push photos/two_people.jpg $CAM/two_people.jpg >/dev/null
for s in $A $B; do
  for f in $(adb -s "$s" shell ls $CAM/ | tr -d '\r'); do
    adb -s "$s" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$CAM/$f" >/dev/null 2>&1
  done
  adb -s "$s" shell content call --uri content://media/ --method scan_volume --arg external_primary >/dev/null 2>&1
  adb -s "$s" shell cmd media.provider scan external_primary >/dev/null 2>&1 || true
  log "[$(nm "$s")] MediaStore: $(adb -s "$s" shell content query --uri content://media/external/images/media --projection _data 2>/dev/null | grep -c DCIM/Camera) fotiek"
  adb -s "$s" install -r -g app.apk >> "$STEPS" 2>&1
  for p in READ_MEDIA_IMAGES READ_MEDIA_VIDEO POST_NOTIFICATIONS ACCESS_MEDIA_LOCATION; do
    adb -s "$s" shell pm grant $PKG android.permission.$p 2>/dev/null
  done
  adb -s "$s" shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null
  adb -s "$s" logcat -c
  adb -s "$s" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
done
sleep 12
for s in $A $B; do
  shot "$s" "0_start_$(nm "$s")"
  texts "$s"
  # uvítanie: spracovanie knižnice spustíme sami len pre tváre (rýchlejšie, menej pamäte)
  tap "$s" "Neskôr" exact || true
  adb -s "$s" shell am start-foreground-service -n $PKG/org.fossify.gallery.services.IndexingService --es task faces >> "$STEPS" 2>&1
done
dbg "$A" name TelefonA
dbg "$B" name TelefonB
# čakanie na rozpoznávanie tvárí (A: 4 fotky, B: 3 fotky)
for i in $(seq 1 60); do
  dbg "$A" dump; RA=$R
  dbg "$B" dump; RB=$R
  pa=$(echo "$RA" | sed -n 's/.*processed=\([0-9]*\).*/\1/p'); pb=$(echo "$RB" | sed -n 's/.*processed=\([0-9]*\).*/\1/p')
  [ "${pa:-0}" -ge 4 ] && [ "${pb:-0}" -ge 3 ] && break
  sleep 10
done
state "0_po_spracovani"
result "príprava: A: $RA"
result "príprava: B: $RB"

# servery bežia počas celého testu (háčik server_on), porty preposlané cez adb
dbg "$A" server_on; PA=${R#port=}
dbg "$B" server_on; PB=${R#port=}
adb -s $A forward tcp:18090 tcp:"$PA"
adb -s $B forward tcp:28090 tcp:"$PB"
adb forward --list >> "$STEPS"
dbg "$A" manual 10.0.2.2:28090
dbg "$B" manual 10.0.2.2:18090

# ---------- (a) párovanie ----------
mark "a_parovanie"
TAGP=a
pair "$A" "$B" 18090
state "a_po_parovani"
if has "$RA" "peers=[TelefonB" && has "$RB" "peers=[TelefonA"; then result "(a) párovanie B skenuje A: PREŠLO"; else result "(a) párovanie B skenuje A: ZLYHALO (A: $RA | B: $RB)"; fi
dbg "$A" sync; SA=$R
dbg "$B" sync; SB=$R
if has "$SA" " OK " && has "$SB" " OK "; then result "(a) synchronizácia oboma smermi: PREŠLA ($SA / $SB)"; else result "(a) synchronizácia: ZLYHALA (A: $SA | B: $SB)"; fi
open_pairing "$A"; shot "$A" "a_4_zoznam_A"
open_pairing "$B"; shot "$B" "a_4_zoznam_B"
open_main "$A"; open_main "$B"

# ---------- (b) premenovanie osoby na A ----------
mark "b_premenovanie"
dbg "$A" create_person Barack "$CAM/obama.jpg"
dbg "$A" assign Barack "$CAM/obama2.jpg" 0
sleep 5
dbg "$A" sync; SA=$R
dbg "$B" dump; RB1=$R
dbg "$A" rename Barack BarackObama
sleep 5
dbg "$A" sync
state "b_po_premenovani"
open_people "$A"; shot "$A" "b_ludia_A"
open_people "$B"; shot "$B" "b_ludia_B"
has "$RB1" ":Barack:" && result "(b) nová osoba z A prišla na B: PREŠLO ($RB1)" || result "(b) nová osoba z A na B: ZLYHALO ($RB1)"
has "$RB" ":BarackObama:" && result "(b) premenovanie A → B: PREŠLO ($RB)" || result "(b) premenovanie A → B: ZLYHALO ($RB)"

# ---------- (c) Poslať do prepojeného telefónu ----------
mark "c_poslat"
dbg "$A" create_person Joe "$CAM/biden.jpg"
dbg "$A" assign BarackObama "$CAM/two_people.jpg" 0
dbg "$A" assign Joe "$CAM/two_people.jpg" 1
sleep 5
dbg "$A" sync
dbstate "$B" "c_pred_poslanim"
dbg "$A" send "$CAM/two_people.jpg"; SEND=$R
sleep 5
state "c_po_poslani"
SUMB="$OUT/db/c_po_poslani_B/summary.txt"
if has "$SEND" "status=OK" && grep -q "two_people" "$SUMB"; then result "(c) fotka prišla na B: PREŠLO ($SEND)"; else result "(c) fotka na B: ZLYHALO ($SEND)"; fi
grep -A40 "faces podľa" "$SUMB" | grep "two_people" | tee -a "$RES" >/dev/null
# B fotku nespracuje znova: spustí sa rozpoznávanie tvárí a riadky tvárí fotky sa nesmú zmeniť
cp "$SUMB" "$OUT/db/c_faces_pred.txt"
adb -s $B shell am start-foreground-service -n $PKG/org.fossify.gallery.services.IndexingService --es task faces >> "$STEPS" 2>&1
sleep 60
dbstate "$B" "c_po_spracovani"
F1=$(grep "two_people" "$OUT/db/c_po_poslani_B/summary.txt" | head -1)
F2=$(grep "two_people" "$OUT/db/c_po_spracovani_B/summary.txt" | head -1)
[ -n "$F1" ] && [ "$F1" = "$F2" ] && result "(c) B fotku znova nespracovalo (tváre rovnaké): PREŠLO" || result "(c) opätovné spracovanie: ZLYHALO alebo neoverené („$F1“ → „$F2“)"
open_people "$B"; shot "$B" "c_ludia_B"
open_main "$A"; open_main "$B"

# ---------- (d) súbežné premenovanie ----------
mark "d_subezne"
dbg "$A" rename Joe JoeA
dbg "$B" rename Joe JoeB
sleep 5
dbg "$A" sync
state "d_po_sync"
result "(d) po súbežnom premenovaní: A: $RA"
result "(d) po súbežnom premenovaní: B: $RB"
if has "$RA" "rename" || has "$RB" "rename"; then result "(d) návrh premenovania vznikol: PREŠLO"; else result "(d) návrh premenovania: ZLYHALO (žiadny návrh)"; fi
open_people "$A"; shot "$A" "d_ludia_A"
open_people "$B"; shot "$B" "d_ludia_B"
open_main "$A"; open_main "$B"

# ---------- (e) zmazanie osoby na A ----------
mark "e_zmazanie"
dbg "$A" delete BarackObama
sleep 5
dbg "$A" sync
state "e_po_zmazani"
if has "$RB" ":BarackObama:" && has "$RB" "delete"; then result "(e) zmazanie na A → na B len návrh: PREŠLO ($RB)"; else result "(e) zmazanie: ZLYHALO ($RB)"; fi
open_people "$B"; shot "$B" "e_ludia_B"
open_main "$B"

# ---------- (f) zrušenie prepojenia (UI na A) ----------
mark "f_zrusenie"
open_pairing "$A"
shot "$A" "f_1_A"
tap "$A" "TelefonB" exact
shot "$A" "f_2_dialog_A"
tap "$A" "Zrušiť prepojenie" exact
sleep 8
shot "$A" "f_3_po_A"
state "f_po_zruseni"
if has "$RA" "peers=[]" && has "$RA" "unpairPending=0" && has "$RB" "peers=[]"; then result "(f) zrušenie prepojenia na oboch: PREŠLO"; else result "(f) zrušenie: ZLYHALO (A: $RA | B: $RB)"; fi

# ---------- (a2) párovanie opačným smerom (A skenuje B) ----------
mark "a2_parovanie_opacne"
TAGP=a2
pair "$B" "$A" 28090
dbg "$A" sync; SA=$R
state "a2_po_parovani"
if has "$RA" "peers=[TelefonB" && has "$RB" "peers=[TelefonA" && has "$SA" " OK "; then result "(a2) párovanie A skenuje B + sync: PREŠLO ($SA)"; else result "(a2) párovanie opačne: ZLYHALO (A: $RA | B: $RB | $SA)"; fi

# ---------- pády ----------
mark "koniec"
crashes
for s in A B; do
  c=$(grep -c "FATAL EXCEPTION\|ANR in" "$OUT/logcat/${s}_crashes.txt")
  result "pády/ANR na $s: $c"
done
exit 0
