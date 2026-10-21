# Stage 9 seek and preview — owner handoff

> **Subsequent device testing:** the owner later authorized installation and tests on NE2211. The newer installed APK, test results, corrections and a remaining photo/lifecycle backward-seek failure are recorded in [the NE2211 device test report](STAGE9_NE2211_DEVICE_TEST_REPORT.md). The build-only status and APK below are the historical first delivery.

**STAGE 9 SEEK & PREVIEW ENGINE IMPROVEMENTS — BUILD COMPLETE, OWNER REAL-DEVICE VERIFICATION PENDING**

21 October 2026. Starting and final Git HEAD: `a33d8da7dae63672c3cd4aabaf9580406ace4c42`, branch `codex/post-release-audit`. The expected older a4cae2b commit was not the actual HEAD. Work remains uncommitted on top of the substantial Stage 9 changes already present. No reset, staging, installation, app-data clear, connected instrumentation, emulator or benchmark campaign was performed.

## Installable artifact

- APK: `/Users/PUBLIC_USER/video editor/deliverables/stage9-seek-preview/video-editor-stage9-seek-preview-20261021-debug.apk`
- SHA-256: `97e69b9001b276309e0267d3569aaac3e0a1940e16b65827c9bb0d25f0430baf`
- Size: 26,383,145 bytes.
- Package `com.videoeditor`, versionName `1.0.0`, versionCode `1`, minSdk 26, targetSdk 36.
- Exact command: `./gradlew :app:assembleDebug --console=plain` — **BUILD SUCCESSFUL**, 490 ms, 321 tasks up-to-date after the source compilation build.
- Additional verification: `./gradlew :app:assembleDebug :core:media:compileDebugUnitTestKotlin :editing:compileDebugUnitTestKotlin --console=plain` — **BUILD SUCCESSFUL**, 2 s in the final source build. Regression sources compiled; tests not executed.
- ZIP CRC check passed; Android APK v2 signature verifies. Sampled proprietary binary names and `fancut/` paths absent from ZIP entries. Gradle version/dependency configuration unchanged.
- Logs and metadata: `deliverables/stage9-seek-preview/{assembleDebug.log,build-verification.log,artifact.json}`. Earlier missing Checkbox import was corrected before final compilation. Existing Compose icon deprecation warnings remain; no final compilation errors. Initial sandbox Gradle-cache lock denial was resolved by an approved build invocation.

## Research and architecture

Read-only reference metadata establishes VESDK-family identifiers, MediaCodec/OES texture/resource-manager identifiers, FFmpeg-related demux dependency, and EGL/GLES-linked native components. It does not establish the exact NLE SDK package/version, active runtime decoder, cache algorithm, Vulkan editor backend or native preview/export graph separation. Protected/nonstandard `libcapcut.so` was not unwrapped. Public BytePlus model/editor/media-session architecture is related SDK documentation, not proof of CapCut internals. Nothing proprietary was copied or executed.

Option A was implemented: retain Media3 1.5.1 and repair its preview execution. The AAR transform is specifically tied to 1.5.1 and patches effect/exoplayer/transformer classes, so a blind upgrade would risk source/binary/export/audio compatibility. Modern scrubbing APIs were researched, not called against an incompatible old library. Separate scrub renderer and commercial migration are documented options only.

## Actual new changes

- Boundary seeks and replay no longer unconditionally replace the composition player/GL graph. Child players persist; codec reuse/reallocation remains format/device dependent.
- Latest target replaces one pending request; unresolved decoding receives an 80 ms interactive work budget after reset admission. Frame delivery admits the next request immediately; release bypasses that budget. Timeline pixels/playhead update independently. All seeks remain precise; the public API has millisecond granularity and source frame cadence.
- Each video/image input must resolve its own source period/reset before submitting into the new generation. The source interval must contain the target; up to 1 ms of speed-mapper Float rounding is normalized. End-of-queued-seek is no longer confused with completed child preparation or submitted output. Repeated identical image pads retain explicit period indices, rather than equality-based lookup.
- PiP-only primary sink lifetime now matches the shared graph's period lifetime, fixing the previous disable-without-matching-enable route.
- Existing interval-constrained two-lane composition, source handle math, mandatory source readiness, bounded output watchdog and one-recovery policy remain. The last surface picture is continuity, not acknowledgment. New reset/release traces aid owner failure diagnosis.
- Speed preserves valid joins and source trims. Clip-local effect windows/keyframes/fades retime with speed; the primary duration/positions recalculate. Free tracks retain authored project positions. Conflicted adjacent joins get a jointly validated shortening proposal with exact old/new durations and an explicit checkbox; nothing is silently removed. Apply/Undo/Redo is one timeline transaction and stale review is rejected.
- History transfers undo/redo stack ownership only after success. Transition defaults prefer a short no-trim duration when available, and the actual stored value is clamped. PiP handles retain sub-degree rotation during a gesture instead of rounding away slow motion.

## Working Stage 8 / Stage 9 behavior preserved

The APK includes all preexisting working-tree text shaping/wrap, effects, canvas, mask/chroma/blend, command identity, importer, transition interval and preview watchdog fixes. This delivery did not rewrite Stage 8 DSP, source-clock AAC, priming/finalization, audio mixing, export compositor/shaders, MediaStore or save/share workflows. Projects, imports, media, exports, historical release artifacts, backups, asset kit and reference directory were not changed. The two preexisting modified device-test sources compare byte-for-byte with the saved starting baseline.

## Audit and external MP4 distinction

[All-tool audit](STAGE9_ALL_TOOL_BEHAVIOR_AUDIT.md) covers timeline, text, stickers, PiP, all 24 effect types, all 13 transitions, masks/chroma/all 13 blends, audio, canvas, export, history and persistence, with links to the retained detailed matrix. Existing missing capabilities remain explicit: PiP spatial crop/split/speed authoring, arbitrary text/sticker keyframes, custom chroma sampling, extra gradients, independent preset-recipe parameters and history across editor closure. This is not full CapCut feature/interaction parity.

The supplied 252.676978-second WhatsApp screen recording was visually sampled every two seconds across the whole file and independently inspected with ffprobe. Contact sheets and the supplied montage corroborate the loading/tool restriction/export story; they are not device traces or measurements. Its ~9.928 s maximum video key gap belongs to WhatsApp's re-encoded recording. The finished 14.9 MB export was not supplied independently, so its timestamp/sample-table/GOP correctness remains unknown. Cached Media3 1.5.1 source already requests a one-second I-frame interval. Export settings were preserved. `scripts/audit_mp4_seek.py` is available for the actual exported file.

## Exact files changed in this session

All paths below are relative to `/Users/PUBLIC_USER/video editor`; `deliverables/stage9-seek-preview/changed-files.json` includes file paths and content hashes and the incremental patch is `session-source-changes.patch`. They distinguish this session from the prior dirty working tree.

Production source:

- `core/media/src/main/java/androidx/media3/exoplayer/video/MultiInputPlaybackVideoGraphWrapper.java`
- `core/media/src/main/java/androidx/media3/transformer/PreviewSequenceRenderersFactory.java`
- `core/media/src/main/java/com/videoeditor/core/media/preview/CompositionPreviewController.kt`
- `core/media/src/main/java/com/videoeditor/core/media/preview/SeekDispatchGate.kt` (new)
- `core/media/src/main/java/com/videoeditor/core/media/preview/SeekResetPosition.kt` (new)
- `editing/src/main/java/com/videoeditor/editing/commands/SpeedChangePlanner.kt` (new)
- `editing/src/main/java/com/videoeditor/editing/commands/TimelineCommands.kt`
- `editing/src/main/java/com/videoeditor/editing/history/EditHistory.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorScreen.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/EditorViewModel.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/LayerCanvasHandles.kt`
- `feature/editor/src/main/java/com/videoeditor/feature/editor/TransitionTools.kt`

New regression/tool source:

- `core/media/src/test/java/com/videoeditor/core/media/preview/SeekDispatchGateTest.kt`
- `editing/src/test/java/com/videoeditor/editing/commands/SpeedChangePlannerTest.kt`
- `scripts/audit_mp4_seek.py`

Eight new documents:

- [Engine research](STAGE9_CAPCUT_ENGINE_RESEARCH.md)
- [Preview decision](STAGE9_PREVIEW_ENGINE_DECISION.md)
- [Seek/scrub architecture](STAGE9_SEEK_AND_SCRUB_ARCHITECTURE.md)
- [Transition seek repair](STAGE9_TRANSITION_SEEK_REPAIR.md)
- [Speed/transition compatibility](STAGE9_SPEED_TRANSITION_COMPATIBILITY.md)
- [MP4 seek audit](STAGE9_EXPORTED_MP4_SEEK_AUDIT.md)
- [All-tool behavior audit](STAGE9_ALL_TOOL_BEHAVIOR_AUDIT.md)
- This handoff.

## Owner manual checklist

Use a duplicate of an important existing project and a fresh project; retain your original data. The assistant did not install this build or alter the phone.

1. Slow/fast forward/backward drag, rapid reversal, flings/interruption and pinch/zoom on video/ruler/audio/text/PiP/sticker lanes. Lift at a chosen frame; confirm picture/time agree and Play works immediately.
2. Add several joins; seek before/at/inside/after each, reverse without waiting, repeat exact boundaries, play across all joins. Exercise all thirteen types, 0.5/0.75/1.5/2×, trim/split, delete and Undo/Redo. Confirm the midpoint is composed, not just outgoing video or padding. Check end/replay.
3. Change speed on outgoing, incoming and a short middle clip with two joins. Valid joins must stay. Review shorter-duration proposals, Cancel and Apply; one Undo restores exact trims/speed/keys/transitions. Check music/voiceover/captions/PiP keep authored positions and primary audio follows the clip.
4. Create/edit English, Pashto/Arabic and mixed-direction multiline captions. Font/style/color/template/animation, keyboard, cancel, bounding boxes, move/scale/rotate/duration/duplicate/delete. Test stickers/search/recent/replace/order and slow PiP rotation/resize, mask/feather/invert, chroma and blends. Watch live response and selection.
5. Stack color/image/preset effects, reorder/intensity/keyframes/disable/reset/Cancel. Canvas ratio/Fit/Fill/background. Then background/foreground and save/close/reopen. Check values and export placement; reopening does not restore undo history.
6. Import/reuse/extract/record audio; volume, automation, fades, EQ, ducking, pan, speed and waveform. Listen for AV sync before/after seeks, speed changes and joins. Panel Reset then Cancel must retain the original settings.
7. Export combined layers/transitions/audio at 720p and 1080p; cancel/retry/save/share. Inspect the actual MP4 separately and compare random seeks, pause/seek/play, endpoints and sync in two players. Send the actual output if the external-seek problem persists.

For failures record the APK hash, project time, selected tool, preceding gesture, whether Play was requested, and the new recording. If available retain logcat containing `PreviewSeekOwnership`, `PreviewInputRouting`, graph errors and decoder events; a recording alone cannot prove the native failure mechanism.

## Known unverified risks

Persistent cross-period preparation, image/video handoff and source EOS ordering are source-corrected but untested on NE2211. Fractional retiming, transition pixels, decoder resource pressure/thermal behavior, VFR/unusual sources, RTL font/IME behavior, shader colors/alpha, AV sync and lifecycle/reopen require physical testing. Structural source/crop/orientation/canvas-dimension or audio-topology edits still reconstruct as needed. No added full-frame LRU, low-resolution scrub renderer, adjacent-decoder prefetch or new commercial engine is claimed. The actual finished MP4's seeking is still unverified. Build success is not a measured smoothness or final acceptance result.
