# Milestone B integration — incomplete

Starting source: `913d12a2e6a273d18bd21c2efcdc83e26dc9f7b8`,
branch `codex/post-release-audit`. Final lifecycle checkpoint is recorded externally
in `multi-input-20261013/lifecycle-verification/final-checkpoint.txt` after commit.
This is an integration progress record, not milestone acceptance.

## Implemented lifecycle correction

Output attachment and explicit initialize now defer native graph creation until
the first input registration. Previously output attachment constructed the
EOS-dependent compositor with zero registered inputs, and its release failed at
DefaultVideoCompositor.release:227. The unchanged regression reproduced this
failure in `logs/run-lifecycle-baseline-mGC0eB6Y` and passed after deferral in
`logs/run-lifecycle-deferred-4EnKARkC`.

A preview-only Apache-2.0 pinned Media3 1.5.1 graph/compositor extension adds
explicit cancellation teardown. Upstream export classes remain unchanged. The
extension retires callbacks, waits for an executor barrier, releases the downstream
composition processor, cancels compositor work, abandons borrowed input-texture
references, deletes compositor-owned textures and sync fences, then releases
upstream processors. Shared-executor processor release is asynchronous; a final
barrier destroys the graph-owned EGL context after those cleanup tasks, on the GL
executor. Interruption/timeouts propagate as failures. No IllegalStateException
is swallowed, no cancellation EOS is synthesized, and normal proof EOS/draining
remains independently tested.

Preview graph states now cover Created, Initializing, Ready, Stopping, Released
and Failed. Input registration failure moves to Failed; subsequent successful
release is idempotent. The player-level Playing/Seeking lifecycle coordinator is
still missing. Callers must stop decoder producers before graph release; the
proof does this explicitly. SurfaceView owns the output surface; processors own
decoder input surfaces. The graph never releases the application's output surface.

## Tests and evidence

The SurfaceView proof adds partial registration cancellation, two-input
cancellation, cancellation with one real decoder and pending input while the
second decoder never starts, and two-real-decoder cancellation without EOS.
Normal solid/moving source comparisons retain their original pixel thresholds.
Failure cleanup in the proof now cancels instead of injecting EOS.

The retained progression includes 3/3 deferred-initialization proof tests,
5/5 registration/barrier proof tests and 6/6 pending-texture proof tests. These
intermediate runs are not evidence for later source edits. Final test totals and
full-suite outcomes are appended below after completion. Probe runner retains
source SHA, APK hash, streaming logcat and images; it now also retains the tracked
and staged source patch and its hash to distinguish dirty-source attempts.

Host tests: 302 executions from 38 JUnit XML files, zero failures/errors/skips,
with log at multi-input-20261013/lifecycle-host-tests.log. Debug/release APK and
release AAB assembly passed with normal R8/resource shrinking enabled at
lifecycle-release-builds.log. Build success is not production runtime acceptance.
Preserved production artifacts/hashes are under lifecycle-verification/.

## Integration blockers and unverified acceptance

The preview pixel defect remains the shared PlaybackVideoGraphWrapper sink:
all visual renderers obtain the same sink, registering input 0. Independent
per-sequence sink/decoder/compositor identity mapping has not been implemented.
Sequence 0 remains the playback clock primary; no sequence reordering was done.
The recovered sequence-aware audio renderer remains unchanged. No overlay audio
was muted to pass a test.

Seeking remains unsafe for multi-input preview: the pinned wrapper flushes an
individual upstream processor without a graph-wide downstream ownership protocol.
Controlled graph replacement is the next candidate, but has not been implemented
or measured. No claim is made for latest-seek preservation, real-time backpressure,
continuous six-second multi-input playback, audio sync, photo/two-overlay parity,
all overlay audio cases, or consumer overlay UI acceptance.

The existing single-input path is preserved. No transitions, keyframes or other
new professional features were started. fancut candidates are recorded in
FANCUT_OFFLINE_RESOURCE_AUDIT.md; no untested native engine/assets were added.

There is no new end-to-end 1080p workflow MP4 or Android workflow recording.
The requested recording would currently expose a broken PiP preview. Earlier
export proofs and decoded-frame screenshots remain preserved externally; they
are not substituted for integrated workflow evidence. No production pacing,
seek latency, long-session resource bound or GPU timing is certified. Only the
cricpro_api36 emulator is connected; physical-device certification remains open.

## Retained final lifecycle evidence

Lifecycle source commit: `18b4a96e107e4173829e9e434b69fd2caa3af7e3`.
The final proof runner built that code from the dirty checkout before commit;
its `source.patch` and SHA-256 are retained with the APK SHA-256 at
`logs/run-final-lifecycle-seven-mmhWvRFz`. It reports **OK (7 tests)** through the
AndroidJUnitRunner; this direct-run proof has no generated Gradle JUnit XML.

Full Gradle core XML: 76 tests, 75 pass, 1 failure, 0 errors, 0 skipped.
This suite ran before the final seventh proof test/entry-state checks; it is not
represented as a final-source full media rerun. The unchanged failing test is
`overlay_renders_in_preview_and_disappears_outside_its_window`, error 210.1943.
Its retained log contains no compositor release exception. All output/logcat files
and JUnit XML are copied into lifecycle-verification/core-device-output/.

Editor XML: 43 tests, 43 pass, 0 failures/errors/skips.
App debug XML: 16 tests, 16 pass, 0 failures/errors/skips.
Their snapshots and exact command results are under lifecycle-verification/xml/
and remaining-results.json. Full media failure remains an acceptance blocker
regardless of these passing editor/application regressions.

Actual independent-graph SurfaceView captures (not consumer editor acceptance):

![Independent SurfaceView solid proof](/Users/PUBLIC_USER/video-editor-milestone-b-evidence/logs/run-final-lifecycle-seven-mmhWvRFz/evidence/multi-input-proof/solid-1000000.png)

![Independent SurfaceView moving-source proof](/Users/PUBLIC_USER/video-editor-milestone-b-evidence/logs/run-final-lifecycle-seven-mmhWvRFz/evidence/multi-input-proof/moving-1000000.png)

Each moving-source sample has retained expected/wrong-timestamp errors in its
`timing-*.txt`. Upstream-normalized source differences for the preview-only fork
are preserved at lifecycle-verification/upstream-preview-diff.patch.

The new lifecycle tests do not force a real second-codec configure failure,
interrupt GL initialization, exhaust GL allocation, certify timeout recovery, or
count native resources over a long session. Those cases remain open. Normal EOS,
partial/no-input release, pending-frame cancellation and successful repeated
release have passing bounded evidence; comprehensive owner/player lifecycle
acceptance is not claimed.

Separate opt-in release instrumentation XML: 16 tests, 16 pass, zero
failures/errors/skips. Command:
`./gradlew -Pvideoeditor.instrumentRelease=true :app:connectedReleaseAndroidTest`.
This uses the documented instrumentation keep rules and is not normal production
runtime acceptance. Device suites were executed sequentially, without clearing
application data or replacing the existing emulator. App debug and release runs
restored the existing microphone-denial test precondition through permission
revocation/flag reset; user project documents and original media were preserved.

Final normal builds after release instrumentation passed:
`:app:assembleDebug :app:assembleRelease :app:bundleRelease`. R8 and resource
shrinking remain enabled. Final production APK/AAB copies, hashes, source identity
and device inventory are in lifecycle-verification/final-production/. The unsigned
production APK has not been installed and independently exercised in this run.
No normal-production PiP or physical-device acceptance is claimed.

Milestone B remains **incomplete**. The implemented and committed work is a
bounded graph lifecycle correction; the integration and workflow requirements
listed above still require implementation and verification.
