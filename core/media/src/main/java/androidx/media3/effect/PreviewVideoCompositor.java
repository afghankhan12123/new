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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.contains;
import static java.lang.Math.abs;
import static java.lang.Math.max;

import android.content.Context;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.SparseArray;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.LongArrayQueue;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;

/**
 * A basic {@link VideoCompositor} implementation that takes in frames from input sources' streams
 * and combines them into one output stream.
 *
 * <p>The first {@linkplain #registerInputSource registered source} will be the primary stream,
 * which is used to determine the output textures' timestamps and dimensions.
 *
 * <p>The input source must be able to have at least two {@linkplain
 * VideoCompositor#queueInputTexture queued textures} in its output buffer.
 *
 * <p>When composited, textures are overlaid over one another in the reverse order of their
 * registration order, so that the first registered source is on the very top. The way the textures
 * are overlaid can be customized using the {@link OverlaySettings} output by {@link
 * VideoCompositorSettings}.
 *
 * <p>Only SDR input with the same {@link ColorInfo} are supported.
 */
@UnstableApi
public final class PreviewVideoCompositor implements VideoCompositor {
  // TODO: b/262694346 -  Flesh out this implementation by doing the following:
  //  * Use a lock to synchronize inputFrameInfos more narrowly, to reduce blocking.
  //  * Add support for mixing SDR streams with different ColorInfo.
  //  * Add support for HDR input.

  private static final String THREAD_NAME = "Effect:PreviewVideoCompositor:GlThread";
  private static final String TAG = "PreviewVideoCompositor";

  private final VideoCompositor.Listener listener;
  private final GlTextureProducer.Listener textureOutputListener;
  private final GlObjectsProvider glObjectsProvider;
  private final VideoCompositorSettings settings;
  private final Stage9CompositorProgram compositorGlProgram;
  private final VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor;

  @GuardedBy("this")
  private final SparseArray<InputSource> inputSources;

  @GuardedBy("this")
  private volatile boolean released;
  private boolean allInputsEnded; // Whether all inputSources have signaled end of input.

  private final TexturePool outputTexturePool;
  private final LongArrayQueue outputTextureTimestamps; // Synchronized with outputTexturePool.
  private final LongArrayQueue syncObjects; // Synchronized with outputTexturePool.

  private @Nullable ColorInfo configuredColorInfo;

  // Only used on the GL Thread.
  private @Nullable EGLContext eglContext;
  private @Nullable EGLDisplay eglDisplay;
  private @Nullable EGLSurface placeholderEglSurface;
  private int primaryInputIndex;
  private final int expectedInputCount;

  /**
   * Creates an instance.
   *
   * <p>If a non-null {@code executorService} is set, the {@link ExecutorService} must be
   * {@linkplain ExecutorService#shutdown shut down} by the caller.
   */
  public PreviewVideoCompositor(
      Context context,
      GlObjectsProvider glObjectsProvider,
      VideoCompositorSettings settings,
      @Nullable ExecutorService executorService,
      VideoCompositor.Listener listener,
      GlTextureProducer.Listener textureOutputListener,
      @IntRange(from = 1) int textureOutputCapacity) {
    this.listener = listener;
    this.textureOutputListener = textureOutputListener;
    this.glObjectsProvider = glObjectsProvider;
    this.settings = settings;
    this.compositorGlProgram = new Stage9CompositorProgram(context);
    // The application background owns cadence; the foreground owns z-order.
    // Generic Media3 settings retain their original first-registered behavior.
    if (settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings) {
      com.videoeditor.core.media.composition.OverlayCompositorSettings projectSettings =
          (com.videoeditor.core.media.composition.OverlayCompositorSettings) settings;
      primaryInputIndex = projectSettings.getTimingPrimaryInputId();
      expectedInputCount = projectSettings.getInputCount();
    } else {
      primaryInputIndex = C.INDEX_UNSET;
      expectedInputCount = 0;
    }

    inputSources = new SparseArray<>();
    outputTexturePool =
        new TexturePool(/* useHighPrecisionColorComponents= */ false, textureOutputCapacity);
    outputTextureTimestamps = new LongArrayQueue(textureOutputCapacity);
    syncObjects = new LongArrayQueue(textureOutputCapacity);

    boolean ownsExecutor = executorService == null;
    ExecutorService instanceExecutorService =
        ownsExecutor ? Util.newSingleThreadExecutor(THREAD_NAME) : checkNotNull(executorService);
    videoFrameProcessingTaskExecutor =
        new VideoFrameProcessingTaskExecutor(
            instanceExecutorService,
            /* shouldShutdownExecutorService= */ ownsExecutor,
            listener::onError);
    videoFrameProcessingTaskExecutor.submit(this::setupGlObjects);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The first registered input will be designated as the primary input.
   */
  @Override
  public synchronized void registerInputSource(int inputIndex) {
    checkState(!contains(inputSources, inputIndex));
    inputSources.put(inputIndex, new InputSource());
    if (primaryInputIndex == C.INDEX_UNSET) {
      primaryInputIndex = inputIndex;
    }
  }

  @Override
  public synchronized void signalEndOfInputSource(int inputIndex) {
    if (released) return;
    checkState(contains(inputSources, inputIndex));
    checkState(primaryInputIndex != C.INDEX_UNSET);
    com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "compositorEOS", inputIndex,
        C.TIME_UNSET, "queue=" + inputSources.get(inputIndex).frameInfos.size());
    inputSources.get(inputIndex).isInputEnded = true;
    boolean allInputsEnded = expectedInputCount == 0 || inputSources.size() == expectedInputCount;
    for (int i = 0; i < inputSources.size(); i++) {
      if (!inputSources.valueAt(i).isInputEnded) {
        allInputsEnded = false;
        break;
      }
    }

    this.allInputsEnded = allInputsEnded;
    if (!contains(inputSources, primaryInputIndex)) return;
    if (inputSources.get(primaryInputIndex).frameInfos.isEmpty()) {
      if (inputIndex == primaryInputIndex) {
        releaseExcessFramesInAllSecondaryStreams();
      }
      if (allInputsEnded) {
        listener.onEnded();
        return;
      }
    }
    if (inputIndex != primaryInputIndex && inputSources.get(inputIndex).frameInfos.size() == 1) {
      // When a secondary stream ends input, composite if there was only one pending frame in the
      // stream.
      videoFrameProcessingTaskExecutor.submit(this::maybeComposite);
    }
  }

  @Override
  public synchronized void queueInputTexture(
      int inputIndex,
      GlTextureProducer textureProducer,
      GlTextureInfo inputTexture,
      ColorInfo colorInfo,
      long presentationTimeUs) {
    if (released) return;
    checkState(contains(inputSources, inputIndex));

    InputSource inputSource = inputSources.get(inputIndex);
    checkState(!inputSource.isInputEnded);
    checkStateNotNull(!ColorInfo.isTransferHdr(colorInfo), "HDR input is not supported.");
    if (configuredColorInfo == null) {
      configuredColorInfo = colorInfo;
    }
    checkState(
        configuredColorInfo.equals(colorInfo), "Mixing different ColorInfos is not supported.");

    CompositorFrame inputFrameInfo =
        new CompositorFrame(
            textureProducer,
            inputTexture,
            presentationTimeUs,
            settings.getOverlaySettings(inputIndex, presentationTimeUs), inputIndex);
    com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "compositorReceived", inputIndex,
        presentationTimeUs, "ended=" + inputSource.isInputEnded);
    inputSource.frameInfos.add(inputFrameInfo);

    if (inputIndex == primaryInputIndex) {
      releaseExcessFramesInAllSecondaryStreams();
    } else if (contains(inputSources, primaryInputIndex)) {
      releaseExcessFramesInSecondaryStream(inputSource);
    }

    videoFrameProcessingTaskExecutor.submit(this::maybeComposite);
  }

  @Override
  public void release() { abort(); }

  /** Caller stops producers and downstream consumer before retiring owned GL resources. */
  public void abort() {
    synchronized (this) { if (released) return; released = true; }
    try {
      videoFrameProcessingTaskExecutor.release(this::releaseGlObjects);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during preview compositor release", e);
    }
  }

  /** GL-thread barrier: the downstream processor has flushed before borrowed inputs are returned. */
  public synchronized void flushForSeek() throws GlUtil.GlException {
    for (int i = 0; i < inputSources.size(); i++) {
      InputSource input = inputSources.valueAt(i);
      releaseFrames(input, input.frameInfos.size());
      input.isInputEnded = false;
      input.completedThroughUs = Long.MIN_VALUE;
    }
    while (outputTexturePool.freeTextureCount() < outputTexturePool.capacity()) outputTexturePool.freeTexture();
    outputTextureTimestamps.clear();
    while (!syncObjects.isEmpty()) GlUtil.deleteSyncObject(syncObjects.remove());
    allInputsEnded = false;
  }

  /** A period boundary is a watermark, never terminal EOS for a reusable lane. */
  public synchronized void signalEndOfInputInterval(int inputIndex, long endUs) {
    if (released || !contains(inputSources, inputIndex)) return;
    InputSource source = inputSources.get(inputIndex);
    source.completedThroughUs = Math.max(source.completedThroughUs, endUs);
    videoFrameProcessingTaskExecutor.submit(this::maybeComposite);
  }

  public synchronized String frameDiagnostics() {
    StringBuilder result = new StringBuilder("primary=").append(primaryInputIndex);
    for (int i = 0; i < inputSources.size(); i++) {
      InputSource input = inputSources.valueAt(i);
      result.append(" input=").append(inputSources.keyAt(i)).append("[");
      for (CompositorFrame frame : input.frameInfos) result.append(frame.presentationTimeUs).append(',');
      result.append("] ended=").append(input.isInputEnded).append(" through=").append(input.completedThroughUs);
    }
    return result.toString();
  }

  /** Whether this input still owns textures awaiting composition. */
  public synchronized boolean hasPendingInputTextures(int inputIndex) {
    return contains(inputSources, inputIndex) && !inputSources.get(inputIndex).frameInfos.isEmpty();
  }

  public synchronized long getLatestInputFrameTimeUs(int inputIndex) {
    if (!contains(inputSources, inputIndex)) return C.TIME_UNSET;
    long latestUs = C.TIME_UNSET;
    for (CompositorFrame frame : inputSources.get(inputIndex).frameInfos) latestUs = frame.presentationTimeUs;
    return latestUs;
  }

  @Override
  public void releaseOutputTexture(long presentationTimeUs) {
    if (released) return;
    videoFrameProcessingTaskExecutor.submit(() -> releaseOutputTextureInternal(presentationTimeUs));
  }

  private synchronized void releaseExcessFramesInAllSecondaryStreams() {
    for (int i = 0; i < inputSources.size(); i++) {
      if (inputSources.keyAt(i) == primaryInputIndex) {
        continue;
      }
      releaseExcessFramesInSecondaryStream(inputSources.valueAt(i));
    }
  }

  /**
   * Release unneeded frames from the {@link InputSource} secondary stream.
   *
   * <p>After this method returns, there should be exactly zero or one frames left with a timestamp
   * less than the primary stream's next timestamp that were present when the method execution
   * began.
   */
  private synchronized void releaseExcessFramesInSecondaryStream(InputSource secondaryInputSource) {
    InputSource primaryInputSource = inputSources.get(primaryInputIndex);
    // If the primary stream output is ended, all secondary frames can be released.
    if (primaryInputSource.frameInfos.isEmpty() && primaryInputSource.isInputEnded) {
      releaseFrames(
          secondaryInputSource,
          /* numberOfFramesToRelease= */ secondaryInputSource.frameInfos.size());
      return;
    }

    // Release frames until the secondary stream has 0-2 frames with presentationTimeUs before or at
    // nextTimestampToComposite.
    @Nullable CompositorFrame nextPrimaryFrame = primaryInputSource.frameInfos.peek();
    long nextTimestampToComposite =
        nextPrimaryFrame != null ? nextPrimaryFrame.presentationTimeUs : C.TIME_UNSET;

    if (nextPrimaryFrame != null
        && settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings) {
      com.videoeditor.core.media.composition.OverlayCompositorSettings project =
          (com.videoeditor.core.media.composition.OverlayCompositorSettings) settings;
      int inputId = C.INDEX_UNSET;
      for (int i = 0; i < inputSources.size(); i++) {
        if (inputSources.valueAt(i) == secondaryInputSource) { inputId = inputSources.keyAt(i); break; }
      }
      boolean required = project.isInputRequired(inputId, nextTimestampToComposite);
      long discardBeforeUs = required ? project.inputStartUs(inputId, nextTimestampToComposite) : nextTimestampToComposite;
      while (!secondaryInputSource.frameInfos.isEmpty()
          && secondaryInputSource.frameInfos.element().presentationTimeUs < discardBeforeUs) {
        releaseFrames(secondaryInputSource, 1);
      }
    }

    int numberOfSecondaryFramesBeforeOrAtNextTargetTimestamp =
        Iterables.size(
            Iterables.filter(
                secondaryInputSource.frameInfos,
                frame -> frame.presentationTimeUs <= nextTimestampToComposite));
    releaseFrames(
        secondaryInputSource,
        /* numberOfFramesToRelease= */ max(
            numberOfSecondaryFramesBeforeOrAtNextTargetTimestamp - 1, 0));
  }

  private synchronized void releaseFrames(InputSource inputSource, int numberOfFramesToRelease) {
    for (int i = 0; i < numberOfFramesToRelease; i++) {
      CompositorFrame frameInfoToRelease = inputSource.frameInfos.remove();
      frameInfoToRelease.textureProducer.releaseOutputTexture(
          frameInfoToRelease.presentationTimeUs);
    }
  }

  // Below methods must be called on the GL thread.
  private void setupGlObjects() throws GlUtil.GlException {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    placeholderEglSurface =
        glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
  }

  private synchronized void maybeComposite()
      throws VideoFrameProcessingException, GlUtil.GlException {
    if (released) return;
    ImmutableList<CompositorFrame> framesToComposite = getFramesToComposite();
    if (framesToComposite.isEmpty()) {
      return;
    }

    CompositorFrame primaryInputFrame = inputSources.get(primaryInputIndex).frameInfos.element();

    ImmutableList.Builder<Size> inputSizes = new ImmutableList.Builder<>();
    for (int i = 0; i < framesToComposite.size(); i++) {
      GlTextureInfo texture = framesToComposite.get(i).texture;
      inputSizes.add(new Size(texture.width, texture.height));
    }
    Size outputSize = settings.getOutputSize(inputSizes.build());
    outputTexturePool.ensureConfigured(
        glObjectsProvider, outputSize.getWidth(), outputSize.getHeight());

    GlTextureInfo outputTexture = outputTexturePool.useTexture();
    long outputPresentationTimestampUs = primaryInputFrame.presentationTimeUs;
    outputTextureTimestamps.add(outputPresentationTimestampUs);

    for (CompositorFrame frame : framesToComposite) {
      com.videoeditor.core.media.preview.PreviewPhaseTrace.composite(this,
          frame.inputIndex, frame.presentationTimeUs, outputPresentationTimestampUs);
    }
    for (CompositorFrame frame : framesToComposite) {
      com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "selected", frame.inputIndex,
          outputPresentationTimestampUs, "inputPts=" + frame.presentationTimeUs);
    }
    compositorGlProgram.drawFrame(framesToComposite, outputTexture, outputPresentationTimestampUs, settings);
    long syncObject = GlUtil.createGlSyncFence();
    syncObjects.add(syncObject);
    textureOutputListener.onTextureRendered(
        /* textureProducer= */ this, outputTexture, outputPresentationTimestampUs, syncObject);

    InputSource primaryInputSource = inputSources.get(primaryInputIndex);
    releaseFrames(primaryInputSource, /* numberOfFramesToRelease= */ 1);
    releaseExcessFramesInAllSecondaryStreams();

    if (allInputsEnded && primaryInputSource.frameInfos.isEmpty()) {
      listener.onEnded();
    }
  }

  /**
   * Checks whether {@code inputSources} is able to composite, and if so, returns a list of {@link
   * CompositorFrame}s that should be composited next.
   *
   * <p>The first input frame info in the list is from the the primary source. An empty list is
   * returned if {@code inputSources} cannot composite now.
   */
  private synchronized ImmutableList<CompositorFrame> getFramesToComposite() {
    if (!contains(inputSources, primaryInputIndex)) return ImmutableList.of();
    if (settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings) {
      if (inputSources.get(primaryInputIndex).frameInfos.isEmpty()) return ImmutableList.of();
      long targetUs = inputSources.get(primaryInputIndex).frameInfos.element().presentationTimeUs;
      com.videoeditor.core.media.composition.OverlayCompositorSettings project =
          (com.videoeditor.core.media.composition.OverlayCompositorSettings) settings;
      for (int id = 0; id < project.getInputCount(); id++) {
        if (project.isInputRequired(id, targetUs) && !contains(inputSources, id)) return ImmutableList.of();
      }
    } else if (expectedInputCount > 0 && inputSources.size() < expectedInputCount) return ImmutableList.of();
    if (outputTexturePool.freeTextureCount() == 0) {
      return ImmutableList.of();
    }
    if (inputSources.get(primaryInputIndex).frameInfos.isEmpty()) return ImmutableList.of();
    ImmutableList.Builder<CompositorFrame> framesToComposite = new ImmutableList.Builder<>();
    CompositorFrame primaryFrameToComposite =
        inputSources.get(primaryInputIndex).frameInfos.element();
    framesToComposite.add(primaryFrameToComposite);

    for (int i = 0; i < inputSources.size(); i++) {
      if (inputSources.keyAt(i) == primaryInputIndex) {
        continue;
      }
      if (settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings
          && !((com.videoeditor.core.media.composition.OverlayCompositorSettings) settings).isInputRequired(
              inputSources.keyAt(i), primaryFrameToComposite.presentationTimeUs)) continue;
      if (inputSources.valueAt(i).frameInfos.isEmpty()) {
        if (inputSources.valueAt(i).isInputEnded
            || inputSources.valueAt(i).completedThroughUs >= primaryFrameToComposite.presentationTimeUs) {
          continue;
        }
        return ImmutableList.of();
      }
      // Select the secondary streams' frame that would be composited next. The frame selected is
      // the closest-timestamp frame from the primary stream's frame, if all secondary streams have:
      //   1. One or more frames, and the secondary stream has ended, or
      //   2. Two or more frames, and at least one frame has timestamp greater than the target
      //      timestamp.
      // The smaller timestamp is taken if two timestamps have the same distance from the primary.
      InputSource secondaryInputSource = inputSources.valueAt(i);
      if (settings instanceof com.videoeditor.core.media.composition.OverlayCompositorSettings) {
        com.videoeditor.core.media.composition.OverlayCompositorSettings project =
            (com.videoeditor.core.media.composition.OverlayCompositorSettings) settings;
        long targetUs = primaryFrameToComposite.presentationTimeUs;
        long startUs = project.inputStartUs(inputSources.keyAt(i), targetUs);
        long endUs = project.inputEndUs(inputSources.keyAt(i), targetUs);
        long[] times = new long[secondaryInputSource.frameInfos.size()];
        int index = 0;
        for (CompositorFrame frame : secondaryInputSource.frameInfos) times[index++] = frame.presentationTimeUs;
        int selectedIndex = com.videoeditor.core.model.render.SecondaryFrameSelector.select(
            times, targetUs, startUs, endUs, secondaryInputSource.isInputEnded || secondaryInputSource.completedThroughUs >= endUs);
        if (selectedIndex < 0) return ImmutableList.of();
        index = 0;
        for (CompositorFrame frame : secondaryInputSource.frameInfos) {
          if (index++ == selectedIndex) { framesToComposite.add(frame); break; }
        }
        continue;
      }
      if (secondaryInputSource.frameInfos.size() == 1 && !secondaryInputSource.isInputEnded
          && secondaryInputSource.frameInfos.element().presentationTimeUs < primaryFrameToComposite.presentationTimeUs - 1) {
        return ImmutableList.of();
      }

      boolean selected = false;
      long minTimeDiffFromPrimaryUs = Long.MAX_VALUE;
      @Nullable CompositorFrame secondaryFrameToComposite = null;
      Iterator<CompositorFrame> frameInfosIterator = secondaryInputSource.frameInfos.iterator();
      while (frameInfosIterator.hasNext()) {
        CompositorFrame candidateFrame = frameInfosIterator.next();
        long candidateTimestampUs = candidateFrame.presentationTimeUs;
        long candidateAbsDistance =
            abs(candidateTimestampUs - primaryFrameToComposite.presentationTimeUs);

        if (candidateAbsDistance < minTimeDiffFromPrimaryUs) {
          minTimeDiffFromPrimaryUs = candidateAbsDistance;
          secondaryFrameToComposite = candidateFrame;
        }

        // An exact timestamp is already the optimal match. Waiting for a
        // later frame can strand a bounded decoder pool at a stream boundary.
        if (candidateTimestampUs >= primaryFrameToComposite.presentationTimeUs - 1
            || (!frameInfosIterator.hasNext() && secondaryInputSource.isInputEnded)) {
          framesToComposite.add(checkNotNull(secondaryFrameToComposite));
          selected = true;
          break;
        }
      }
      if (!selected) return ImmutableList.of();
    }
    ImmutableList<CompositorFrame> framesToCompositeList = framesToComposite.build();

    // Registration determines timestamp cadence; input identity determines
    // stacking. Keep these independent when the background is registered first
    // but has the highest compositor input ID.
    java.util.ArrayList<CompositorFrame> ordered = new java.util.ArrayList<>(framesToCompositeList);
    ordered.sort(java.util.Comparator.comparingInt(frame -> frame.inputIndex));
    return ImmutableList.copyOf(ordered);
  }

  private synchronized void releaseOutputTextureInternal(long presentationTimeUs)
      throws VideoFrameProcessingException, GlUtil.GlException {
    while (outputTexturePool.freeTextureCount() < outputTexturePool.capacity()
        && outputTextureTimestamps.element() <= presentationTimeUs) {
      outputTexturePool.freeTexture();
      outputTextureTimestamps.remove();
      GlUtil.deleteSyncObject(syncObjects.remove());
    }
    maybeComposite();
  }

  private void releaseGlObjects() {
    try {
      // Textures belong to upstream processors; abandon references before their release.
      for (int i = 0; i < inputSources.size(); i++) {
        inputSources.valueAt(i).frameInfos.clear();
      }
      while (!syncObjects.isEmpty()) {
        GlUtil.deleteSyncObject(syncObjects.remove());
      }
      outputTextureTimestamps.clear();
      compositorGlProgram.release();
      outputTexturePool.deleteAllTextures();
      if (eglDisplay != null && placeholderEglSurface != null) GlUtil.destroyEglSurface(eglDisplay, placeholderEglSurface);
    } catch (GlUtil.GlException e) {
      Log.e(TAG, "Error releasing GL resources", e);
    } finally {
      try {
        if (eglDisplay != null) glObjectsProvider.release(eglDisplay);
      } catch (GlUtil.GlException e) {
        Log.e(TAG, "Error releasing GL objects", e);
      }
    }
  }

  /** Holds information on an input source. */
  private static final class InputSource {
    /**
     * A queue of {link CompositorFrame}s, monotonically increasing in order of {@code
     * presentationTimeUs} values.
     */
    public final Queue<CompositorFrame> frameInfos;

    public boolean isInputEnded;
    public long completedThroughUs = Long.MIN_VALUE;

    public InputSource() {
      frameInfos = new ArrayDeque<>();
    }
  }

}
