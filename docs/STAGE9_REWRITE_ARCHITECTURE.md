# Stage 9 rewrite architecture — 2026-10-21

Status: implementation and build verification only. Owner device testing is pending.

The owner recording reports persistent preview failure after transitions and export frame-processing failure near 0%. It is evidence of failed workflows, not a decoder/GL stack trace. This rewrite addresses concrete source-level mechanisms; no claim identifies a proven physical-device root cause.

## Source audit and replacement decisions

The starting revision was `898e3afb165f60571e65bcd42d4223d12bcbbf32` on `codex/post-release-audit`, with six existing modified files. Existing production edits were retained and integrated. The two existing instrumentation-test edits were left unchanged and outside the implementation commit. `codex/stage9-rewrite-checkpoint-20261021` retains the starting HEAD; the pre-existing diff is preserved separately as a Git object/ref (see handoff).

Concrete findings:

- Each join allocated two full-project decoded video sequences, with hidden footage repeatedly tiled as padding. Decoder/input count grew with every join.
- Multi-input preview rejected ordinary in-place seeks; controller seeks and even pause could replace the entire player.
- Preview and export duplicated their compositor shader implementation.
- Fade-through transitions left the opaque original primary video underneath; several wipe shaders used reversed smoothstep bounds.
- Effect stacks aggregated parameters, so stack reordering did not reliably change pass order. Effect clocks and several authoring panels disagreed about project versus local time.
- Text/sticker/effect changes entered full composition signatures. Stickers were missing from one signature. Effect/mask/chroma/blend drafts entered command history before Apply.
- Independent panel flags and a second PiP-selection ID could disagree about active tools and selection.
- Full-source thumbnail strips lost useful detail on deeply zoomed long clips.

## Fourteen architectural contracts

1. **Edit model:** existing immutable `Project`, stable clip IDs, version-1 serialization and Room/document storage remain authoritative. `ProjectRenderPlan` is a derived, non-persisted IR.
2. **Commands:** existing `EditHistory` remains the sole commit path. `TransitionHandlePlanner.Apply` commits the validated trims and join together. Text and visual panel drafts are preview-only until Apply.
3. **Time:** integer microseconds; intervals are half-open. `ResolvedTransition` maps handles around an unchanged cut; `ItemTimeMapping` translates adapter timestamps to project/clip time.
4. **Preview graph:** one controller/player/surface owner, explicit `PreviewPhase`, immutable plan publication per session, in-place seek barriers and latest-target coalescing.
5. **Export graph:** a fresh Transformer per invocation under a session mutex. Its plan publication and GPU objects are isolated from preview.
6. **GL rendering:** both queue adapters call `Stage9CompositorProgram`; transition parameters are evaluated at the primary output timestamp. Pixel transitions are compositor operations, not clip filters.
7. **GPU ownership:** native input/output pools bound queues. Preprocessors lend textures; compositor returns those leases before seek flush; each effect owns/reuses its own program/intermediates.
8. **Parity:** both adapters consume the same plan, transition equations, canvas overlay renderer, effect evaluator and layer compositor. Execution adapters still differ for playback silence/image sources and Transformer source-clock handling.
9. **Overlays:** `ProjectCanvasOverlayEffect` is a permanent canvas pass for any number of text/sticker model objects, with byte-bounded caption rasters. PiP transform/opacity/blend/mask/chroma parameters publish through the session.
10. **Effects:** one compiled shader program per effect-stage instance, ordered passes through two reused intermediate textures, clip-local activation/intensity evaluation and existing preset mappings.
11. **Navigation:** `TimelineSelection` includes PiP/transition/effect identities; `selectedOverlayId` is derived. `EditorToolState` supplies mutually exclusive panel routes and typed draft routes.
12. **History:** canvas gestures commit one command on successful lift and roll back on cancellation. Text Apply commits one create/update; canceled text does not leave a resurrectable draft in history.
13. **Persistence:** the optional PiP Fit/Fill field defaults to Fit for existing version-1 documents; no database migration or incompatible project conversion. Rebasing local visual keys on split/head trim preserves interpolation; existing source references, URI durability and imported files remain intact.
14. **Export configuration:** actual target canvas and cadence, existing bitrate ladder/AAC DSP, encoder fallback disabled, real progress, cancellation cleanup and container validation before success. Existing gallery/Files finalization is retained.

## Deliberately retained foundations

Stage 8 DSP, SourceClock/AAC implementation, all five audio fade curves, volume envelopes, EQ, ducking, panning, existing import/metadata repositories, Room schema, atomic document persistence and output destinations were not rewritten. Their integration remains a device-verification dependency.

## Limits

Eight visual compositor inputs remain the pinned preview limit: primary + two transition lanes + up to five PiP layers; without transitions up to seven PiP layers. The number of joins no longer consumes additional input slots. Video-to-video transitions retain the existing source-handle contract. This is an SDR pipeline; broader HDR/codec/device qualification is not asserted.
