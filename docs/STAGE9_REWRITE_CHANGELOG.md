# Stage 9 rewrite changelog — 2026-10-21

- Introduced one derived project render plan and explicit item/project/clip time mapping; retained serialized project version 1.
- Replaced two video inputs per join with two reusable transition lanes for all joins, using transparent still padding outside active windows.
- Centralized thirteen transition types and thirteen blend modes in one shared preview/export compositor program; corrected fade backing, wipe bounds/endpoints and Soft Light composition.
- Added coordinated downstream/compositor/child seek flushing, typed preview lifecycle, callback ownership, bounded recovery and Retry Preview.
- Added session-local live effect/transform/canvas state so visual edits reuse source topology.
- Replaced production per-item captions/stickers with a permanent shared canvas pass and bounded raster cache.
- Executed effects in authored order using reusable intermediate textures; corrected local effect/mask/opacity keyframe authoring/navigation and split/head-trim rebasing.
- Made transition source adjustments and join application one validated history operation with an explicit visible proposal.
- Made text creation/editing transactional; canceled drafts no longer leave extra history actions.
- Consolidated tool routes and selection; replaced production overlay gesture handlers with shared fitted-plane manipulation/snapping/handles.
- Bounded PiP render windows to the primary duration while retaining original authored source/timing data.
- Added explicit PiP Fit/Fill with a backward-compatible default for existing saved layers.
- Refined dark editor surfaces, expanded main-preview allocation, retained reachable tool/keyboard actions and reduced unused space.
- Added visible-source-window thumbnail sampling, cancellation of obsolete loads, photo-strip drawing and more precise viewport intermediate math.
- Serialized fresh export sessions, added structured failures, exact selected-cadence conversion with bounded backpressure and finished-MP4 validation before success.
- Preserved Stage 8 DSP/AAC/SourceClock, Room/document persistence, URI import durability, output destinations, pre-existing source work and historical release artifacts.
- Added unexecuted regression source and owner-only manual testing documentation. Build verification only; no runtime acceptance claim.
