/*
 * Copyright 2026 The Video Editor Project
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

import android.content.Context;
import android.os.Handler;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.VideoSink;
import java.util.ArrayList;

/**
 * Creates renderers for the overlay (picture-in-picture) visual sequences of a
 * preview composition — the one thing upstream Media3 1.5.1's package-private
 * {@code PreviewSequenceRenderersFactory} cannot do, because it wires secondary
 * sequences to audio-only renderers.
 *
 * Each overlay sequence is played by its own internal ExoPlayer; this factory
 * gives that player:
 *
 * <ul>
 *   <li>a video renderer connected to the supplied preview sink (independent
 *       compositor input routing is still required),
 *   <li>an image renderer for photo overlays using the same external image
 *       decoder the primary sequence uses,
 *   <li>an audio renderer that mixes the overlay's soundtrack into the shared
 *       playback audio graph through a per-sequence input.
 * </ul>
 *
 * Timing is already handled upstream: the patched player feeds each secondary
 * player's media source the same composition-aligned timestamps the audio-only
 * path uses (explicitly padded to the primary sequence's duration), so decoded
 * overlay frames arrive at the compositor stamped in project time.
 *
 * The class lives in the {@code androidx.media3.transformer} package because
 * the shared audio graph sink ({@code PreviewPlaybackAudioGraphWrapper.createInput})
 * is package-private upstream; it is original code, not a copy of
 * PreviewSequenceRenderersFactory, and is pinned to Media3 1.5.1 for the same reason
 * as {@code PatchedCompositionPlayer}.
 */
@UnstableApi
final class OverlaySequenceRenderersFactory implements RenderersFactory {

  private final Context context;
  private final EditedMediaItemSequence sequence;
  private final PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper;
  private final VideoSink videoSink;
  private final ImageDecoder.Factory imageDecoderFactory;
  private final int inputIndex;

  OverlaySequenceRenderersFactory(
      Context context,
      EditedMediaItemSequence sequence,
      PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      VideoSink videoSink,
      ImageDecoder.Factory imageDecoderFactory,
      int inputIndex) {
    this.context = context;
    this.sequence = sequence;
    this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    this.videoSink = videoSink;
    this.imageDecoderFactory = imageDecoderFactory;
    this.inputIndex = inputIndex;
  }

  @Override
  public Renderer[] createRenderers(
      Handler eventHandler,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    ArrayList<Renderer> renderers = new ArrayList<>();
    // Reuse Media3's sequence-aware audio renderer. It initializes the audio
    // graph sink on stream changes and seeks, maps edited-item timestamps, and
    // drains the shared graph. A plain codec renderer omits that lifecycle.
    Renderer[] audioRenderers =
        PreviewSequenceRenderersFactory.createForAudio(
                context, sequence, playbackAudioGraphWrapper, inputIndex)
            .createRenderers(
                eventHandler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput);
    java.util.Collections.addAll(renderers, audioRenderers);
    // The supplied sink is currently shared with the primary sequence. This
    // does not establish an independent compositor input; multi-input preview
    // remains experimental until per-sequence sink routing is verified.
    renderers.add(
        new MediaCodecVideoRenderer(
            context,
            androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory.getDefault(context),
            MediaCodecSelector.DEFAULT,
            /* allowedJoiningTimeMs= */ 0,
            /* enableDecoderFallback= */ false,
            eventHandler,
            videoRendererEventListener,
            /* maxDroppedFramesToNotify= */ 50,
            /* assumedMinimumCodecOperatingRate= */ -1f,
            videoSink));
    // Photo overlays use the external loader path and the supplied sink.
    renderers.add(new OverlayImageRenderer(sequence, imageDecoderFactory, videoSink));
    return renderers.toArray(new Renderer[0]);
  }
}
