# Milestone 3 delivery report — 2026-10-09

**Status: partial delivery. Milestone 3 and first-release readiness are not complete.**

Starting HEAD was clean `1d0a365`, following source/test commit `355a9d8`.
Implementation checkpoints: `1265ee9`, `2305b6c` (main). Existing architecture, serialized
schema, source media, preview/export interpretation and history were preserved.
No AI features or GPU rendering paths were added.

## Delivered changes

- Removed the permanent central preview playback overlay. Actual player-state
  transport and fullscreen access remain available below/beside the preview.
- Moved Fit into dedicated timeline navigation. Added accessible zoom buttons
  using the existing pinch gesture math; zoom preserves the playhead without
  rebuilding the composition.
- Fit uses actual edit duration and the trailing half of the fixed-playhead
  viewport, instead of treating thirty seconds of zoom-out slack as content.
- Independently decoded filmstrip frames are center-cropped to their tiles rather
  than stretched. Source trim sampling, density buckets and bounded caches remain.
- Removed duplicate Audio/Text entry points from the contextual row. No-selection
  state now leaves entry points in the primary toolbar. Selected clip actions
  have icons, readable labels, scrolling and 48dp touch bounds. Speed is disabled without a selected video clip.
- Adjusted portrait/landscape timeline heights to accommodate navigation while
  keeping the existing two-pane preview and surface ownership.
- Made text clip backgrounds opaque to prevent underlying labels bleeding through
  overlapping text clips. Separate selectable text lanes still need work.
- Removed Home's duplicate settings gear and added the Create navigation label.
- Repaired Home search: the query now switches to Room's saved-project search,
  supports clearing back to recent projects, and displays empty-result feedback.
- Disabled the four empty-handler Home shortcuts. Proper project handoff and
  camera capture are still pending; these shortcuts are not delivered features.
- Removed the duplicate export-success heading and simplified produced FPS text.

## Verification

125 logical host tests, run in Debug and Release variants, zero failures.
26 core-media emulator tests passed, including real decoded MP4/caption tests.
40 editor emulator tests passed, including the added navigation zoom test.
Four app acceptance tests passed after correcting test fixture identity and
Fit/reorder pointer geometry. An additional final visual workflow passed after the opaque text background; the final four-test checkpoint suite adds an assertion for disabled Speed. Original failures remain in evidence.
Debug and R8 release builds passed; final checkpoint revalidation is recorded in
`evidence/milestone-3/final-checkpoint-build.txt`. Production signing is not configured.

The first build attempt used Java25 and failed before compilation; Java17 fixed
that environment mismatch. The first app run failed on duplicate fixture names
and obsolete scroll semantics. The next run exposed stale reorder coordinates.
A concurrent final build hit Kotlin/KSP internal compiler errors; the raw failure is retained and a serialized rerun records the outcome. Tests retain their edit/persistence/export assertions; no test was removed.
Shared-emulator instrumentation ran one suite at a time; independent builds
were concurrent, so these executions cannot support performance conclusions.

## Visual evidence and limits

Before: `evidence/release-audit/screens/release-audit-selected.png` and supplied
premium reference images. Both supplied acceptance recordings were inspected at
sampled frames. After: `evidence/milestone-3/screens/selected.png`, `landscape.png`,
`portrait.png`, `home.png`, `text-editing.png`, `audio-editing.png`, `fullscreen.png`,
and export/destination screens. Inspected selected and landscape captures show
unobstructed preview, navigation separated from edits, preserved transport and
reachable toolbar. Early editor capture contains assets still decoding and is
retained as `editor-first-pass.png`; it is not filmstrip completion evidence.

These captures use synthetic acceptance media, not licensed natural footage.
The UI is not screenshot-equivalent to the supplied designs. Light Home,
small-screen/font-scale, natural footage, Filters/Crop/Adjust and all requested
failure-state captures are outstanding. Overlapping captions in the video itself
remain user-controlled positions; this patch does not implement transform handles.

## Preview/export and performance

No new engine effects were implemented. Existing decoded media/export tests
reverify their sampled behavior, not exhaustive frame/glyph/audio parity.
Short/offset audio remains explicitly omitted from preview and included in export.
There are no fresh controlled performance measurements for this checkpoint.
Prior matched 3.03% median jank and 225–231ms seek observations are historical
results only; no speed improvement is claimed. Existing vendor EGL/gralloc crash
remains open even though current acceptance passes. No physical device attached.

## First-release readiness matrix

| Area | This checkpoint | Remaining gate |
| --- | --- | --- |
| Main editor UI / timeline | Incrementally improved, emulator exercised | Natural footage, small devices, richer text/audio lanes and final visual refinement |
| Structural editing / speed / volume | Existing behavior regression-tested | Physical codecs, broader source matrix |
| Crop / rotation / flips / canvas editing | Not completed | Real shared effects and decoded parity |
| Text | Existing sampled export tests retained | Full controls, interactive handles, multilingual preview comparison |
| Audio | Existing functionality retained | Short/offset preview synchronization, fades/duplicate UI, voiceover |
| Filters / adjustments | Models only, toolbar disabled | GPU implementation and decoded preview/export comparison |
| Home / projects / settings | Search/navigation repaired | Shortcut handoffs, details, menu/settings audit |
| Export destinations | Existing acceptance path passed | Exhaustion, interruption, revoked grants and provider/device matrix |
| Lifecycle / performance | Existing guards retained | Vendor crash investigation, matched benchmarks, physical hardware |
| Release | Blocked | All remaining P0 gates and production signing |

The source inventory in MILESTONE_3_FEATURE_INVENTORY.md is the starting audit;
this report records the delivered delta. Milestones 3B–3E remain outstanding.
