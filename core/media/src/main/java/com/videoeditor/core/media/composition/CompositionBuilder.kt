package com.videoeditor.core.media.composition

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.videoeditor.core.media.audio.VolumeAudioProcessor
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.ScaleMode
import com.videoeditor.core.model.resolveTransitions
import com.videoeditor.core.model.render.ProjectRenderPlan
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts the authoritative [Project] edit model into a Media3 [Composition].
 *
 * This is the single bridge between the non-destructive edit description and the
 * rendered output. Preview (CompositionPlayer) and export (Transformer) both
 * consume the composition produced here, so there is no second interpretation of
 * the edit.
 *
 * Structure (preview order; export reverses visual input registration for Media3 stacking):
 *  - Sequence 0: the main video track. Each clip becomes an [EditedMediaItem]
 *    with trimming (via [MediaItem.ClippingConfiguration]), speed, volume, and
 *    canvas presentation.
 *  - Two shared silent transition lanes and independently padded PiP sequences, when present.
 *  - Following sequences: each audio clip (music / voiceover) in its own sequence so
 *    the engines mix it with the main track. An audio clip that starts after the
 *    composition start is preceded by a gap item carrying its offset.
 *
 * Constant speed uses a deterministic TimestampAdjustment recognized by both
 * engines and SonicAudioProcessor with pitch 1. Preview retimes MediaPeriods
 * before effects; export retimes frames after effects. Silent/muted clips never
 * depend on an absent audio processor to release video frames.
 *
 * Preview audio uses real silent WAV segments for leading/trailing padding.
 * Export retains synthetic gaps. Both variants keep the original audio window,
 * speed and envelope; no independent audio clock is introduced.
 */
@UnstableApi
@Singleton
class CompositionBuilder @Inject constructor(
    /**
     * Process-local cache of computed ducking curves.
     *
     * A single @Inject constructor parameter: Hilt provides the shared singleton
     * in production. Device tests construct the builder directly with an explicit
     * cache (usually empty), which means no ducking processor is inserted — the
     * documented no-reduction failure policy.
     *
     * Internal rather than private so [com.videoeditor.core.media.export.ExportEngine]
     * can run analysis over the *same* instance when it is constructed manually,
     * which keeps preview and export reading one cache even outside Hilt.
     */
    internal val duckingCache: com.videoeditor.core.media.audio.DuckingAnalysisCache,
) {
    /**
     * Secondary constructor for device tests that construct the builder directly.
     * Each call creates a fresh, isolated cache so tests cannot leak analysis
     * state between runs. Production always uses the Hilt-injected primary
     * constructor above.
     */
    constructor() : this(com.videoeditor.core.media.audio.DuckingAnalysisCache())

    /**
     * A composition tailored for playback, plus the edits that could not be
     * represented in it.
     */
    data class PreviewVariant(
        val composition: Composition,
        /** Ids of audio clips unsupported by preview (offset or shorter sequence). */
        val omittedAudioClipIds: List<String> = emptyList(),
        val audioEnvelopes: List<PreviewEnvelope> = emptyList(),
        /** Compositor layout for overlay layers, or null when the single-input
         * preview graph suffices (no overlays). */
        val overlayCompositorSettings: OverlayCompositorSettings? = null,
        val renderState: RenderPlanState? = null,
    )

    /**
     * A time-based gain processor plus the timeline position its clip starts at,
     * so the preview player can re-base the processor's envelope on every seek.
     *
     * Holds the [com.videoeditor.core.media.audio.SeekableGainAudioProcessor]
     * contract rather than a concrete processor type, which is what lets fades,
     * volume automation and ducking all be repositioned through one call site.
     */
    data class PreviewEnvelope(
        val startMicros: Long,
        val processor: com.videoeditor.core.media.audio.SeekableGainAudioProcessor,
    ) {
        fun seekTo(projectMicros: Long) = processor.setPositionMicros((projectMicros - startMicros).coerceAtLeast(0L))
    }

    /** The full composition used for export, including gap-padded audio. */
    fun build(project: Project): Composition =
        buildExportComposition(project, 0, 0)

    /**
     * The export composition rendered at an explicit output resolution, so the
     * selected resolution is a real encoder input rather than cosmetic UI state.
     * Presentation sizes every clip to the export canvas. When
     * [targetFrameRate] is positive, a composition-level
     * [androidx.media3.effect.FrameRateResampleEffect] re-times the output to
     * that cadence (nearest-source frame dropping/duplication; the source's
     * real motion detail is never invented). Bitrate is applied separately in
     * [com.videoeditor.core.media.export.ExportEngine].
     */
    fun buildForExport(project: Project, width: Int, height: Int, targetFrameRate: Int = 0): Composition =
        buildExportComposition(project, width, height, targetFrameRate)

    private fun buildExportComposition(project: Project, exportWidth: Int, exportHeight: Int, targetFrameRate: Int = 0): Composition {
        // Input IDs determine stacking: input 0 is foreground. The patched
        // compositor uses the trailing background as its timing primary, so
        // photo/PiP cadence cannot replace the main video's decoded timestamps.
        // Every overlay is still padded to the full project duration.
        val renderState = RenderPlanState(ProjectRenderPlan.compile(project))
        val overlays = renderState.plan.layers.reversed()
        val builder = Composition.Builder(
            buildSequences(
                project, forPreview = false, orderedOverlays = overlays,
                exportWidth = exportWidth, exportHeight = exportHeight, renderState = renderState,
            ),
        ).experimentalSetForceAudioTrack(hasPrimaryAudio(project))
        // Media3's exporter selects its multi-input graph from the registered
        // concurrent video tracks, not from the compositor settings, and that
        // graph requires explicit compositor settings. Compose with overlays
        // therefore needs both, consistently.
        val compositionEffects = mutableListOf<androidx.media3.common.Effect>()
        // Resampling is always explicit, including 30 fps sources with a different cadence.
        if (targetFrameRate > 0) compositionEffects += androidx.media3.effect.FrameRateResampleEffect(targetFrameRate)
        val (outWidth, outHeight) = outputSize(project, exportWidth, exportHeight)
        if (overlays.isNotEmpty()) builder.setVideoCompositorSettings(
            OverlayCompositorSettings(overlays + listOf(null), outWidth, outHeight,
                renderState.plan.transitions, renderState),
        )
        compositionEffects += CanvasBackgroundEffect(renderState)
        compositionEffects += ProjectCanvasOverlayEffect(renderState, outWidth, outHeight)
        if (compositionEffects.isNotEmpty()) builder.setEffects(Effects(emptyList(), compositionEffects))
        return builder.build()
    }

    private fun renderLayers(project: Project) = ProjectRenderPlan.compile(project).layers

    private fun outputSize(project: Project, width: Int, height: Int): Pair<Int, Int> {
        val canvas = project.canvas
        if (width <= 0 || height <= 0) return canvas.width to canvas.height
        val short = minOf(canvas.width, canvas.height)
        fun even(value: Double) = ((value / 2).toInt() * 2).coerceAtLeast(2)
        return even(height.toDouble() * canvas.width / short) to even(height.toDouble() * canvas.height / short)
    }

    // A primary sequence beginning with a photo/silent item still needs an
    // audio sample consumer before a later audible item enters that sequence.
    private fun needsStereoMix(project: Project): Boolean {
        val audio = project.timeline.audioClips.filter { !it.muted }
        val overlays = project.timeline.overlayClips.filter { !it.muted && !it.isPhoto }
        val videos = project.timeline.videoClips.filter { !it.muted && !it.isPhoto }
        val hasStereo = audio.any { !it.balance.isIdentity || (project.mediaRef(it.mediaRefId)?.audioChannelCount ?: 1) >= 2 } ||
            (overlays.map { it.mediaRefId } + videos.map { it.mediaRefId }).any { (project.mediaRef(it)?.audioChannelCount ?: 1) >= 2 }
        return hasStereo && audio.size + overlays.size + (if (hasPrimaryAudio(project)) 1 else 0) > 1
    }

    private fun hasPrimaryAudio(project: Project): Boolean = project.timeline.videoClips.any {
        !it.isPhoto && !it.muted && project.mediaRef(it.mediaRefId)?.hasAudio != false
    }

    /**
     * The canonical render plan adapted for CompositionPlayer, including authored audio.
     * Each invocation owns its render publication and all its downstream GL resources.
     */
    fun buildForPreview(project: Project): PreviewVariant {
        val envelopes = mutableListOf<PreviewEnvelope>()
        val renderState = RenderPlanState(ProjectRenderPlan.compile(project))
        val overlays = renderState.plan.layers
        return PreviewVariant(
            composition = Composition.Builder(buildSequences(project, forPreview = true,
                envelopes = envelopes, renderState = renderState))
                .setEffects(Effects(emptyList(), listOf(CanvasBackgroundEffect(renderState), ProjectCanvasOverlayEffect(renderState,
                    project.canvas.width, project.canvas.height))))
                .build(),
            audioEnvelopes = envelopes,
            overlayCompositorSettings = if (overlays.isEmpty()) null else OverlayCompositorSettings(
                overlays.reversed() + listOf(null), project.canvas.width, project.canvas.height,
                renderState.plan.transitions, renderState),
            renderState = renderState,
        )
    }

    /** Real silence assets avoid unsupported synthetic gaps in Media3 1.5.1.
     * Every preview audio sequence spans exactly the visual canvas, preventing looping. */
    private fun silenceItems(durationUs: Long): List<EditedMediaItem> {
        val items = mutableListOf<EditedMediaItem>()
        var remaining = durationUs
        while (remaining > 0L) {
            val chunk = minOf(remaining, 1_000_000L)
            val media = MediaItem.Builder().setUri("asset:///preview-silence.wav")
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionUs(chunk).build()).build()
            items += EditedMediaItem.Builder(media).setDurationUs(1_000_000L).setRemoveVideo(true).build()
            remaining -= chunk
        }
        return items
    }

    private fun buildSequences(
        project: Project,
        forPreview: Boolean,
        orderedOverlays: List<com.videoeditor.core.model.OverlayClip> =
            renderLayers(project),
        exportWidth: Int = 0,
        exportHeight: Int = 0,
        envelopes: MutableList<PreviewEnvelope>? = null,
        renderState: RenderPlanState = RenderPlanState(ProjectRenderPlan.compile(project)),
    ): List<EditedMediaItemSequence> {
        val canvas = project.canvas
        // Export can target a different canvas than the editing canvas; preview
        // always uses the editing canvas.
        val (outWidth, outHeight) = outputSize(project, exportWidth, exportHeight)
        val sequences = mutableListOf<EditedMediaItemSequence>()

        val backgroundSequence = run {
            val videoItems = project.timeline.videoClips.map { clip ->
                val mediaRef = project.mediaRef(clip.mediaRefId)
                    ?: error("Clip references missing media: ${clip.mediaRefId}")
                buildVideoItem(
                    clip,
                    Uri.parse(mediaRef.uri),
                    mediaRef.mimeType,
                    mediaRef.photoOrientation,
                    canvas.scaleMode,
                    outWidth,
                    outHeight,
                    emptyList(),
                    emptyList(),
                    mediaRef.hasAudio != false,
                    forPreview,
                    envelopes,
                    forceTranscode = !forPreview && orderedOverlays.isEmpty(),
                    renderState = renderState,
                    forceStereoMix = needsStereoMix(project),
                )
            }
            if (videoItems.isEmpty()) null else EditedMediaItemSequence(videoItems)
        }

        // Preview keeps the background at sequences[0]: the patched composition
        // player pins that index as its timing primary, and its video input ids
        // follow stream-registration order rather than sequence order.
        if (forPreview) {
            backgroundSequence?.let { sequences += it }
        }

        // Overlay layers, each in its own sequence, in the caller's stacking
        // order. For export that order is descending z-index, because Media3
        // 1.5.1 draws compositor input 0 LAST (on top) and input 0 also fixes
        // the output duration; both were verified on device with solid-color
        // probes. Every overlay sequence therefore spans the full project so the
        // topmost one cannot truncate the export.
        val totalDuration = project.timeline.renderedDurationMicros
        orderedOverlays.forEach { overlay ->
            if (ProjectRenderPlan.isTransitionLane(overlay.id)) {
                val incoming = overlay.id == ProjectRenderPlan.INCOMING_LANE
                val items = mutableListOf<EditedMediaItem>()
                var cursor = 0L
                renderState.plan.transitions.forEach { join ->
                    items += transparentPadItems(join.startMicros - cursor, forPreview)
                    val layer = renderState.plan.transitionLayer(join, incoming)
                    val source = if (incoming) join.incoming else join.outgoing
                    val ref = requireNotNull(project.mediaRef(source.mediaRefId))
                    items += buildVideoItem(source.copy(
                        sourceStartMicros = layer.sourceStartMicros, sourceEndMicros = layer.sourceEndMicros,
                        timelineStartMicros = join.startMicros, muted = true, fadeInMicros = 0L, fadeOutMicros = 0L),
                        Uri.parse(ref.uri), ref.mimeType, null, canvas.scaleMode,
                        outWidth, outHeight, emptyList(), emptyList(), false, forPreview, null,
                        renderState = renderState, effectOriginMicros = source.timelineStartMicros)
                    cursor = join.endMicros
                }
                items += transparentPadItems(totalDuration - cursor, forPreview)
                sequences += EditedMediaItemSequence(items)
                return@forEach
            }
            val mediaRef = project.mediaRef(overlay.mediaRefId)
                ?: error("Overlay references missing media: ${overlay.mediaRefId}")
            if (totalDuration <= 0L || overlay.timelineEndMicros <= 0L ||
                overlay.timelineStartMicros >= totalDuration
            ) {
                return@forEach
            }
            val items = mutableListOf<EditedMediaItem>()
            // Every compositor input must span project time, including hidden
            // windows. Use real decoded pads; compositor alpha hides them.
            items += overlayPadItems(
                overlay, mediaRef, durationUs = overlay.timelineStartMicros,
                canvasWidth = outWidth, canvasHeight = outHeight, forPreview = forPreview,
            )
            items += buildOverlayItem(
                overlay, Uri.parse(mediaRef.uri), mediaRef, forPreview = forPreview,
                canvasWidth = outWidth, canvasHeight = outHeight, forceStereoMix = needsStereoMix(project),
                renderState = renderState,
            )
            items += overlayPadItems(
                overlay, mediaRef, durationUs = totalDuration - overlay.timelineEndMicros,
                canvasWidth = outWidth, canvasHeight = outHeight, forPreview = forPreview,
            )
            if (forPreview && overlay.isPhoto) {
                for (index in items.indices) {
                    val item = items[index]
                    items[index] = EditedMediaItem.Builder(item.mediaItem.buildUpon()
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE).build())
                        .setDurationUs(item.durationUs).setFrameRate(item.frameRate)
                        .setRemoveAudio(item.removeAudio).setEffects(item.effects).build()
                }
            }
            if (items.isNotEmpty()) sequences += EditedMediaItemSequence(items)
        }

        // Export registers the background after the overlays so it lands at the
        // highest video input id and is drawn first, i.e. underneath every layer.
        if (!forPreview) {
            backgroundSequence?.let { sequences += it }
        }

        project.timeline.audioClips.forEach { originalAudio ->
            val audio = project.timeline.renderedAudioClip(originalAudio) ?: return@forEach
            val mediaRef = project.mediaRef(audio.mediaRefId) ?: return@forEach
            val needsOffset = audio.timelineStartMicros > 0L
            val items = mutableListOf<EditedMediaItem>()
            if (needsOffset) {
                if (forPreview) items += silenceItems(audio.timelineStartMicros)
                else items += gapItem(audio.timelineStartMicros)
            }
            items += buildAudioItem(project, audio, Uri.parse(mediaRef.uri), envelopes, forPreview)
            val trailingSilence = project.timeline.renderedDurationMicros -
                audio.timelineStartMicros - audio.timelineDurationMicros
            if (trailingSilence > 0L) {
                if (forPreview) items += silenceItems(trailingSilence)
                else items += gapItem(trailingSilence)
            }
            sequences += EditedMediaItemSequence(items)
        }

        return sequences
    }

    /** Transparent still intervals preserve sequence offsets without allocating hidden video decoders. */
    private fun overlayPadItems(
        overlay: com.videoeditor.core.model.OverlayClip,
        mediaRef: com.videoeditor.core.model.MediaRef,
        durationUs: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        forPreview: Boolean,
    ): List<EditedMediaItem> {
        return transparentPadItems(durationUs, forPreview)
    }

    /** Invisible intervals are an image, never repeated source video/decoder allocations. */
    private fun transparentPadItems(durationUs: Long, forPreview: Boolean): List<EditedMediaItem> {
        if (durationUs <= 0L) return emptyList()
        val media = MediaItem.Builder().setUri("asset:///render-transparent.png")
            .setMimeType(if (forPreview) androidx.media3.common.MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE else "image/png")
            .setImageDurationMs((durationUs + 999L) / 1000L).build()
        return listOf(EditedMediaItem.Builder(media).setDurationUs(durationUs)
            .setRemoveAudio(true).setFrameRate(30).build())
    }

    private fun buildVideoItem(
        clip: Clip,
        uri: Uri,
        mimeType: String?,
        photoOrientation: Int?,
        scaleMode: ScaleMode,
        canvasWidth: Int,
        canvasHeight: Int,
        textOverlays: List<com.videoeditor.core.model.TextOverlay>,
        stickers: List<com.videoeditor.core.model.StickerClip> = emptyList(),
        hasSourceAudio: Boolean,
        forPreview: Boolean,
        envelopes: MutableList<PreviewEnvelope>?,
        forceTranscode: Boolean = false,
        forceStereoMix: Boolean = false,
        renderState: RenderPlanState? = null,
        effectOriginMicros: Long = clip.timelineStartMicros,
    ): EditedMediaItem {
        // Picker/provider URIs often have numeric paths, so extension inference
        // cannot distinguish still images from progressive video.
        val mediaItemBuilder = MediaItem.Builder().setUri(uri).setMimeType(mimeType)
        if (clip.isPhoto) {
            // Preview loads provider photos directly through Media3's bitmap
            // resolver. Progressive extraction can stall on picker PNG URIs.
            if (forPreview) mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE)
            // Still images have no intrinsic playback duration; size them
            // explicitly rather than via clipping (which does not apply).
            mediaItemBuilder.setImageDurationMs(
                androidx.media3.common.util.Util.usToMs(clip.photoDurationMicros ?: 0L),
            )
        } else {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.sourceStartMicros)
                    .setEndPositionUs(clip.sourceEndMicros)
                    .build(),
            )
        }

        val audioProcessors = mutableListOf<AudioProcessor>()
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        // Media3 decodes EXIF rotation but omits reflection. Restore reflection
        // in rotated coordinates before applying the user's crop and transform.
        if (clip.isPhoto && photoOrientation in listOf(2, 4, 5, 7)) {
            val horizontal = photoOrientation == 2 || photoOrientation == 4
            videoEffects += androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f).build()
        }
        var timestampAdjustment: androidx.media3.common.Effect? = null

        if (clip.playbackSpeed != 1f) {
            // Constant project speed gives both paths an exact timestamp map.
            // Sonic changes tempo while retaining natural pitch; it never drives
            // video callbacks, so silent/muted sources cannot stall the graph.
            timestampAdjustment = androidx.media3.effect.TimestampAdjustment(
                { inputTimeUs, consumer -> consumer.onTimestamp(clip.timelineStartMicros +
                    ((inputTimeUs - clip.timelineStartMicros) / clip.playbackSpeed).toLong()) },
                ConstantSpeedProvider(clip.playbackSpeed),
            )
            if (!clip.muted && !clip.isPhoto && hasSourceAudio) audioProcessors +=
                androidx.media3.common.audio.SonicAudioProcessor().apply { setSpeed(clip.playbackSpeed); setPitch(1f) }
        }
        if (!clip.muted && clip.volume != 1f) {
            audioProcessors += VolumeAudioProcessor(clip.volume)
        }
        // Source crop and orientation precede canvas presentation; placement follows it.
        val transform = clip.transform
        transform.crop?.takeIf { it.isValid() }?.let { crop ->
            videoEffects += androidx.media3.effect.Crop(
                crop.left * 2f - 1f, crop.right * 2f - 1f,
                1f - crop.bottom * 2f, 1f - crop.top * 2f,
            )
        }
        if (transform.rotationDegrees % 360 != 0 || transform.flipHorizontal || transform.flipVertical) {
            videoEffects += androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setScale(if (transform.flipHorizontal) -1f else 1f, if (transform.flipVertical) -1f else 1f)
                .setRotationDegrees(-transform.rotationDegrees.toFloat())
                .build()
        }
        videoEffects += CanvasPresentationEffect(canvasWidth, canvasHeight) { renderState?.plan?.project?.canvas?.scaleMode ?: scaleMode }
        videoEffects += CanvasPlacementEffect(transform.scale, transform.offsetX, transform.offsetY,
            com.videoeditor.core.model.render.ItemTimeMapping(clip.timelineStartMicros,
                if (forPreview || clip.isPhoto) 1f else clip.playbackSpeed), effectOriginMicros,
            renderState?.let { state -> { state.plan.project.timeline.videoClips.firstOrNull { it.id == clip.id } ?: clip } })
        videoEffects += VisualEffectsGlEffect(clip.filter, clip.colorAdjustments, clip.effects,
            timelineStartMicros = clip.timelineStartMicros,
            effectOriginMicros = effectOriginMicros,
            sourceSpeed = if (forPreview || clip.isPhoto) 1f else clip.playbackSpeed,
            liveClip = renderState?.let { state -> { state.plan.project.timeline.videoClips.firstOrNull { it.id == clip.id } ?: clip } })
        if (stickers.isNotEmpty()) {
            // Stickers composite per clip exactly like captions: project-time
            // gated, sequence-offset aware, drawn beneath text from one code
            // path shared by preview and export.
            videoEffects += StickerOverlayEffect(
                stickers, clip.timelineStartMicros,
                if (forPreview || clip.isPhoto) 1f else clip.playbackSpeed,
                minOf(canvasWidth, canvasHeight),
            )
        }
        if (textOverlays.isNotEmpty()) {
            // Text is composited per clip so it appears in both preview and
            // export from one code path. The effect gates each overlay by
            // project time with sequence offset and playback speed, so an overlay only
            // draws during its own window regardless of which clip hosts it.
            videoEffects += TextOverlayEffect(
                textOverlays, clip.timelineStartMicros,
                // CompositionPlayer retimes its MediaPeriod before effects;
                // Transformer retimes video after captions. Adapt timestamps,
                // while retaining one authoritative project-time window.
                if (forPreview || clip.isPhoto) 1f else clip.playbackSpeed,
                minOf(canvasWidth, canvasHeight) / 1080f,
            )
        }

        if (clip.fadeInMicros > 0L || clip.fadeOutMicros > 0L) {
            videoEffects += VideoFadeEffect(clip, if (forPreview || clip.isPhoto) 1f else clip.playbackSpeed)
            if (!clip.muted && !clip.isPhoto) audioProcessors += com.videoeditor.core.media.audio.FadeAudioProcessor(
                clip.timelineDurationMicros, clip.fadeInMicros, clip.fadeOutMicros,
                clip.fadeInCurve, clip.fadeOutCurve).also {
                    envelopes?.add(PreviewEnvelope(clip.timelineStartMicros, it))
                }
        }

        if (forceStereoMix && !clip.muted && !clip.isPhoto && hasSourceAudio) {
            audioProcessors += com.videoeditor.core.media.audio.BalanceAudioProcessor(com.videoeditor.core.model.AudioBalance(), forceStereo = true)
        }
        // Gate captions before timestamp retiming: graph input timestamps carry
        // the sequence offset, while local source progress still needs speed scaling.
        timestampAdjustment?.let { videoEffects += it }
        if (!forPreview && !clip.muted && !clip.isPhoto) {
            audioProcessors += com.videoeditor.core.media.audio.DurationAudioProcessor(clip.timelineDurationMicros)
        }

        if (forceTranscode && videoEffects.none {
            it is TextOverlayEffect || it is StickerOverlayEffect || it is ColorGradeEffect ||
                it is VisualEffectsGlEffect || it is LayerMaskGlEffect ||
                it is ChromaKeyGlEffect || it is CanvasPlacementEffect || it is VideoFadeEffect ||
                it is androidx.media3.effect.TimestampAdjustment
        }) {
            // An all-no-op chain lets Media3 transmux the bitstream, silently
            // discarding the requested bitrate. Force a real encode.
            videoEffects += ForceTranscodeEffect()
        }

        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(Effects(audioProcessors, videoEffects))
            .setRemoveAudio(clip.muted || clip.isPhoto || !hasSourceAudio)
        // ImageAssetLoader requires an explicit frame rate for still-image frames.
        if (clip.isPhoto) builder.setFrameRate(30)
        // CompositionPlayer requires an explicit durationUs per primary-sequence
        // item (it builds a silence timing bed of this length, clipped to the
        // trim window); Transformer derives it from the media instead. Sizing it
        // to the source end satisfies both: it is >= the clip's end position, and
        // the item's presentation duration is still taken from the clipping
        // configuration. Photos carry no clipping, so their duration is the image
        // display time.
        builder.setDurationUs(
            if (clip.isPhoto) clip.photoDurationMicros ?: 0L else clip.sourceEndMicros,
        )
        return builder.build()
    }

    /**
     * Builds one overlay layer's media item. Placement, opacity and stacking are
     * compositor-level ([OverlayCompositorSettings]); the item itself only trims,
     * retimes and crops its source and fits the canvas so the layer's scale of 1
     * means "fit the canvas".
     */
    private fun buildOverlayItem(
        overlay: com.videoeditor.core.model.OverlayClip,
        uri: Uri,
        mediaRef: com.videoeditor.core.model.MediaRef,
        forPreview: Boolean,
        canvasWidth: Int,
        canvasHeight: Int,
        forceStereoMix: Boolean = false,
        renderState: RenderPlanState? = null,
    ): EditedMediaItem {
        val mediaItemBuilder = MediaItem.Builder().setUri(uri).setMimeType(mediaRef.mimeType)
        if (overlay.isPhoto) {
            if (forPreview) {
                mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE)
            }
            mediaItemBuilder.setImageDurationMs(
                androidx.media3.common.util.Util.usToMs(overlay.timelineDurationMicros),
            )
        } else {
            // This item carries exactly the overlay's own trimmed source window.
            // The surrounding project time is covered by separate pad items, so
            // the clipping range never reaches past what the file contains.
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(overlay.sourceStartMicros)
                    .setEndPositionUs(overlay.sourceEndMicros)
                    .build(),
            )
        }

        val audioProcessors = mutableListOf<AudioProcessor>()
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        var timestampAdjustment: androidx.media3.common.Effect? = null
        if (overlay.playbackSpeed != 1f && !overlay.isPhoto) {
            timestampAdjustment = androidx.media3.effect.TimestampAdjustment(
                { inputTimeUs, consumer -> consumer.onTimestamp(overlay.timelineStartMicros +
                    ((inputTimeUs - overlay.timelineStartMicros) / overlay.playbackSpeed).toLong()) },
                ConstantSpeedProvider(overlay.playbackSpeed),
            )
            if (!overlay.muted) audioProcessors +=
                androidx.media3.common.audio.SonicAudioProcessor().apply {
                    setSpeed(overlay.playbackSpeed); setPitch(1f)
                }
        }
        val volume = if (overlay.muted) 0f else overlay.volume
        if (volume != 1f) audioProcessors += VolumeAudioProcessor(volume)

        val transform = overlay.transform
        transform.crop?.takeIf { it.isValid() }?.let { crop ->
            videoEffects += androidx.media3.effect.Crop(
                crop.left * 2f - 1f, crop.right * 2f - 1f,
                1f - crop.bottom * 2f, 1f - crop.top * 2f,
            )
        }
        // Overlay rotation is applied once by the compositor after canvas fit.
        // Source flips remain preprocessing effects.
        if (transform.flipHorizontal || transform.flipVertical) {
            videoEffects += androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setScale(if (transform.flipHorizontal) -1f else 1f, if (transform.flipVertical) -1f else 1f)
                .build()
        }
        timestampAdjustment?.let { videoEffects += it }
        // Fit the overlay source into the canvas so the compositor's scale of 1
        // means "fit the canvas" (matching the primary track's presentation).
        videoEffects += CanvasPresentationEffect(canvasWidth, canvasHeight) {
            renderState?.plan?.layers?.firstOrNull { it.id == overlay.id }?.scaleMode ?: overlay.scaleMode
        }

        val liveOverlay = renderState?.let { state -> { state.plan.project.timeline.overlayClips.firstOrNull { it.id == overlay.id } ?: overlay } }
        videoEffects += VisualEffectsGlEffect(effects = overlay.effects,
            timelineStartMicros = overlay.timelineStartMicros, liveOverlay = liveOverlay)
        videoEffects += ChromaKeyGlEffect(overlay.chromaKey, liveOverlay?.let { provider -> { provider().chromaKey } })
        videoEffects += LayerMaskGlEffect(overlay.mask, overlay.timelineStartMicros,
            liveOverlay?.let { provider -> { provider().mask } })

        if (forceStereoMix && !overlay.muted && !overlay.isPhoto && mediaRef.hasAudio != false) {
            audioProcessors += com.videoeditor.core.media.audio.BalanceAudioProcessor(com.videoeditor.core.model.AudioBalance(), forceStereo = true)
        }
        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(Effects(audioProcessors, videoEffects))
            .setRemoveAudio(overlay.muted || overlay.isPhoto || mediaRef.hasAudio == false)
        if (overlay.isPhoto) builder.setFrameRate(30)
        // Same convention as the primary video items: the presentation duration
        // comes from the clipping configuration, while durationUs must be at
        // least the clip's end position so the preview player's timing bed and
        // the exporter both derive the trimmed window rather than TIME_UNSET.
        builder.setDurationUs(
            if (overlay.isPhoto) overlay.timelineDurationMicros else overlay.sourceEndMicros,
        )
        return builder.build()
    }

    private fun buildAudioItem(project: Project, audio: AudioClip, uri: Uri, envelopes: MutableList<PreviewEnvelope>?, forPreview: Boolean): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(audio.sourceStartMicros)
            .setEndPositionUs(audio.sourceEndMicros)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()

        val audioProcessors = mutableListOf<AudioProcessor>()
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        if (audio.playbackSpeed != 1f) {
            audioProcessors += androidx.media3.common.audio.SonicAudioProcessor().apply {
                setSpeed(audio.playbackSpeed); setPitch(1f)
            }
            // Media3 derives item duration and preview MediaPeriod speed from
            // TimestampAdjustment, including items with video removed.
            videoEffects += androidx.media3.effect.TimestampAdjustment(
                { timeUs, consumer -> consumer.onTimestamp(audio.timelineStartMicros +
                    ((timeUs - audio.timelineStartMicros) / audio.playbackSpeed).toLong()) },
                ConstantSpeedProvider(audio.playbackSpeed),
            )
        }
        val volume = if (audio.muted) 0f else audio.volume
        if (volume != 1f) {
            audioProcessors += VolumeAudioProcessor(volume)
        }

        // Frozen Stage 8 DSP order: base volume, then automation, then fades,
        // then EQ, then balance, then ducking, then the shared duration trim.
        // Each stage multiplies once, so no envelope is applied twice, and the
        // same order runs in preview and export because both come from here.
        if (!audio.muted && audio.volumeEnvelope.points.isNotEmpty()) {
            audioProcessors += com.videoeditor.core.media.audio.VolumeEnvelopeAudioProcessor(
                audio.volumeEnvelope.normalized(), audio.sourceStartMicros, audio.playbackSpeed,
            ).also { envelopes?.add(PreviewEnvelope(audio.timelineStartMicros, it)) }
        }

        if (audio.fadeInMicros > 0L || audio.fadeOutMicros > 0L) {
            audioProcessors += com.videoeditor.core.media.audio.FadeAudioProcessor(
                audio.timelineDurationMicros, audio.fadeInMicros, audio.fadeOutMicros,
                audio.fadeInCurve, audio.fadeOutCurve).also {
                    envelopes?.add(PreviewEnvelope(audio.timelineStartMicros, it))
                }
        }
        if (audio.equalizer.isActive) {
            audioProcessors += com.videoeditor.core.media.audio.EqualizerAudioProcessor(audio.equalizer.coerced())
        }
        // A mono first input must not choose a mono mix for later stereo tracks.
        // Promote centered mono to dual-mono only in a multi-sequence mix; an
        // ordinary single centered mono clip retains its neutral Stage7 format.
        val mixedAudio = needsStereoMix(project)
        if (!audio.balance.isIdentity || mixedAudio) {
            audioProcessors += com.videoeditor.core.media.audio.BalanceAudioProcessor(audio.balance.coerced(), forceStereo = mixedAudio)
        }
        // Ducking needs every trigger's measured curve, which is computed off the
        // playback thread and cached. A missing or failed analysis yields no
        // processor at all, which is the documented policy: never an arbitrary
        // reduction.
        duckingCache.curveFor(project, audio)?.let { curve ->
            audioProcessors += com.videoeditor.core.media.audio.DuckingGainAudioProcessor(
                curve, audio.timelineStartMicros,
            ).also { envelopes?.add(PreviewEnvelope(audio.timelineStartMicros, it)) }
        }
        // Both paths must bound Sonic/decoder padding to the authored window.
        // Preview seeks rebase this bound just like the gain envelope; otherwise
        // a seek would incorrectly grant the clip a new full-duration tail.
        audioProcessors += com.videoeditor.core.media.audio.DurationAudioProcessor(audio.timelineDurationMicros).also {
            envelopes?.add(PreviewEnvelope(audio.timelineStartMicros, it))
        }
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            // durationUs is mandatory, not optional: with clipping set but
            // durationUs left unset, getPresentationDurationUs() returns
            // TIME_UNSET (a large negative value). CompositionPlayer's video
            // path asserts against this, but its audio path does not — it feeds
            // the negative duration straight to ConcatenatingMediaSource2 and
            // exhausts the heap. Sizing to the source end yields the trimmed
            // duration via endPositionUs - startPositionUs.
            .setDurationUs(audio.sourceEndMicros)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }

    /**
     * A silent filler occupying [durationMicros] of a sequence so the following
     * item starts at the right composition time. Legal for export only —
     * CompositionPlayer rejects gap-containing compositions.
     */
    private fun gapItem(durationMicros: Long): EditedMediaItem {
        val gapMediaItem = MediaItem.Builder()
            .setMediaId(GAP_MEDIA_ID)
            .build()
        return EditedMediaItem.Builder(gapMediaItem)
            .setDurationUs(durationMicros)
            .setRemoveVideo(true)
            .build()
    }

    private companion object {
        /**
         * Media3's internal sentinel media id marking a gap item
         * (EditedMediaItem.GAP_MEDIA_ID, package-private — mirrored here; the
         * instrumented tests assert Transformer accepts compositions built with
         * it).
         */
        const val GAP_MEDIA_ID = "androidx-media3-GapMediaItem"
    }
}
