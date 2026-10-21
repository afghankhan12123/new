/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

// Pinned Apache-2.0 Media3 1.5.1 source. Local preview variant permits an
// empty configured audio stream to register its graph input before EOS.
// Transformer export continues using the unmodified upstream implementation.

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.exoplayer.DefaultRenderersFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS;
import static androidx.media3.exoplayer.DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.image.ImageRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.VideoSink;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/** A {@link RenderersFactory} for an {@link EditedMediaItemSequence}. */
/* package */ final class PreviewSequenceRenderersFactory implements RenderersFactory {

  private static final int DEFAULT_FRAME_RATE = 30;

  private final Context context;
  private final EditedMediaItemSequence sequence;
  private final PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper;
  @Nullable private final VideoSink videoSink;
  @Nullable private final ImageDecoder.Factory imageDecoderFactory;
  private final int inputIndex;
  private final boolean enableDecoderFallback;

  /** Creates a renderers factory for a player that will play video, image and audio. */
  public static PreviewSequenceRenderersFactory create(
      Context context,
      EditedMediaItemSequence sequence,
      PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      VideoSink videoSink,
      ImageDecoder.Factory imageDecoderFactory,
      int inputIndex,
      boolean enableDecoderFallback) {
    return new PreviewSequenceRenderersFactory(
        context, sequence, playbackAudioGraphWrapper, videoSink, imageDecoderFactory, inputIndex, enableDecoderFallback);
  }

  /** Creates a renderers factory that for a player that will only play audio. */
  public static PreviewSequenceRenderersFactory createForAudio(
      Context context,
      EditedMediaItemSequence sequence,
      PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      int inputIndex) {
    return new PreviewSequenceRenderersFactory(
        context,
        sequence,
        playbackAudioGraphWrapper,
        /* videoSink= */ null,
        /* imageDecoderFactory= */ null,
        inputIndex,
        /* enableDecoderFallback= */ false);
  }

  private PreviewSequenceRenderersFactory(
      Context context,
      EditedMediaItemSequence sequence,
      PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      @Nullable VideoSink videoSink,
      @Nullable ImageDecoder.Factory imageDecoderFactory,
      int inputIndex,
      boolean enableDecoderFallback) {
    this.context = context;
    this.sequence = sequence;
    this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    this.videoSink = videoSink;
    this.imageDecoderFactory = imageDecoderFactory;
    this.inputIndex = inputIndex;
    this.enableDecoderFallback = enableDecoderFallback;
  }

  @Override
  public Renderer[] createRenderers(
      Handler eventHandler,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    List<Renderer> renderers = new ArrayList<>();
    Renderer audioRenderer = new SequenceAudioRenderer(
        context, eventHandler, audioRendererEventListener, sequence,
        /* audioSink= */ playbackAudioGraphWrapper.createInput(inputIndex),
        playbackAudioGraphWrapper, videoSink);

    if (videoSink != null) {
      renderers.add(
          new SequenceVideoRenderer(
              checkStateNotNull(context),
              eventHandler,
              videoRendererEventListener,
              sequence,
              videoSink,
              enableDecoderFallback));
      renderers.add(
          new SequenceImageRenderer(sequence, checkStateNotNull(imageDecoderFactory), videoSink));
    }

    // Let native video decoding and bitmap GL setup begin before synchronous
    // source-clock AAC preparation occupies this shared playback thread. Audio
    // input construction/registration order remains unchanged; audio-only
    // sequences still contain only their audio renderer.
    renderers.add(audioRenderer);
    return renderers.toArray(new Renderer[0]);
  }

  /** One bounded drain on the existing playback looper while a paused frame is pending. */
  public static void renderPendingPausedFrame(Renderer renderer) throws ExoPlaybackException {
    if (renderer.getState() == Renderer.STATE_ENABLED && renderer instanceof SequenceVideoRenderer) {
      SequenceVideoRenderer video = (SequenceVideoRenderer) renderer;
      if (video.lastWorkPositionUs != C.TIME_UNSET) {
        video.render(video.lastWorkPositionUs, android.os.SystemClock.elapsedRealtime() * 1000);
      }
    }
  }

  private static long getOffsetToCompositionTimeUs(
      EditedMediaItemSequence sequence, int mediaItemIndex, long offsetUs) {
    // Reverse engineer how timestamps and offsets are computed with a ConcatenatingMediaSource2
    // to compute an offset converting buffer timestamps to composition timestamps.
    // startPositionUs is not used because it is equal to offsetUs + clipping start time + seek
    // position when seeking from any MediaItem in the playlist to the first MediaItem.
    // The offset to convert the sample timestamps to composition time is negative because we need
    // to remove the large offset added by ExoPlayer to make sure the decoder doesn't received any
    // negative timestamps. We also need to remove the clipping start position.
    long offsetToCompositionTimeUs = -offsetUs;
    if (mediaItemIndex == 0) {
      offsetToCompositionTimeUs -=
          sequence.editedMediaItems.get(0).mediaItem.clippingConfiguration.startPositionUs;
    }
    for (int i = 0; i < mediaItemIndex; i++) {
      offsetToCompositionTimeUs +=
          getRepeatedEditedMediaItem(sequence, i).getPresentationDurationUs();
    }
    return offsetToCompositionTimeUs;
  }

  // Video periods and samples have already passed through SpeedProviderMediaPeriod.
  // The first clipped period origin must be expressed in that same time domain.
  private static long getVisualOffsetToCompositionTimeUs(
      EditedMediaItemSequence sequence, int index, long offsetUs) {
    long adjustment = getOffsetToCompositionTimeUs(sequence, index, offsetUs);
    if (index != 0) return adjustment;
    EditedMediaItem first = sequence.editedMediaItems.get(0);
    long sourceStartUs = first.mediaItem.clippingConfiguration.startPositionUs;
    long adjustedStartUs = sourceStartUs;
    for (Effect effect : first.effects.videoEffects) {
      if (effect instanceof InactiveTimestampAdjustment) {
        adjustedStartUs = com.videoeditor.core.media.preview.PreviewPeriodTime.adjustedTrimStartUs(
            adjustedStartUs, ((InactiveTimestampAdjustment) effect).speedProvider.getSpeed(0));
      }
    }
    return adjustment + sourceStartUs - adjustedStartUs;
  }

  private static void acknowledgeResolvedReset(VideoSink sink, EditedMediaItemSequence sequence,
      int itemIndex, long actualProjectPositionUs) {
    if (!(sink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware)) return;
    androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware lifecycle =
        (androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) sink;
    long startUs = 0;
    for (int i = 0; i < itemIndex; i++) startUs += getRepeatedEditedMediaItem(sequence, i).getPresentationDurationUs();
    long endUs = startUs + getRepeatedEditedMediaItem(sequence, itemIndex).getPresentationDurationUs();
    long requestedUs = lifecycle.getRequestedProjectPositionUs();
    boolean matches = com.videoeditor.core.media.preview.SeekResetPosition.matches(
        requestedUs, actualProjectPositionUs, startUs, endUs);
    com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(sink, "resetResolved", itemIndex,
        actualProjectPositionUs, "requested=" + requestedUs + " periodStart=" + startUs
            + " periodEnd=" + endUs + " accepted=" + matches);
    if (matches) {
      lifecycle.onRendererPositionResetCompleted(requestedUs);
    }
  }

  /**
   * Gets the {@link EditedMediaItem} of a given {@code index}.
   *
   * <p>The index could be greater than {@link EditedMediaItemSequence#editedMediaItems} because the
   * sequence might be {@linkplain EditedMediaItemSequence#isLooping looping}.
   */
  private static EditedMediaItem getRepeatedEditedMediaItem(
      EditedMediaItemSequence sequence, int index) {
    if (sequence.isLooping) {
      index %= sequence.editedMediaItems.size();
    }
    return sequence.editedMediaItems.get(index);
  }

  private static final class SequenceAudioRenderer extends MediaCodecAudioRenderer {
    private final EditedMediaItemSequence sequence;
    private final Context audioContext;
    @Nullable private Codec sourceClockDecoder;
    @Nullable private com.videoeditor.core.media.audio.SourceClockAacDecoder.ReusableCodec reusableSourceCodec;
    private boolean sourceClockActive;
    private boolean sourceClockConfigured;
    private boolean sourceClockHasQueuedPcm;
    private boolean sourceClockEos;
    private long sourceClockRendererStartUs;
    private long itemCompositionStartUs;
    private long sourceClockResetPositionUs;
    private long sourceClockCurrentPositionUs;
    @Nullable private Format sourceClockInputFormat;
    private final androidx.media3.decoder.DecoderInputBuffer sourceClockDiscard =
        new androidx.media3.decoder.DecoderInputBuffer(androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    private final PreviewAudioGraphInputAudioSink audioSink;
    private final PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper;
    @Nullable private final VideoSink startupVideoSink;

    @Nullable private EditedMediaItem pendingEditedMediaItem;
    private long pendingOffsetToCompositionTimeUs;

    // TODO - b/320007703: Revisit the abstractions needed here (editedMediaItemProvider and
    //  Supplier<EditedMediaItem>) once we finish all the wiring to support multiple sequences.
    public SequenceAudioRenderer(
        Context context,
        @Nullable Handler eventHandler,
        @Nullable AudioRendererEventListener eventListener,
        EditedMediaItemSequence sequence,
        PreviewAudioGraphInputAudioSink audioSink,
        PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper,
        @Nullable VideoSink startupVideoSink) {
      super(context, MediaCodecSelector.DEFAULT, eventHandler, eventListener, audioSink);
      this.sequence = sequence;
      this.audioContext = context;
      this.audioSink = audioSink;
      this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
      this.startupVideoSink = startupVideoSink;
    }

    // MediaCodecAudioRenderer methods

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      boolean trace = android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled();
      if (trace) android.os.Trace.beginSection("Preview.audioRenderer");
      try {
        if (!sourceClockActive) super.render(positionUs, elapsedRealtimeUs);
        if (sourceClockActive) {
          if (trace) android.os.Trace.beginSection("Preview.sourceClockRender");
          try { renderSourceClock(); }
          finally { if (trace) android.os.Trace.endSection(); }
        }
        if (trace) android.os.Trace.beginSection("Preview.audioGraphDrain");
        try { while (playbackAudioGraphWrapper.processData()) {} }
        finally { if (trace) android.os.Trace.endSection(); }
      } catch (ExportException
          | AudioSink.WriteException
          | AudioSink.InitializationException
          | AudioSink.ConfigurationException e) {
        throw createRendererException(
            e, /* format= */ null, ExoPlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED);
      } finally {
        if (trace) android.os.Trace.endSection();
      }
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);

      // TODO: b/331392198 - Repeat only looping sequences, after sequences can be of arbitrary
      //  length.
      // The media item might have been repeated in the sequence.
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      // We must first update the pending media item state before calling super.onStreamChanged()
      // because the super method will call onProcessedStreamChange()
      boolean leavingSourceClock = sourceClockActive;
      closeSourceClock();
      pendingEditedMediaItem = getRepeatedEditedMediaItem(sequence, mediaItemIndex);
      sourceClockInputFormat = formats[0];
      sourceClockActive = com.videoeditor.core.media.audio.SourceClockAacDecoder.isEnabled()
          && MimeTypes.AUDIO_AAC.equals(formats[0].sampleMimeType)
          && formats[0].channelCount == 1 && (formats[0].sampleRate == 44100 || formats[0].sampleRate == 48000);
      itemCompositionStartUs = 0;
      for (int i = 0; i < mediaItemIndex; i++)
        itemCompositionStartUs += getRepeatedEditedMediaItem(sequence, i).getPresentationDurationUs();
      sourceClockResetPositionUs = startPositionUs;
      sourceClockCurrentPositionUs = startPositionUs;
      pendingOffsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(sequence, mediaItemIndex, offsetUs);
      // Native EOS belongs to the previous item. The parent renderer may keep
      // its raw-silence bypass configured, so no configure callback is guaranteed
      // when handing the next generated gap back to it.
      if (leavingSourceClock) audioSink.flush();
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
      if (sourceClockActive) { releaseCodec(); audioSink.flush(); onMediaItemChanged(); }
    }

    @Override
    protected void onProcessedStreamChange() {
      super.onProcessedStreamChange();
      onMediaItemChanged();
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      closeSourceClock(true);
      sourceClockResetPositionUs = positionUs;
      sourceClockCurrentPositionUs = positionUs;
      super.onPositionReset(positionUs, joining);
      onMediaItemChanged();
    }

    @Override protected void onDisabled() {
      closeSourceClock();
      super.onDisabled();
    }

    @Override public boolean isReady() {
      if (!sourceClockActive) return super.isReady();
      if (isPausedAudioDeferred()) return true;
      return sourceClockEos && !sourceClockHasQueuedPcm || sourceClockHasQueuedPcm &&
          audioSink.getCurrentPositionUs(sourceClockEos) != AudioSink.CURRENT_POSITION_NOT_SET;
    }

    @Override public boolean isEnded() {
      return sourceClockActive ? sourceClockEos && audioSink.isEnded() : super.isEnded();
    }

    @Override public long getPositionUs() {
      if (!sourceClockActive) return super.getPositionUs();
      // Match MediaCodecAudioRenderer: a paused renderer reports its reset
      // position, not a stale shared AudioTrack position from before the seek.
      if (getState() == STATE_STARTED) {
        long position = audioSink.getCurrentPositionUs(sourceClockEos);
        if (position != AudioSink.CURRENT_POSITION_NOT_SET)
          sourceClockCurrentPositionUs = Math.max(sourceClockCurrentPositionUs, position);
      }
      return sourceClockCurrentPositionUs;
    }

    @Override protected void onStopped() {
      if (sourceClockActive) {
        long position = audioSink.getCurrentPositionUs(sourceClockEos);
        if (position != AudioSink.CURRENT_POSITION_NOT_SET)
          sourceClockCurrentPositionUs = Math.max(sourceClockCurrentPositionUs, position);
      }
      super.onStopped();
    }

    private void closeSourceClock() { closeSourceClock(false); }

    private void closeSourceClock(boolean allowReuse) {
      if (reusableSourceCodec != null) reusableSourceCodec.release();
      reusableSourceCodec = null;
      if (allowReuse && sourceClockDecoder instanceof com.videoeditor.core.media.audio.SourceClockAacDecoder)
        reusableSourceCodec = ((com.videoeditor.core.media.audio.SourceClockAacDecoder) sourceClockDecoder).detachReusableCodec();
      else if (sourceClockDecoder != null) sourceClockDecoder.release();
      sourceClockDecoder = null;
      sourceClockConfigured = false;
      sourceClockHasQueuedPcm = false;
      sourceClockEos = false;
    }

    private void renderSourceClock() throws ExportException, AudioSink.ConfigurationException,
        AudioSink.InitializationException, AudioSink.WriteException {
      if (isPausedAudioDeferred()) return;
      if (sourceClockEos) {
        // Graph input ownership can backpressure the EOS buffer as well as PCM.
        audioSink.playToEndOfStream();
        return;
      }
      // A paused/buffering renderer needs enough PCM to establish its clock,
      // not additional decode-ahead while video is landing. isReady() proves
      // the sink has a position; processors needing more input keep pumping.
      // Graph draining still runs in render(), and starting playback resumes
      // native decoding without discarding or retimestamping buffered output.
      if (getState() != STATE_STARTED && sourceClockHasQueuedPcm && isReady()) return;
      if (sourceClockDecoder == null) {
        EditedMediaItem original = checkNotNull(pendingEditedMediaItem);
        long clipStart = original.mediaItem.clippingConfiguration.startPositionUs;
        long intoItemUs = Math.max(0, sourceClockResetPositionUs + pendingOffsetToCompositionTimeUs - itemCompositionStartUs);
        sourceClockRendererStartUs = itemCompositionStartUs - pendingOffsetToCompositionTimeUs + intoItemUs;
        androidx.media3.common.MediaItem nativeItem = original.mediaItem.buildUpon()
            .setClippingConfiguration(original.mediaItem.clippingConfiguration.buildUpon()
                .setStartPositionUs(clipStart + intoItemUs).build()).build();
        sourceClockDecoder = com.videoeditor.core.media.audio.SourceClockAacDecoder.create(
            audioContext, original.buildUpon().setMediaItem(nativeItem).build(), checkNotNull(sourceClockInputFormat), reusableSourceCodec);
        reusableSourceCodec = null;
        if (sourceClockDecoder == null) {
          sourceClockActive = false;
          return;
        }
      }
      Codec source = checkNotNull(sourceClockDecoder);
      if (!sourceClockConfigured) {
        Format format = source.getOutputFormat();
        if (format == null) return;
        audioSink.configure(format, 0, null);
        sourceClockConfigured = true;
      }
      // All sequence renderers share one playback looper. Yield after one PCM
      // buffer so decoding audio ahead cannot monopolize a turn while video is
      // seeking. The decoder retains unconsumed output; no samples are dropped.
      // Never release a native output while the sink retains a partial view.
      for (int i = 0; i < 1; i++) {
        java.nio.ByteBuffer pcm = source.getOutputBuffer();
        if (pcm == null) break;
        long timeUs = checkNotNull(source.getOutputBufferInfo()).presentationTimeUs + sourceClockRendererStartUs;
        if (!audioSink.handleBuffer(pcm, timeUs, 1)) return;
        sourceClockHasQueuedPcm = true;
        source.releaseOutputBuffer(false);
      }
      if (source.isEnded()) {
        // Consume the original stream only to complete ExoPlayer's period ownership.
        // Its AAC payload and timestamps never enter the corrected audio graph.
        for (int i = 0; i < 32 && !hasReadStreamToEnd(); i++) {
          sourceClockDiscard.clear();
          int read = readSource(getFormatHolder(), sourceClockDiscard, 0);
          if (read == C.RESULT_NOTHING_READ) break;
        }
        if (hasReadStreamToEnd()) {
          audioSink.playToEndOfStream();
          sourceClockEos = true;
        }
      }
    }

    // Other methods

    // A paused visual seek has no audible output to prepare. Explicit transport
    // intent ends this state before the children start, so readiness then again
    // requires real queued PCM and a configured graph clock. Video readiness
    // and submitted-frame verification remain independent.
    private boolean isPausedAudioDeferred() {
      // Period handoffs and configurations rejected by the qualified native
      // decoder retain eager preparation and their existing media-clock path.
      if (!playbackAudioGraphWrapper.isPausedSeekAudioDeferralAllowed()
          || sourceClockDecoder != null || playbackAudioGraphWrapper.isPlaybackRequested()
          || sequence.editedMediaItems.size() != 1 || pendingEditedMediaItem == null
          || pendingEditedMediaItem.flattenForSlowMotion) return false;
      for (androidx.media3.common.audio.AudioProcessor processor : pendingEditedMediaItem.effects.audioProcessors) {
        if (processor instanceof androidx.media3.common.audio.SonicAudioProcessor) return false;
      }
      return startupVideoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware
          && ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) startupVideoSink)
              .canDeferPausedSeekAudio();
    }

    private void onMediaItemChanged() {
      EditedMediaItem currentEditedMediaItem = checkStateNotNull(pendingEditedMediaItem);
      // Use reference equality intentionally.
      int lastEditedMediaItemIndex = getTimeline().getPeriodCount() - 1;
      boolean isLastInSequence =
          currentEditedMediaItem == getRepeatedEditedMediaItem(sequence, lastEditedMediaItemIndex);
      audioSink.onMediaItemChanged(
          currentEditedMediaItem, pendingOffsetToCompositionTimeUs, isLastInSequence);
    }
  }

  private static final class SequenceVideoRenderer extends MediaCodecVideoRenderer {
    private final EditedMediaItemSequence sequence;
    private final VideoSink videoSink;

    @Nullable private ImmutableList<Effect> pendingEffect;
    private long offsetToCompositionTimeUs;
    private final java.util.Map<Long, Integer> outputItemIndices = new java.util.HashMap<>();
    private int diagnosticItemIndex;
    private long lastDiagnosticDecodedPts = C.TIME_UNSET;
    private final java.util.Map<Long, java.util.ArrayDeque<QueuedFrame>> queuedSourceFrames = new java.util.HashMap<>();
    private boolean inputHasClipBoundary;
    @Nullable private androidx.media3.decoder.DecoderInputBuffer lastReadInputBuffer;
    private boolean periodReinitializedStream;
    private long processingFrameAdjustmentUs = C.TIME_UNSET;
    private int lastDecodedItemIndex = -1;
    private long lastWorkPositionUs = C.TIME_UNSET;
    private long projectResetPositionUs;
    private final java.util.Map<Integer, Long> itemStreamOffsets = new java.util.HashMap<>();

    private static final class QueuedFrame {
      final int itemIndex;
      final long adjustmentUs;
      final long sourcePtsUs;
      final boolean preroll;
      final boolean postroll;
      QueuedFrame(int itemIndex, long adjustmentUs, long sourcePtsUs, boolean preroll, boolean postroll) {
        this.itemIndex = itemIndex; this.adjustmentUs = adjustmentUs; this.sourcePtsUs = sourcePtsUs;
        this.preroll = preroll; this.postroll = postroll;
      }
    }

    @Override
    protected void resetCodecStateForFlush() {
      super.resetCodecStateForFlush();
      // The superclass constructor can invoke this before subclass fields exist.
      if (queuedSourceFrames != null) queuedSourceFrames.clear();
      if (lastReadInputBuffer != null) androidx.media3.exoplayer.source.VideoClipPacket.discard(lastReadInputBuffer);
      lastReadInputBuffer = null;
      lastDecodedItemIndex = -1;
    }

    @Override
    protected androidx.media3.exoplayer.DecoderReuseEvaluation onInputFormatChanged(androidx.media3.exoplayer.FormatHolder holder) throws ExoPlaybackException {
      inputHasClipBoundary = androidx.media3.exoplayer.source.VideoClipEndMetadata.from(checkNotNull(holder.format).metadata) != Long.MAX_VALUE;
      return super.onInputFormatChanged(holder);
    }
    private boolean wasEnabled;
    private boolean periodReinitializationPending;

    @Override
    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream) throws ExoPlaybackException {
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "rendererEnabled", -1, C.TIME_UNSET, "previouslyEnabled=" + wasEnabled);
      periodReinitializationPending = wasEnabled ||
          (videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware
              && ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink).hasRegisteredInputStream());
      wasEnabled = true;
      if (videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware)
        ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink).setRendererPeriodReinitialization(periodReinitializationPending);
      super.onEnabled(joining, mayRenderStartOfStream);
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      lastWorkPositionUs = positionUs;
      periodReinitializedStream = periodReinitializationPending;
      androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware lifecycle =
          videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware
              ? (androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink : null;
      // Re-enabling after an asynchronous seek is not a natural period handoff.
      periodReinitializedStream = periodReinitializationPending &&
          (lifecycle == null || !lifecycle.isAwaitingProjectSeekReset());
      int readingItemIndex = outputItemIndices.getOrDefault(getStreamOffsetUs(), diagnosticItemIndex);
      // A placeholder timeline can select the next odd-duration period before
      // its source offset resolves. Use the authoritative composition seek,
      // rather than converting that provisional renderer position.
      projectResetPositionUs = lifecycle == null
          ? positionUs + getVisualOffsetToCompositionTimeUs(sequence, readingItemIndex, getStreamOffsetUs())
          : lifecycle.getRequestedProjectPositionUs();
      if (periodReinitializedStream) {
        projectResetPositionUs = 0;
        for (int i = 0; i < readingItemIndex; i++) projectResetPositionUs += sequence.editedMediaItems.get(i).getPresentationDurationUs();
      }
      if (lifecycle != null) {
        lifecycle.setRendererPeriodReinitialization(periodReinitializedStream);
        lifecycle.setProjectResetPositionUs(projectResetPositionUs);
      }
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "projectReset", readingItemIndex,
          C.TIME_UNSET, "projectPts=" + projectResetPositionUs + " rendererPts=" + positionUs + " offset=" + getStreamOffsetUs());
      try {
        super.onPositionReset(positionUs, joining);
      } finally {
        periodReinitializationPending = false;
        if (lifecycle != null) lifecycle.setRendererPeriodReinitialization(false);
      }
      acknowledgeResolvedReset(videoSink, sequence, readingItemIndex, positionUs
          + getVisualOffsetToCompositionTimeUs(sequence, readingItemIndex, getStreamOffsetUs()));
    }

    public SequenceVideoRenderer(
        Context context,
        Handler eventHandler,
        VideoRendererEventListener videoRendererEventListener,
        EditedMediaItemSequence sequence,
        VideoSink videoSink,
        boolean enableDecoderFallback) {
      super(
          context,
          MediaCodecAdapter.Factory.getDefault(context),
          MediaCodecSelector.DEFAULT,
          DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS,
          enableDecoderFallback,
          eventHandler,
          videoRendererEventListener,
          MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
          /* assumedMinimumCodecOperatingRate= */ DEFAULT_FRAME_RATE,
          videoSink);
      this.sequence = sequence;
      this.videoSink = videoSink;
      experimentalEnableProcessedStreamChangedAtStart();
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);
      // Reading may announce the next period while old decoder buffers still
      // drain. Bind timestamps and clip bounds to the OUTPUT stream offset.
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      outputItemIndices.put(offsetUs, mediaItemIndex);
      itemStreamOffsets.put(mediaItemIndex, offsetUs);
      offsetToCompositionTimeUs = getVisualOffsetToCompositionTimeUs(sequence, mediaItemIndex, offsetUs);
      diagnosticItemIndex = mediaItemIndex;
      EditedMediaItem diagnosticItem = sequence.editedMediaItems.get(mediaItemIndex);
      pendingEffect = diagnosticItem.effects.videoEffects;
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
      if (videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) {
        long requestedUs = ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink).getRequestedProjectPositionUs();
        acknowledgeResolvedReset(videoSink, sequence, mediaItemIndex, requestedUs);
      }
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "rendererStream", mediaItemIndex, C.TIME_UNSET,
          "sink=" + System.identityHashCode(videoSink) + " source=" + diagnosticItem.mediaItem.localConfiguration.uri
          + " clipStart=" + diagnosticItem.mediaItem.clippingConfiguration.startPositionUs
          + " clipEnd=" + diagnosticItem.mediaItem.clippingConfiguration.endPositionUs
          + " adjustment=" + offsetToCompositionTimeUs + " offset=" + offsetUs);
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      lastWorkPositionUs = positionUs;
      super.render(positionUs, elapsedRealtimeUs);
      // A last buffer can still belong to a prior period while the next period
      // has been announced. Only codec/stream EOS proves the entire sequence
      // has drained. Secondary sink readiness does not wait for output release.
      int outputItemIndex = lastDecodedItemIndex;
      // ExoPlayer marks a non-seamless PERIOD final before re-enabling the
      // renderer. That is not end-of-sequence: confirm the actual output item.
      if (isCurrentStreamFinal() && outputItemIndex == sequence.editedMediaItems.size() - 1 && super.isEnded()
          && videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) {
        ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink)
            .signalEndOfFinalSequence();
      }
    }

    @Override
    protected boolean shouldSkipDecoderInputBuffer(androidx.media3.decoder.DecoderInputBuffer buffer) {
      lastReadInputBuffer = buffer;
      boolean skip = super.shouldSkipDecoderInputBuffer(buffer);
      androidx.media3.exoplayer.source.VideoClipPacket packet = androidx.media3.exoplayer.source.VideoClipPacket.peek(buffer);
      if (packet != null && !packet.preroll && !packet.postroll) {
        int itemIndex = outputItemIndices.getOrDefault(getStreamOffsetUs(), diagnosticItemIndex);
        for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
          if (sequence.editedMediaItems.get(i).mediaItem == packet.mediaItem) { itemIndex = i; break; }
        }
        long offsetUs = itemStreamOffsets.getOrDefault(itemIndex, getStreamOffsetUs());
        if (buffer.timeUs + getVisualOffsetToCompositionTimeUs(sequence,itemIndex,offsetUs) >= projectResetPositionUs) skip = false;
      }
      if (skip) androidx.media3.exoplayer.source.VideoClipPacket.discard(buffer);
      return skip;
    }

    @Override
    protected void onQueueInputBuffer(androidx.media3.decoder.DecoderInputBuffer buffer) throws ExoPlaybackException {
      androidx.media3.exoplayer.source.VideoClipPacket packet = androidx.media3.exoplayer.source.VideoClipPacket.take(buffer);
      lastReadInputBuffer = null;
      int itemIndex = outputItemIndices.getOrDefault(getStreamOffsetUs(), diagnosticItemIndex);
      if (packet != null) {
        for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
          if (sequence.editedMediaItems.get(i).mediaItem == packet.mediaItem) { itemIndex = i; break; }
        }
      }
      long packetStreamOffsetUs = packet == null ? getStreamOffsetUs() : itemStreamOffsets.getOrDefault(itemIndex, getStreamOffsetUs());
      long adjustmentUs = getVisualOffsetToCompositionTimeUs(sequence, itemIndex, packetStreamOffsetUs);
      long projectStartUs = 0;
      for (int i = 0; i < itemIndex; i++) projectStartUs += sequence.editedMediaItems.get(i).getPresentationDurationUs();
      EditedMediaItem item = sequence.editedMediaItems.get(itemIndex);
      long sourcePtsUs = buffer.timeUs - getStreamOffsetUs()
          + (itemIndex == 0 ? 0 : item.mediaItem.clippingConfiguration.startPositionUs);
      if (packet != null && !buffer.isEndOfStream()) {
        sourcePtsUs = packet.sourcePtsUs;
        QueuedFrame frame = new QueuedFrame(itemIndex, adjustmentUs, sourcePtsUs, packet.preroll, packet.postroll);
        queuedSourceFrames.computeIfAbsent(buffer.timeUs, ignored -> new java.util.ArrayDeque<>()).add(frame);
      }
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "decoderQueued", itemIndex,
          buffer.timeUs + adjustmentUs, "sourcePts=" + sourcePtsUs + " rawPts=" + buffer.timeUs + " buffer=" + System.identityHashCode(buffer) + " packet=" + (packet == null ? "none" : (packet.preroll + "/" + packet.postroll)) + " eos=" + buffer.isEndOfStream());
      super.onQueueInputBuffer(buffer);
    }

    @Override
    protected boolean processOutputBuffer(long positionUs, long elapsedRealtimeUs,
        @Nullable MediaCodecAdapter codec, @Nullable java.nio.ByteBuffer buffer,
        int bufferIndex, int bufferFlags, int sampleCount, long bufferPresentationTimeUs,
        boolean isDecodeOnlyBuffer, boolean isLastBuffer, Format format) throws ExoPlaybackException {
      java.util.ArrayDeque<QueuedFrame> frames = queuedSourceFrames.get(bufferPresentationTimeUs);
      QueuedFrame queued = frames == null ? null : frames.peek();
      int outputItemIndex = queued == null
          ? outputItemIndices.getOrDefault(getOutputStreamOffsetUs(), diagnosticItemIndex) : queued.itemIndex;
      EditedMediaItem item = sequence.editedMediaItems.get(outputItemIndex);
      processingFrameAdjustmentUs = queued == null ? C.TIME_UNSET : queued.adjustmentUs;
      boolean processed = false;
      try {
        // Decoder dependency PTS may overlap the next period's PTS. Format
        // queues cannot identify those pictures after a period announcement.
        // The immutable input snapshot remains attached until actual release.
        boolean outsideClip = queued != null && (queued.preroll || queued.postroll);
        boolean skipSeekPreroll = !isLastBuffer && (queued == null ? isDecodeOnlyBuffer
            : bufferPresentationTimeUs + queued.adjustmentUs < projectResetPositionUs);
        if (outsideClip || skipSeekPreroll) {
          com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "decodedDiscarded", outputItemIndex,
              bufferPresentationTimeUs + getBufferTimestampAdjustmentUs(), "sourcePts=" + (queued == null ? -1 : queued.sourcePtsUs) + " clipDependency=" + outsideClip + " seekPreroll=" + skipSeekPreroll);
          skipOutputBuffer(checkNotNull(codec), bufferIndex, bufferPresentationTimeUs - getOutputStreamOffsetUs());
          if (queued != null && queued.postroll && isLastBuffer
              && videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) {
            long projectEndUs = 0;
            for (int i = 0; i <= outputItemIndex; i++) projectEndUs += sequence.editedMediaItems.get(i).getPresentationDurationUs();
            ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink).markCurrentStreamDecoded(projectEndUs);
          }
          processed = true;
          return true;
        }
        long sourcePtsUs = queued == null ? bufferPresentationTimeUs - getOutputStreamOffsetUs()
            + (outputItemIndex == 0 ? 0 : item.mediaItem.clippingConfiguration.startPositionUs) : queued.sourcePtsUs;
        if (lastDiagnosticDecodedPts != bufferPresentationTimeUs) {
          lastDiagnosticDecodedPts = bufferPresentationTimeUs;
          com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "decoded", outputItemIndex,
              bufferPresentationTimeUs + getBufferTimestampAdjustmentUs(),
              "sink=" + System.identityHashCode(videoSink) + " source=" + item.mediaItem.localConfiguration.uri
              + " sourcePts=" + sourcePtsUs + " seekPreroll=" + isDecodeOnlyBuffer + " last=" + isLastBuffer);
        }
        processed = super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex,
            bufferFlags, sampleCount, bufferPresentationTimeUs,
            queued == null ? isDecodeOnlyBuffer : bufferPresentationTimeUs + queued.adjustmentUs < projectResetPositionUs,
            isLastBuffer, format);
        if (processed && isLastBuffer
            && videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) {
          long projectEndUs = 0;
          for (int i = 0; i <= outputItemIndex; i++) projectEndUs += sequence.editedMediaItems.get(i).getPresentationDurationUs();
          ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink)
              .markCurrentStreamDecoded(projectEndUs);
        }
        return processed;
      } finally {
        processingFrameAdjustmentUs = C.TIME_UNSET;
        if (processed) {
          lastDecodedItemIndex = outputItemIndex;
          if (frames != null) {
            frames.remove();
            if (frames.isEmpty()) queuedSourceFrames.remove(bufferPresentationTimeUs);
          }
        }
      }
    }

    @Override
    protected void onDisabled() {
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "rendererDisabled", diagnosticItemIndex,
          C.TIME_UNSET, "sink=" + System.identityHashCode(videoSink));
      super.onDisabled();
    }

    @Override
    protected long getBufferTimestampAdjustmentUs() {
      if (processingFrameAdjustmentUs != C.TIME_UNSET) return processingFrameAdjustmentUs;
      Integer outputItemIndex = outputItemIndices.get(getOutputStreamOffsetUs());
      return outputItemIndex == null ? offsetToCompositionTimeUs
          : getVisualOffsetToCompositionTimeUs(sequence, outputItemIndex, getOutputStreamOffsetUs());
    }

    @Override
    protected void onReadyToChangeVideoSinkInputStream() {
      Integer outputItemIndex = outputItemIndices.get(getOutputStreamOffsetUs());
      if (outputItemIndex != null) videoSink.setPendingVideoEffects(sequence.editedMediaItems.get(outputItemIndex).effects.videoEffects);
      this.pendingEffect = null;
    }
  }

  private static final class SequenceImageRenderer extends ImageRenderer {

    private final EditedMediaItemSequence sequence;
    private final VideoSink videoSink;

    private ImmutableList<Effect> videoEffects;
    private @Nullable ConstantRateTimestampIterator timestampIterator;
    private @Nullable EditedMediaItem editedMediaItem;
    private int currentImageItemIndex;
    @Nullable private ExoPlaybackException pendingExoPlaybackException;
    private boolean inputStreamPending;
    private long streamStartPositionUs;
    private boolean mayRenderStartOfStream;
    private long offsetToCompositionTimeUs;

    public SequenceImageRenderer(
        EditedMediaItemSequence sequence,
        ImageDecoder.Factory imageDecoderFactory,
        VideoSink videoSink) {
      super(imageDecoderFactory, ImageOutput.NO_OP);
      this.sequence = sequence;
      this.videoSink = videoSink;
      videoEffects = ImmutableList.of();
      streamStartPositionUs = C.TIME_UNSET;
    }

    // ImageRenderer methods

    @Override
    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
        throws ExoPlaybackException {
      super.onEnabled(joining, mayRenderStartOfStream);
      this.mayRenderStartOfStream = mayRenderStartOfStream;
      videoSink.onRendererEnabled(mayRenderStartOfStream);
      if (!videoSink.isInitialized()) {
        Format format = new Format.Builder().build();
        try {
          videoSink.initialize(format);
        } catch (VideoSink.VideoSinkException e) {
          throw createRendererException(
              e, format, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED);
        }
      }
      // TODO - b/328444280: Do not set a listener on VideoSink, but MediaCodecVideoRenderer must
      //  unregister itself as a listener too.
      videoSink.setListener(VideoSink.Listener.NO_OP, /* executor= */ (runnable) -> {});
    }

    @Override
    protected void onDisabled() {
      super.onDisabled();
      videoSink.onRendererDisabled();
    }

    @Override
    public boolean isEnded() {
      return super.isEnded()
          && videoSink.isEnded()
          && (timestampIterator == null || !timestampIterator.hasNext());
    }

    @Override
    public boolean isReady() {
      if (mayRenderStartOfStream) {
        // The image renderer is not playing after a video. We must wait until the first frame is
        // rendered.
        return videoSink.isReady(/* rendererOtherwiseReady= */ super.isReady());
      } else {
        // The image renderer is playing after a video. We don't need to wait until the first frame
        // is rendered.
        return super.isReady();
      }
    }

    @Override
    protected void onReset() {
      super.onReset();
      pendingExoPlaybackException = null;
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      videoSink.flush(/* resetPosition= */ true);
      super.onPositionReset(positionUs, joining);
      int targetItemIndex = currentImageItemIndex;
      if (videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) {
        long requestedUs = ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink).getRequestedProjectPositionUs();
        long startUs = 0;
        for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
          long dur = getRepeatedEditedMediaItem(sequence, i).getPresentationDurationUs();
          if (requestedUs >= startUs && (i == sequence.editedMediaItems.size() - 1 || requestedUs < startUs + dur)) {
            targetItemIndex = i;
            break;
          }
          startUs += dur;
        }
      }
      currentImageItemIndex = targetItemIndex;
      timestampIterator = createTimestampIterator(positionUs);
      acknowledgeResolvedReset(videoSink, sequence, targetItemIndex, positionUs + offsetToCompositionTimeUs);
    }

    @Override
    protected void onStarted() throws ExoPlaybackException {
      super.onStarted();
      videoSink.onRendererStarted();
    }

    @Override
    protected void onStopped() {
      super.onStopped();
      videoSink.onRendererStopped();
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
      streamStartPositionUs = startPositionUs;
      // The media item might have been repeated in the sequence.
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      editedMediaItem = sequence.editedMediaItems.get(mediaItemIndex);
      currentImageItemIndex = mediaItemIndex;
      offsetToCompositionTimeUs = getVisualOffsetToCompositionTimeUs(sequence, mediaItemIndex, offsetUs);
      timestampIterator = createTimestampIterator(/* positionUs= */ startPositionUs);
      videoEffects = editedMediaItem.effects.videoEffects;
      inputStreamPending = true;
      if (videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) {
        long requestedUs = ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink).getRequestedProjectPositionUs();
        acknowledgeResolvedReset(videoSink, sequence, currentImageItemIndex, requestedUs);
      }
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      if (pendingExoPlaybackException != null) {
        ExoPlaybackException exoPlaybackException = pendingExoPlaybackException;
        pendingExoPlaybackException = null;
        throw exoPlaybackException;
      }
      super.render(positionUs, elapsedRealtimeUs);
      try {
        videoSink.render(positionUs, elapsedRealtimeUs);
      } catch (VideoSink.VideoSinkException e) {
        throw createRendererException(
            e, e.format, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
      }
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs, long elapsedRealtimeUs, Bitmap outputImage, long timeUs) {
      if (inputStreamPending) {
        checkState(streamStartPositionUs != C.TIME_UNSET);
        videoSink.setPendingVideoEffects(videoEffects);
        videoSink.setStreamTimestampInfo(
            streamStartPositionUs,
            getStreamOffsetUs(),
            /* bufferTimestampAdjustmentUs= */ offsetToCompositionTimeUs,
            getLastResetPositionUs());
        if (videoSink instanceof androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) {
          ((androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper.EndOfInputAware) videoSink)
              .setFinalInputStream(editedMediaItem == sequence.editedMediaItems.get(sequence.editedMediaItems.size() - 1));
        }
        videoSink.onInputStreamChanged(
            VideoSink.INPUT_TYPE_BITMAP,
            new Format.Builder()
                .setSampleMimeType(MimeTypes.IMAGE_RAW)
                .setWidth(outputImage.getWidth())
                .setHeight(outputImage.getHeight())
                .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                .setFrameRate(/* frameRate= */ DEFAULT_FRAME_RATE)
                .build());
        inputStreamPending = false;
      }
      return videoSink.handleInputBitmap(outputImage, checkStateNotNull(timestampIterator));
    }

    private ConstantRateTimestampIterator createTimestampIterator(long positionUs) {
      long streamOffsetUs = getStreamOffsetUs();
      long imageBaseTimestampUs = streamOffsetUs + offsetToCompositionTimeUs;
      long positionWithinImage = positionUs - streamOffsetUs;
      // Keep the image cadence anchored to the media item, independent of
      // seek millisecond rounding. Restarting the grid at the seek cursor can
      // leave its final bitmap just before the final decoded video frame,
      // preventing the two-input compositor from releasing that video frame.
      long imageDurationUs = checkNotNull(editedMediaItem).getPresentationDurationUs();
      long lastFrameIndex = ((imageDurationUs - 1) * DEFAULT_FRAME_RATE) / C.MICROS_PER_SECOND;
      long firstFrameIndex = Math.min(lastFrameIndex,
          (positionWithinImage * DEFAULT_FRAME_RATE + C.MICROS_PER_SECOND - 1)
              / C.MICROS_PER_SECOND);
      long firstBitmapTimeUs = imageBaseTimestampUs
          + Math.round(firstFrameIndex * (double) C.MICROS_PER_SECOND / DEFAULT_FRAME_RATE);
      long lastBitmapTimeUs = imageBaseTimestampUs + imageDurationUs;
      return new ConstantRateTimestampIterator(
          /* startPositionUs= */ firstBitmapTimeUs,
          /* endPositionUs= */ lastBitmapTimeUs,
          DEFAULT_FRAME_RATE);
    }
  }
}
