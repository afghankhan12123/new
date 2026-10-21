# Android emulator compatibility correction — October 13, 2026

Starting Git SHA: `8ff418dc86df0660a75bb419d3d7630a31e0401f`, branch
`codex/post-release-audit`. Final commit is recorded in the external checkpoint
file after committing, avoiding a self-referencing SHA.

## Confirmed cause

On `cricpro_api36`, `emulator-5554`, Android SDK 36, installed
`com.videoeditor.core.media.test` and `com.videoeditor.feature.editor.test`
both had `minSdk=26 targetSdk=26`. Consumer `com.videoeditor` and its
`com.videoeditor.test` instrumentation package targeted 36. The library tests
are self-instrumenting APKs; they do not inherit the application's target SDK.
The shared library convention specified minSdk but omitted a test target.

The previous actual dialog XML was inspected at
`/Users/PUBLIC_USER/video-editor-milestone-b-evidence/integrated-preview-20261013/atomic-final-gate/compat-dialog-0.xml`.
Its title is `com.videoeditor.core.media.test`, with the exact older-Android
security/privacy warning reported by the user. The current before screenshot
showed Home without a dialog; it is not substituted for the historical warning.
Only the pasted request was attached in this session, not a separate screenshot,
workflow video or output MP4. Matching local evidence was used where specified.

## Correction

`buildSrc/src/main/kotlin/ProjectExtensions.kt` sets
`testOptions.targetSdk = AndroidConfig.targetSdk` in the shared library
convention. This is the supported AGP 8.7.3 test-specific DSL; the deprecated
library `defaultConfig.targetSdk` alternative was investigated and replaced
before final verification. No consumer target or minSdk change was necessary.

Both final merged instrumentation manifests and `aapt2 dump badging` report
minSdk 26 and targetSdk 36. After `adb install -r` of both rebuilt library test
APKs, installed metadata agrees. No application uninstall, project clearing,
security-setting change or dialog acknowledgement was used to correct it.

See [AGP TestOptions](https://developer.android.com/reference/tools/gradle-api/8.7/com/android/build/api/dsl/TestOptions).
Independently checked [Google Play targeting requirements](https://developer.android.com/google/play/requirements/target-sdk)
on October 13: new mobile apps/updates require API 36 from August 31, 2026;
existing-app availability requires API 35. Production target 36 meets the SDK
number requirement; this is not full Play submission certification.

## Recurrence prevention

`scripts/check_instrumentation_compatibility.py` inventories installed
instrumentation packages and their actual SDK metadata; validates owned editor
test targets; saves metadata, accessibility XML and a screenshot; rejects stale
SDK targets and visible compatibility/crash/ANR messages. It is read-only.
A negative run rejected both original SDK-26 packages; a positive run accepted
all three installed SDK-36 test packages.

`scripts/run_premium_regression.py` rebuilds/replaces both library test packages
before device preflight, runs suites sequentially on an explicit serial, monitors
system windows for deprecated-target/crash/ANR dialogs, and preserves blocking
screenshots, XML and window dumps. Device logcat and JUnit snapshots are retained.
The Gradle leave-APKs-installed option preserves consumer/fixture data at suite
teardown. Test APKs remain installed for fixture/evidence continuity; cleanup is
not needed for correctly targeted packages. No unrelated packages are removed.
Microphone permission-denial test setup remains scoped to the consumer app's
existing stated test precondition.

## Evidence and current verification

Evidence root: `/Users/PUBLIC_USER/video-editor-compatibility-evidence/20261013/`.
Before installed metadata: `media-before.txt`, `editor-before.txt`.
Final manifests and APK metadata: `media-manifest.xml`, `editor-manifest.xml`,
`media-apk-badging.txt`, `editor-apk-badging.txt`.
Preflight evidence: `preflight-before/`, `preflight-after/`.
Supported DSL build: `build-supported-dsl.log`, successful.
Fresh host JUnit: 304 executions in 38 XML files; zero failures/errors/skips.
Preflight unit regression: four tests pass (`preflight-unit-tests.txt`).
Fresh media device JUnit: 82 tests; zero failures/errors/skips.
Fresh editor device JUnit: 44 tests; zero failures/errors/skips.
Fresh app debug JUnit: 17 tests; zero failures/errors/skips.
Fresh opt-in R8 app release JUnit: 17 tests; zero failures/errors/skips.
Debug APK, normal unsigned R8 release APK and release AAB builds all pass.
Exact module/variant-filtered counts: `fresh-test-counts.json`; raw final XML
snapshots and command logs: `regression/`. All device suites completed without
compatibility-dialog acknowledgement or any detected blocking system dialog.

Normal-production smoke: an external copy of the normal unsigned R8 APK was
signed with the standard emulator certificate and installed with `-r`. Installed
consumer metadata shows minSdk 26, targetSdk 36 and no DEBUGGABLE flag. Existing
saved `PiP profile 2 visual layers` opens without missing media; playback reaches
EOS, and screenshots differ in the preview region (mean RGB difference 42.74).
This is a motion/reopen smoke, not a new decoded pixel-parity benchmark. A real
23.198-second native recording is retained at `normal-production/playback.mp4`;
preflight and after-playback metadata/XML/screenshots show no blocking warning.
No user edit was committed by this smoke. This is not the future 32-step
professional-feature acceptance recording; those features remain unfinished.


Independent fresh workflow exports also pass unchanged host verifiers: debug and
release PiP frames at 0.5/2/3/4.5s have maximum global RGB-sum mean error 0.448
and right-half error 0.75 (threshold 30). Decoded PCM contains both main/overlay
tones inside the PiP window and overlay amplitude below 0.019 outside it. Both
debug and release mixed native workflows pass strict full decode, monotonic
video/audio DTS, resolution/duration checks and both imported audio tones at
720p and 1080p. Reports/PNGs/PCM/MP4s are retained under `workflows/`.

No media engine changes, preview performance improvements, new creative features
or physical-device acceptance are claimed by this compatibility checkpoint.
