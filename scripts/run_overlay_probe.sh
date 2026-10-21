#!/usr/bin/env bash
# Reproducible overlay-diagnostic runner.
#
# Installs the core:media instrumented test APK, runs a selected test class or
# method, captures full logcat for the run, and pulls the on-device evidence
# directory BEFORE instrumentation teardown removes the test package. This exists
# because the stopped session repeatedly lost decoded frames when the
# instrumentation app was uninstalled at the end of the run.
#
# Usage:
#   scripts/run_overlay_probe.sh <tag> [instrumentation-filter-args...]
# Example:
#   scripts/run_overlay_probe.sh probe-order \
#     -e class com.videoeditor.core.media.OverlayCompositorProbeTest
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
TEST_APK="$REPO/core/media/build/outputs/apk/androidTest/debug/media-debug-androidTest.apk"
TEST_PKG="com.videoeditor.core.media.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"

EV="$HOME/video-editor-milestone-b-evidence"
if [[ $# -lt 1 ]]; then echo "usage: $0 <tag> [am-instrument-args...]" >&2; exit 2; fi
TAG="$1"; shift
[[ "$TAG" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "invalid run tag" >&2; exit 2; }
mkdir -p "$EV/logs"
RUN_DIR="$(mktemp -d "$EV/logs/run-$TAG-XXXXXXXX")"
(cd "$REPO" && git rev-parse HEAD && git status --short) > "$RUN_DIR/source.txt"
(cd "$REPO" && git diff HEAD --binary) > "$RUN_DIR/source.patch"
shasum -a 256 "$RUN_DIR/source.patch" > "$RUN_DIR/source-patch.sha256"

echo "[run_overlay_probe] serial=$SERIAL tag=$TAG"
echo "[run_overlay_probe] args: $*"

# 1. Always build: APK existence does not establish source freshness.
{
  echo "[run_overlay_probe] building test APK..."
  (cd "$REPO" && ./gradlew :core:media:assembleDebugAndroidTest --console=plain) \
    > "$RUN_DIR/build.log" 2>&1 || { echo "build failed; see $RUN_DIR/build.log"; tail -30 "$RUN_DIR/build.log"; exit 1; }
}

shasum -a 256 "$TEST_APK" > "$RUN_DIR/apk.sha256"

# 2. Install (replace) the test APK. The library test is self-instrumenting.
echo "[run_overlay_probe] installing $TEST_APK"
"$ADB" -s "$SERIAL" install -r -g "$TEST_APK" > "$RUN_DIR/install.log" 2>&1 \
  || { echo "install failed"; cat "$RUN_DIR/install.log"; exit 1; }

# Retain prior device evidence before moving it aside; never delete it.
DEVICE_EV="/sdcard/Android/data/$TEST_PKG/files/overlay-evidence"
if "$ADB" -s "$SERIAL" shell test -d "$DEVICE_EV"; then
  "$ADB" -s "$SERIAL" pull "$DEVICE_EV" "$RUN_DIR/prior-evidence" > "$RUN_DIR/prior-pull.log" 2>&1
  "$ADB" -s "$SERIAL" shell mv "$DEVICE_EV" "${DEVICE_EV}-$(basename "$RUN_DIR")"
fi

# 3. Start a fresh logcat capture in the background.
"$ADB" -s "$SERIAL" logcat -T 1 -v time > "$RUN_DIR/logcat.log" 2>&1 &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null || true' EXIT

# 4. Run instrumentation.
echo "[run_overlay_probe] starting instrumentation..."
set +e
"$ADB" -s "$SERIAL" shell am instrument -w -r "$@" \
  "$TEST_PKG/$RUNNER" > "$RUN_DIR/instrument.log" 2>&1
INSTR_EXIT=$?
set -e
echo "[run_overlay_probe] instrumentation exit=$INSTR_EXIT"

# 5. Pull evidence BEFORE teardown can remove it.
sleep 1
"$ADB" -s "$SERIAL" pull "/sdcard/Android/data/$TEST_PKG/files/overlay-evidence" \
  "$RUN_DIR/evidence" > "$RUN_DIR/pull.log" 2>&1 \
  || echo "[run_overlay_probe] no evidence directory to pull (ok if test wrote none)"

# 6. Snapshot the instrumented package presence (did it survive?).
"$ADB" -s "$SERIAL" shell pm list packages | grep "$TEST_PKG" > "$RUN_DIR/packages.txt" 2>&1 || true

echo "[run_overlay_probe] evidence -> $RUN_DIR/evidence"
echo "[run_overlay_probe] logcat   -> $RUN_DIR/logcat.log"
echo "[run_overlay_probe] results  -> $RUN_DIR/instrument.log"
echo "---- instrument.log tail ----"
tail -40 "$RUN_DIR/instrument.log" || true
# adb can return zero when AndroidJUnitRunner reports test failures.
if [[ "$INSTR_EXIT" -ne 0 ]]; then exit "$INSTR_EXIT"; fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[12]|Process crashed' "$RUN_DIR/instrument.log"; then
  echo "[run_overlay_probe] instrumentation reported a failure" >&2
  exit 1
fi
if ! grep -Eq '^OK \([0-9]+ tests?\)' "$RUN_DIR/instrument.log"; then
  echo "[run_overlay_probe] instrumentation did not report passing tests" >&2
  exit 1
fi
exit 0
