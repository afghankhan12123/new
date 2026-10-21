# Editor capability matrix — October 15–16, 2026

Starting branch `codex/post-release-audit`, HEAD `d8e9c2656c85f93648d4b27d3354465805f7fba0`. Final production editor source is `20b089e9`, alongside the preserved original Stage 6 working-tree changes. No protected-directory contents were read.

Classification: **1** fully implemented and verified in the stated emulator scope; **2** implemented but awaiting further qualification; **3** partially implemented; **4** not implemented. Verification is bounded by [the recorded commands, fixtures and failures](VERIFICATION.md), rather than a claim about every codec or device.

| Capability | Classification | Production integration and limits |
|---|---|---|
| Source trim | 1 | Existing handles plus exact integer-microsecond source in/out panel; bounds, speed-derived duration, persistence and history tested |
| Project trim | 3 | Ripple packing implemented; project placement displayed separately. No independent project-range trimming command or editable second domain is advertised |
| Selected split | 1 | Actual command; live playhead eligibility and boundary safeguards; Apply/Undo walkthrough |
| Split all unlocked | 4 | No lock model or command. Explanation only, no enabled operation |
| Source crop | 1 | Normalized rectangle, draggable cached-source handles and precise sliders; actual shared transform/render path |
| Aspect ratio / canvas | 1 | Real transient portrait output before Apply; existing presets retained, save/history commands preserved |
| Free canvas / blur background | 4 | No consistent preview/export implementation; omitted |
| Rotate / transform | 1 | Shared draft drives exact rotation, visible bounds, drag/pinch, crop, scale, offsets, flips and fit/fill. Three quarter turns wrap to -90°. Actual rotated-video pixel assertion passes |
| Constant speed | 1 | 0.5/1/1.5/2 presets and real duration. Existing shared audio/video path; non-1x blocked on dissolve participants |
| Speed curves / ramp / reverse / freeze | 4 | Omitted |
| Preserve-pitch toggle | 2 | Basic supported-speed pitch behavior has device coverage; no independently qualified on/off setting is exposed |
| Clip volume / mute | 1 | Actual selected video/audio clip command; mute preserves gain. No ambiguous track-wide label |
| Fade | 1 | Real audio-clip fade-in/out controls; existing main-video Fade retains its paired picture/audio behavior |
| Track-wide volume / ducking / normalize | 4 | No corresponding track-wide/DSP control is shipped |
| Cross Dissolve | 2 | Existing 1x, handle, duration and input-count safeguards retained; preview, Apply/Cancel/Remove and history tested. Independent Stage 6 post-EOS media case still fails |
| Slide / zoom / blur transitions / easing | 4 | Omitted |
| Text | 1 | Existing styling, timing, multilingual text, transforms and animations; live editor, snapshot cancellation and recreation tested |
| Filters | 1 | Real source thumbnails, shared GPU effect, transient preview and staged Apply/Cancel |
| Adjustments | 1 | Real GPU parameters and reset baseline; bounded scrolling controls; no empty HSL tab |
| Overlays / PiP | 1 | Actual imported layers, trim/placement/audio/transform controls; immediate undoable edits are identified. Existing input-count limit retained |
| Stickers / marketplace | 4 | Omitted |
| Undo / redo | 1 | Existing command history; transform plus fit mode uses one composite action |
| Persistence | 1 | Existing atomic project documents and Room index; real reopening tests, no schema change |
| Preview / export overall | 2 | Common supported workflows and ordinary R8 consumer checks pass. Specialized Stage 6 concurrency qualification remains open; this redesign does not expand its accepted scope |

Fresh isolated media coverage includes passing decoded transform/color-grade export pixels, transformed multilingual preview/export parity, supported speed/pitch/clock cases, decoded audio fades/mute, and overlay workflows. The one failed Stage 6 source-clock case remains recorded instead of being hidden by those passes.

The ordinary non-debuggable R8 consumer check passes playback-to-EOS, backward and alternating scrubs, boundaries, exact end, post-end seek, play-after-seek and replay on the retained Stage 6 project, with independently checked main/PiP/caption pixels. This does not establish full Stage 6 acceptance or claim that cosmetic work corrected its previously reported issue.
