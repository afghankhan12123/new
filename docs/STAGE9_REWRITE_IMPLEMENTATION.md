# Stage 9 rewrite implementation inventory

## New production components

- `core/model/.../render/ProjectRenderPlan.kt`: validated immutable render IR, shared transition lane identities and visual/topology comparison.
- `core/model/.../render/VisualTimeMapping.kt`: explicit adapter/clip clocks and local visual rebasing.
- `editing/.../commands/TransitionHandlePlanner.kt`: source-aware proposal and atomic undoable application.
- `core/media/.../composition/RenderPlanState.kt`: isolated preview/export publication.
- `core/media/.../composition/ProjectCanvasOverlayEffect.kt`: permanent caption/sticker canvas pass with shaped-text raster caching.
- `core/media/.../androidx/media3/effect/CompositorFrame.java` and `Stage9CompositorProgram.java`: shared borrowed-frame representation and GPU layer program.
- `feature/editor/.../EditorToolState.kt`, `LayerCanvasHandles.kt`, `KeyframeNavigation.kt`: typed tool routing, shared direct manipulation and local-key navigation.

## Replaced integration

`CompositionBuilder` compiles the plan, creates two reusable transition sequences, clips PiP render windows to the primary end without mutating authored layers, uses transparent still padding and installs the same canvas pass in both adapters. The old `TransitionGlEffect.kt` was removed after production-reference inspection; transition pixels now belong to the shared compositor. The existing legacy standalone caption/sticker effects remain for compatibility/reference consumers but are not the builder's production overlay path.

The preview graph/player adapters now implement a coordinated seek barrier, distinguish period changes from seeks, stop old callback admission and use cancellation-safe idempotent compositor release. `CompositionPreviewController` owns phases, same-session visual updates, latest-target admission, bounded recovery and explicit retry. Existing end clamping, tail-frame probing, source-clock audio behavior and suspend/resume ownership remain integrated.

The GPU effects implementation executes the authored stack in order, preserves existing nine adjustments/eight image effects/seven presets and reads clip-local intensity tracks. It reuses one program plus two scratch textures. Layer mask geometry uses the shared Y/rotation convention, retains rectangle/rounded/ellipse/gradient/regular 3–8-sided polygons, feather, inversion and opacity. Chroma, all thirteen blend modes and opacity keyframes remain implemented. Existing source/canvas crop and flip paths remain intact.

Export creates fresh isolated sessions, always applies the selected rational frame grid, retires resources on the application looper, retains structured errors and validates the finished container. The frame converter admits one pending input and one output lease; slow/irregular input cannot emit past a fixed texture pool in one loop. Output canvas selection, bitrate ladder, AAC finalizer, cancellation and existing gallery/Files save paths remain integrated.

## History/persistence and audio

Text and effects/mask/chroma/blend panel drafts no longer populate history before Apply. Shared canvas gestures retain one-command commits. `TimelineCommands` rebases clip-local visual timing on split/head trim; `TransitionHandlePlanner` preserves standalone authored track positions. PiP Fit/Fill adds an optional `OverlayClip.scaleMode` field defaulting to Fit for legacy version-1 documents. No Room migration, schema version bump or destructive project conversion was introduced. No Stage 8 DSP source or imported media was modified.

## Build-only verification

Production Kotlin/Java/resources and APK packaging were compiled with `./gradlew :app:assembleDebug --console=plain`. The final handoff/manifest records the build and artifact identity. Regression source in `editing/src/test/.../Stage9RenderPlanRegressionTest.kt` covers input budgets across many joins, visual/topology distinction, atomic handle Undo/Redo, smooth-keyframe split preservation and adapter time conversion. It was not executed. No test task, instrumentation, emulator, phone install/launch, benchmark or device capture was run.

## Review boundaries

Compilation does not establish MediaCodec/EGL correctness, decoded frame identity, AV synchronization, successful hardware exports or performance. Those are pending the owner checklist. The recording did not include a throwable chain, so source-level failure mechanisms must not be described as a proven unique diagnosis of its 0% export failure.
