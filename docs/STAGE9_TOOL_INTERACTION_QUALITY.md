# Tool interaction quality audit

Concrete repairs:

- Sticker Replace previously called `UpdateSticker`, whose invariant rejected any asset-key change. It now permits a nonblank key; the ViewModel verifies it against the real catalog. ID, transform, timing and history survive replacement.
- PiP contextual Duplicate previously reached a video-only handler and did nothing. It now dispatches `DuplicateOverlay`, respects the input budget and selects the new stable ID.
- Audio split keeps its envelope boundary point IDs across Redo, so later automation edits still reference the same points. Audio extraction preserves existing fade durations/curves.
- Primary/PiP duplicate commands and primary split now keep the same IDs on Redo. Split/head trim preserve the exact original clip for Undo. Primary photo splitting is supported at command level; the existing contextual UI still uses Duration for photos.
- PiP trim Undo restores the entire previous layer including duration. Head trim/split rebase visual-effect, opacity/transform-track and mask-track local time. The production PiP source sliders use that command rather than an unrelated full-layer update.
- Undo of audio/PiP insertion preserves preexisting project-library references. Append Undo restores previous media-reference state without deleting imported source files.
- Full Mask, Chroma and Blend sheets had no reachable production opening action. They are now reachable from PiP contextual actions. Full mask includes independent height, rotation, feather, opacity and grouped keyframes. Existing compact PiP tools remain.
- Effect intensity, PiP opacity and mask navigation share `KeyframeNavigation`, including selected-key identity, interpolation, delete and Move here. Mask property keys move/delete together. Text retains its supported enter/exit animation model; no arbitrary text keyframe editor is claimed.
- Audio automation/fade/EQ/balance/ducking Reset now changes the panel draft only; Cancel cannot leave an unwanted Reset command in history. Apply uses the existing Stage 8 update command/DSP order.
- Missing-media detection and relink duration validation include PiP sources.
- `DurableMediaImport` replaces duplicated copy/grant code in initial import and editor import. A provider returning no stream or a failed copy produces a user-visible failure, not a saved temporary URI. Only a new incomplete private copy is cleaned up; original files remain untouched.
- Export screen recreation no longer reloads an already-loaded project over an active session. Export preference writes serialize and read the latest selection after admission. Late source-FPS discovery cannot change an active export's configuration. Cancel also cancels an export queued during project loading. Transformer, codecs, progress, AAC finalization, completed files and sharing remain the existing path.

The former separate text/sticker/PiP canvas gesture implementations were unreachable after `LayerCanvasHandles` became authoritative. They were removed after checking production references. The live shared handler retains drag/corner scale/pinch/rotation, center guides, selection order and transactional gesture Undo. Safe-area feedback is amber; it does not rewrite intentional overflow.

Scope limits are explicit in the all-tools matrix: PiP spatial crop has model/render support but its current PiP Crop section edits source timing; PiP split/speed command APIs lack dedicated toolbar UI; only LinearGradient mask is implemented; custom sampled chroma colors have no picker; undo history remains bounded/in-memory across an editor session. No icons are counted as proof of end-to-end correctness.
