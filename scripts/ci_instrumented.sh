#!/usr/bin/env bash
# CI (emulator): run the instrumented Compose tests, then record the emulator screen while a scripted demo of the
# real app runs. Output: build/device-recording/protagonist-<name>.mp4
# Usage: scripts/ci_instrumented.sh <recording-name>
set -uo pipefail
NAME="${1:-device}"
OUT="build/device-recording"
mkdir -p "$OUT"

# 1. The regular instrumented test suite (the demo test skips itself without its argument).
if ! ./gradlew connectedDebugAndroidTest; then
  # Print failing tests straight into the CI log (the HTML report is only in the artifact).
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
for f in glob.glob("app/build/outputs/androidTest-results/connected/**/*.xml", recursive=True):
    for tc in ET.parse(f).getroot().iter("testcase"):
        for bad in list(tc.iter("failure")) + list(tc.iter("error")):
            print(f"FAILED {tc.get('classname')}.{tc.get('name')}: {(bad.text or '')[:1500]}")
PY
  exit 1
fi

# 2. Install app + test APKs, then record the screen while the demo runs.
./gradlew installDebug installDebugAndroidTest || exit 1
adb shell settings put system screen_off_timeout 600000
adb shell rm -f /sdcard/demo.mp4
adb shell screenrecord --time-limit 170 --bit-rate 8000000 /sdcard/demo.mp4 &
REC_PID=$!
sleep 2
DEMO_LOG="$OUT/demo-instrumentation-$NAME.txt"
adb shell am instrument -w -r -e class com.protagonist.app.DeviceDemoTest -e protagonist.demo true \
  com.protagonist.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$DEMO_LOG"
sleep 2
adb shell pkill -INT screenrecord || true
wait "$REC_PID" || true
sleep 3 # let screenrecord finalize the MP4
adb pull /sdcard/demo.mp4 "$OUT/protagonist-$NAME.mp4" || exit 1
ls -la "$OUT"

# am instrument exits 0 even on test failure; check its report.
if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=Process crashed" "$DEMO_LOG"; then
  echo "Device demo failed" >&2
  exit 1
fi
if ! grep -qE "^OK \(1 test\)" "$DEMO_LOG"; then
  echo "Device demo did not report OK (1 test)" >&2
  exit 1
fi
