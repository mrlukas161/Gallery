#!/usr/bin/env bash
# Spustí druhý emulátor (B, port 5556) vedľa prvého (A, emulator-runner, 5554) a potom family.sh.
set -u
OUT=${OUT:-out}
mkdir -p "$OUT"
IMG="system-images;android-34;google_apis;x86_64"
AVDM=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1)
echo "avdmanager: $AVDM" >> "$OUT/steps.txt"
echo no | "$AVDM" create avd -n e2eB -k "$IMG" -d pixel_6 --force >> "$OUT/steps.txt" 2>&1
CFG="$HOME/.android/avd/e2eB.avd/config.ini"
{ echo "hw.ramSize=2048"; echo "hw.cpu.ncore=2"; echo "disk.dataPartition.size=4G"; } >> "$CFG"
nohup "$ANDROID_HOME/emulator/emulator" -avd e2eB -port 5556 -no-window -gpu swiftshader_indirect -noaudio \
  -no-boot-anim -no-snapshot -camera-back none -memory 2048 -cores 2 > "$OUT/emulator_B.txt" 2>&1 &
adb -s emulator-5556 wait-for-device
for i in $(seq 1 120); do
  [ "$(adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
echo "B nabehol po ~$((i * 5)) s" >> "$OUT/steps.txt"
free -m >> "$OUT/steps.txt"
adb devices >> "$OUT/steps.txt"
timeout 3000 bash "$(dirname "$0")/family.sh"
free -m >> "$OUT/steps.txt"
exit 0
