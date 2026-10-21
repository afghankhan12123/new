# Preview/export stability repair

## Reproduced evidence

An isolated NE2211 fixture containing two primary clips and a short offset PiP reproduced the owner's `ERROR_CODE_FAILED_RUNTIME_CHECK`. The first stack identified `MultiInputPlaybackVideoGraphWrapper.InputVideoSink.flush`: renderer re-enable asserted that all pending processor frames and borrowed compositor textures had drained. The assumption was false after a backward seek. A subsequent run identified `DefaultVideoFrameProcessor.registerInputFrame` after a short overlay had signaled terminal EOS. Simply flushing that processor does not reset its terminal `inputStreamEnded` flag.

The test also reproduced a permanently pending text refresh: an old seek's output could satisfy a new deadline before the asynchronous flush retired it, while `EVENT_RENDERED_FIRST_FRAME` did not necessarily fire again.

## Repairs

- One graph owner returns compositor leases and flushes all inputs under the project-seek barrier. Late child renderer resets update bookkeeping instead of flushing a now-live borrowed texture pool.
- Renderer re-enable distinguishes outstanding explicit seek reset from ordinary period handoff. Shared stream registration can advance without requiring the timing-primary output to drain first.
- Reusable preview EOS is now a compositor interval event after its final queued source timestamp, rather than terminal VFP EOS. Pending EOS work is generation-scoped and cleared by seek.
- A requested seek synchronously retires prior submitted timestamps. Output callbacks and render completion reject stale generations and callbacks crossing the seek barrier.
- Real submitted output establishes readiness even if a first-frame event does not repeat. One bounded recovery remains; successful current-generation output starts a new recovery incident budget. Recovering is explicit in playback state.
- In multi-input edits, a seek crossing primary/overlay/transition source periods retires the bounded graph before constructing the new landing. In-period seeks continue in place. A generation's stale frames cannot replace the latest requested position.

The last valid SurfaceView image remains while seeking; real errors remain visible. No renderer exception is swallowed or replaced by an unlimited retry loop. Resource/session ownership and the saved project survive errors.

## Export

The connected composed-export fixture initially failed with Media3's explicit mixed-audio-track error. `CompositionBuilder` had disabled forced audio whenever any transition existed. Removing that transition exception preserves the established `hasPrimaryAudio` policy, allowing silence generation for primary items without audio before audible items. This changes composition configuration; Stage 8 SourceClock, AAC handling, fades, EQ, ducking and gain processing are unchanged.

A repeated editor/export run then reproduced `SequenceAssetLoader.onOutputFormat` asserting on a not-yet-ready forced audio consumer. The pinned 1.5.1 adapter now caches already-associated consumers and follows the nullable retry contract until both required consumers are ready. The AAR transform removes the original class to avoid duplicate definitions. This prevents the resulting cleanup cascade from releasing decoder surfaces during their initialization; no arbitrary delay or export retry conceals the error.

Exports still use fresh isolated Transformer sessions and shared canonical rendering semantics. Configuration values remain actual resolution/cadence/bitrate inputs. Progress presentation now uses actual fractional exporter progress and a compact linear bar; existing container validation and actual result metadata remain. No fake percentage or success state was added.

## Limits

Targeted checks are fixture evidence, not full physical-device acceptance. Source-period graph retirement can introduce a visible wait during a cross-period scrub. Long projects, sustained thermal load, every transition/blend/mask, HDR/unusual codecs, interrupted exports, external-player interoperability and exhaustive audio sample parity need broader owner testing. An isolated earlier workflow used one bounded watchdog recovery during a paused visual refresh; no zero-recovery or zero-crash guarantee is asserted.
