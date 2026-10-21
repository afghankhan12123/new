package com.videoeditor.core.media.preview;

import static org.junit.Assert.*;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.Futures;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies the URI is replayed after EOS without starting another provider load. */
@RunWith(AndroidJUnit4.class)
public class SeekableImageMediaPeriodDeviceTest {
  @Test public void consumedImageSampleIsReadableAfterEverySeek() {
    int[] loads = {0};
    SeekableImageMediaPeriod period = new SeekableImageMediaPeriod(
        Uri.parse("content://fixture/photo"), MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE,
        request -> { loads[0]++; return Futures.immediateFuture(null); });
    period.prepare(new MediaPeriod.Callback() {
      @Override public void onPrepared(MediaPeriod mediaPeriod) {}
      @Override public void onContinueLoadingRequested(MediaPeriod mediaPeriod) {}
    }, 0);
    SampleStream[] streams = new SampleStream[1];
    period.selectTracks(new ExoTrackSelection[] {
        new FixedTrackSelection(period.getTrackGroups().get(0), 0)},
        new boolean[1], streams, new boolean[1], 0);
    FormatHolder holder = new FormatHolder();
    DecoderInputBuffer buffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    for (long position : new long[] {0, 200_000, 400_000, 100_000, 200_000}) {
      assertEquals(position, period.seekToUs(position));
      buffer.clear();
      assertEquals(C.RESULT_FORMAT_READ, streams[0].readData(holder, buffer, 0));
      assertEquals(C.RESULT_BUFFER_READ, streams[0].readData(holder, buffer, 0));
      assertFalse(buffer.isEndOfStream());
      assertEquals(0, buffer.timeUs);
      buffer.clear();
      assertEquals(C.RESULT_BUFFER_READ, streams[0].readData(holder, buffer, 0));
      assertTrue(buffer.isEndOfStream());
    }
    assertEquals(1, loads[0]);
    period.releasePeriod();
  }
}
