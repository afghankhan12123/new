# Speed and transition compatibility

21 October 2026. The UI and ViewModel's blanket “remove transitions” restriction is removed; validation is replaced rather than bypassed.

`SpeedChangePlanner.plan` creates a complete immutable proposal before history/preview mutation:

1. Validate finite video speed 0.5–2× and retain the original source in/out points.
2. Recalculate selected clip duration and repack the primary track. Unaffected clips keep source trims/identity/content; downstream primary positions ripple by the duration delta.
3. Retain source-attached clip-local events by multiplying their local project times by oldSpeed/newSpeed: effect start/duration/intensity keyframes, transform keyframes and fade durations. Decimal arithmetic with nearest-microsecond rounding avoids cascading Float narrowing. Event values, IDs and interpolation stay intact.
4. Music, extracted/standalone audio, voiceover, text, stickers and PiP keep absolute authored project positions and their own time-domain contracts. They do not silently follow the primary ripple. Rendered track clipping at the new project end remains the existing behavior.
5. Compile the canonical render plan with all original transitions. If valid, preserve every transition unchanged.
6. Otherwise search a common duration fraction for the selected clip's adjacent joins, validating source handles, adjacent durations and overlapping windows together. Unaffected joins never change. Keep a 0.10 s floor (or an already shorter legacy duration); never lengthen or remove a join. If even that is impossible, report the conflict and require another speed/source trim choice.
7. The panel lists each proposed old/new transition duration and requires “Apply speed and shorter transitions together.” Cancel keeps the original project. It does not automatically trim source media.

`SpeedChangePlanner.Apply` checks the timeline still matches the reviewed snapshot, validates the result again and commits one timeline transaction. Undo restores the entire previous timeline; Redo restores the same identities/timing. Existing `TimelineCommands.SetSpeed` delegates to this planner and rejects shortening unless explicitly reviewed, so EditHistory's generic structural-join pruning cannot silently discard speed-conflicted transitions.

`EditHistory.undo/redo` now evaluates the command before moving stack ownership, preserving stacks if validation throws. No persisted project fields or schema were changed. History remains in-session, bounded to 100 edits.

Regression sources cover valid speed/source mapping, free-track anchoring, key/fade retiming, insufficient handles and shortening, a short middle clip with two adjacent windows, exact Undo/Redo, stale proposals and failed-Redo stack ownership. They were compiled, not executed. Runtime pitch, AV alignment, fractional rounding, transition pixels and persisted reopen behavior require owner checks. Stage 8 DSP/AAC code is unchanged.
