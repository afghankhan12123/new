# Milestone 3 source inventory

Audited starting HEAD: `1d0a365`, clean tree, 2026-10-09. Historical verification comes from RELEASE_READINESS_AUDIT.md; it is not a fresh test pass.

| Tool | Classification | Source evidence / remaining work |
| --- | --- | --- |
| Split, trim, reorder, duplicate, delete, undo/redo | Fully implemented and verified historically | editing/commands/TimelineCommands.kt; editor pointer/device tests |
| Real thumbnails and decoded waveforms | Implemented but requires UI completion | TimelineMediaRepository.kt, TimelineDrawing.kt; strips currently distort narrow frames |
| Fit, pinch, scrub, snapping | Implemented but requires UI completion | TimelineViewport.kt, TimelineView.kt; move Fit out of contextual tools |
| Speed and clip volume/mute | Fully implemented and verified historically | EditorViewModel.setSpeed/setVolume/setSelectedMuted; CompositionBuilder |
| Crop, rotate, flip | Partially implemented | Clip transform model; editor controls/effect wiring absent |
| Canvas aspect presets | Partially implemented | Canvas model and creation flow; full edit Fit/Fill verification pending |
| Caption add/edit/style/position | Implemented but output parity unverified | TextEditorSheet, caption effects and decoded export matrix; exhaustive preview/RTL parity open |
| Caption scale/rotation/font/duration controls | Implemented but requires UI completion | TextOverlay model; current sheet exposes subset |
| Audio import, trim, reposition, volume, mute, delete | Partially implemented | EditorViewModel and gestures; short/offset preview explicitly omitted |
| Audio fade and duplicate UI | Implemented but requires UI completion | AudioClip and commands/processors; output tests needed |
| Voiceover | Not implemented | No recorder UI; keep hidden |
| Transitions | Intentionally deferred | Cuts only; no verified transition engine |
| Filters and adjustments | Placeholder | Effects models; disabled toolbar, no GPU wiring |
| Project search, rename, duplicate, trash/favorites | Fully implemented and verified historically | feature/projects and core/data; fresh workflow audit pending |
| Project details | Not implemented | No detail screen |
| Camera / Home shortcuts | Placeholder | HomeScreen shortcuts need valid project handoff; camera absent |
| Photo Picker import | Fully implemented and verified historically | feature/importmedia and app acceptance |
| Export, Photos, Files, Share | Fully implemented and verified historically | ExportEngine, feature/export; provider failure matrix incomplete |
| Settings | Partially implemented | DataStore preferences; audit each visible row before release |
| Surface lifecycle and performance | Partially implemented | Controller ownership guards and probes exist; retained vendor crash and physical gates open |

Only an emulator is attached. Physical hardware, production signing, performance sign-off and complete audio preview parity remain release gates.
