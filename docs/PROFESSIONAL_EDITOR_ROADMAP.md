> Current continuation (2026-10-19): Stage7 accepted at51090d81. Stage8 advanced audio is implemented and under final qualification; **NOT ACCEPTED**. Timeline point editing, focused audio UI and broader PCM/mixed exports have new evidence; full regressions/performance/release gates remain pending. [Stage8 checkpoint](STAGE8_CONTINUATION_CHECKPOINT.md). Historical roadmap below is retained; Stage9 has not begun.

# Professional editor continuation roadmap

Stage 6 continuation, 2026-10-14: the preserved AVC correction passes original
frame-74 identities and continuous preview. The combined odd-seek candidate
passes all focused cases and repeated diagnostics-disabled boundaries; independent
FFmpeg verifies 2,317 final-candidate frame checks. Fresh full media regression passes 95/95, and its independent final-run verification passes
1,524 frame checks and 24 tone windows. PCM boundary/concurrency, consumer
UI/workflow and final R8 gates remain open.
Stage 6 remains incomplete and hidden. See
[the current investigation](STAGE6_ODD_SEEK_INVESTIGATION.md).
Stage 5 acceptance remains historical and does not certify this candidate.


Checkpoint begins at `8ff418dc86df0660a75bb419d3d7630a31e0401f` on
`codex/post-release-audit`. Preserve original user projects/media, the verified
PiP preview extension and untracked `fancut/`. This is a continuation of the
existing editor, not a new application.

Completed work at this checkpoint: recovery inspection; SDK-36 library test APK
correction and test-runner safeguards; read-only reference inventory/license
classification; source/public-feature gap analysis. Exact current regression
results and remaining verification are in ANDROID_EMULATOR_COMPATIBILITY_FIX.md
and PROFESSIONAL_EDITOR_FINAL_REPORT.md. Creative milestones remain unfinished.

## Next session: Stage 5, then Stage 6

1. Confirm `git status --short`, current checkpoint SHA, emulator serial and
   `python3 scripts/check_instrumentation_compatibility.py --output <new-external-directory>`.
   Inspect the final external `checkpoint.txt`; do not reuse old XML as new results.
2. Audit the actual normal R8 editor with native import, PiP/text/audio/filter/
   speed/export/saved-project/settings/fullscreen interactions, compact and
   landscape screenshots. Preserve charcoal/blue/cyan styling and decoded accuracy.
   Current-session screenshots are compatibility diagnostics, not a complete UX audit.
3. Investigate extract-audio history grouping: `extractSelectedAudio()` currently
   applies AddAudioClip and SetClipMuted separately. Evaluate whether a single
   atomic command should represent one user action, retaining both undo states.
   No change to this behavior was made at this checkpoint.
4. Measure normal-production paused two/three-input seeks with actual decoded
   frame acknowledgement, p50/p95, frame pacing and memory. Existing 504ms median
   includes correctness-test overhead. Preserve graph replacement and surface
   ownership; optimize redundant work only with matching decoded-frame regressions.
5. Design one original cross-dissolve join with explicit trim-handle/overlap policy.
   Existing main-track clips repack contiguously. Specify source windows, duration,
   audio overlap and split/reorder/delete semantics before introducing a persisted
   Transition model and reversible set/remove commands with backward-compatible defaults.
6. Route the two-source overlap through the established multi-input compositor,
   retaining sequence identity versus visual rank versus compositor input identity.
   Implement matching preview/export from one timing specification. Add the real
   join picker with Apply/Cancel/Remove only when the rendering path is functional.
7. Verify real decoded frames before/inside/after overlap, insufficient handles,
   pause/scrub/replay, PCM timing, persistence/history and R8. Expand effects one
   at a time only after cross-dissolve acceptance. Do not add every shader at once.

## Subsequent required order

| Stage | Deliverable and acceptance gate |
|---|---|
| 7 Keyframes | Shared position/scale/rotation/opacity evaluator; markers/navigation/easing; moving/scaling overlay preview and decoded export at several times |
| 8 Speed | Piecewise project/source time mapping; ramp presets; bounded reverse decoding with explicit audio policy; freeze insertion; trimmed/split/reordered/PiP/audio cases |
| 9 Compositing | Original masks/feather/invert, chroma/spill controls, blend modes; non-destructive GPU preview/export parity |
| 10 Text/graphics | Subtitle track/SRT/style-all; letter/line spacing and presets; original/imported shapes/stickers; complex-script/Pashto/mixed-direction decoded tests |
| 11 Audio | Duplicate/solo/envelopes/ducking/beats/snap; conditional EQ/balance/gate; actual decoded PCM and timeline-edit persistence |
| 12 Canvas/export | 21:9, real backgrounds, naming; qualified frame rate/codecs/resolution; unsupported choices disabled |
| 13 Templates | Real editable local timing/media placeholders/styles/transitions; no decorative-only cards |
| 14 Premium UX | Complete contextual tools, safe gestures, distinct Apply/Cancel/Reset/Done, compact/landscape/accessibility acceptance |
| 15 Verification | Fresh host/device/debug/R8 suites and licensed real-world media; full 32-step recorded native workflow and decoded 1080p/PCM |
| 16 Hardware | Normal-production profiling and physical-device codec/GPU/thermal/long-session gates; explicitly document unavailable hardware |
| 17 Delivery | Stable separate commits; exact test XML totals, source hashes, artifacts, licenses and honest remaining gates |

After each media milestone, run sequential regression using
`python3 scripts/run_premium_regression.py --output <unique-external-evidence-directory>`.
It runs forced host tests, media/editor/app debug/release instrumentation and
normal debug/release APK/AAB builds, retains fixture/application data, validates
installed test targets and preserves diagnostics. Separately sign an external
copy of the normal unsigned R8 APK for emulator smoke/profile; do not substitute
the opt-in instrumented release for normal production measurements.

No physical-device certification, unrestricted 4K/60fps support, full consumer
quality certification or completion of these creative milestones is implied.

## Stage 5 continuation checkpoint

Extract Audio's two-step Undo was reproduced by a failing actual ViewModel device
test and fixed using the existing EditHistory with a composite command. See
[Stage 5 evidence and remaining gates](STAGE5_PREMIUM_UX_PERFORMANCE.md). The actual
UI audit and production seek measurements remain blocked/unverified; Stage 5 is
incomplete. [Stage 6](STAGE6_CROSS_DISSOLVE_IMPLEMENTATION.md) remains unimplemented;
the timing proposal is not an accepted implementation. Do not skip ahead.
