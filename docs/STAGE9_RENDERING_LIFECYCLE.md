# Rendering lifecycle and texture ownership

## Preview

`CompositionPreviewController` publishes Uninitialized, Preparing, Ready, Playing, Paused, Seeking, Reconfiguring, RecoverableError, FatalError and Released phases. The current immutable project snapshot and latest requested seek remain authoritative. Listener callbacks check the owning player identity before mutating state.

A source topology change retires the old player before constructing its replacement and restores position/play intent. A visual-only update publishes a new `ProjectRenderPlan` to the active `RenderPlanState`; source decoders remain owned by that session. Paused updates seek the same frame through the changed render passes. Text/sticker add/remove, effects, mask/chroma/blend changes and PiP placement do not require rebuilding source topology. Primary crop/orientation, timing, speed and source changes still rebuild.

All child players use the same playback looper. A project seek queues `beginProjectSeek`, child seek/reset messages, then `endProjectSeek` in order:

1. Retire pending output admission and mark the graph as seeking.
2. Flush the downstream composition processor.
3. Cross the shared GL executor barrier; return compositor-held input leases, free retired output slots/fences and clear old output queues/EOS.
4. Flush/reset each child's processor while late old input output is discarded and its producer is notified.
5. Cross the GL barrier, reopen frame admission and restore the established audio seek contract.

Ordinary concatenated period changes are distinguished from project seeks. Secondary image/video renderer re-enables preserve borrowed input ownership instead of flushing a live compositor. Surface replacement requests a retained-position refresh. Backgrounding releases native preview resources; returning restores the project paused.

Seek input is latest-wins at display cadence, with one in-flight landing and a real frame/reset readiness condition. Startup/landing output has a bounded four-second deadline with one recovery attempt. Active decoder/renderer errors retain the original throwable, perform at most one automatic recovery and then expose Retry Preview. Retry preserves the editable project and its history. Resource-release failures are terminal for that preview owner.

## Shared compositor

`PreviewVideoCompositor` and `DefaultVideoCompositor` retain their execution-specific adapters but share `CompositorFrame`, `Stage9CompositorProgram` and `OverlayCompositorSettings`. The background input supplies timestamps independently of draw order. Invisible optional inputs do not gate output; an empty, ended ordinary overlay cannot hold primary output indefinitely. Visible transition inputs require their real source frames.

Queues are bounded by Media3's preprocessor/output texture pools. Input frames are borrowed, never deleted by the compositor. Owned output textures/fences, blend scratch texture and shader programs are released on their GL executor. Release is idempotent and cancellation does not require every producer to have reached EOS. Callback admission stops before teardown; release does not hold the compositor monitor while waiting on the GL executor.

The ordered effects shader reuses two intermediate textures/FBOs and saves the actual output FBO before allocating scratch resources. Programs and framebuffer objects are session-owned. Creative blend shaders initialize only when a creative blend is used.

## Export

A mutex serializes engine sessions. A fresh output path, plan, Transformer, codec factories and render publication are constructed per session. Progress is read only when Transformer reports it available. Cancellation and every terminal path cancel/retire Transformer on its application looper; incomplete output is removed. Preview objects are never borrowed for export.

AAC finalization uses the retained Stage 8 path. Before success, the engine reads the actual MP4 container to verify non-empty data, expected video/audio tracks, physical dimensions including rotation, readable video samples and duration within a bounded frame tolerance of the render plan. Gallery/Files saving remains the existing post-render path.

`MediaError.ExportFailed` retains category, original throwable and optional session ID. Categories distinguish missing assets, invalid plans, storage, shader/EGL, decoder/encoder, unsupported media and finalization; cancellation propagates through coroutine cancellation. Unknown causes retain their actual chain rather than claiming a specific hardware diagnosis.

All runtime ordering, hardware resource limits and visual/AV behavior remain unverified for this rewrite until owner device testing.
