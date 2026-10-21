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
package androidx.media3.exoplayer.video;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.exoplayer.video.VideoSink.INPUT_TYPE_SURFACE;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.FrameInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PreviewingVideoGraph;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlaybackException;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;



/**
 * Processes input from {@link VideoSink} instances, plumbing the data through a {@link VideoGraph}
 * and rendering the output.
 */
@UnstableApi
@RestrictTo({Scope.LIBRARY_GROUP})
public final class MultiInputPlaybackVideoGraphWrapper implements VideoSinkProvider, VideoGraph.Listener {

  /** Listener for {@link MultiInputPlaybackVideoGraphWrapper} events. */
  public interface Listener {
    /**
     * Called when the video frame processor renders the first frame.
     *
     * @param playbackVideoGraphWrapper The {@link MultiInputPlaybackVideoGraphWrapper} which triggered this
     *     event.
     */
    void onFirstFrameRendered(MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper);

    /**
     * Called when the video frame processor dropped a frame.
     *
     * @param playbackVideoGraphWrapper The {@link MultiInputPlaybackVideoGraphWrapper} which triggered this
     *     event.
     */
    void onFrameDropped(MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper);

    /**
     * Called before a frame is rendered for the first time since setting the surface, and each time
     * there's a change in the size, rotation or pixel aspect ratio of the video being rendered.
     *
     * @param playbackVideoGraphWrapper The {@link MultiInputPlaybackVideoGraphWrapper} which triggered this
     *     event.
     * @param videoSize The video size.
     */
    void onVideoSizeChanged(
        MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper, VideoSize videoSize);

    /**
     * Called when the video frame processor encountered an error.
     *
     * @param playbackVideoGraphWrapper The {@link MultiInputPlaybackVideoGraphWrapper} which triggered this
     *     event.
     * @param videoFrameProcessingException The error.
     */
    void onError(
        MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper,
        VideoFrameProcessingException videoFrameProcessingException);
  }

  /** A builder for {@link MultiInputPlaybackVideoGraphWrapper} instances. */
  public static final class Builder {
    private boolean transitionClippingEnabled;
    public Builder setTransitionClippingEnabled(boolean enabled) {
      transitionClippingEnabled = enabled;
      return this;
    }
    private final Context context;
    private final VideoFrameReleaseControl videoFrameReleaseControl;

    private @Nullable VideoFrameProcessor.Factory videoFrameProcessorFactory;
    private @Nullable PreviewingVideoGraph.Factory previewingVideoGraphFactory;
    private List<Effect> compositionEffects;
    private Clock clock;
    private boolean built;
    private int[] inputIndices = {0};

    /** Sequence identity to compositor identity; sequence zero owns the output clock. */
    public Builder setInputIndices(int[] inputIndices) {
      checkState(inputIndices.length > 0 && inputIndices.length <= 8);
      java.util.HashSet<Integer> distinct = new java.util.HashSet<>();
      for (int index : inputIndices) checkState(index >= 0 && distinct.add(index));
      this.inputIndices = inputIndices.clone();
      return this;
    }

    /** Creates a builder. */
    public Builder(Context context, VideoFrameReleaseControl videoFrameReleaseControl) {
      this.context = context.getApplicationContext();
      this.videoFrameReleaseControl = videoFrameReleaseControl;
      compositionEffects = ImmutableList.of();
      clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link VideoFrameProcessor.Factory} that will be used for creating {@link
     * VideoFrameProcessor} instances.
     *
     * <p>By default, the {@code DefaultVideoFrameProcessor.Factory} with its default values will be
     * used.
     *
     * @param videoFrameProcessorFactory The {@link VideoFrameProcessor.Factory}.
     * @return This builder, for convenience.
     */
    
    public Builder setVideoFrameProcessorFactory(
        VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
      return this;
    }

    /**
     * Sets the {@link PreviewingVideoGraph.Factory} that will be used for creating {@link
     * PreviewingVideoGraph} instances.
     *
     * <p>By default, the {@code PreviewingSingleInputVideoGraph.Factory} will be used.
     *
     * @param previewingVideoGraphFactory The {@link PreviewingVideoGraph.Factory}.
     * @return This builder, for convenience.
     */
    
    public Builder setPreviewingVideoGraphFactory(
        PreviewingVideoGraph.Factory previewingVideoGraphFactory) {
      this.previewingVideoGraphFactory = previewingVideoGraphFactory;
      return this;
    }

    /**
     * Sets the {@linkplain Effect effects} to apply after compositing the sinks' data.
     *
     * @param compositionEffects The composition {@linkplain Effect effects}.
     * @return This builder, for convenience.
     */
    
    public Builder setCompositionEffects(List<Effect> compositionEffects) {
      this.compositionEffects = compositionEffects;
      return this;
    }

    /**
     * Sets the {@link Clock} that will be used.
     *
     * <p>By default, {@link Clock#DEFAULT} will be used.
     *
     * @param clock The {@link Clock}.
     * @return This builder, for convenience.
     */
    
    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Builds the {@link MultiInputPlaybackVideoGraphWrapper}.
     *
     * <p>This method must be called at most once and will throw an {@link IllegalStateException} if
     * it has already been called.
     */
    public MultiInputPlaybackVideoGraphWrapper build() {
      checkState(!built);

      if (previewingVideoGraphFactory == null) {
        if (videoFrameProcessorFactory == null) {
          videoFrameProcessorFactory = new ReflectiveDefaultVideoFrameProcessorFactory();
        }
        previewingVideoGraphFactory =
            new ReflectivePreviewingSingleInputVideoGraphFactory(videoFrameProcessorFactory);
      }
      MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper = new MultiInputPlaybackVideoGraphWrapper(this);
      built = true;
      return playbackVideoGraphWrapper;
    }
  }

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({STATE_CREATED, STATE_INITIALIZED, STATE_RELEASED, STATE_STOPPING, STATE_FAILED})
  private @interface State {}

  private static final int STATE_CREATED = 0;
  private static final int STATE_INITIALIZED = 1;
  private static final int STATE_RELEASED = 2;
  private static final int STATE_STOPPING = 3;
  private static final int STATE_FAILED = 4;

  private static final Executor NO_OP_EXECUTOR = runnable -> {};

  private final Context context;
  private final InputVideoSink inputVideoSink;
  private final List<InputVideoSink> inputVideoSinks;
  private final int[] inputIndices;
  private final boolean transitionClippingEnabled;
  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final VideoFrameRenderControl videoFrameRenderControl;
  private final PreviewingVideoGraph.Factory previewingVideoGraphFactory;
  private final List<Effect> compositionEffects;
  private final VideoSink defaultVideoSink;
  private final Clock clock;
  private final CopyOnWriteArraySet<MultiInputPlaybackVideoGraphWrapper.Listener> listeners;

  private @Nullable Format outputFormat;
  private @Nullable VideoFrameMetadataListener videoFrameMetadataListener;
  private @Nullable HandlerWrapper handler;
  private @Nullable PreviewingVideoGraph videoGraph;
  @Nullable private Pair<Surface, Size> currentSurfaceAndSize;
  private int pendingFlushCount;
  private final java.util.ArrayDeque<Long> pendingProjectOutputTimesUs = new java.util.ArrayDeque<>();
  private boolean admittedFirstOutputFrame;
  private volatile int pendingOutputFrameCount;
  public int getPendingOutputFrameCount() { return pendingOutputFrameCount; }
  public String getFrameDiagnostics() {
    return videoGraph instanceof androidx.media3.effect.CancellablePreviewVideoGraph
        ? ((androidx.media3.effect.CancellablePreviewVideoGraph) videoGraph).frameDiagnostics()
        : "single input; outputQueue=" + pendingOutputFrameCount;
  }
  private long lastDiagnosticMs;
  private long currentProjectPositionUs;
  private long lastRendererPositionUs = C.TIME_UNSET;
  private long acknowledgedPrimaryGeneration = -1;
  private volatile long requestedProjectPositionUs;
  private volatile long outputGeneration;
  private volatile boolean awaitingSeekBarrier;
  private volatile long completedPrimaryResetPositionUs = C.TIME_UNSET;
  public void setRequestedProjectPositionUs(long positionUs) {
    outputGeneration++;
    awaitingSeekBarrier = true;
    synchronized (scheduledOutputFrames) { scheduledOutputFrames.clear(); lastRenderedProjectTimeUs = C.TIME_UNSET; }
    completedPrimaryResetPositionUs = C.TIME_UNSET;
    requestedProjectPositionUs = positionUs;
    com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "seekRequested", -1,
        positionUs, "requestId=" + outputGeneration);

  }
  /** A reset/flush barrier, not evidence of decoded output or frame identity. */
  public boolean hasCompletedPrimaryVideoReset() {
    long completed = completedPrimaryResetPositionUs;
    return completed != C.TIME_UNSET && completed == requestedProjectPositionUs;
  }
  private @State int state;

  /**
   * Converts the buffer timestamp (the player position, with renderer offset) to the composition
   * timestamp, in microseconds. The composition time starts from zero, add this adjustment to
   * buffer timestamp to get the composition time.
   */
  private long bufferTimestampAdjustmentUs;
  private long lastRenderedProjectTimeUs = C.TIME_UNSET;
  private volatile boolean primaryFinalOutputRendered;
  public boolean hasRenderedPrimaryFinalOutput() { return primaryFinalOutputRendered; }
  private final java.util.ArrayDeque<long[]> scheduledOutputFrames = new java.util.ArrayDeque<>();

  /** Latest decoded output whose presentation deadline has arrived on the surface clock. */
  public long getLastRenderedProjectTimeUs() {
    synchronized (scheduledOutputFrames) {
      long nowNs = clock.nanoTime();
      while (!scheduledOutputFrames.isEmpty() && scheduledOutputFrames.peek()[0] <= nowNs) {
        lastRenderedProjectTimeUs = scheduledOutputFrames.remove()[1];
      }
      return lastRenderedProjectTimeUs;
    }
  }

  private MultiInputPlaybackVideoGraphWrapper(Builder builder) {
    context = builder.context;
    transitionClippingEnabled = builder.transitionClippingEnabled;
    inputIndices = builder.inputIndices.clone();
    inputVideoSinks = new ArrayList<>();
    for (int i = 0; i < inputIndices.length; i++) {
      inputVideoSinks.add(new InputVideoSink(context, i, inputIndices[i]));
    }
    inputVideoSink = inputVideoSinks.get(0);
    clock = builder.clock;
    videoFrameReleaseControl = builder.videoFrameReleaseControl;
    videoFrameReleaseControl.setClock(clock);
    videoFrameRenderControl =
        new VideoFrameRenderControl(new FrameRendererImpl(), videoFrameReleaseControl);
    previewingVideoGraphFactory = checkStateNotNull(builder.previewingVideoGraphFactory);
    compositionEffects = builder.compositionEffects;
    defaultVideoSink = new DefaultVideoSink(videoFrameReleaseControl, videoFrameRenderControl);
    listeners = new CopyOnWriteArraySet<>();
    state = STATE_CREATED;
    for (InputVideoSink sink : inputVideoSinks) addListener(sink);
  }

  /**
   * Adds a {@link MultiInputPlaybackVideoGraphWrapper.Listener}.
   *
   * @param listener The listener to be added.
   */
  public void addListener(MultiInputPlaybackVideoGraphWrapper.Listener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a {@link MultiInputPlaybackVideoGraphWrapper.Listener}.
   *
   * @param listener The listener to be removed.
   */
  public void removeListener(MultiInputPlaybackVideoGraphWrapper.Listener listener) {
    listeners.remove(listener);
  }

  // VideoSinkProvider methods

  @Override
  public VideoSink getSink() {
    return inputVideoSink;
  }

  public VideoSink getSink(int visualSequenceIndex) {
    return inputVideoSinks.get(visualSequenceIndex);
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    if (currentSurfaceAndSize != null
        && currentSurfaceAndSize.first.equals(outputSurface)
        && currentSurfaceAndSize.second.equals(outputResolution)) {
      return;
    }
    currentSurfaceAndSize = Pair.create(outputSurface, outputResolution);
    maybeSetOutputSurfaceInfo(
        outputSurface, outputResolution.getWidth(), outputResolution.getHeight());
  }

  @Override
  public void clearOutputSurfaceInfo() {
    maybeSetOutputSurfaceInfo(
        /* surface= */ null,
        /* width= */ Size.UNKNOWN.getWidth(),
        /* height= */ Size.UNKNOWN.getHeight());
    currentSurfaceAndSize = null;
  }

  @Override
  public void release() {
    if (state == STATE_RELEASED) {
      return;
    }

    state = STATE_STOPPING;
    if (handler != null) {
      handler.removeCallbacksAndMessages(/* token= */ null);
    }

    try {
      if (videoGraph != null) videoGraph.release();
      currentSurfaceAndSize = null;
      state = STATE_RELEASED;
    } catch (RuntimeException failure) {
      state = STATE_FAILED;
      throw failure;
    }
  }

  // VideoGraph.Listener

  @Override
  public void onOutputSizeChanged(int width, int height) {
    if (state == STATE_RELEASED || state == STATE_STOPPING || state == STATE_FAILED) return;
    // We forward output size changes to the sink even if we are still flushing.
    Format format = new Format.Builder().setWidth(width).setHeight(height).build();
    defaultVideoSink.onInputStreamChanged(INPUT_TYPE_SURFACE, format);
  }

  @Override
  public void onOutputFrameAvailableForRendering(long framePresentationTimeUs) {
    if (state == STATE_RELEASED || state == STATE_STOPPING || state == STATE_FAILED) return;
    if (pendingFlushCount > 0) {
      // Ignore available frames while flushing
      return;
    }
    com.videoeditor.core.media.preview.PreviewPhaseTrace.graph(this, "outputReadyUs", framePresentationTimeUs);
    // The frame presentation time is relative to the start of the Composition and without the
    // renderer offset
    if (inputIndices.length > 1) {
      // The native output pool bounds this queue. Do not submit a future
      // composited frame just because a renderer has decoded ahead of the clock.
      pendingProjectOutputTimesUs.add(framePresentationTimeUs);
      pendingOutputFrameCount = pendingProjectOutputTimesUs.size();
      if (Util.SDK_INT >= 29) android.os.Trace.setCounter("Preview.outputQueueDepth", pendingProjectOutputTimesUs.size());
    } else {
      videoFrameRenderControl.onOutputFrameAvailableForRendering(
          framePresentationTimeUs - bufferTimestampAdjustmentUs);
    }
    // Output arrival is work, including while ExoPlayer is paused and polls at
    // one-second intervals. Drain on this playback-looper callback immediately.
    if (!projectSeekInProgress && lastRendererPositionUs != C.TIME_UNSET) {
      try { render(lastRendererPositionUs, clock.elapsedRealtime() * 1000); }
      catch (ExoPlaybackException error) {
        onError(VideoFrameProcessingException.from(error));
      }
    }
  }

  @Override
  public void onEnded(long finalFramePresentationTimeUs) {
    if (state == STATE_RELEASED || state == STATE_STOPPING || state == STATE_FAILED) return;
    // Output EOS is expected after every visual sequence drains. Output may
    // still be queued for presentation; each sink's isEnded waits for release.
    if (Util.SDK_INT >= 29) android.os.Trace.setCounter("Preview.compositorFinalUs", finalFramePresentationTimeUs);
  }

  @Override
  public void onError(VideoFrameProcessingException exception) {
    if (state == STATE_RELEASED || state == STATE_STOPPING || state == STATE_FAILED) return;
    for (MultiInputPlaybackVideoGraphWrapper.Listener listener : listeners) {
      listener.onError(/* playbackVideoGraphWrapper= */ this, exception);
    }
  }

  // Internal methods

  private VideoFrameProcessor initialize(Format sourceFormat, int compositorInputIndex) throws VideoSink.VideoSinkException {
    if (state == STATE_INITIALIZED) return checkStateNotNull(videoGraph).getProcessor(compositorInputIndex);
    checkState(state == STATE_CREATED);

    ColorInfo inputColorInfo = getAdjustedInputColorInfo(sourceFormat.colorInfo);
    ColorInfo outputColorInfo = inputColorInfo;
    if (inputColorInfo.colorTransfer == C.COLOR_TRANSFER_HLG && Util.SDK_INT < 34) {
      // PQ SurfaceView output is supported from API 33, but HLG output is supported from API 34.
      // Therefore, convert HLG to PQ below API 34, so that HLG input can be displayed properly on
      // API 33.
      outputColorInfo =
          inputColorInfo.buildUpon().setColorTransfer(C.COLOR_TRANSFER_ST2084).build();
    }
    handler = clock.createHandler(checkStateNotNull(Looper.myLooper()), /* callback= */ null);
    try {
      videoGraph =
          previewingVideoGraphFactory.create(
              context,
              outputColorInfo,
              DebugViewProvider.NONE,
              /* listener= */ this,
              /* listenerExecutor= */ handler::post,
              /* compositionEffects= */ inputIndices.length > 1 ? compositionEffects : ImmutableList.of(),
              /* initialTimestampOffsetUs= */ 0);
      if (currentSurfaceAndSize != null) {
        Surface surface = currentSurfaceAndSize.first;
        Size size = currentSurfaceAndSize.second;
        maybeSetOutputSurfaceInfo(surface, size.getWidth(), size.getHeight());
      }
      // Register every bounded input before any decoder is given its surface.
      for (int index : inputIndices) videoGraph.registerInput(index);
    } catch (VideoFrameProcessingException e) {
      throw new VideoSink.VideoSinkException(e, sourceFormat);
    }
    defaultVideoSink.initialize(sourceFormat);
    state = STATE_INITIALIZED;
    return videoGraph.getProcessor(compositorInputIndex);
  }

  private boolean isInitialized() {
    return state == STATE_INITIALIZED;
  }

  private void maybeSetOutputSurfaceInfo(@Nullable Surface surface, int width, int height) {
    if (videoGraph == null) {
      return;
    }
    // Update the surface on the video graph and the default video sink together.
    if (surface != null) {
      videoGraph.setOutputSurfaceInfo(new SurfaceInfo(surface, width, height));
      defaultVideoSink.setOutputSurfaceInfo(surface, new Size(width, height));
    } else {
      videoGraph.setOutputSurfaceInfo(/* outputSurfaceInfo= */ null);
      defaultVideoSink.clearOutputSurfaceInfo();
    }
  }

  private boolean isReady(boolean rendererOtherwiseReady) {
    return defaultVideoSink.isReady(
        /* rendererOtherwiseReady= */ rendererOtherwiseReady && pendingFlushCount == 0);
  }

  private boolean hasReleasedFrame(long presentationTimeUs) {
    return pendingFlushCount == 0 && videoFrameRenderControl.hasReleasedFrame(presentationTimeUs);
  }

  /**
   * Incrementally renders available video frames.
   *
   * @param positionUs The current playback position, in microseconds.
   * @param elapsedRealtimeUs {@link android.os.SystemClock#elapsedRealtime()} in microseconds,
   *     taken approximately at the time the playback position was {@code positionUs}.
   */
  private void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    lastRendererPositionUs = positionUs;
    currentProjectPositionUs = positionUs + bufferTimestampAdjustmentUs;
    while (!pendingProjectOutputTimesUs.isEmpty()) {
      long bufferTimeUs = pendingProjectOutputTimesUs.peek() - bufferTimestampAdjustmentUs;
      // First-frame rendering remains possible while paused at a non-frame-
      // aligned seek. Subsequent frames are admitted by sequence zero only.
      if (admittedFirstOutputFrame && bufferTimeUs > positionUs) break;
      pendingProjectOutputTimesUs.remove();
      pendingOutputFrameCount = pendingProjectOutputTimesUs.size();
      if (Util.SDK_INT >= 29) android.os.Trace.setCounter("Preview.outputQueueDepth", pendingProjectOutputTimesUs.size());
      videoFrameRenderControl.onOutputFrameAvailableForRendering(bufferTimeUs);
      admittedFirstOutputFrame = true;
    }
    videoFrameRenderControl.render(positionUs, elapsedRealtimeUs);
    long diagnosticNowMs = clock.elapsedRealtime();
    if (inputIndices.length > 1 && (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        && diagnosticNowMs - lastDiagnosticMs >= 200) {
      lastDiagnosticMs = diagnosticNowMs;
      StringBuilder diagnostic = new StringBuilder("clock=").append(positionUs + bufferTimestampAdjustmentUs)
          .append(" output=").append(getLastRenderedProjectTimeUs()).append(" queued=").append(pendingProjectOutputTimesUs);
      for (InputVideoSink sink : inputVideoSinks) diagnostic.append(" input=").append(sink.compositorInputIndex)
          .append(" last=").append(sink.lastBufferPresentationTimeUs + sink.inputBufferTimestampAdjustmentUs)
          .append(" pending=").append(sink.videoFrameProcessor == null ? -1 : sink.videoFrameProcessor.getPendingInputFrameCount())
          .append(" change=").append(sink.isInputStreamChangePending);
      android.util.Log.i("PreviewInputRouting", diagnostic.toString());
    }

  }

  /** One owner retires downstream output and compositor leases before renderer flushes. */
  private boolean projectSeekInProgress;

  public void beginProjectSeek() {
    com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "seekBegin", -1,
        requestedProjectPositionUs, "requestId=" + outputGeneration);
    projectSeekInProgress = true;
    if (videoGraph instanceof androidx.media3.effect.CancellablePreviewVideoGraph) {
      ((androidx.media3.effect.CancellablePreviewVideoGraph) videoGraph).beginSeek();
    }
    flush(true);
    // Own the complete reset here. Renderer re-enables can arrive asynchronously
    // after endProjectSeek; they must never flush a processor with new borrowed textures.
    for (InputVideoSink sink : inputVideoSinks) sink.flushForProjectSeek();
  }

  public void endProjectSeek() {
    if (videoGraph instanceof androidx.media3.effect.CancellablePreviewVideoGraph) {
      ((androidx.media3.effect.CancellablePreviewVideoGraph) videoGraph).endSeek();
    }
    projectSeekInProgress = false;
    awaitingSeekBarrier = false;
    com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "seekBarrierEnd", -1,
        requestedProjectPositionUs, "requestId=" + outputGeneration);
    completePrimaryResetIfReady();
    // Child period preparation can finish AFTER this message. Only the primary
    // renderer's resolved project reset acknowledges admission of another seek.
  }

  private void completePrimaryResetIfReady() {
    if (state == STATE_INITIALIZED && pendingFlushCount == 0 && !awaitingSeekBarrier
        && acknowledgedPrimaryGeneration == outputGeneration) {
      completedPrimaryResetPositionUs = requestedProjectPositionUs;
    }
  }

  private void flush(boolean resetPosition) {
    if (!isInitialized()) {
      return;
    }
    pendingFlushCount++;
    pendingProjectOutputTimesUs.clear();
    pendingOutputFrameCount = 0;
    admittedFirstOutputFrame = false;
    primaryFinalOutputRendered = false;
    synchronized (scheduledOutputFrames) { scheduledOutputFrames.clear(); lastRenderedProjectTimeUs = C.TIME_UNSET; }
    defaultVideoSink.flush(resetPosition);
    // Handle pending video graph callbacks to ensure video size changes reach the video render
    // control.
    checkStateNotNull(handler).post(() -> { pendingFlushCount--; completePrimaryResetIfReady(); });
  }

  private void setVideoFrameMetadataListener(
      VideoFrameMetadataListener videoFrameMetadataListener) {
    this.videoFrameMetadataListener = videoFrameMetadataListener;
  }

  private void setPlaybackSpeed(float speed) {
    defaultVideoSink.setPlaybackSpeed(speed);
  }

  private void onStreamOffsetChange(
      long bufferTimestampAdjustmentUs, long bufferPresentationTimeUs, long streamOffsetUs) {
    this.bufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
    videoFrameRenderControl.onStreamOffsetChange(bufferPresentationTimeUs, streamOffsetUs);
  }

  private static ColorInfo getAdjustedInputColorInfo(@Nullable ColorInfo inputColorInfo) {
    if (inputColorInfo == null || !inputColorInfo.isDataSpaceValid()) {
      return ColorInfo.SDR_BT709_LIMITED;
    }

    return inputColorInfo;
  }

  /** The sequence renderer identifies its final media item after prior buffers drain. */
  public interface EndOfInputAware {
    void setFinalInputStream(boolean isFinal);
    void markCurrentStreamDecoded(long projectEndUs);
    void setProjectResetPositionUs(long positionUs);
    void onRendererPositionResetCompleted(long positionUs);
    boolean canDeferPausedSeekAudio();
    long getRequestedProjectPositionUs();
    void signalEndOfFinalSequence();
    void setRendererPeriodReinitialization(boolean reinitializing);
    boolean isAwaitingProjectSeekReset();
    boolean hasRegisteredInputStream();
  }

  /** Receives input from an ExoPlayer renderer and forwards it to the video graph. */
  private final class InputVideoSink implements VideoSink, EndOfInputAware, MultiInputPlaybackVideoGraphWrapper.Listener {

    private long submittedFrames;
    private long lastDiagnosticReceivedPts = C.TIME_UNSET;
    private boolean finalInputStream;
    private boolean inputEndSignalled;
    private boolean rendererPeriodReinitialization;
    private boolean awaitingProjectSeekReset;
    private boolean awaitingRendererReset;
    @Override public boolean isAwaitingProjectSeekReset() { return awaitingProjectSeekReset || projectSeekInProgress; }
    @Override public boolean hasRegisteredInputStream() { return hasRegisteredFirstInputStream; }

    private void resetInputBookkeeping() {
      hasRegisteredFirstInputStream = false;
      finalBufferPresentationTimeUs = C.TIME_UNSET;
      lastBufferPresentationTimeUs = C.TIME_UNSET;
      inputEndSignalled = false;
      pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
      isInputStreamChangePending = false;
      periodReinitializedStream = false;
      currentStreamDecoded = false;
      decodedStreamEndProjectUs = C.TIME_UNSET;
    }

    private void flushForProjectSeek() {
      awaitingProjectSeekReset = true;
      awaitingRendererReset = true;
      if (isInitialized()) videoFrameProcessor.flush();
      resetInputBookkeeping();
    }
    private boolean periodReinitializedStream;
    private long projectResetPositionUs = C.TIME_UNSET;
    private boolean currentStreamDecoded;
    private long decodedStreamEndProjectUs = C.TIME_UNSET;

    @Override
    public void setRendererPeriodReinitialization(boolean reinitializing) {
      rendererPeriodReinitialization = reinitializing;
    }

    @Override
    public void setProjectResetPositionUs(long positionUs) { projectResetPositionUs = positionUs; }
    @Override
    public boolean canDeferPausedSeekAudio() {
      // Transition handles retain the established eager audio readiness path.
      return !transitionClippingEnabled && inputIndices.length > 1 && requestedProjectPositionUs > 0;
    }
    @Override
    public void onRendererPositionResetCompleted(long positionUs) {
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(MultiInputPlaybackVideoGraphWrapper.this,
          "rendererResetAck", compositorInputIndex, positionUs,
          "requested=" + requestedProjectPositionUs + " generation=" + outputGeneration);
      if (positionUs != requestedProjectPositionUs) return;
      awaitingRendererReset = false;
      if (sequenceIndex != 0 || !isInitialized()) return;
      acknowledgedPrimaryGeneration = outputGeneration;
      completePrimaryResetIfReady();
    }

    @Override public long getRequestedProjectPositionUs() { return requestedProjectPositionUs; }

    @Override
    public void markCurrentStreamDecoded(long projectEndUs) {
      finalBufferPresentationTimeUs = lastBufferPresentationTimeUs;
      currentStreamDecoded = true;
      decodedStreamEndProjectUs = projectEndUs;
      if (sequenceIndex != 0 && lastBufferPresentationTimeUs != C.TIME_UNSET
          && videoGraph instanceof androidx.media3.effect.CancellablePreviewVideoGraph) {
        ((androidx.media3.effect.CancellablePreviewVideoGraph) videoGraph).signalEndOfPreviewInterval(
            compositorInputIndex, projectEndUs, lastBufferPresentationTimeUs + inputBufferTimestampAdjustmentUs);
      }
    }


    @Override
    public void setFinalInputStream(boolean isFinal) {
      finalInputStream = isFinal;
    }

    @Override
    public void signalEndOfFinalSequence() {
      finalInputStream = true;
      if (sequenceIndex == 0) primaryFinalOutputRendered = true;
      signalFinalInputEnd();
    }

    private void signalFinalInputEnd() {
      // Primary/single-input replay retains its existing flush lifecycle. Only
      // secondary multi-input streams need EOS to release nearest-frame holds.
      if (sequenceIndex != 0 && inputIndices.length > 1 && finalInputStream && !inputEndSignalled) {
        // EOS drains registered frames and retained textures; it never flushes
        // a processor whose textures are still owned by the compositor.
        if (videoGraph instanceof androidx.media3.effect.CancellablePreviewVideoGraph) {
          ((androidx.media3.effect.CancellablePreviewVideoGraph) videoGraph).signalEndOfPreviewInput(
              compositorInputIndex, lastBufferPresentationTimeUs == C.TIME_UNSET ? C.TIME_UNSET
                  : lastBufferPresentationTimeUs + inputBufferTimestampAdjustmentUs);
        } else {
          checkStateNotNull(videoFrameProcessor).signalEndOfInput();
        }
        inputEndSignalled = true;
        com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(MultiInputPlaybackVideoGraphWrapper.this,
            "sinkEOS", compositorInputIndex, C.TIME_UNSET, "sink=" + System.identityHashCode(this));
      }
    }
    private final int sequenceIndex;
    private final int compositorInputIndex;
    private final int videoFrameProcessorMaxPendingFrameCount;
    private final ArrayList<Effect> videoEffects;
    private final VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo;

    private @Nullable VideoFrameProcessor videoFrameProcessor;
    @Nullable private Format inputFormat;
    private @InputType int inputType;
    private long inputStreamStartPositionUs;
    private long inputStreamOffsetUs;
    private long inputBufferTimestampAdjustmentUs;
    private long lastResetPositionUs;
    private boolean pendingInputStreamOffsetChange;

    /** The buffer presentation time, in microseconds, of the final frame in the stream. */
    private long finalBufferPresentationTimeUs;

    /**
     * The buffer presentation timestamp, in microseconds, of the most recently registered frame.
     */
    private long lastBufferPresentationTimeUs;

    private boolean hasRegisteredFirstInputStream;
    private boolean isInputStreamChangePending;
    private long pendingInputStreamBufferPresentationTimeUs;
    private VideoSink.Listener listener;
    private Executor listenerExecutor;

    /** Creates a new instance. */
    public InputVideoSink(Context context, int sequenceIndex, int compositorInputIndex) {
      this.sequenceIndex = sequenceIndex;
      this.compositorInputIndex = compositorInputIndex;
      // TODO b/226330223 - Investigate increasing frame count when frame dropping is allowed.
      // TODO b/278234847 - Evaluate whether limiting frame count when frame dropping is not allowed
      //  reduces decoder timeouts, and consider restoring.
      videoFrameProcessorMaxPendingFrameCount =
          Util.getMaxPendingFramesCountForMediaCodecDecoders(context);
      videoEffects = new ArrayList<>();
      frameReleaseInfo = new VideoFrameReleaseControl.FrameReleaseInfo();
      finalBufferPresentationTimeUs = C.TIME_UNSET;
      lastBufferPresentationTimeUs = C.TIME_UNSET;
      inputEndSignalled = false;
      listener = VideoSink.Listener.NO_OP;
      listenerExecutor = NO_OP_EXECUTOR;
    }

    @Override
    public void onRendererEnabled(boolean mayRenderStartOfStream) {
      if (sequenceIndex == 0 && !(inputIndices.length > 1
          && (rendererPeriodReinitialization || hasRegisteredFirstInputStream && !isAwaitingProjectSeekReset()))) {
        defaultVideoSink.onRendererEnabled(mayRenderStartOfStream);
      }
    }

    @Override
    public void onRendererDisabled() {
      // A shared sink spans the child renderer's period lifetimes. Disabling
      // it here has no matching re-enable during multi-input period handoff.
      if (sequenceIndex == 0 && inputIndices.length == 1) defaultVideoSink.onRendererDisabled();
    }

    @Override
    public void onRendererStarted() {
      if (sequenceIndex == 0) defaultVideoSink.onRendererStarted();
    }

    @Override
    public void onRendererStopped() {
      if (sequenceIndex == 0) defaultVideoSink.onRendererStopped();
    }

    @Override
    public void setListener(Listener listener, Executor executor) {
      this.listener = listener;
      listenerExecutor = executor;
    }

    @Override
    public void initialize(Format sourceFormat) throws VideoSinkException {
      checkState(!isInitialized());
      videoFrameProcessor = MultiInputPlaybackVideoGraphWrapper.this.initialize(sourceFormat, compositorInputIndex);
    }

    @Override
    
    public boolean isInitialized() {
      return videoFrameProcessor != null;
    }

    @Override
    public void flush(boolean resetPosition) {
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(MultiInputPlaybackVideoGraphWrapper.this,
          "sinkFlush", compositorInputIndex, C.TIME_UNSET, "reset=" + resetPosition + " last=" + lastBufferPresentationTimeUs);
      if (awaitingProjectSeekReset || projectSeekInProgress) {
        // The graph owner already returned every compositor lease and flushed
        // all inputs under one barrier. A late renderer reset only resets its
        // stream bookkeeping; it cannot discard frames another input now owns.
        resetInputBookkeeping();
        return;
      }
      if (inputIndices.length > 1 && (rendererPeriodReinitialization || hasRegisteredFirstInputStream)) {
        // Ordinary period handoff preserves queued primary AND secondary frames.
        // registerInputStream serializes the next format after the prior stream;
        // flushing here asserted an untrue drained-input assumption on backward seeks.
        periodReinitializedStream = true;
        return;
      }
      if (isInitialized()) {
        videoFrameProcessor.flush();
      }
      hasRegisteredFirstInputStream = false;
      finalBufferPresentationTimeUs = C.TIME_UNSET;
      lastBufferPresentationTimeUs = C.TIME_UNSET;
      inputEndSignalled = false;
      MultiInputPlaybackVideoGraphWrapper.this.flush(resetPosition);
      pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
      // Don't change input stream offset or reset the pending input stream offset change so that
      // it's announced with the next input frame.
      // Don't reset isInputStreamChangePending because it's not guaranteed to receive a new input
      // stream after seeking.
    }

    @Override
    public boolean isReady(boolean rendererOtherwiseReady) {
      if (transitionClippingEnabled && sequenceIndex != 0 && inputIndices.length > 1 && lastBufferPresentationTimeUs != C.TIME_UNSET) {
        // Secondary processor backpressure means decoded frames are waiting
        // for the timing primary, not that the composition needs to buffer.
        // Requiring shared-output readiness here can pause the primary while
        // its next period needs to decode the frame that releases this pool.
        boolean waitingForPeriodHandoff = videoGraph instanceof androidx.media3.effect.CancellablePreviewVideoGraph
            && (((androidx.media3.effect.CancellablePreviewVideoGraph) videoGraph)
                .getLatestInputFrameTimeUs(compositorInputIndex) >= currentProjectPositionUs
                || (currentStreamDecoded && currentProjectPositionUs < decodedStreamEndProjectUs
                    && ((androidx.media3.effect.CancellablePreviewVideoGraph) videoGraph)
                        .hasPendingInputTextures(compositorInputIndex)));
        return isInitialized() && (rendererOtherwiseReady || waitingForPeriodHandoff
            || checkStateNotNull(videoFrameProcessor).getPendingInputFrameCount() > 0);
      }
      boolean graphHasQueuedFrames = transitionClippingEnabled && inputIndices.length > 1 && isInitialized()
          && (checkStateNotNull(videoFrameProcessor).getPendingInputFrameCount() > 0
              || pendingOutputFrameCount > 0);
      return MultiInputPlaybackVideoGraphWrapper.this.isReady(
          /* rendererOtherwiseReady= */ (rendererOtherwiseReady || graphHasQueuedFrames) && isInitialized());
    }

    @Override
    public boolean isEnded() {
      if (transitionClippingEnabled && currentStreamDecoded && lastBufferPresentationTimeUs == C.TIME_UNSET) return true;
      boolean ended = isInitialized()
          && finalBufferPresentationTimeUs != C.TIME_UNSET
          && (sequenceIndex == 0
              ? MultiInputPlaybackVideoGraphWrapper.this.hasReleasedFrame(finalBufferPresentationTimeUs)
              : checkStateNotNull(videoFrameProcessor).getPendingInputFrameCount() == 0);
      if (ended && sequenceIndex == 0 && finalInputStream) primaryFinalOutputRendered = true;
      return ended;
    }

    @Override
    public void onInputStreamChanged(@InputType int inputType, Format format) {
      checkState(isInitialized());
      switch (inputType) {
        case INPUT_TYPE_SURFACE:
        case INPUT_TYPE_BITMAP:
          break;
        default:
          throw new UnsupportedOperationException("Unsupported input type " + inputType);
      }
      if (sequenceIndex == 0) videoFrameReleaseControl.setFrameRate(format.frameRate);
      this.inputType = inputType;
      this.inputFormat = format;

      if (!hasRegisteredFirstInputStream || (transitionClippingEnabled && lastBufferPresentationTimeUs == C.TIME_UNSET)) {
        maybeRegisterInputStream();
        hasRegisteredFirstInputStream = true;
        // If an input stream registration is pending and seek causes a format change, execution
        // reaches here before registerInputFrame(). Reset pendingInputStreamTimestampUs to
        // avoid registering the same input stream again in registerInputFrame().
        isInputStreamChangePending = false;
        pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
      } else {
        // If we reach this point, we must have registered at least one frame for processing.
        checkState(lastBufferPresentationTimeUs != C.TIME_UNSET);
        isInputStreamChangePending = true;
        pendingInputStreamBufferPresentationTimeUs = lastBufferPresentationTimeUs;
      }
    }

    @Override
    public Surface getInputSurface() {
      checkState(isInitialized());
      return checkStateNotNull(videoFrameProcessor).getInputSurface();
    }

    @Override
    public void setVideoFrameMetadataListener(
        VideoFrameMetadataListener videoFrameMetadataListener) {
      MultiInputPlaybackVideoGraphWrapper.this.setVideoFrameMetadataListener(videoFrameMetadataListener);
    }

    @Override
    public void setPlaybackSpeed(@FloatRange(from = 0, fromInclusive = false) float speed) {
      if (sequenceIndex == 0) MultiInputPlaybackVideoGraphWrapper.this.setPlaybackSpeed(speed);
    }

    @Override
    public void setVideoEffects(List<Effect> videoEffects) {
      if (this.videoEffects.equals(videoEffects)) {
        return;
      }
      setPendingVideoEffects(videoEffects);
      maybeRegisterInputStream();
    }

    @Override
    public void setPendingVideoEffects(List<Effect> videoEffects) {
      this.videoEffects.clear();
      this.videoEffects.addAll(videoEffects);
      if (inputIndices.length == 1) this.videoEffects.addAll(compositionEffects);
    }

    @Override
    public void setStreamTimestampInfo(
        long streamStartPositionUs,
        long streamOffsetUs,
        long bufferTimestampAdjustmentUs,
        long lastResetPositionUs) {
      // Ors because this method could be called multiple times on a stream offset change.
      pendingInputStreamOffsetChange |=
          inputStreamOffsetUs != streamOffsetUs
              || inputBufferTimestampAdjustmentUs != bufferTimestampAdjustmentUs;
      inputStreamStartPositionUs = streamStartPositionUs;
      inputStreamOffsetUs = streamOffsetUs;
      inputBufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
      this.lastResetPositionUs = periodReinitializedStream ? streamStartPositionUs : lastResetPositionUs;
    }

    @Override
    public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
      MultiInputPlaybackVideoGraphWrapper.this.setOutputSurfaceInfo(outputSurface, outputResolution);
    }

    @Override
    public void clearOutputSurfaceInfo() {
      MultiInputPlaybackVideoGraphWrapper.this.clearOutputSurfaceInfo();
    }

    @Override
    public void setChangeFrameRateStrategy(
        @C.VideoChangeFrameRateStrategy int changeFrameRateStrategy) {
      defaultVideoSink.setChangeFrameRateStrategy(changeFrameRateStrategy);
    }

    @Override
    public void enableMayRenderStartOfStream() {
      if (sequenceIndex == 0) defaultVideoSink.enableMayRenderStartOfStream();
    }

    @Override
    public boolean handleInputFrame(
        long framePresentationTimeUs,
        boolean isLastFrame,
        long positionUs,
        long elapsedRealtimeUs,
        VideoFrameHandler videoFrameHandler)
        throws VideoSinkException {
      checkState(isInitialized());
      // An old child can still drain while another child's period prepares.
      // Do not let old-period textures enter the new seek generation.
      if (awaitingRendererReset || projectSeekInProgress) {
        if (!projectSeekInProgress && !awaitingProjectSeekReset) {
          awaitingRendererReset = false;
        } else {
          return false;
        }
      }

      // The sink takes in frames with monotonically increasing, non-offset frame
      // timestamps. That is, with two ten-second long videos, the first frame of the second video
      // should bear a timestamp of 10s seen from VideoFrameProcessor; while in ExoPlayer, the
      // timestamp of the said frame would be 0s, but the streamOffset is incremented by 10s to
      // include the duration of the first video. Thus this correction is needed to account for the
      // different handling of presentation timestamps in ExoPlayer and VideoFrameProcessor.
      //
      // inputBufferTimestampAdjustmentUs adjusts the frame presentation time (which is relative to
      // the start of a composition) to the buffer timestamp (that corresponds to the player
      // position).
      if (lastDiagnosticReceivedPts != framePresentationTimeUs) {
        lastDiagnosticReceivedPts = framePresentationTimeUs;
        com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(MultiInputPlaybackVideoGraphWrapper.this,
          "sinkReceived", compositorInputIndex, framePresentationTimeUs,
          "last=" + isLastFrame + " adjustment=" + inputBufferTimestampAdjustmentUs + " offset=" + inputStreamOffsetUs);
      }
      long bufferPresentationTimeUs = framePresentationTimeUs - inputBufferTimestampAdjustmentUs;
      // The frame release action should be retrieved for all frames (even the ones that will be
      // skipped), because the release control estimates the content frame rate from frame
      // timestamps and we want to have this information known as early as possible, especially
      // during seeking.
      @VideoFrameReleaseControl.FrameReleaseAction int frameReleaseAction;
      try {
        // Only composited OUTPUT timestamps may update the shared pacing
        // estimator. Decoder lookahead timestamps are ahead of the clock and
        // would corrupt its vsync prediction when output then arrives earlier.
        frameReleaseAction = inputIndices.length > 1
            ? VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED
            :
            videoFrameReleaseControl.getFrameReleaseAction(
                bufferPresentationTimeUs,
                positionUs,
                elapsedRealtimeUs,
                inputStreamStartPositionUs,
                isLastFrame,
                frameReleaseInfo);
      } catch (ExoPlaybackException e) {
        throw new VideoSinkException(e, checkStateNotNull(inputFormat));
      }
      if (frameReleaseAction == VideoFrameReleaseControl.FRAME_RELEASE_IGNORE) {
        // The buffer is no longer valid and needs to be ignored.
        return false;
      }

      boolean beforeReset = transitionClippingEnabled && projectResetPositionUs != C.TIME_UNSET
          ? framePresentationTimeUs < projectResetPositionUs : bufferPresentationTimeUs < lastResetPositionUs;
      if (beforeReset && !isLastFrame) {
        videoFrameHandler.skip();
        return true;
      }

      // The shared graph's pacing domain belongs to the timing primary.
      // Auxiliary renderer positions carry different period/source offsets.
      if (!transitionClippingEnabled || sequenceIndex == 0) render(positionUs, elapsedRealtimeUs);

      // Single-input playback waits for display release. In a shared graph,
      // registerInputStream orders the processor stream change after earlier
      // processing while compositor-owned textures remain valid. Waiting for
      // display here can deadlock the primary at a concatenated clip boundary.
      if (isInputStreamChangePending) {
        if (inputIndices.length > 1 || pendingInputStreamBufferPresentationTimeUs == C.TIME_UNSET
            || MultiInputPlaybackVideoGraphWrapper.this.hasReleasedFrame(
                pendingInputStreamBufferPresentationTimeUs)) {
          maybeRegisterInputStream();
          isInputStreamChangePending = false;
          pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
        } else {
          return false;
        }
      }
      if (checkStateNotNull(videoFrameProcessor).getPendingInputFrameCount()
          >= videoFrameProcessorMaxPendingFrameCount) {
        return false;
      }
      if (!checkStateNotNull(videoFrameProcessor).registerInputFrame()) {
        return false;
      }

      maybeSetStreamOffsetChange(bufferPresentationTimeUs);
      lastBufferPresentationTimeUs = bufferPresentationTimeUs;
      if (isLastFrame) {
        finalBufferPresentationTimeUs = bufferPresentationTimeUs;
      }
      // Use the frame presentation time as render time so that the SurfaceTexture is accompanied
      // by this timestamp. Setting a realtime based release time is only relevant when rendering to
      // a SurfaceView, but we render to a surface in this case.
      awaitingProjectSeekReset = false;
      videoFrameHandler.render(/* renderTimestampNs= */ framePresentationTimeUs * 1000);
      com.videoeditor.core.media.preview.PreviewPhaseTrace.input(MultiInputPlaybackVideoGraphWrapper.this,
          sequenceIndex, compositorInputIndex, framePresentationTimeUs, bufferPresentationTimeUs);
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(MultiInputPlaybackVideoGraphWrapper.this,
          "sinkSubmitted", compositorInputIndex, framePresentationTimeUs, "buffer=" + bufferPresentationTimeUs + " sink=" + System.identityHashCode(this));
      submittedFrames++;
      if (Util.SDK_INT >= 29) {
        android.os.Trace.setCounter("Preview.input." + compositorInputIndex + ".submittedFrames", submittedFrames);
        android.os.Trace.setCounter("Preview.input." + compositorInputIndex + ".pendingFrames", videoFrameProcessor.getPendingInputFrameCount());
      }
      return true;
    }

    @Override
    public boolean handleInputBitmap(Bitmap inputBitmap, TimestampIterator timestampIterator) {
      checkState(isInitialized());
      if (awaitingRendererReset || projectSeekInProgress) {
        if (!projectSeekInProgress) {
          awaitingRendererReset = false;
        } else {
          return false;
        }
      }

      if (!maybeRegisterPendingInputStream()) {
        return false;
      }

      if (!checkStateNotNull(videoFrameProcessor)
          .queueInputBitmap(inputBitmap, timestampIterator)) {
        return false;
      }

      // Create a copy of iterator because we need to take the next timestamp but we must not alter
      // the state of the iterator.
      TimestampIterator copyTimestampIterator = timestampIterator.copyOf();
      long bufferPresentationTimeUs = copyTimestampIterator.next();
      com.videoeditor.core.media.preview.PreviewPhaseTrace.input(MultiInputPlaybackVideoGraphWrapper.this,
          sequenceIndex, compositorInputIndex, bufferPresentationTimeUs,
          bufferPresentationTimeUs - inputBufferTimestampAdjustmentUs);
      // TimestampIterator generates frame time.
      long lastBufferPresentationTimeUs =
          copyTimestampIterator.getLastTimestampUs() - inputBufferTimestampAdjustmentUs;
      checkState(lastBufferPresentationTimeUs != C.TIME_UNSET);
      maybeSetStreamOffsetChange(bufferPresentationTimeUs);
      this.lastBufferPresentationTimeUs = lastBufferPresentationTimeUs;
      finalBufferPresentationTimeUs = lastBufferPresentationTimeUs;
      awaitingProjectSeekReset = false;
      signalFinalInputEnd();
      return true;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
      if (sequenceIndex != 0) return;
      try {
        MultiInputPlaybackVideoGraphWrapper.this.render(positionUs, elapsedRealtimeUs);
      } catch (ExoPlaybackException e) {
        throw new VideoSinkException(
            e, inputFormat != null ? inputFormat : new Format.Builder().build());
      }
    }

    @Override
    public void join(boolean renderNextFrameImmediately) {
      if (sequenceIndex == 0) defaultVideoSink.join(renderNextFrameImmediately);
    }

    @Override
    public void release() {
      // Renderer borrows this sink. The player owner releases the shared graph
      // only after ALL decoder producers have stopped.
    }

    // Other methods

    private void maybeSetStreamOffsetChange(long bufferPresentationTimeUs) {
      if (pendingInputStreamOffsetChange && sequenceIndex == 0) {
        MultiInputPlaybackVideoGraphWrapper.this.onStreamOffsetChange(
            inputBufferTimestampAdjustmentUs,
            bufferPresentationTimeUs,
            /* streamOffsetUs= */ inputStreamOffsetUs);
        pendingInputStreamOffsetChange = false;
      }
    }

    /**
     * Attempt to register any pending input stream to the video graph input and returns {@code
     * true} if a pending stream was registered and/or there is no pending input stream waiting for
     * registration, hence it's safe to queue images or frames to the video graph input.
     */
    private boolean maybeRegisterPendingInputStream() {
      if (!isInputStreamChangePending) {
        return true;
      }
      // Single-input playback waits for display release. In a shared graph,
      // registerInputStream orders the processor stream change after earlier
      // processing while compositor-owned textures remain valid. Waiting for
      // display here can deadlock the primary at a concatenated clip boundary.
      if ((sequenceIndex != 0 && inputIndices.length > 1) || pendingInputStreamBufferPresentationTimeUs == C.TIME_UNSET
          || MultiInputPlaybackVideoGraphWrapper.this.hasReleasedFrame(
              pendingInputStreamBufferPresentationTimeUs)) {
        maybeRegisterInputStream();
        isInputStreamChangePending = false;
        pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
        return true;
      }
      return false;
    }

    private void maybeRegisterInputStream() {
      if (inputFormat == null) {
        return;
      }

      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(MultiInputPlaybackVideoGraphWrapper.this,
          "inputRegistered", compositorInputIndex, requestedProjectPositionUs,
          "requestId=" + outputGeneration + " type=" + inputType + " adjustment=" + inputBufferTimestampAdjustmentUs);
      ArrayList<Effect> effects = new ArrayList<>(videoEffects);
      Format inputFormat = checkNotNull(this.inputFormat);
      checkStateNotNull(videoFrameProcessor)
          .registerInputStream(
              inputType,
              effects,
              new FrameInfo.Builder(
                      getAdjustedInputColorInfo(inputFormat.colorInfo),
                      inputFormat.width,
                      inputFormat.height)
                  .setPixelWidthHeightRatio(inputFormat.pixelWidthHeightRatio)
                  .build());
      finalBufferPresentationTimeUs = C.TIME_UNSET;
      currentStreamDecoded = false;
      decodedStreamEndProjectUs = C.TIME_UNSET;
    }

    // MultiInputPlaybackVideoGraphWrapper.Listener implementation

    @Override
    public void onFirstFrameRendered(MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(() -> currentListener.onFirstFrameRendered(/* videoSink= */ this));
    }

    @Override
    public void onFrameDropped(MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(
          () -> currentListener.onFrameDropped(checkStateNotNull(/* reference= */ this)));
    }

    @Override
    public void onVideoSizeChanged(
        MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper, VideoSize videoSize) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(
          () -> currentListener.onVideoSizeChanged(/* videoSink= */ this, videoSize));
    }

    @Override
    public void onError(
        MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper,
        VideoFrameProcessingException videoFrameProcessingException) {
      VideoSink.Listener currentListener = listener;
      // A graph error may precede this input's first format notification. Keep
      // the processing exception observable instead of replacing it with a
      // null-format assertion on the callback thread.
      Format errorFormat = inputFormat != null ? inputFormat : new Format.Builder().build();
      listenerExecutor.execute(
          () -> currentListener.onError(
              /* videoSink= */ this,
              new VideoSinkException(videoFrameProcessingException, errorFormat)));
    }
  }

  private final class FrameRendererImpl implements VideoFrameRenderControl.FrameRenderer {

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      outputFormat =
          new Format.Builder()
              .setWidth(videoSize.width)
              .setHeight(videoSize.height)
              .setSampleMimeType(MimeTypes.VIDEO_RAW)
              .build();
      for (MultiInputPlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onVideoSizeChanged(MultiInputPlaybackVideoGraphWrapper.this, videoSize);
      }
    }

    @Override
    public void renderFrame(
        long renderTimeNs,
        long bufferPresentationTimeUs,
        long streamOffsetUs,
        boolean isFirstFrame) {
      if (isFirstFrame && currentSurfaceAndSize != null) {
        for (MultiInputPlaybackVideoGraphWrapper.Listener listener : listeners) {
          listener.onFirstFrameRendered(MultiInputPlaybackVideoGraphWrapper.this);
        }
      }
      if (videoFrameMetadataListener != null) {
        // TODO b/292111083 - outputFormat is initialized after the first frame is rendered because
        //  onVideoSizeChanged is announced after the first frame is available for rendering.
        Format format = outputFormat == null ? new Format.Builder().build() : outputFormat;
        videoFrameMetadataListener.onVideoFrameAboutToBeRendered(
            /* presentationTimeUs= */ bufferPresentationTimeUs,
            clock.nanoTime(),
            format,
            /* mediaFormat= */ null);
      }
      long projectTimeUs = bufferPresentationTimeUs + bufferTimestampAdjustmentUs;
      final long frameGeneration = outputGeneration;
      Runnable completed = () -> {
        if (awaitingSeekBarrier || frameGeneration != outputGeneration) return;
        com.videoeditor.core.media.preview.PreviewPhaseTrace.graph(MultiInputPlaybackVideoGraphWrapper.this,
            "outputSubmittedUs", projectTimeUs);
        synchronized (scheduledOutputFrames) {
          scheduledOutputFrames.add(new long[] {
              renderTimeNs < 0 ? clock.nanoTime() : renderTimeNs, projectTimeUs});
          while (scheduledOutputFrames.size() > 16) lastRenderedProjectTimeUs = scheduledOutputFrames.remove()[1];
        }
      };
      PreviewingVideoGraph graph = checkStateNotNull(videoGraph);
      if (graph instanceof androidx.media3.effect.CancellablePreviewVideoGraph) {
        ((androidx.media3.effect.CancellablePreviewVideoGraph) graph)
            .renderPreviewOutputFrame(renderTimeNs, completed);
      } else {
        graph.renderOutputFrame(renderTimeNs);
        completed.run();
      }
    }

    @Override
    public void dropFrame() {
      for (MultiInputPlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onFrameDropped(MultiInputPlaybackVideoGraphWrapper.this);
      }
      checkStateNotNull(videoGraph).renderOutputFrame(VideoFrameProcessor.DROP_OUTPUT_FRAME);
    }
  }

  /**
   * Delays reflection for loading a {@linkplain PreviewingVideoGraph.Factory
   * PreviewingSingleInputVideoGraph} instance.
   */
  private static final class ReflectivePreviewingSingleInputVideoGraphFactory
      implements PreviewingVideoGraph.Factory {

    private final VideoFrameProcessor.Factory videoFrameProcessorFactory;

    public ReflectivePreviewingSingleInputVideoGraphFactory(
        VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    }

    @Override
    public PreviewingVideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        VideoGraph.Listener listener,
        Executor listenerExecutor,
        List<Effect> compositionEffects,
        long initialTimestampOffsetUs)
        throws VideoFrameProcessingException {
      try {
        Class<?> previewingSingleInputVideoGraphFactoryClass =
            Class.forName("androidx.media3.effect.PreviewingSingleInputVideoGraph$Factory");
        PreviewingVideoGraph.Factory factory =
            (PreviewingVideoGraph.Factory)
                previewingSingleInputVideoGraphFactoryClass
                    .getConstructor(VideoFrameProcessor.Factory.class)
                    .newInstance(videoFrameProcessorFactory);
        return factory.create(
            context,
            outputColorInfo,
            debugViewProvider,
            listener,
            listenerExecutor,
            compositionEffects,
            initialTimestampOffsetUs);
      } catch (Exception e) {
        throw VideoFrameProcessingException.from(e);
      }
    }
  }

  /**
   * Delays reflection for loading a {@linkplain VideoFrameProcessor.Factory
   * DefaultVideoFrameProcessor.Factory} instance.
   */
  private static final class ReflectiveDefaultVideoFrameProcessorFactory
      implements VideoFrameProcessor.Factory {
    private static final Supplier<VideoFrameProcessor.Factory>
        VIDEO_FRAME_PROCESSOR_FACTORY_SUPPLIER =
            Suppliers.memoize(
                () -> {
                  try {
                    Class<?> defaultVideoFrameProcessorFactoryBuilderClass =
                        Class.forName(
                            "androidx.media3.effect.DefaultVideoFrameProcessor$Factory$Builder");
                    Object builder =
                        defaultVideoFrameProcessorFactoryBuilderClass
                            .getConstructor()
                            .newInstance();
                    return (VideoFrameProcessor.Factory)
                        checkNotNull(
                            defaultVideoFrameProcessorFactoryBuilderClass
                                .getMethod("build")
                                .invoke(builder));
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });

    @Override
    public VideoFrameProcessor create(
        Context context,
        DebugViewProvider debugViewProvider,
        ColorInfo outputColorInfo,
        boolean renderFramesAutomatically,
        Executor listenerExecutor,
        VideoFrameProcessor.Listener listener)
        throws VideoFrameProcessingException {
      return VIDEO_FRAME_PROCESSOR_FACTORY_SUPPLIER
          .get()
          .create(
              context,
              debugViewProvider,
              outputColorInfo,
              renderFramesAutomatically,
              listenerExecutor,
              listener);
    }
  }
}
