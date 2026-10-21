> Current continuation (2026-10-19): Stage7 accepted at51090d81. Stage8 advanced audio is implemented and under final qualification; **NOT ACCEPTED**. Timeline point editing, focused audio UI and broader PCM/mixed exports have new evidence; full regressions/performance/release gates remain pending. [Stage8 checkpoint](STAGE8_CONTINUATION_CHECKPOINT.md). Historical roadmap below is retained; Stage9 has not begun.

# Post-Stage 6 development roadmap (proposal — not approved)

Prepared 2026-10-18 by the Fable continuation. Stage 6 remains **NOT ACCEPTED** pending the export-contract
decision; this roadmap is prepared independently and does not imply acceptance. No Stage 7 work has begun.

Basis: actual source audit plus `FINAL_FEATURE_MATRIX.md`, `PROFESSIONAL_EDITOR_ROADMAP.md` and the
feature inventory. "Implemented" below means functional code exists in the current tree; per-feature
preview/export verification status follows the Stage 5/6 evidence.

## Current implementation state (verified against source, 2026-10-18)

**Functional and covered by the passing gates:**
trim/split/reorder/duplicate/delete; crop/rotate/flip; fit/fill/canvas ratio; speed (0.5–2x, pitch-preserved);
volume/mute/fades; photo clips with duration; text (styling, positioning, timing, animation); audio
import/trim/split/move/extract/voiceover; filters and Adjust (shared GPU ColorGradeEffect); PiP overlay;
cross-dissolve transitions (with dedicated qualified correctness coverage); project management
(search/favorites/trash/rename/duplicate); themes/preferences; MP4 export at 720p/1080p with
gallery/Files/share destinations, atomic interrupted-export recovery and storage preflight; missing-media
relink; manual save/recovery.

**Present but thin:** stickers — UI and artwork exist (`EditorStickerArtwork.kt`, `EditorLibraryPanels.kt`),
no dedicated correctness evidence in the Stage 6 gates.

**Not implemented (no source):** keyframes (no matches in `feature/` or `core/`), speed curves/ramps
(piecewise time mapping), audio ducking/EQ/balance, masks/chroma/blend modes, subtitle/SRT track,
templates, 21:9 and additional export formats.

## Proposed stages

| Stage | Scope | Rationale |
|---|---|---|
| 7 — Essential completion | Sticker model + persistence + preview/export parity with dedicated tests; export settings depth (frame rate, bitrate presets); undo/redo audit for the newest tools | Closes the only shipped-UI-without-verified-function gap first |
| 8 — Advanced audio | Multi-envelope volume automation, ducking, fade curves; decoded-PCM qualification per feature, extending the existing SourceClock AAC contract | Builds on the strongest qualified subsystem |
| 9 — Effects and creative tools | Additional original transitions (one at a time, per the proven cross-dissolve process); masks/blend modes only after each transition is qualified | The Oct 18 evidence shows effects are where regressions surface |
| 10 — Professional editing | Keyframes (shared position/scale/rotation/opacity evaluator), speed curves with explicit audio policy, freeze frames | Highest functional value; largest engine surface; keyed off the existing microsecond timeline model |
| 11 — Export and compatibility | Export performance benchmark execution (if Option B is approved), physical-device QA matrix, wider API/codec sweep, thermal/long-session gates | Carries the export gate and device coverage that Stage 6 deliberately scoped out |
| 12 — Release QA and distribution | Production signing, full recorded workflow on hardware, Play readiness | Final gate only |

## Testing principles for the next stages

- Keep physical-device checks inside every stage from Stage 7 onward rather than deferring all hardware
  qualification to the end (Stage 6's emulator-only scope is the main reason a device gap remains).
- Every new media feature ships with decoded-frame/PCM identity tests in the style of the Stage 6
  verifiers, before the next feature starts.
- Performance budgets change only by explicit approval, with the statistic, fixture and timing boundaries
  written down first — the exact lesson of the unrecoverable 4.75 s reference.

## Explicitly out of scope until approved

Any UI redesign (the premium dark-navy design is final), any rebuild of the media engine, any change to the
passing seek-path optimizations, and Stage 7 implementation itself.
