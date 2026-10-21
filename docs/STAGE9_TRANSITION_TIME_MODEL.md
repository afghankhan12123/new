# Transition time model

A persisted `Transition` still names adjacent clip IDs, type and duration. Requested duration lives in the tool draft; the applied duration is the value committed to `Transition`. Its derived render plan holds the source requirements, resolved windows, parameters and lane mapping. Removing the object disables the transition; there is no incompatible persisted enable flag.

For applied duration D and ordinary primary cut J:

- pre = floor(D/2), post = D-pre
- window = [J-pre, J+post)
- progress = clamp((projectTime-(J-pre))/D, 0, 1)
- outgoing source = outgoing.sourceEnd + floor((projectTime-J) × outgoing.speed)
- incoming source = incoming.sourceStart + floor((projectTime-J) × incoming.speed)

The existing decimal/integer `ResolvedTransition` evaluator remains shared by validation, plan compilation and adapters. A transition with existing handles does not ripple the timeline or alter audio cuts. Source windows and neighboring transitions are validated before native resources are allocated.

`TransitionHandlePlanner` computes outgoing tail and incoming head requirements from the requested duration and each speed, rounds required source handles up, subtracts existing handles, and checks minimum retained visible duration (200 ms), verified source bounds, adjacency, neighboring windows and the input budget. If adjustment is possible, the UI displays exact proposed source trims and timeline reduction. Apply commits both trims, repacking and the join in one history entry. Undo restores original ranges and joins. Music, captions, stickers, PiP and voiceovers retain their authored project positions. Original media is never rewritten.

All non-overlapping joins reuse one outgoing and one incoming sequence. Their hidden intervals are transparent still-image items. Source footage is decoded only for visible handle windows. Primary visual cadence and original primary audio remain in their own original sequence. The compositor hides primary video during the active transition, then restores it outside the half-open window.

`Stage9CompositorProgram` applies wipes/split/white/black pixel semantics at final project time. `OverlayCompositorSettings` evaluates alpha, placement and zoom at the same time. All thirteen persisted types remain: cross dissolve, black, white, slide left/right, wipe left/right/up/down, push, split, zoom dissolve and smooth zoom. Wipe edges have increasing smoothstep bounds and explicit progress-zero/one endpoints. Outgoing dissolve content stays opaque under the incoming alpha ramp.

Time domains:

| Domain | Conversion |
| --- | --- |
| Project | Canonical integer microseconds on the authored timeline |
| Item | Project timestamp minus sequence item's start |
| Clip local | Project time minus the original clip's start |
| Source | Source start plus local project time × speed |
| Transition local | Project time minus resolved transition start |
| Preview effect PTS | Already retimed by the playback MediaPeriod |
| Export source-effect PTS | `ItemTimeMapping` removes source speed before local evaluation |
| Composition/encoder PTS | Final project time; frame-rate conversion emits a rational output grid |

Splits/head trims rebase clip-local visual keys/intervals. Negative preceding keys are retained internally to preserve the original interpolation curve. Source audio envelopes retain their established source-time domain. Fractional-speed joins and frame rounding require owner playback/export checks; compilation cannot establish frame identity or AV sync.
