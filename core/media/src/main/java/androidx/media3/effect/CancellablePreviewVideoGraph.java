/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.effect;

import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_TEXTURE_ID;
import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.GlUtil.destroyEglContext;
import static androidx.media3.common.util.GlUtil.getDefaultEglDisplay;
import static androidx.media3.common.util.Util.contains;
import static androidx.media3.common.util.Util.newSingleThreadScheduledExecutor;
import static androidx.media3.effect.DebugTraceUtil.COMPONENT_COMPOSITOR;
import static androidx.media3.effect.DebugTraceUtil.COMPONENT_VFP;
import static androidx.media3.effect.DebugTraceUtil.EVENT_OUTPUT_TEXTURE_RENDERED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.SparseArray;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.FrameInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/** A {@link VideoGraph} that handles multiple input streams. */
@UnstableApi
public abstract class CancellablePreviewVideoGraph implements VideoGraph {

  // Pinned from androidx.media3:media3-effect:1.5.1, Apache-2.0.
  // Preview-only differences: explicit cancellation, callback retirement and GL cleanup barriers.
  // Export continues to use the unmodified upstream graph.

  private static final String TAG = "MultiInputVG";
  private static final String SHARED_EXECUTOR_NAME = "Effect:CancellablePreviewVideoGraph:Thread";

  private static final long RELEASE_WAIT_TIME_MS = 1_000;
  private static final int PRE_COMPOSITOR_TEXTURE_OUTPUT_CAPACITY = 2;
  private static final int COMPOSITOR_TEXTURE_OUTPUT_CAPACITY = 1;

  private final Context context;

  private final ColorInfo outputColorInfo;
  private final SingleContextGlObjectsProvider glObjectsProvider;
  private final DebugViewProvider debugViewProvider;
  private final VideoGraph.Listener listener;
  private final Executor listenerExecutor;
  private final VideoCompositorSettings videoCompositorSettings;
  private final List<Effect> compositionEffects;
  private final SparseArray<VideoFrameProcessor> preProcessors;

  private final ExecutorService sharedExecutorService;

  private final DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory;
  private final Queue<CompositorOutputTextureInfo> compositorOutputTextures;
  private final SparseArray<CompositorOutputTextureRelease> compositorOutputTextureReleases;

  private final long initialTimestampOffsetUs;
  private final boolean renderFramesAutomatically;

  @Nullable private VideoFrameProcessor compositionVideoFrameProcessor;
  @Nullable private PreviewVideoCompositor videoCompositor;

  private boolean compositionVideoFrameProcessorInputStreamRegistered;
  private boolean compositionVideoFrameProcessorInputStreamRegistrationCompleted;
  private boolean compositorEnded;
  private volatile boolean stopping;
  private volatile boolean seeking;
  private volatile long frameGeneration;
  private boolean released;
  private long lastRenderedPresentationTimeUs;
  // Preview EOS belongs to the compositor interval, not the reusable VFP's terminal lifetime.
  private final java.util.Map<Integer, Long> previewInputEndTimes = new java.util.HashMap<>();
  private final java.util.Map<Integer, Long> latestPreviewInputTimes = new java.util.HashMap<>();

  private volatile boolean hasProducedFrameWithTimestampZero;

  protected CancellablePreviewVideoGraph(
      Context context,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      ColorInfo outputColorInfo,
      DebugViewProvider debugViewProvider,
      Listener listener,
      Executor listenerExecutor,
      VideoCompositorSettings videoCompositorSettings,
      List<Effect> compositionEffects,
      long initialTimestampOffsetUs,
      boolean renderFramesAutomatically) {
    checkArgument(videoFrameProcessorFactory instanceof DefaultVideoFrameProcessor.Factory);
    this.context = context;
    this.outputColorInfo = outputColorInfo;
    this.debugViewProvider = debugViewProvider;
    this.listener = listener;
    this.listenerExecutor = listenerExecutor;
    this.videoCompositorSettings = videoCompositorSettings;
    this.compositionEffects = new ArrayList<>(compositionEffects);
    this.initialTimestampOffsetUs = initialTimestampOffsetUs;
    this.renderFramesAutomatically = renderFramesAutomatically;
    lastRenderedPresentationTimeUs = C.TIME_UNSET;
    preProcessors = new SparseArray<>();
    sharedExecutorService = newSingleThreadScheduledExecutor(SHARED_EXECUTOR_NAME);
    glObjectsProvider = new SingleContextGlObjectsProvider();
    // TODO - b/289986435: Support injecting arbitrary VideoFrameProcessor.Factory.
    this.videoFrameProcessorFactory =
        ((DefaultVideoFrameProcessor.Factory) videoFrameProcessorFactory)
            .buildUpon()
            .setGlObjectsProvider(glObjectsProvider)
            .setExecutorService(sharedExecutorService)
            .build();
    compositorOutputTextures = new ArrayDeque<>();
    compositorOutputTextureReleases = new SparseArray<>();
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method must be called at most once.
   */
  @Override
  public void initialize() throws VideoFrameProcessingException {
    checkState(
        preProcessors.size() == 0
            && videoCompositor == null
            && compositionVideoFrameProcessor == null
            && !stopping);

    // Setting up the compositionVideoFrameProcessor
    compositionVideoFrameProcessor =
        videoFrameProcessorFactory.create(
            context,
            debugViewProvider,
            outputColorInfo,
            renderFramesAutomatically,
            /* listenerExecutor= */ MoreExecutors.directExecutor(),
            new VideoFrameProcessor.Listener() {
              // All of this listener's methods are called on the sharedExecutorService.
              @Override
              public void onInputStreamRegistered(
                  @VideoFrameProcessor.InputType int inputType,
                  List<Effect> effects,
                  FrameInfo frameInfo) {
                compositionVideoFrameProcessorInputStreamRegistrationCompleted = true;
                queueCompositionOutputInternal();
              }

              @Override
              public void onOutputSizeChanged(int width, int height) {
                listenerExecutor.execute(() -> { if (!stopping) listener.onOutputSizeChanged(width, height); });
              }

              @Override
              public void onOutputFrameAvailableForRendering(long presentationTimeUs) {
                if (stopping || seeking) return;
                final long generation = frameGeneration;
                if (presentationTimeUs == 0) {
                  hasProducedFrameWithTimestampZero = true;
                }
                lastRenderedPresentationTimeUs = presentationTimeUs;

                listenerExecutor.execute(
                    () -> { if (!stopping && !seeking && generation == frameGeneration) listener.onOutputFrameAvailableForRendering(presentationTimeUs); });
              }

              @Override
              public void onError(VideoFrameProcessingException exception) {
                handleVideoFrameProcessingException(exception);
              }

              @Override
              public void onEnded() {
                listenerExecutor.execute(() -> { if (!stopping) listener.onEnded(lastRenderedPresentationTimeUs); });
              }
            });
    // Release the compositor's output texture.
    compositionVideoFrameProcessor.setOnInputFrameProcessedListener(
        this::onCompositionVideoFrameProcessorInputFrameProcessed);

    // Setting up the compositor.
    videoCompositor =
        new PreviewVideoCompositor(
            context,
            glObjectsProvider,
            videoCompositorSettings,
            sharedExecutorService,
            new VideoCompositor.Listener() {
              // All of this listener's methods are called on the sharedExecutorService.
              @Override
              public void onError(VideoFrameProcessingException exception) {
                handleVideoFrameProcessingException(exception);
              }

              @Override
              public void onEnded() {
                onVideoCompositorEnded();
              }
            },
            /* textureOutputListener= */ this::processCompositorOutputTexture,
            COMPOSITOR_TEXTURE_OUTPUT_CAPACITY);
    com.videoeditor.core.media.preview.PreviewPhaseTrace.counter(
        "compositor." + System.identityHashCode(videoCompositor) + ".ownerGraphIdentity",
        System.identityHashCode(listener));
  }

  /** Completes on the GL executor after the output render task, never on a retired graph. */
  public final void renderPreviewOutputFrame(long renderTimeNs, Runnable completion) {
    checkState(!stopping);
    checkNotNull(compositionVideoFrameProcessor).renderOutputFrame(renderTimeNs);
    sharedExecutorService.execute(() -> {
      if (!stopping) completion.run();
    });
  }

  @Override
  public void registerInput(@IntRange(from = 0) int inputIndex)
      throws VideoFrameProcessingException {
    checkState(!stopping);
    checkState(!contains(preProcessors, inputIndex));
    checkNotNull(videoCompositor).registerInputSource(inputIndex);
    // Creating a new VideoFrameProcessor for the input.
    VideoFrameProcessor preProcessor =
        videoFrameProcessorFactory
            .buildUpon()
            .setTextureOutput(
                // Texture output to compositor.
                (textureProducer, texture, presentationTimeUs, syncObject) ->
                    queuePreProcessingOutputToCompositor(
                        inputIndex, textureProducer, texture, presentationTimeUs),
                PRE_COMPOSITOR_TEXTURE_OUTPUT_CAPACITY)
            .build()
            .create(
                context,
                DebugViewProvider.NONE,
                outputColorInfo,
                // Pre-processors render frames as soon as available, to VideoCompositor.
                /* renderFramesAutomatically= */ true,
                listenerExecutor,
                new VideoFrameProcessor.Listener() {
                  // All of this listener's methods are called on the sharedExecutorService.

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    handleVideoFrameProcessingException(exception);
                  }

                  @Override
                  public void onEnded() {
                    onPreProcessingVideoFrameProcessorEnded(inputIndex);
                  }
                });
    preProcessors.put(inputIndex, preProcessor);
  }

  @Override
  public VideoFrameProcessor getProcessor(int inputIndex) {
    checkState(!stopping);
    checkState(contains(preProcessors, inputIndex));
    return preProcessors.get(inputIndex);
  }

  @Override
  public void setOutputSurfaceInfo(@Nullable SurfaceInfo outputSurfaceInfo) {
    checkState(!stopping);
    checkNotNull(compositionVideoFrameProcessor).setOutputSurfaceInfo(outputSurfaceInfo);
  }

  @Override
  public boolean hasProducedFrameWithTimestampZero() {
    return hasProducedFrameWithTimestampZero;
  }

  @Override
  public void release() {
    if (released) {
      return;
    }

    // Retire callbacks first. Producers must already have stopped at the owner boundary.
    checkState(!stopping, "Previous preview retirement failed");
    stopping = true;
    awaitGlTask(() -> {}); // Finish callbacks already executing before retiring their objects.
    // Downstream consumers retire before their texture producers. This is cancellation,
    // not EOS: pending frames are discarded and onEnded is never synthesized.
    if (compositionVideoFrameProcessor != null) {
      compositionVideoFrameProcessor.release();
      compositionVideoFrameProcessor = null;
    }
    if (videoCompositor != null) {
      videoCompositor.abort();
      videoCompositor = null;
    }
    compositorOutputTextures.clear();
    compositorOutputTextureReleases.clear();
    for (int i = 0; i < preProcessors.size(); i++) {
      preProcessors.valueAt(i).release();
    }
    preProcessors.clear();

    // Processor release on a borrowed executor queues cleanup without waiting.
    // Destroy the shared context on that same executor AFTER every cleanup task.
    awaitGlTask(() -> {
      if (glObjectsProvider.singleEglContext != null) {
        destroyEglContext(getDefaultEglDisplay(), glObjectsProvider.singleEglContext);
      }
      return;
    });
    sharedExecutorService.shutdown();
    try {
      checkState(sharedExecutorService.awaitTermination(RELEASE_WAIT_TIME_MS, MILLISECONDS),
          "Preview GL executor did not terminate");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while releasing preview graph", e);
    }
    released = true;
  }

  private interface GlReleaseTask { void run() throws GlException; }

  private void awaitGlTask(GlReleaseTask task) {
    try {
      sharedExecutorService.submit(() -> { task.run(); return null; })
          .get(RELEASE_WAIT_TIME_MS, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during preview GL retirement", e);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("Preview GL retirement failed", e);
    }
  }

  /** Runs on the one playback looper BEFORE any child renderer resets its processor. */
  public void beginSeek() {
    if (stopping || seeking) return;
    seeking = true;
    frameGeneration++;
    awaitGlTask(() -> {});
    if (compositionVideoFrameProcessor != null) compositionVideoFrameProcessor.flush();
    awaitGlTask(() -> {
      if (videoCompositor != null) videoCompositor.flushForSeek();
      compositorOutputTextures.clear();
      compositorOutputTextureReleases.clear();
      previewInputEndTimes.clear();
      previewIntervalEnds.clear();
      latestPreviewInputTimes.clear();
    });
    compositorEnded = false;
    hasProducedFrameWithTimestampZero = false;
  }

  /** All child reset/flush messages precede this message on the shared playback looper. */
  public void endSeek() {
    awaitGlTask(() -> {});
    seeking = false;
  }

  /** Reusable preview inputs must not call VFP.signalEndOfInput(), which is terminal even after flush. */
  public void signalEndOfPreviewInterval(int inputIndex, long endUs, long finalPresentationTimeUs) {
    if (stopping || seeking) return;
    final long generation = frameGeneration;
    sharedExecutorService.execute(() -> {
      if (stopping || seeking || generation != frameGeneration) return;
      previewIntervalEnds.put(inputIndex, new long[] {endUs, finalPresentationTimeUs});
      maybeSignalPreviewInputEnd(inputIndex);
    });
  }

  public String frameDiagnostics() {
    PreviewVideoCompositor active = videoCompositor;
    return active == null ? "compositor unavailable" : active.frameDiagnostics();
  }

  public void signalEndOfPreviewInput(int inputIndex, long finalPresentationTimeUs) {
    if (stopping || seeking) return;
    final long generation = frameGeneration;
    sharedExecutorService.execute(() -> {
      if (stopping || seeking || generation != frameGeneration) return;
      previewInputEndTimes.put(inputIndex, finalPresentationTimeUs);
      maybeSignalPreviewInputEnd(inputIndex);
    });
  }

  private final android.util.SparseArray<long[]> previewIntervalEnds = new android.util.SparseArray<>();

  private void maybeSignalPreviewInputEnd(int inputIndex) {
    long[] interval = previewIntervalEnds.get(inputIndex);
    Long latest = latestPreviewInputTimes.get(inputIndex);
    if (interval != null && latest != null && latest >= interval[1]) {
      checkNotNull(videoCompositor).signalEndOfInputInterval(inputIndex, interval[0]);
      previewIntervalEnds.remove(inputIndex);
    }
    Long end = previewInputEndTimes.get(inputIndex);
    Long queued = latestPreviewInputTimes.get(inputIndex);
    if (end != null && (end == C.TIME_UNSET || queued != null && queued >= end)) {
      checkNotNull(videoCompositor).signalEndOfInputSource(inputIndex);
      previewInputEndTimes.remove(inputIndex);
    }
  }

  /** Renderer reset is safe only after this input's compositor textures drain. */
  public boolean hasPendingInputTextures(int inputIndex) {
    return checkNotNull(videoCompositor).hasPendingInputTextures(inputIndex);
  }

  public long getLatestInputFrameTimeUs(int inputIndex) {
    return checkNotNull(videoCompositor).getLatestInputFrameTimeUs(inputIndex);
  }

  protected VideoFrameProcessor getCompositionVideoFrameProcessor() {
    return checkStateNotNull(compositionVideoFrameProcessor);
  }

  protected long getInitialTimestampOffsetUs() {
    return initialTimestampOffsetUs;
  }

  // This method is called on the sharedExecutorService.
  private void queuePreProcessingOutputToCompositor(
      int videoCompositorInputId,
      GlTextureProducer textureProducer,
      GlTextureInfo texture,
      long presentationTimeUs) {
    if (stopping) return;
    if (seeking) { textureProducer.releaseOutputTexture(presentationTimeUs); return; }
    com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "processorOutput", videoCompositorInputId,
        presentationTimeUs, "compositor=" + System.identityHashCode(videoCompositor));
    DebugTraceUtil.logEvent(COMPONENT_VFP, EVENT_OUTPUT_TEXTURE_RENDERED, presentationTimeUs);
    checkNotNull(videoCompositor)
        .queueInputTexture(
            videoCompositorInputId,
            textureProducer,
            texture,
            // Color is converted to outputColor in pre processing.
            /* colorInfo= */ outputColorInfo,
            presentationTimeUs);
    latestPreviewInputTimes.put(videoCompositorInputId, presentationTimeUs);
    maybeSignalPreviewInputEnd(videoCompositorInputId);
  }

  // This method is called on the sharedExecutorService.
  private void processCompositorOutputTexture(
      GlTextureProducer textureProducer,
      GlTextureInfo outputTexture,
      long presentationTimeUs,
      long syncObject) {
    if (stopping || seeking) return;
    checkStateNotNull(compositionVideoFrameProcessor);
    checkState(!compositorEnded);
    DebugTraceUtil.logEvent(
        COMPONENT_COMPOSITOR, EVENT_OUTPUT_TEXTURE_RENDERED, presentationTimeUs);

    compositorOutputTextures.add(
        new CompositorOutputTextureInfo(outputTexture, presentationTimeUs));
    compositorOutputTextureReleases.put(
        outputTexture.texId,
        new CompositorOutputTextureRelease(textureProducer, presentationTimeUs));

    if (!compositionVideoFrameProcessorInputStreamRegistered) {
      checkNotNull(compositionVideoFrameProcessor)
          .registerInputStream(
              INPUT_TYPE_TEXTURE_ID,
              compositionEffects,
              // Pre-processing VideoFrameProcessors have converted the inputColor to outputColor
              // already, so use outputColorInfo for the input color to the
              // compositionVideoFrameProcessor.
              new FrameInfo.Builder(outputColorInfo, outputTexture.width, outputTexture.height)
                  .build());
      compositionVideoFrameProcessorInputStreamRegistered = true;
      // Return as the VideoFrameProcessor rejects input textures until the input is registered.
      return;
    }
    queueCompositionOutputInternal();
  }

  // This method is called on the sharedExecutorService.
  private void onCompositionVideoFrameProcessorInputFrameProcessed(int textureId, long syncObject) {
    // CompositionVideoFrameProcessor's input is VideoCompositor's output.
    if (stopping) return;
    checkState(contains(compositorOutputTextureReleases, textureId));
    compositorOutputTextureReleases.get(textureId).release();
    compositorOutputTextureReleases.remove(textureId);
    queueCompositionOutputInternal();
  }

  // This method is called on the sharedExecutorService.
  private void onPreProcessingVideoFrameProcessorEnded(int videoCompositorInputId) {
    if (stopping || seeking) return;
    checkNotNull(videoCompositor).signalEndOfInputSource(videoCompositorInputId);
  }

  // This method is called on the sharedExecutorService.
  private void onVideoCompositorEnded() {
    if (stopping || seeking) return;
    compositorEnded = true;
    if (compositorOutputTextures.isEmpty()) {
      checkNotNull(compositionVideoFrameProcessor).signalEndOfInput();
    } else {
      queueCompositionOutputInternal();
    }
  }

  // This method is called on the sharedExecutorService.
  private void queueCompositionOutputInternal() {
    if (stopping || seeking) return;
    checkStateNotNull(compositionVideoFrameProcessor);
    if (!compositionVideoFrameProcessorInputStreamRegistrationCompleted) {
      return;
    }

    @Nullable CompositorOutputTextureInfo outputTexture = compositorOutputTextures.peek();
    if (outputTexture == null) {
      return;
    }

    checkState(
        checkNotNull(compositionVideoFrameProcessor)
            .queueInputTexture(
                outputTexture.glTextureInfo.texId, outputTexture.presentationTimeUs));
    compositorOutputTextures.remove();
    if (compositorEnded && compositorOutputTextures.isEmpty()) {
      checkNotNull(compositionVideoFrameProcessor).signalEndOfInput();
    }
  }

  // This method is called on the sharedExecutorService.
  private void handleVideoFrameProcessingException(Exception e) {
    if (stopping) return;
    listenerExecutor.execute(
        () ->
            listener.onError(
                e instanceof VideoFrameProcessingException
                    ? (VideoFrameProcessingException) e
                    : VideoFrameProcessingException.from(e)));
  }

  private static final class CompositorOutputTextureInfo {
    public final GlTextureInfo glTextureInfo;
    public final long presentationTimeUs;

    private CompositorOutputTextureInfo(GlTextureInfo glTextureInfo, long presentationTimeUs) {
      this.glTextureInfo = glTextureInfo;
      this.presentationTimeUs = presentationTimeUs;
    }
  }

  private static final class CompositorOutputTextureRelease {
    private final GlTextureProducer textureProducer;
    private final long presentationTimeUs;

    public CompositorOutputTextureRelease(
        GlTextureProducer textureProducer, long presentationTimeUs) {
      this.textureProducer = textureProducer;
      this.presentationTimeUs = presentationTimeUs;
    }

    public void release() {
      textureProducer.releaseOutputTexture(presentationTimeUs);
    }
  }

  /**
   * A {@link GlObjectsProvider} that creates a new {@link EGLContext} in {@link #createEglContext}
   * with the same shared EGLContext.
   */
  private static final class SingleContextGlObjectsProvider implements GlObjectsProvider {
    private final GlObjectsProvider glObjectsProvider;
    private @Nullable EGLContext singleEglContext;

    public SingleContextGlObjectsProvider() {
      this.glObjectsProvider = new DefaultGlObjectsProvider();
    }

    @Override
    public EGLContext createEglContext(
        EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes) throws GlException {
      if (singleEglContext == null) {
        singleEglContext =
            glObjectsProvider.createEglContext(eglDisplay, openGlVersion, configAttributes);
      }
      return singleEglContext;
    }

    @Override
    public EGLSurface createEglSurface(
        EGLDisplay eglDisplay,
        Object surface,
        @C.ColorTransfer int colorTransfer,
        boolean isEncoderInputSurface)
        throws GlException {
      return glObjectsProvider.createEglSurface(
          eglDisplay, surface, colorTransfer, isEncoderInputSurface);
    }

    @Override
    public EGLSurface createFocusedPlaceholderEglSurface(
        EGLContext eglContext, EGLDisplay eglDisplay) throws GlException {
      return glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
    }

    @Override
    public GlTextureInfo createBuffersForTexture(int texId, int width, int height)
        throws GlException {
      return glObjectsProvider.createBuffersForTexture(texId, width, height);
    }

    @Override
    public void release(EGLDisplay eglDisplay) {
      // The eglContext is released in the VideoGraph after all VideoFrameProcessors are released.
    }
  }
}
