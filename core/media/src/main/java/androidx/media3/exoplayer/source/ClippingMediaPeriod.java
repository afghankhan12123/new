/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.StreamKey;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.ClippingMediaSource.IllegalClippingException;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import java.io.IOException;
import java.util.List;

/**
 * Wraps a {@link MediaPeriod} and clips its {@link SampleStream}s to provide a subsequence of their
 * samples.
 */
@UnstableApi
public final class ClippingMediaPeriod implements MediaPeriod, MediaPeriod.Callback {

  /** The {@link MediaPeriod} wrapped by this clipping media period. */
  public final MediaPeriod mediaPeriod;

  @Nullable private MediaPeriod.Callback callback;
  private @NullableType ClippingSampleStream[] sampleStreams;
  private long pendingInitialDiscontinuityPositionUs;
  /* package */ long startUs;
  /* package */ long endUs;
  @Nullable private IllegalClippingException clippingError;
  private boolean videoReorderDrainEnabled;
  @Nullable private androidx.media3.common.MediaItem reorderMediaItem;

  /** Enabled by the transition graph's media source factory before preparation. */
  public void enableVideoReorderDrain(androidx.media3.common.MediaItem mediaItem) { videoReorderDrainEnabled = true; reorderMediaItem = mediaItem; }

  /**
   * Creates a new clipping media period that provides a clipped view of the specified {@link
   * MediaPeriod}'s sample streams.
   *
   * <p>If the start point is guaranteed to be a key frame, pass {@code false} to {@code
   * enableInitialPositionDiscontinuity} to suppress an initial discontinuity when the period is
   * first read from.
   *
   * @param mediaPeriod The media period to clip.
   * @param enableInitialDiscontinuity Whether the initial discontinuity should be enabled.
   * @param startUs The clipping start time, in microseconds.
   * @param endUs The clipping end time, in microseconds, or {@link C#TIME_END_OF_SOURCE} to
   *     indicate the end of the period.
   */
  public ClippingMediaPeriod(
      MediaPeriod mediaPeriod, boolean enableInitialDiscontinuity, long startUs, long endUs) {
    this.mediaPeriod = mediaPeriod;
    sampleStreams = new ClippingSampleStream[0];
    pendingInitialDiscontinuityPositionUs = enableInitialDiscontinuity ? startUs : C.TIME_UNSET;
    this.startUs = startUs;
    this.endUs = endUs;
  }

  /**
   * Updates the clipping start/end times for this period, in microseconds.
   *
   * @param startUs The clipping start time, in microseconds.
   * @param endUs The clipping end time, in microseconds, or {@link C#TIME_END_OF_SOURCE} to
   *     indicate the end of the period.
   */
  public void updateClipping(long startUs, long endUs) {
    this.startUs = startUs;
    this.endUs = endUs;
  }

  /**
   * Sets a clipping error detected by the media source so that it can be thrown as a period error
   * at the next opportunity.
   *
   * @param clippingError The clipping error.
   */
  public void setClippingError(IllegalClippingException clippingError) {
    this.clippingError = clippingError;
  }

  @Override
  public void prepare(MediaPeriod.Callback callback, long positionUs) {
    this.callback = callback;
    mediaPeriod.prepare(this, positionUs);
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    if (clippingError != null) {
      throw clippingError;
    }
    mediaPeriod.maybeThrowPrepareError();
  }

  @Override
  public List<StreamKey> getStreamKeys(List<ExoTrackSelection> trackSelections) {
    return mediaPeriod.getStreamKeys(trackSelections);
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return mediaPeriod.getTrackGroups();
  }

  @Override
  public long selectTracks(
      @NullableType ExoTrackSelection[] selections,
      boolean[] mayRetainStreamFlags,
      @NullableType SampleStream[] streams,
      boolean[] streamResetFlags,
      long positionUs) {
    sampleStreams = new ClippingSampleStream[streams.length];
    @NullableType SampleStream[] childStreams = new SampleStream[streams.length];
    for (int i = 0; i < streams.length; i++) {
      sampleStreams[i] = (ClippingSampleStream) streams[i];
      childStreams[i] = sampleStreams[i] != null ? sampleStreams[i].childStream : null;
    }
    long enablePositionUs =
        mediaPeriod.selectTracks(
            selections, mayRetainStreamFlags, childStreams, streamResetFlags, positionUs);
    pendingInitialDiscontinuityPositionUs =
        isPendingInitialDiscontinuity()
                && positionUs == startUs
                && shouldKeepInitialDiscontinuity(startUs, selections)
            ? enablePositionUs
            : C.TIME_UNSET;
    Assertions.checkState(
        enablePositionUs == positionUs
            || (enablePositionUs >= startUs
                && (endUs == C.TIME_END_OF_SOURCE || enablePositionUs <= endUs)));
    for (int i = 0; i < streams.length; i++) {
      if (childStreams[i] == null) {
        sampleStreams[i] = null;
      } else if (sampleStreams[i] == null || sampleStreams[i].childStream != childStreams[i]) {
        sampleStreams[i] = new ClippingSampleStream(childStreams[i]);
      }
      // Loading can run ahead of renderer enablement/its first format read.
      // Qualify the selected codec now so a prefetched AVC period cannot
      // advertise EOS before its bounded reference drain has even started.
      if (sampleStreams[i] != null && selections[i] != null) {
        sampleStreams[i].reorderAwareVideo = videoReorderDrainEnabled
            && MimeTypes.VIDEO_H264.equals(selections[i].getSelectedFormat().sampleMimeType);
      }
      streams[i] = sampleStreams[i];
    }
    return enablePositionUs;
  }

  @Override
  public void discardBuffer(long positionUs, boolean toKeyframe) {
    mediaPeriod.discardBuffer(positionUs, toKeyframe);
  }

  @Override
  public void reevaluateBuffer(long positionUs) {
    mediaPeriod.reevaluateBuffer(positionUs);
  }

  @Override
  public long readDiscontinuity() {
    if (isPendingInitialDiscontinuity()) {
      long initialDiscontinuityUs = pendingInitialDiscontinuityPositionUs;
      pendingInitialDiscontinuityPositionUs = C.TIME_UNSET;
      // Always read an initial discontinuity from the child, and use it if set.
      long childDiscontinuityUs = readDiscontinuity();
      return childDiscontinuityUs != C.TIME_UNSET ? childDiscontinuityUs : initialDiscontinuityUs;
    }
    long discontinuityUs = mediaPeriod.readDiscontinuity();
    if (discontinuityUs == C.TIME_UNSET) {
      return C.TIME_UNSET;
    }
    Assertions.checkState(discontinuityUs >= startUs);
    Assertions.checkState(endUs == C.TIME_END_OF_SOURCE || discontinuityUs <= endUs);
    return discontinuityUs;
  }

  @Override
  public long getBufferedPositionUs() {
    long bufferedPositionUs = mediaPeriod.getBufferedPositionUs();
    if (bufferedPositionUs == C.TIME_END_OF_SOURCE
        || (endUs != C.TIME_END_OF_SOURCE && bufferedPositionUs >= endUs && !needsVideoPostrollLoading())) {
      return C.TIME_END_OF_SOURCE;
    }
    return bufferedPositionUs;
  }

  @Override
  public long seekToUs(long positionUs) {
    pendingInitialDiscontinuityPositionUs = C.TIME_UNSET;
    for (@Nullable ClippingSampleStream sampleStream : sampleStreams) {
      if (sampleStream != null) {
        sampleStream.clearSentEos();
      }
    }
    long seekUs = mediaPeriod.seekToUs(positionUs);
    Assertions.checkState(
        seekUs == positionUs
            || (seekUs >= startUs && (endUs == C.TIME_END_OF_SOURCE || seekUs <= endUs)));
    return seekUs;
  }

  @Override
  public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
    if (positionUs == startUs) {
      // Never adjust seeks to the start of the clipped view.
      return startUs;
    }
    SeekParameters clippedSeekParameters = clipSeekParameters(positionUs, seekParameters);
    return mediaPeriod.getAdjustedSeekPositionUs(positionUs, clippedSeekParameters);
  }

  @Override
  public long getNextLoadPositionUs() {
    long nextLoadPositionUs = mediaPeriod.getNextLoadPositionUs();
    if (nextLoadPositionUs == C.TIME_END_OF_SOURCE
        || (endUs != C.TIME_END_OF_SOURCE && nextLoadPositionUs >= endUs && !needsVideoPostrollLoading())) {
      return C.TIME_END_OF_SOURCE;
    }
    return nextLoadPositionUs;
  }

  private boolean needsVideoPostrollLoading() {
    for (ClippingSampleStream stream : sampleStreams) {
      if (stream != null && stream.reorderAwareVideo && !stream.sentEos
          && stream.consecutivePostrollSamples < ClippingSampleStream.AVC_MAX_REORDER_PICTURES) return true;
    }
    return false;
  }

  @Override
  public boolean continueLoading(LoadingInfo loadingInfo) {
    return mediaPeriod.continueLoading(loadingInfo);
  }

  @Override
  public boolean isLoading() {
    return mediaPeriod.isLoading();
  }

  // MediaPeriod.Callback implementation.

  @Override
  public void onPrepared(MediaPeriod mediaPeriod) {
    if (clippingError != null) {
      return;
    }
    Assertions.checkNotNull(callback).onPrepared(this);
  }

  @Override
  public void onContinueLoadingRequested(MediaPeriod source) {
    Assertions.checkNotNull(callback).onContinueLoadingRequested(this);
  }

  /* package */ boolean isPendingInitialDiscontinuity() {
    return pendingInitialDiscontinuityPositionUs != C.TIME_UNSET;
  }

  private SeekParameters clipSeekParameters(long positionUs, SeekParameters seekParameters) {
    long toleranceBeforeUs =
        Util.constrainValue(
            seekParameters.toleranceBeforeUs, /* min= */ 0, /* max= */ positionUs - startUs);
    long toleranceAfterUs =
        Util.constrainValue(
            seekParameters.toleranceAfterUs,
            /* min= */ 0,
            /* max= */ endUs == C.TIME_END_OF_SOURCE ? Long.MAX_VALUE : endUs - positionUs);
    if (toleranceBeforeUs == seekParameters.toleranceBeforeUs
        && toleranceAfterUs == seekParameters.toleranceAfterUs) {
      return seekParameters;
    } else {
      return new SeekParameters(toleranceBeforeUs, toleranceAfterUs);
    }
  }

  private static boolean shouldKeepInitialDiscontinuity(
      long startUs, @NullableType ExoTrackSelection[] selections) {
    // If the clipping start position is non-zero, the clipping sample streams will adjust
    // timestamps on buffers they read from the unclipped sample streams. These adjusted buffer
    // timestamps can be negative, because sample streams provide buffers starting at a key-frame,
    // which may be before the clipping start point. When the renderer reads a buffer with a
    // negative timestamp, its offset timestamp can jump backwards compared to the last timestamp
    // read in the previous period. Renderer implementations may not allow this, so we signal a
    // discontinuity which resets the renderers before they read the clipping sample stream.
    // However, for tracks where all samples are sync samples, we assume they have random access
    // seek behaviour and do not need an initial discontinuity to reset the renderer.
    if (startUs != 0) {
      for (ExoTrackSelection trackSelection : selections) {
        if (trackSelection != null) {
          Format selectedFormat = trackSelection.getSelectedFormat();
          if (!MimeTypes.allSamplesAreSyncSamples(
              selectedFormat.sampleMimeType, selectedFormat.codecs)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** Wraps a {@link SampleStream} and clips its samples. */
  private final class ClippingSampleStream implements SampleStream {

    public final SampleStream childStream;

    private boolean sentEos;
    private boolean reorderAwareVideo;
    private int consecutivePostrollSamples;
    // AVC limits its decoded picture buffer to sixteen frames (H.264 A.3.1).
    // That permits at most thirty-two separately coded field pictures. Use
    // the field-picture bound so progressive and interlaced AVC are safe.
    // Decode those references, but never present them.
    private static final int AVC_MAX_REORDER_PICTURES = 32;

    public ClippingSampleStream(SampleStream childStream) {
      this.childStream = childStream;
    }

    public void clearSentEos() {
      sentEos = false;
      consecutivePostrollSamples = 0;
    }

    @Override
    public boolean isReady() {
      return !isPendingInitialDiscontinuity() && childStream.isReady();
    }

    @Override
    public void maybeThrowError() throws IOException {
      childStream.maybeThrowError();
    }

    @Override
    public int readData(
        FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
      VideoClipPacket.discard(buffer);
      if (isPendingInitialDiscontinuity()) {
        return C.RESULT_NOTHING_READ;
      }
      if (sentEos || (reorderAwareVideo && consecutivePostrollSamples >= AVC_MAX_REORDER_PICTURES)) {
        sentEos = true;
        buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
        return C.RESULT_BUFFER_READ;
      }
      long bufferedPositionUs = reorderAwareVideo ? mediaPeriod.getBufferedPositionUs() : getBufferedPositionUs();
      @ReadDataResult int result = childStream.readData(formatHolder, buffer, readFlags);
      if (result == C.RESULT_BUFFER_READ && buffer.isEndOfStream() && (readFlags & FLAG_PEEK) == 0) sentEos = true;
      if (result == C.RESULT_FORMAT_READ) {
        Format format = Assertions.checkNotNull(formatHolder.format);
        reorderAwareVideo = videoReorderDrainEnabled && MimeTypes.VIDEO_H264.equals(format.sampleMimeType);
        if (reorderAwareVideo && endUs != C.TIME_END_OF_SOURCE) {
          VideoClipEndMetadata entry = new VideoClipEndMetadata(endUs);
          androidx.media3.common.Metadata metadata = format.metadata == null
              ? new androidx.media3.common.Metadata(entry) : format.metadata.copyWithAppendedEntries(entry);
          format = format.buildUpon().setMetadata(metadata).build();
          formatHolder.format = format;
        }
        if (format.encoderDelay != 0 || format.encoderPadding != 0) {
          // Clear gapless playback metadata if the start/end points don't match the media.
          int encoderDelay = startUs != 0 ? 0 : format.encoderDelay;
          int encoderPadding = endUs != C.TIME_END_OF_SOURCE ? 0 : format.encoderPadding;
          formatHolder.format =
              format
                  .buildUpon()
                  .setEncoderDelay(encoderDelay)
                  .setEncoderPadding(encoderPadding)
                  .build();
        }
        return C.RESULT_FORMAT_READ;
      }
      boolean clipBuffer = result == C.RESULT_BUFFER_READ && buffer.timeUs >= endUs;
      if (endUs != C.TIME_END_OF_SOURCE && reorderAwareVideo
          && result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        if ((readFlags & FLAG_PEEK) == 0) VideoClipPacket.mark(buffer,
            Assertions.checkNotNull(reorderMediaItem), buffer.timeUs, startUs, endUs);
        if (buffer.timeUs < endUs) {
          if ((readFlags & FLAG_PEEK) == 0) consecutivePostrollSamples = 0;
        } else if (consecutivePostrollSamples < AVC_MAX_REORDER_PICTURES) {
          clipBuffer = false;
          if ((readFlags & FLAG_PEEK) == 0) consecutivePostrollSamples++;
        }
        if (buffer.timeUs >= endUs - 150_000 && buffer.timeUs <= endUs + 150_000) {
          com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(ClippingMediaPeriod.this,
              "clippingRead", System.identityHashCode(this), C.TIME_UNSET,
              "buffer=" + System.identityHashCode(buffer) + " sourcePts=" + buffer.timeUs + " start=" + startUs + " end=" + endUs
              + " decodeOnly=" + (buffer.timeUs >= endUs) + " terminate=" + clipBuffer);
        }
      }
      if (endUs != C.TIME_END_OF_SOURCE
          && ((result == C.RESULT_BUFFER_READ && clipBuffer)
              || (result == C.RESULT_NOTHING_READ
                  && bufferedPositionUs == C.TIME_END_OF_SOURCE
                  && !buffer.waitingForKeys))) {
        buffer.clear();
        buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
        sentEos = true;
        return C.RESULT_BUFFER_READ;
      }
      return result;
    }

    @Override
    public int skipData(long positionUs) {
      if (isPendingInitialDiscontinuity()) {
        return C.RESULT_NOTHING_READ;
      }
      return childStream.skipData(positionUs);
    }
  }
}
