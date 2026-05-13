#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-/home/pepper/android-sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
OUT_DIR="${1:-$ROOT/build/ui-check}"
AVD_NAME="${AVD_NAME:-oak_buzzer_tablet}"
PACKAGE="com.example.redbutton"
ACTIVITY=".MainActivity"

mkdir -p "$OUT_DIR"
cd "$ROOT"
./gradlew assembleDebug

if ! "$ADB" get-state >/dev/null 2>&1; then
  if [[ ! -x "$EMULATOR" ]]; then
    echo "No connected Android device and emulator binary not installed." >&2
    echo "Install SDK emulator + a system image, or connect a tablet with USB debugging." >&2
    exit 2
  fi

  if [[ ! -d "$HOME/.android/avd/$AVD_NAME.avd" ]]; then
    echo "no" | "$AVDMANAGER" create avd \
      -n "$AVD_NAME" \
      -k "system-images;android-34;google_apis;x86_64" \
      -d "pixel_tablet"
  fi

  "$EMULATOR" -avd "$AVD_NAME" -accel off -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect -no-window >/tmp/oak-buzzer-emulator.log 2>&1 &
  EMU_PID=$!
  trap 'kill "$EMU_PID" >/dev/null 2>&1 || true' EXIT
  "$ADB" wait-for-device
  deadline=$((SECONDS + 240))
  until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if (( SECONDS > deadline )); then
      echo "Emulator did not finish booting within 240s; no APK will be posted without a screenshot check." >&2
      exit 3
    fi
    sleep 2
  done
fi

"$ADB" install -r "$APK" >/dev/null
"$ADB" shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
"$ADB" shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
"$ADB" shell settings put system user_rotation 1 >/dev/null 2>&1 || true
"$ADB" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null
sleep 2
"$ADB" exec-out screencap -p > "$OUT_DIR/main.png"

# Tap a representative command path: Op1, Exam, Dr. Riad, Send.
# Coordinates are intentionally broad for tablet landscape; adjust if screenshots show drift.
"$ADB" shell input tap 90 190
"$ADB" shell input tap 320 300
"$ADB" shell input tap 130 405
"$ADB" shell input tap 1060 735
sleep 1
"$ADB" exec-out screencap -p > "$OUT_DIR/after-send.png"

echo "UI screenshots written:"
echo "  $OUT_DIR/main.png"
echo "  $OUT_DIR/after-send.png"
