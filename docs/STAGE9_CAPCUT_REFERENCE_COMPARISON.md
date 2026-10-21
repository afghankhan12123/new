# Stage 9 recording comparison — 21 October 2026

Evidence: owner recording A (291.41 s, 286×640) and reference recording B (220.53 s, 286×640), sampled across their full durations. Contact sheets are in `deliverables/capcut-redesign/reference/`. These are compressed recordings, not layout dimensions. In B, the old editor shown INSIDE the preview is the imported video; only the outer UI belongs to CapCut. The old untracked visual-defect audit contains historical acceptance assertions and is not evidence of this implementation's acceptance.

| Area | Recording A | Recording B (outer controls) | Implementation direction and production components |
|---|---|---|---|
| Home | 0:00–0:20 picker/import; 4:50 shows the old home inside a video player | 0:00 prominent New video and recents | Keep original project identity, simpler creation card and accurate project covers (`HomeScreen`, existing repositories). No claimed import observation beyond actual picker flow. |
| Import | Picker, then redundant-looking selected-media review at 0:20 | Import not demonstrated beyond opening an existing edit | Preserve multi-select ordering and durable URI ownership; retain review where reordering is useful (`ImportScreen`, `EditorViewModel`). |
| Header | Repeated Main Editor or tool name; gradient export competes with selection | 0:10 onward compact close, output selector and export | Compact project header, history and output controls; remove repeated tool names (`EditorScreen`). |
| Preview | Large when idle, very short with every form; repeated Loading preview | Stable fit plane; direct selected-layer outline | Allocate by actual remaining window, IME and active tool; retain one SurfaceView (`EditorScreen`, shared canvas handles). |
| Transport | Time, play, undo, redo, fullscreen | Time, central play, keyframe/history controls | Central play, stable time, history in header, boundary and timeline options (`TransportBar`). |
| Timeline | Multiple small empty lanes, left icon rail, small thumbnails | 0:16–0:44 clear thumbnail strip and center playhead; visible end + | Shared viewport math, larger main strip, quieter rail, readable colored layers, end append (`TimelineView`, `TimelineDrawing`). |
| Pan/scrub/zoom | Repeated preview loading while repositioning; no reliable latency measurement possible from compression | Content moves under fixed playhead | Retain latest-wins seek and viewport math; correct output landing contract, gesture ownership, avoid graph rebuild on visual edits. |
| Selection/tools | Two stacked tool rows; global tools remain under forms | 0:30 selected clip replaces global tools; back returns | One contextual dock with persistent back action, full functional tool panels in same workspace (`EditorToolState`, `ContextualActionBar`). |
| Transitions | 4:00 reports two videos required despite a multi-item edit | Join markers visible; full transition browser not demonstrated | Direct tappable valid joins; resolve actual adjacent videos and retain explicit undoable handle plan (`TransitionTools`, `TransitionHandlePlanner`). |
| Text | 2:00, 2:40 conventional form, no automatic input focus; stopped preview at 2:10 | 1:20–1:50 direct input, keyboard, templates/fonts/styles/animations, orange duration block | Focused input with selected draft identity, compact header confirmation, renderable style recipes and categories; explicit new versus edit (`TextEditorSheet`, text transaction). |
| Stickers | Tiny gallery/artwork; large transformation handles | Arrow layer visible at 1:20; insertion gallery not shown | Larger real bundled artwork, category/search and recent history; shared direct manipulation (`EditorStickerPanel`, `LayerCanvasHandles`). No invented catalog. |
| Audio | Audio waveform, form volume/fade/EQ controls | 0:50 contextual extracted-audio waveform; Split/Fade/Volume | Preserve Stage 8 DSP, improve dock and audio source browser; processing unchanged. |
| Overlay | PiP shown 2:30 onward; extra separate row/panel | 2:00–2:20 layered colored strips | Same canvas transform owner for PiP/sticker/text; outside tap and selection changes reconcile tools; preserve masks/chroma/blend. |
| Filters/effects | 0:50 parameters; 3:50 mostly repeated adjustment icons | 1:00 visual grid, categories, selection border | Real source-derived thumbnail swatches from existing effect/color renderer; library before details, intensity and compare (`VisualEffectsPanel`). |
| Canvas | Transform/position form at 1:30 | Actual outer canvas crop workflow not demonstrated | Retain real ratio/crop/fit/fill values, same canvas plane and model. |
| Export | 4:10 config, 4:20 progress; no verified output fidelity in the recording | 2:50–3:30 uncluttered thumbnail with actual percentage | Streamline presentation; preserve isolated sessions and actual MP4 validation (`ExportScreen`, `ExportViewModel`, export engine). |

## Implementation order

1. Shared cinematic tokens, compact panel header, one contextual dock, measured IME-aware shell.
2. Timeline track hierarchy, direct joins and shared selection navigation.
3. Direct focused text transaction, renderable templates, canvas selection, larger sticker gallery and recent assets.
4. Visual effect browser with real rendered thumbnails, stack and parameter detail; retain all existing effect/mask/blend/audio functionality.
5. Diagnose preview output/seek and lifecycle contracts using source and targeted NE2211 fixtures; fix supported defects, preserve Stage 8 audio.
6. Export/home finishing; assemble, focused runtime checks authorized by the latest user message, then deliver APK/hash and explicit limits.

The user's latest direct message authorizes necessary tests on connected NE2211, superseding the pasted brief's build-only restriction. No broad acceptance claim is implied.
