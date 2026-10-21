# Runtime visual comparison — October 15–16, 2026

Primary authority: Dark Android Video Editor UI Concept Board-1.png. Secondary: Android Video Editor UI Showcase-2.png. Neither board is used as runtime media or evidence.

Evidence root: `/Users/PUBLIC_USER/video-editor-redesign-evidence/20261015/`.
[Twenty-five final-source runtime captures](SCREENSHOTS.md) are in `final-source-debug-captures/`, with source SHA and hashes in its manifest. The walkthrough passes state/history checks and actual screenshot pixels for rotation. `layered-pass-01/` and earlier failed captures remain retained as intermediate evidence and must not be substituted for the final captures.

| Reference region | Implemented comparison | Remaining difference / qualification |
|---|---|---|
| Navy workspace | Editor-local #07111F background, #0B192A panel, #102136 controls; thin borders | Other application destinations retain their own theme |
| Header | Back, actual project or tool title, actual export resolution, gradient Export | Source title ellipsizes; resolution opens actual export configuration |
| Preview | Real composed output with source aspect, letterboxing, captions and PiP | Fixture is waterfall footage, not the boards' mountain illustration; black canvas is real |
| Playback | Shared actual clock to tenths, play/pause, boundaries, history, fullscreen | Boundary navigation retained as an existing functional capability |
| Timeline | Real thumbnails, purple text, blue decoded waveform, teal PiP, white trim handles, cyan selection | Existing centered-playhead semantics retained, including empty space before time zero; track controls are not yet the boards' narrow icon rail |
| Bottom tools | Scrollable outline tools; cyan selected tiles; existing destinations include Canvas, Overlay and Transition | Future stickers/marketplace omitted; disabled tools cannot commit actions |
| Tool panel | Bounded scroll body, stable surface, pinned Cancel/Reset/Apply | Text and PiP retain existing live-edit semantics and Done; PiP labels explain immediate undoable edits |
| Trim | Source numeric values, duration, project-placement label, Set to Playhead, reset and staged commit | Project placement is distinguished but is not a second independent editable trim domain; handle trimming remains immediate outside staged panels |
| Crop & Ratio | Main actual composition changes to portrait before Apply; existing ratio presets retained | Free-form project canvas and blur background are unavailable; source crop remains distinct from canvas ratio |
| Transform | Exact rotation, 90-degree steps, center, scale/position, flips, fit/fill, crop bounds | Selection overlay and source-thumbnail crop handles implemented; actual rotated video matches its outline; quarter turns wrap through the full rotation |
| Speed | Dedicated 0.5/1/1.5/2 controls with actual resulting duration | No unqualified pitch, curve, reverse or freeze controls |
| Volume | Accurate selected clip scope, percentage and mute; real audio-clip fades | No fictitious track-wide gain or ducking; main-video Fade retains its established picture/audio behavior |
| Transitions | Actual existing Cross Dissolve validation, duration, preview, remove and shared actions | No unsupported gallery effects or easing; source handles can legitimately disable a dissolve |
| Filters / Adjust | Actual source thumbnails, GPU preview and committed settings; staged cancellation | No empty HSL tab; advanced parameters scroll in the bounded body |
| Responsive | 360x640, 393x852, 412x892, 800x1280, 640x360 landscape and 130% font walkthrough passed with Apply/Cancel/playback and nonzero preview bounds | TalkBack, additional font scales and physical-device acceptance remain to be completed; landscape temporarily hides bottom navigation while a tool owns the panel |

This is a fidelity improvement, not a pixel-perfect or full acceptance claim. The reference's iPhone hardware decoration is intentionally excluded. Material differences above remain explicit rather than being hidden by mockup screenshots.
