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
 *
 * Adapted from Media3 1.5.1 SequenceRenderersFactory.SequenceImageRenderer
 * (Apache-2.0). The upstream class is package-private and its composition-time
 * offset helper assumes the primary sequence layout; this variant feeds an
 * overlay sequence into the shared multi-input preview video sink and keeps
 * the same timestamp-offset math inline.
 */
package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;

import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.image.ImageRenderer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.VideoSink;
import com.google.common.collect.ImmutableList;

/**
 * Feeds decoded overlay stills into the shared preview {@link VideoSink} at the
 * overlay sequence's graph input, with the same composition-time alignment the
 * upstream primary-sequence image renderer applies.
 */
@UnstableApi
final class OverlayImageRenderer extends ImageRenderer {

  /** The frame rate used for still-image overlay frames, matching the export image asset loader. */
  private static final float IMAGE_FRAME_RATE_FPS = 30f;

  private final EditedMediaItemSequence sequence;
  private final VideoSink videoSink;

  private ImmutableList<Effect> videoEffects = ImmutableList.of();
  @Nullable private ConstantRateTimestampIterator timestampIterator;
  @Nullable private EditedMediaItem editedMediaItem;
  private boolean inputStreamPending;
  private long streamStartPositionUs = C.TIME_UNSET;
  private boolean mayRenderStartOfStream;
  private long offsetToCompositionTimeUs;

  OverlayImageRenderer(
      EditedMediaItemSequence sequence, ImageDecoder.Factory imageDecoderFactory, VideoSink videoSink) {
    super(imageDecoderFactory, ImageOutput.NO_OP);
    this.sequence = sequence;
    this.videoSink = videoSink;
  }

  @Override
  protected void onEnabled(boolean joinRenderers, boolean mayRenderStartOfStream)
      throws ExoPlaybackException {
    super.onEnabled(joinRenderers, mayRenderStartOfStream);
    this.mayRenderStartOfStream = mayRenderStartOfStream;
    videoSink.onRendererEnabled(mayRenderStartOfStream);
    if (!videoSink.isInitialized()) {
      Format format = new Format.Builder().build();
      try {
        videoSink.initialize(format);
      } catch (VideoSink.VideoSinkException e) {
        throw createRendererException(e, format, 7000);
      }
    }
    videoSink.setListener(VideoSink.Listener.NO_OP, Runnable::run);
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
      return videoSink.isReady(super.isReady());
    }
    return super.isReady();
  }

  @Override
  protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
    videoSink.flush(/* reset= */ true);
    super.onPositionReset(positionUs, joining);
    timestampIterator = createTimestampIterator(positionUs);
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
      Format[] formats, long startPositionUs, long offsetUs, MediaSource.MediaPeriodId mediaPeriodId)
      throws ExoPlaybackException {
    checkState(getTimeline().getWindowCount() == 1);
    super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    streamStartPositionUs = startPositionUs;
    int periodIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
    editedMediaItem = sequence.editedMediaItems.get(periodIndex);
    offsetToCompositionTimeUs = getOffsetToCompositionTimeUs(sequence, periodIndex, offsetUs);
    timestampIterator = createTimestampIterator(startPositionUs);
    videoEffects = editedMediaItem.effects.videoEffects;
    inputStreamPending = true;
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    super.render(positionUs, elapsedRealtimeUs);
    try {
      videoSink.render(positionUs, elapsedRealtimeUs);
    } catch (VideoSink.VideoSinkException e) {
      throw createRendererException(e, e.format, 7001);
    }
  }

  @Override
  protected boolean processOutputBuffer(
      long positionUs, long elapsedRealtimeUs, Bitmap bitmap, long presentationTimeUs) {
    if (inputStreamPending) {
      checkState(streamStartPositionUs != C.TIME_UNSET);
      videoSink.setPendingVideoEffects(videoEffects);
      videoSink.setStreamTimestampInfo(
          streamStartPositionUs, getStreamOffsetUs(), offsetToCompositionTimeUs, getLastResetPositionUs());
      videoSink.onInputStreamChanged(
          VideoSink.INPUT_TYPE_BITMAP,
          new Format.Builder()
              .setSampleMimeType("image/raw")
              .setWidth(bitmap.getWidth())
              .setHeight(bitmap.getHeight())
              .setColorInfo(ColorInfo.SRGB_BT709_FULL)
              .setFrameRate(IMAGE_FRAME_RATE_FPS)
              .build());
      inputStreamPending = false;
    }
    return videoSink.handleInputBitmap(bitmap, checkNotNull(timestampIterator));
  }

  private ConstantRateTimestampIterator createTimestampIterator(long positionUs) {
    long streamOffsetUs = getStreamOffsetUs();
    long startOffsetUs = streamOffsetUs + offsetToCompositionTimeUs;
    long positionOffsetUs = positionUs - streamOffsetUs;
    long startTimestampUs = startOffsetUs + positionOffsetUs;
    long endTimestampUs = startOffsetUs + checkNotNull(editedMediaItem).getPresentationDurationUs();
    return new ConstantRateTimestampIterator(startTimestampUs, endTimestampUs, IMAGE_FRAME_RATE_FPS);
  }

  /**
   * Composition-time offset of the given period: its own position within the
   * sequence minus the period's leading clip, matching the upstream primary
   * sequence's math (there is no shared silence wrapper in overlay sequences).
   */
  private static long getOffsetToCompositionTimeUs(
      EditedMediaItemSequence sequence, int periodIndex, long periodPositionOffsetUs) {
    long offset = -periodPositionOffsetUs;
    for (int i = 0; i < periodIndex; i++) {
      offset += sequence.editedMediaItems.get(i).getPresentationDurationUs();
    }
    return offset;
  }
}
