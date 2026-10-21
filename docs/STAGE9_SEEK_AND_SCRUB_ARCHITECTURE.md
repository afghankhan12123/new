# Timeline and preview seeking

21 October 2026. This delivery supersedes the cross-period rebuild limitation in the earlier `STAGE9_TIMELINE_SCROLL_SEEK_REFACTOR.md` handoff.

## Three independent clocks/problems

1. Timeline scrolling: `TimelineView` gestures → `EditorViewModel.scrubBy` → `TimelineViewport` → immediate scroll/playhead state. No decoder waits in coordinate updates. Existing focal zoom, fling ownership, Double intermediates, shared lane navigation, thumbnail and waveform bounds are preserved.
2. Preview: controller retains one latest target, dispatches composition seeks, coordinates source inputs, then requires actual submitted composed output. Its asynchronous result cannot pull an active finger gesture back to a stale clock.
3. Finished MP4: external player, encoded sync samples and container tables. See the separate MP4 audit; no gesture fix claims to repair external seeking.

## Implemented request path

`seekForScrub` immediately publishes project microseconds and replaces the one pending request. Choreographer handles admission. `SeekDispatchGate` gives an unresolved decode up to **80 ms** before another interactive request supersedes it, provided the primary reset has resolved. A delivered frame permits the next request immediately. This is a cancellation work budget, not a sleep, a loading-indicator delay or a frame-success timeout. The ticker revisits a pending request even if pointer events stop. There is no FIFO of stale targets.

`finishScrubSeek` marks settling and bypasses the interactive work budget. It still respects unresolved child/reset ownership. All requests use precise seeks; there is no permanently approximate keyframe image to replace. Public Media3 player positions are milliseconds, so final positioning has that API granularity plus source frame cadence; microsecond model storage is retained. At logical EOS the existing source-tail mapping requests a valid last-frame interval.

The old controller compared source-period windows and rebuilt every multi-input crossing. That branch and its boundary-list bookkeeping are removed. Ordinary forward/backward/cross-join seeks and replay now keep the player, child players and GL context. MediaCodec may flush/reuse/reallocate when source format changes; a persistent player is not a promise of zero codec allocation.

## Reset versus picture completion

On the application thread the wrapper retires submitted-output ownership and publishes the target/generation. The shared playback looper retires downstream/compositor leases before input flushes. Each input is closed to frame submission until its renderer reports a reset whose **resolved source-period interval contains the requested time**, with its actual timestamp within 1 ms of that target. The 1 ms tolerance accounts for the pinned speed mapper’s Float round-trip and public millisecond seek API; the callback is normalized to the requested target only after period identity passes. Old/provisional period callbacks cannot authorize old textures. Both video and image renderers participate. An image submit now retires its pending seek-reset state, matching video.

`endProjectSeek` only ends the queued graph barrier. It no longer prematurely declares that an asynchronous child period has reset. A matching primary renderer acknowledgment, queued behind pending flush callbacks and checked against output generation, admits supersession. Actual output is separately observed by `GraphOutputDeadline`; optimistic player position, READY and first-frame events alone cannot settle it.

## Recovery and diagnostics

The existing four-second output deadline and one-replacement recovery remain. A second failure exposes Retry/error with input queue diagnostics; no endless automatic rebuild loop is added. Startup/surface waits and natural playback output watchdog remain distinct. The last surface picture is continuity, not proof of fresh successful playback.

Existing traces cover requested/accepted/dispatched targets, graph build, player identity, decoder initialization, per-input PTS and submitted output. New release identity/generation and renderer reset acknowledgment traces make resource retention and provisional callbacks reviewable. No device trace was collected here.

Source regressions cover the reset work-budget/lift behavior; production and regression sources compile. Direction reversal, repeated exact boundaries, async source prepare, images/pads, fractional speeds, several joins, background/foreground and Play immediately after lift remain owner-device checks.
