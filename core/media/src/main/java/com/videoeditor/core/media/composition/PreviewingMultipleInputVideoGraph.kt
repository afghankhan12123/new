package com.videoeditor.core.media.composition

import android.content.Context
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Effect
import androidx.media3.common.PreviewingVideoGraph
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CancellablePreviewVideoGraph
import androidx.media3.effect.VideoCompositorSettings
import java.util.concurrent.Executor

/**
 * A preview video graph that composites one primary visual sequence and any
 * number of overlay (picture-in-picture) sequences through a single
 * [androidx.media3.effect.DefaultVideoCompositor], so video and photo overlays
 * render in the editor preview with the same placement the exporter produces.
 *
 * Input identities and visual ordering come from [OverlayCompositorSettings]; everything
 * after the compositor (the composition-level effects list, color handling,
 * frame release control) is pinned [CancellablePreviewVideoGraph] behavior,
 * identical to the single-input preview graph.
 */
@UnstableApi
class PreviewingMultipleInputVideoGraph(
    context: Context,
    videoFrameProcessorFactory: VideoFrameProcessor.Factory,
    outputColorInfo: ColorInfo,
    debugViewProvider: DebugViewProvider,
    listener: VideoGraph.Listener,
    listenerExecutor: Executor,
    videoCompositorSettings: VideoCompositorSettings,
    compositionEffects: List<Effect>,
    initialTimestampOffsetUs: Long,
) : CancellablePreviewVideoGraph(
    context,
    videoFrameProcessorFactory,
    outputColorInfo,
    debugViewProvider,
    listener,
    listenerExecutor,
    videoCompositorSettings,
    compositionEffects,
    initialTimestampOffsetUs,
    /* renderFramesAutomatically= */ false,
),
    PreviewingVideoGraph {

    /**
     * `MultipleInputVideoGraph.initialize()` creates the composition frame
     * processor and the compositor; upstream's `SingleInputVideoGraph.initialize()`
     * is a no-op that defers to `registerInput()`, and its `setOutputSurfaceInfo`
     * tolerates a processor that does not exist yet. `PlaybackVideoGraphWrapper`
     * was written against that contract: it sets the output surface, then
     * registers input 0, and never calls `initialize()`. With a multi-input graph
     * that order trips `checkNotNull(compositionVideoFrameProcessor)`, so
     * initialization is performed here on first use instead.
     */
    enum class State { Created, Initializing, Ready, Stopping, Released, Failed }

    var state: State = State.Created
        private set
    private var pendingOutputSurfaceInfo: androidx.media3.common.SurfaceInfo? = null

    /** Output attachment alone must not allocate an EOS-dependent compositor. */
    override fun initialize() {
        check(state == State.Created || state == State.Ready)
        // The upstream wrapper may initialize explicitly. Native allocation is
        // deferred until registerInput, just as in the single-input graph.
    }

    private fun ensureInitialized() {
        if (state == State.Ready) return
        check(state == State.Created)
        state = State.Initializing
        try {
            super.initialize()
            super.setOutputSurfaceInfo(pendingOutputSurfaceInfo)
            state = State.Ready
        } catch (failure: Exception) {
            state = State.Failed
            throw failure
        }
    }

    override fun setOutputSurfaceInfo(outputSurfaceInfo: androidx.media3.common.SurfaceInfo?) {
        check(state == State.Created || state == State.Ready)
        pendingOutputSurfaceInfo = outputSurfaceInfo
        if (state == State.Ready) super.setOutputSurfaceInfo(outputSurfaceInfo)
    }

    override fun registerInput(inputIndex: Int) {
        ensureInitialized()
        android.util.Log.i(TAG, "registerInput inputIndex=$inputIndex")
        try {
            super.registerInput(inputIndex)
        } catch (failure: Exception) {
            state = State.Failed
            throw failure
        }
    }

    override fun getProcessor(inputIndex: Int): VideoFrameProcessor {
        check(state == State.Ready)
        android.util.Log.i(TAG, "getProcessor inputIndex=$inputIndex")
        return super.getProcessor(inputIndex)
    }

    override fun renderOutputFrame(availableFramePresentationTimeUs: Long) {
        check(state == State.Ready)
        getCompositionVideoFrameProcessor().renderOutputFrame(availableFramePresentationTimeUs)
    }

    override fun release() {
        if (state == State.Released) return
        state = State.Stopping
        try {
            super.release()
            pendingOutputSurfaceInfo = null
            state = State.Released
        } catch (failure: Exception) {
            state = State.Failed
            throw failure
        }
    }

    private companion object {
        const val TAG = "PreviewMultiInputGraph"
    }

    /** Creates [PreviewingMultipleInputVideoGraph] instances for the patched composition player. */
    class Factory(
        private val videoCompositorSettings: VideoCompositorSettings,
    ) : PreviewingVideoGraph.Factory {
        override fun create(
            context: Context,
            outputColorInfo: ColorInfo,
            debugViewProvider: DebugViewProvider,
            listener: VideoGraph.Listener,
            listenerExecutor: Executor,
            compositionEffects: List<Effect>,
            initialTimestampOffsetUs: Long,
        ): PreviewingVideoGraph {
            try {
                return PreviewingMultipleInputVideoGraph(
                    context,
                    androidx.media3.effect.DefaultVideoFrameProcessor.Factory.Builder().build(),
                    outputColorInfo,
                    debugViewProvider,
                    listener,
                    listenerExecutor,
                    videoCompositorSettings,
                    compositionEffects,
                    initialTimestampOffsetUs,
                )
            } catch (e: VideoFrameProcessingException) {
                throw e
            }
        }
    }
}
