# Transition preview repair

21 October 2026. Sequential export and interactive preview use different execution sessions; this change is confined to preview ownership and editor behavior.

## New corrections

- Removed automatic player/GL reconstruction whenever a seek crosses a clip, transition interval or overlay boundary. The current graph performs the seek.
- Each input blocks frame submission until its own renderer's resolved project reset matches the newest requested timestamp. The graph's enqueued `endSeek` message alone cannot acknowledge asynchronous period preparation.
- Primary reset completion now covers multi-input compositions. Generation checks reject old acknowledgments; no wait on an obsolete secondary lane authorizes a bare frame.
- PiP-only period handoff previously called `defaultVideoSink.onRendererDisabled` while the re-enable branch deliberately skipped multi-input period reinitializations. Primary sink lifetime now consistently spans multi-input child periods, as it already did for transitions.
- Bitmap inputs also acknowledge source resets and retire their pending reset flag after submission. This matters for transparent pads and image PiP, not only primary photos.
- Repeated unresolved scrubs no longer reset decoding every display frame; newest target replaces the pending request and final lift bypasses the work budget.
- A new transition defaults to at most 0.30 s and prefers the available no-trim range. Stored duration is clamped to the real permitted range, not only the slider thumb. Existing joins retain their authored value when valid. Explicit handle trimming/review remains atomic and undoable.

## Preserved canonical math and rendering

`Project.resolveTransitions` resolves a centered half-open interval around each contiguous join. Both source timestamps come from `ResolvedTransition.outgoingSourceAt` / `incomingSourceAt`, incorporating speed and trims. `ProjectRenderPlan` supplies the reusable outgoing/incoming lanes; `CompositionBuilder` copies source visual properties into their source windows. `OverlayCompositorSettings` and `Stage9CompositorProgram` retain the shared transition progress/math for preview and export.

All thirteen types share this path: Cross Dissolve, Fade Black, Fade White, Slide Left, Slide Right, Wipe Left, Wipe Right, Wipe Up, Wipe Down, Push, Split, Zoom Dissolve, Smooth Zoom. No shader/type math or export compositor was rewritten.

The starting working tree already included important interval/padding/EOS repairs: `SecondaryFrameSelector` excludes transparent pads outside visible source intervals, reusable lane decoded-end watermarks are distinct from terminal sequence EOS, hidden inputs do not gate composition and mandatory visible inputs cannot silently disappear. Those changes are retained, not attributed as new work here. See [prior root-cause audit](STAGE9_TRANSITION_STALL_ROOT_CAUSE.md).

## Evidence and limits

The recording shows loading after transition application (~00:36–00:43), over a join (~00:50) and after layer edits (~02:01–02:12); later export completes. Two-second samples cover the full 252.68 s file in five contact sheets under the delivery evidence directory, alongside the supplied montage. Sampling is not a decoder trace or frame-latency measurement. Black opening/source-watermark material is not automatically a defect. The exact hardware cause of each observed stall remains unproven.

Required owner cases: before/inside/after each join; backward across joins; reversal before a prior seek lands; repeated same boundary; multi-join playback; fractional speeds; trim/split; delete/Undo/Redo; transitions with photo/video PiP; end/replay. Check that the midpoint contains both intended sources, that no pad/terminal texture crosses generations, and that final lift converges to the chosen frame. Compilation does not verify these pixels or phone codec ordering.
