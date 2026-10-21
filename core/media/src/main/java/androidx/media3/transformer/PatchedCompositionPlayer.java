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

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.usToMs;
import static java.lang.Math.min;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.PreviewingVideoGraph;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoSize;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.effect.PreviewingSingleInputVideoGraph;
import androidx.media3.effect.TimestampAdjustment;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ExternalLoader;
import androidx.media3.exoplayer.source.FilteringMediaSource;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SilenceMediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.WrappingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.video.MultiInputPlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Player} implementation that plays {@linkplain Composition compositions} of media assets.
 * The {@link Composition} specifies how the assets should be arranged, and the audio and video
 * effects to apply to them.
 *
 * <p>{@code PatchedCompositionPlayer} instances must be accessed from a single application thread. For the
 * vast majority of cases this should be the application's main thread. The thread on which a
 * PatchedCompositionPlayer instance must be accessed can be explicitly specified by passing a {@link
 * Looper} when creating the player. If no {@link Looper} is specified, then the {@link Looper} of
 * the thread that the player is created on is used, or if that thread does not have a {@link
 * Looper}, the {@link Looper} of the application's main thread is used. In all cases the {@link
 * Looper} of the thread from which the player must be accessed can be queried using {@link
 * #getApplicationLooper()}.
 *
 * <p>This player only supports setting the {@linkplain #setRepeatMode(int) repeat mode} as
 * {@linkplain Player#REPEAT_MODE_ALL all} of the {@link Composition}, or {@linkplain
 * Player#REPEAT_MODE_OFF off}.
 */
@UnstableApi
@RestrictTo(LIBRARY_GROUP)
public final class PatchedCompositionPlayer extends SimpleBasePlayer
    implements PreviewCompositionPlayerInternal.Listener,
        MultiInputPlaybackVideoGraphWrapper.Listener,
        SurfaceHolder.Callback {

  /** A builder for {@link PatchedCompositionPlayer} instances. */
  public static final class Builder {
    private boolean transitionClippingEnabled;
    public Builder setTransitionClippingEnabled(boolean enabled) {
      transitionClippingEnabled = enabled;
      return this;
    }
    private final Context context;

    private Looper looper;
    private AudioSink audioSink;
    private ExternalLoader externalImageLoader;
    private ImageDecoder.Factory imageDecoderFactory;
    private Clock clock;
    private PreviewingVideoGraph.Factory previewingVideoGraphFactory;
    private boolean built;

    /**
     * Creates an instance
     *
     * @param context The application context.
     */
    public Builder(Context context) {
      this.context = context.getApplicationContext();
      imageDecoderFactory = ImageDecoder.Factory.DEFAULT;
      clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link Looper} from which the player can be accessed and {@link Player.Listener}
     * callbacks are dispatched too.
     *
     * <p>By default, the builder uses the looper of the thread that calls {@link #build()}.
     *
     * @param looper The {@link Looper}.
     * @return This builder, for convenience.
     */
    
    public Builder setLooper(Looper looper) {
      this.looper = looper;
      return this;
    }

    /**
     * Sets the {@link AudioSink} that will be used to play out audio.
     *
     * <p>By default, a {@link DefaultAudioSink} with its default configuration is used.
     *
     * @param audioSink The {@link AudioSink}.
     * @return This builder, for convenience.
     */
    
    public Builder setAudioSink(AudioSink audioSink) {
      this.audioSink = audioSink;
      return this;
    }

    /**
     * Sets the {@link ExternalLoader} for loading image media items with MIME type set to {@link
     * MimeTypes#APPLICATION_EXTERNALLY_LOADED_IMAGE}. When setting an external loader, also set an
     * {@link ImageDecoder.Factory} with {@link #setImageDecoderFactory(ImageDecoder.Factory)}.
     *
     * <p>By default, the player will not be able to load images with media type of {@link
     * androidx.media3.common.MimeTypes#APPLICATION_EXTERNALLY_LOADED_IMAGE}.
     *
     * @param externalImageLoader The {@link ExternalLoader}.
     * @return This builder, for convenience.
     * @see DefaultMediaSourceFactory#setExternalImageLoader(ExternalLoader)
     */
    
    public Builder setExternalImageLoader(ExternalLoader externalImageLoader) {
      this.externalImageLoader = externalImageLoader;
      return this;
    }

    /**
     * Sets an {@link ImageDecoder.Factory} that will create the {@link ImageDecoder} instances to
     * decode images.
     *
     * <p>By default, {@link ImageDecoder.Factory#DEFAULT} is used.
     *
     * @param imageDecoderFactory The {@link ImageDecoder.Factory}.
     * @return This builder, for convenience.
     */
    
    public Builder setImageDecoderFactory(ImageDecoder.Factory imageDecoderFactory) {
      this.imageDecoderFactory = imageDecoderFactory;
      return this;
    }

    /**
     * Sets the {@link Clock} that will be used by the player.
     *
     * <p>By default, {@link Clock#DEFAULT} is used.
     *
     * @param clock The {@link Clock}.
     * @return This builder, for convenience.
     */
    @VisibleForTesting
    
    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Sets the {@link PreviewingVideoGraph.Factory} that will be used by the player.
     *
     * <p>By default, a {@link PreviewingSingleInputVideoGraph.Factory} is used.
     *
     * @param previewingVideoGraphFactory The {@link PreviewingVideoGraph.Factory}.
     * @return This builder, for convenience.
     */
    @VisibleForTesting
    
    public Builder setPreviewingVideoGraphFactory(
        PreviewingVideoGraph.Factory previewingVideoGraphFactory) {
      this.previewingVideoGraphFactory = previewingVideoGraphFactory;
      return this;
    }

    /**
     * Builds the {@link PatchedCompositionPlayer} instance. Must be called at most once.
     *
     * <p>If no {@link Looper} has been called with {@link #setLooper(Looper)}, then this method
     * must be called within a {@link Looper} thread which is the thread that can access the player
     * instance and where {@link Player.Listener} callbacks are dispatched.
     */
    public PatchedCompositionPlayer build() {
      checkState(!built);
      if (looper == null) {
        looper = checkStateNotNull(Looper.myLooper());
      }
      if (audioSink == null) {
        audioSink = new DefaultAudioSink.Builder(context).build();
      }
      if (previewingVideoGraphFactory == null) {
        previewingVideoGraphFactory = new PreviewingSingleInputVideoGraph.Factory();
      }
      PatchedCompositionPlayer compositionPlayer = new PatchedCompositionPlayer(this);
      built = true;
      return compositionPlayer;
    }
  }

  private static final Commands AVAILABLE_COMMANDS =
      new Commands.Builder()
          .addAll(
              COMMAND_PLAY_PAUSE,
              COMMAND_PREPARE,
              COMMAND_STOP,
              COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
              COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
              COMMAND_SEEK_BACK,
              COMMAND_SEEK_FORWARD,
              COMMAND_GET_CURRENT_MEDIA_ITEM,
              COMMAND_GET_TIMELINE,
              COMMAND_SET_REPEAT_MODE,
              COMMAND_SET_VIDEO_SURFACE,
              COMMAND_GET_VOLUME,
              COMMAND_SET_VOLUME,
              COMMAND_RELEASE)
          .build();

  private static final @Event int[] SUPPORTED_LISTENER_EVENTS =
      new int[] {
        EVENT_PLAYBACK_STATE_CHANGED,
        EVENT_PLAY_WHEN_READY_CHANGED,
        EVENT_PLAYER_ERROR,
        EVENT_POSITION_DISCONTINUITY,
        EVENT_MEDIA_ITEM_TRANSITION,
      };

  private static final String TAG = "PatchedCompositionPlayer";

  private final Context context;
  private final Clock clock;
  private final HandlerWrapper applicationHandler;
  private final List<ExoPlayer> players;
  private final AudioSink finalAudioSink;
  @Nullable private final ExternalLoader externalImageLoader;
  private final ImageDecoder.Factory imageDecoderFactory;
  private final PreviewingVideoGraph.Factory previewingVideoGraphFactory;
  private boolean releaseInProgress;
  private final HandlerWrapper compositionInternalListenerHandler;

  private HandlerThread playbackThread;
  @Nullable private PreviewPlaybackAudioGraphWrapper playbackAudioGraph;
  @Nullable private HandlerWrapper audioClockHandler;

  private void setAudioClockPlaying(boolean playing) {
    PreviewPlaybackAudioGraphWrapper graph = playbackAudioGraph;
    HandlerWrapper handler = audioClockHandler;
    if (graph != null && handler != null) handler.post(() -> graph.setPlaying(playing));
  }
  private PreviewCompositionPlayerInternal compositionPlayerInternal;
  @Nullable private MultiInputPlaybackVideoGraphWrapper previewVideoGraphWrapper;

  public boolean hasRenderedPrimaryFinalOutput() {
    return previewVideoGraphWrapper != null && previewVideoGraphWrapper.hasRenderedPrimaryFinalOutput();
  }

  public int getPendingPreviewOutputFrameCount() {
    return previewVideoGraphWrapper == null ? 0 : previewVideoGraphWrapper.getPendingOutputFrameCount();
  }

  /** Decoded compositor output timestamp; independent of the reported player clock. */
  public long getLastRenderedProjectTimeUs() {
    return previewVideoGraphWrapper == null ? C.TIME_UNSET : previewVideoGraphWrapper.getLastRenderedProjectTimeUs();
  }
  public String getFrameDiagnostics() {
    return previewVideoGraphWrapper == null ? "graph unavailable" : previewVideoGraphWrapper.getFrameDiagnostics();
  }

  private final java.util.concurrent.atomic.AtomicBoolean pendingFrameWork = new java.util.concurrent.atomic.AtomicBoolean();

  /** A paused ExoPlayer may poll at one second even with codec preroll left to drain. */
  public void pumpPendingVideoFrame() {
    if (players.isEmpty() || !pendingFrameWork.compareAndSet(false, true)) return;
    java.util.ArrayList<Renderer> frameRenderers = new java.util.ArrayList<>();
    for (ExoPlayer child : players) {
      for (int i = 0; i < child.getRendererCount(); i++) frameRenderers.add(child.getRenderer(i));
    }
    players.get(0).createMessage((type, payload) -> {
      try {
        for (Renderer renderer : frameRenderers) {
          PreviewSequenceRenderersFactory.renderPendingPausedFrame(renderer);
        }
      } finally { pendingFrameWork.set(false); }
    }).send();
  }

  public boolean hasCompletedPrimaryVideoReset() {
    return previewVideoGraphWrapper != null && previewVideoGraphWrapper.hasCompletedPrimaryVideoReset();
  }
  private ImmutableList<MediaItemData> playlist;
  private Composition composition;
  private Size videoOutputSize;
  private long compositionDurationUs;
  private boolean playWhenReady;
  private final boolean transitionClippingEnabled;
  private @PlayWhenReadyChangeReason int playWhenReadyChangeReason;
  private @RepeatMode int repeatMode;
  private float volume;
  private boolean renderedFirstFrame;
  @Nullable private Object videoOutput;
  @Nullable private PlaybackException playbackException;
  private @Player.State int playbackState;
  @Nullable private SurfaceHolder surfaceHolder;
  @Nullable private Surface displaySurface;
  private boolean repeatingCompositionSeekInProgress;

  private PatchedCompositionPlayer(Builder builder) {
    super(checkNotNull(builder.looper), builder.clock);
    transitionClippingEnabled = builder.transitionClippingEnabled;
    context = builder.context;
    clock = builder.clock;
    applicationHandler = clock.createHandler(builder.looper, /* callback= */ null);
    finalAudioSink = checkNotNull(builder.audioSink);
    externalImageLoader = builder.externalImageLoader;
    imageDecoderFactory = builder.imageDecoderFactory;
    previewingVideoGraphFactory = checkNotNull(builder.previewingVideoGraphFactory);
    compositionInternalListenerHandler = clock.createHandler(builder.looper, /* callback= */ null);
    players = new ArrayList<>();
    compositionDurationUs = C.TIME_UNSET;
    playbackState = STATE_IDLE;
    volume = 1.0f;
  }

  /**
   * Sets the {@link Composition} to play.
   *
   * <p>This method should only be called once.
   *
   * @param composition The {@link Composition} to play. Every {@link EditedMediaItem} in the {@link
   *     Composition} must have its {@link EditedMediaItem#durationUs} set.
   */
  public void setComposition(Composition composition) {
    verifyApplicationThread();
    checkArgument(!composition.sequences.isEmpty());
    checkArgument(!composition.hasGaps());
    checkState(this.composition == null);
    composition = deactivateSpeedAdjustingVideoEffects(composition);

    setCompositionInternal(composition);
    if (videoOutput != null) {
      if (videoOutput instanceof SurfaceHolder) {
        setVideoSurfaceHolderInternal((SurfaceHolder) videoOutput);
      } else if (videoOutput instanceof SurfaceView) {
        SurfaceView surfaceView = (SurfaceView) videoOutput;
        setVideoSurfaceHolderInternal(surfaceView.getHolder());
      } else if (videoOutput instanceof Surface) {
        setVideoSurfaceInternal((Surface) videoOutput, checkNotNull(videoOutputSize));
      } else {
        throw new IllegalStateException(videoOutput.getClass().toString());
      }
    }
    // Update the composition field at the end after everything else has been set.
    this.composition = composition;
  }

  /** Sets the {@link Surface} and {@link Size} to render to. */
  @VisibleForTesting
  public void setVideoSurface(Surface surface, Size videoOutputSize) {
    videoOutput = surface;
    this.videoOutputSize = videoOutputSize;
    setVideoSurfaceInternal(surface, videoOutputSize);
  }

  // MultiInputPlaybackVideoGraphWrapper.Listener methods. Called on playback thread.

  @Override
  public void onFirstFrameRendered(MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper) {
    applicationHandler.post(
        () -> {
          PatchedCompositionPlayer.this.renderedFirstFrame = true;
          invalidateState();
        });
  }

  @Override
  public void onFrameDropped(MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper) {
    // Do not post to application thread on each dropped frame, because onFrameDropped
    // may be called frequently when resources are already scarce.
  }

  @Override
  public void onVideoSizeChanged(
      MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper, VideoSize videoSize) {
    // TODO: b/328219481 - Report video size change to app.
  }

  @Override
  public void onError(
      MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper,
      VideoFrameProcessingException videoFrameProcessingException) {
    // The error will also be surfaced from the underlying ExoPlayer instance via
    // PlayerListener.onPlayerError, and it will arrive to the composition player twice.
    applicationHandler.post(
        () ->
            maybeUpdatePlaybackError(
                "Error processing video frames",
                videoFrameProcessingException,
                PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED));
  }

  // SurfaceHolder.Callback methods. Called on application thread.

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    videoOutputSize = new Size(holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
    setVideoSurfaceInternal(holder.getSurface(), videoOutputSize);
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    maybeSetOutputSurfaceInfo(width, height);
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    clearVideoSurfaceInternal();
  }

  // SimpleBasePlayer methods

  @Override
  protected State getState() {
    @Player.State int oldPlaybackState = playbackState;
    updatePlaybackState();
    if (oldPlaybackState != playbackState && (playbackState == STATE_ENDED || playbackState == STATE_IDLE)) {
      setAudioClockPlaying(false);
    }
    if (oldPlaybackState != STATE_READY && playbackState == STATE_READY && playWhenReady) {
      setAudioClockPlaying(true);
      for (int i = 0; i < players.size(); i++) {
        players.get(i).setPlayWhenReady(true);
      }
    } else if (oldPlaybackState == STATE_READY
        && playWhenReady
        && playbackState == STATE_BUFFERING) {
      // Only pause players if primary sequence 0 itself is buffering.
      if (!players.isEmpty() && players.get(0).getPlaybackState() == STATE_BUFFERING) {
        setAudioClockPlaying(false);
        for (int i = 0; i < players.size(); i++) {
          players.get(i).setPlayWhenReady(false);
        }
      }
    }
    // TODO: b/328219481 - Report video size change to app.
    State.Builder state =
        new State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaybackState(playbackState)
            .setPlayerError(playbackException)
            .setPlayWhenReady(playWhenReady, playWhenReadyChangeReason)
            .setRepeatMode(repeatMode)
            .setVolume(volume)
            .setContentPositionMs(this::getContentPositionMs)
            .setContentBufferedPositionMs(this::getBufferedPositionMs)
            .setTotalBufferedDurationMs(this::getTotalBufferedDurationMs)
            .setNewlyRenderedFirstFrame(getRenderedFirstFrameAndReset());
    if (repeatingCompositionSeekInProgress) {
      state.setPositionDiscontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION, C.TIME_UNSET);
      repeatingCompositionSeekInProgress = false;
    }
    if (playlist != null) {
      // Update the playlist only after it has been set so that SimpleBasePlayer announces a
      // timeline change with reason TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED.
      state.setPlaylist(playlist);
    }
    return state.build();
  }

  @Override
  protected ListenableFuture<?> handlePrepare() {
    checkStateNotNull(composition, "No composition set");

    if (playbackState != Player.STATE_IDLE) {
      // The player has been prepared already.
      return Futures.immediateVoidFuture();
    }
    for (int i = 0; i < players.size(); i++) {
      players.get(i).prepare();
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
    this.playWhenReady = playWhenReady;
    if (playbackAudioGraph != null) playbackAudioGraph.setPlaybackRequested(playWhenReady);
    playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST;
    setAudioClockPlaying(playWhenReady && playbackState == STATE_READY);
    if (playbackState == STATE_READY) {
      for (int i = 0; i < players.size(); i++) {
        players.get(i).setPlayWhenReady(playWhenReady);
      }
    } // else, wait until all players are ready.
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetRepeatMode(@RepeatMode int repeatMode) {
    // Composition is treated as a single item, so only supports being repeated as a whole.
    checkArgument(repeatMode != REPEAT_MODE_ONE);
    this.repeatMode = repeatMode;
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleStop() {
    for (int i = 0; i < players.size(); i++) {
      players.get(i).stop();
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleRelease() {
    if (composition == null) {
      return Futures.immediateVoidFuture();
    }

    checkState(checkStateNotNull(playbackThread).isAlive());
    releaseInProgress = true;
    // Release the players first so that they stop rendering.
    for (int i = 0; i < players.size(); i++) {
      players.get(i).release();
    }
    checkStateNotNull(compositionPlayerInternal).release();
    removeSurfaceCallbacks();
    // Remove any queued callback from the internal player.
    compositionInternalListenerHandler.removeCallbacksAndMessages(/* token= */ null);
    displaySurface = null;
    playbackAudioGraph = null;
    audioClockHandler = null;
    checkStateNotNull(playbackThread).quitSafely();
    applicationHandler.removeCallbacksAndMessages(/* token= */ null);
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
    checkArgument(Util.areEqual(videoOutput, this.videoOutput));

    this.videoOutput = null;
    if (composition == null) {
      return Futures.immediateVoidFuture();
    }
    removeSurfaceCallbacks();
    clearVideoSurfaceInternal();
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
    if (!(videoOutput instanceof SurfaceHolder || videoOutput instanceof SurfaceView)) {
      throw new UnsupportedOperationException(videoOutput.getClass().toString());
    }
    this.videoOutput = videoOutput;
    if (composition == null) {
      return Futures.immediateVoidFuture();
    }
    if (videoOutput instanceof SurfaceHolder) {
      setVideoSurfaceHolderInternal((SurfaceHolder) videoOutput);
    } else {
      setVideoSurfaceHolderInternal(((SurfaceView) videoOutput).getHolder());
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetVolume(float volume) {
    this.volume = Util.constrainValue(volume, /* min= */ 0.0f, /* max= */ 1.0f);
    finalAudioSink.setVolume(this.volume);
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSeek(
      int mediaItemIndex, long positionMs, @Player.Command int seekCommand) {
    PreviewCompositionPlayerInternal compositionPlayerInternal =
        checkStateNotNull(this.compositionPlayerInternal);
    if (previewVideoGraphWrapper != null) previewVideoGraphWrapper.setRequestedProjectPositionUs(
        positionMs == C.TIME_UNSET ? 0 : androidx.media3.common.util.Util.msToUs(positionMs));
    compositionPlayerInternal.startSeek(positionMs);
    // ExoPlayer deliberately skips same-millisecond seeks. A preview refresh has
    // already retired graph textures, so it requires a real renderer reset.
    // Queue a bounded adjacent reset and the exact target under the SAME barrier;
    // neither intermediate frame may enter the graph.
    for (int i = 0; i < players.size(); i++) {
      ExoPlayer child = players.get(i);
      if (positionMs != C.TIME_UNSET && Math.abs(child.getCurrentPosition() - positionMs) <= 1
          && (child.getPlaybackState() == Player.STATE_READY
              || child.getPlaybackState() == Player.STATE_BUFFERING)) {
        child.seekTo(positionMs >= 2 ? positionMs - 2 : positionMs + 2);
      }
    }

    for (int i = 0; i < players.size(); i++) {
      players.get(i).seekTo(positionMs);
    }
    compositionPlayerInternal.endSeek();
    return Futures.immediateVoidFuture();
  }

  // PreviewCompositionPlayerInternal.Listener methods

  @Override
  public void onError(String message, Exception cause, int errorCode) {
    maybeUpdatePlaybackError(message, cause, errorCode);
  }

  // Internal methods

  private static Composition deactivateSpeedAdjustingVideoEffects(Composition composition) {
    List<EditedMediaItemSequence> newSequences = new ArrayList<>();
    for (EditedMediaItemSequence sequence : composition.sequences) {
      List<EditedMediaItem> newEditedMediaItems = new ArrayList<>();
      for (EditedMediaItem editedMediaItem : sequence.editedMediaItems) {
        ImmutableList<Effect> videoEffects = editedMediaItem.effects.videoEffects;
        List<Effect> newVideoEffects = new ArrayList<>();
        for (Effect videoEffect : videoEffects) {
          if (videoEffect instanceof TimestampAdjustment) {
            newVideoEffects.add(
                new InactiveTimestampAdjustment(((TimestampAdjustment) videoEffect).speedProvider));
          } else {
            newVideoEffects.add(videoEffect);
          }
        }
        newEditedMediaItems.add(
            editedMediaItem
                .buildUpon()
                .setEffects(new Effects(editedMediaItem.effects.audioProcessors, newVideoEffects))
                .build());
      }
      newSequences.add(
          new EditedMediaItemSequence.Builder(newEditedMediaItems)
              .setIsLooping(sequence.isLooping)
              .build());
    }
    return composition.buildUpon().setSequences(newSequences).build();
  }

  private void updatePlaybackState() {
    if (players.isEmpty() || playbackException != null) {
      playbackState = STATE_IDLE;
      return;
    }

    // Sequence 0 is the primary sequence and master clock owner.
    // Secondary visual sequences (like transition lanes or PiP pads) must not drag the
    // composition into STATE_BUFFERING while sequence 0 is ready.
    @Player.State int primaryState = players.get(0).getPlaybackState();
    if (primaryState == STATE_IDLE) {
      playbackState = STATE_IDLE;
      return;
    }

    int endedCount = 0;
    for (int i = 0; i < players.size(); i++) {
      if (players.get(i).getPlaybackState() == STATE_ENDED) {
        endedCount++;
      }
    }
    if (endedCount == players.size()) {
      playbackState = STATE_ENDED;
      return;
    }

    if (primaryState == STATE_BUFFERING) {
      playbackState = STATE_BUFFERING;
      return;
    }

    playbackState = STATE_READY;
  }

  @SuppressWarnings("VisibleForTests") // Calls ExoPlayer.Builder.setClock()
  private void setCompositionInternal(Composition composition) {
    compositionDurationUs = getCompositionDurationUs(composition);
    playbackThread = new HandlerThread("CompositionPlaybackThread", Process.THREAD_PRIORITY_AUDIO);
    playbackThread.start();
    // Create the audio and video composition components now in order to setup the audio and video
    // pipelines. Once this method returns, further access to the audio and video graph wrappers
    // must done on the playback thread only, to ensure related components are accessed from one
    // thread only.
    PreviewPlaybackAudioGraphWrapper playbackAudioGraphWrapper =
        new PreviewPlaybackAudioGraphWrapper(
            new DefaultAudioMixer.Factory(),
            composition.effects.audioProcessors,
            checkNotNull(finalAudioSink), transitionClippingEnabled,
            !transitionClippingEnabled && hasSingleAudiblePrimary(composition));
    // Export registers standalone audio before the visual background. Mirror
    // that format choice when its first item is real audio at project zero.
    // Gap-padded sequences retain the existing primary-first registration, so
    // waiting for a future item can never block the graph's leading silence.
    for (int index = 1; index < composition.sequences.size(); index++) {
      EditedMediaItem first = composition.sequences.get(index).editedMediaItems.get(0);
      if (first.removeVideo && !first.removeAudio
          && first.mediaItem.localConfiguration != null
          && !first.mediaItem.localConfiguration.uri.toString().equals("asset:///preview-silence.wav")) {
        playbackAudioGraphWrapper.setPreferredFormatInputIndex(index);
        break;
      }
    }
    playbackAudioGraph = playbackAudioGraphWrapper;
    playbackAudioGraphWrapper.setPlaybackRequested(playWhenReady);
    audioClockHandler = clock.createHandler(playbackThread.getLooper(), /* callback= */ null);
    int visualCount = 1;
    for (int i = 1; i < composition.sequences.size(); i++) {
      if (isOverlayVisualSequence(composition.sequences.get(i))) visualCount++;
    }
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context, new CompositionFrameTimingEvaluator(visualCount == 1), /* allowedJoiningTimeMs= */ 0);
    int[] compositorInputs = new int[visualCount];
    // Primary remains sequence zero and audio-clock owner. Lowest compositor
    // input is foreground; visual sequence order is background then ascending z.
    for (int i = 0; i < visualCount; i++) compositorInputs[i] = visualCount - 1 - i;
    MultiInputPlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        new MultiInputPlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setTransitionClippingEnabled(transitionClippingEnabled)
            .setPreviewingVideoGraphFactory(checkNotNull(previewingVideoGraphFactory))
            .setInputIndices(compositorInputs)
            .setCompositionEffects(composition.effects.videoEffects)
            .setClock(clock)
            .build();
    previewVideoGraphWrapper = playbackVideoGraphWrapper;
    com.videoeditor.core.media.preview.PreviewPhaseTrace.counter(
        "player." + System.identityHashCode(this) + ".graphIdentity",
        System.identityHashCode(playbackVideoGraphWrapper));
    playbackVideoGraphWrapper.addListener(this);
    // Establish the owner before child/source construction can fail.
    compositionPlayerInternal =
        new PreviewCompositionPlayerInternal(
            playbackThread.getLooper(),
            clock,
            playbackAudioGraphWrapper,
            playbackVideoGraphWrapper,
            /* listener= */ this,
            compositionInternalListenerHandler);

    long primarySequenceDurationUs =
        getSequenceDurationUs(checkNotNull(composition.sequences.get(0)));
    int visualSequenceIndex = 0;
    for (int i = 0; i < composition.sequences.size(); i++) {
      EditedMediaItemSequence editedMediaItemSequence = composition.sequences.get(i);
      // Overlay sequences keep their video track: their frames must reach the
      // shared multi-input preview graph instead of being dropped by the
      // upstream audio-only secondary path.
      boolean hasVisualTrack = isOverlayVisualSequence(editedMediaItemSequence);
      RenderersFactory sequenceRenderersFactory =
          i == 0
              ? PreviewSequenceRenderersFactory.create(
                  context,
                  editedMediaItemSequence,
                  playbackAudioGraphWrapper,
                  playbackVideoGraphWrapper.getSink(visualSequenceIndex++),
                  imageDecoderFactory,
                  /* inputIndex= */ i,
                  /* enableDecoderFallback= */ transitionClippingEnabled)
              : hasVisualTrack
                  ? PreviewSequenceRenderersFactory.create(
                      context,
                      editedMediaItemSequence,
                      playbackAudioGraphWrapper,
                      playbackVideoGraphWrapper.getSink(visualSequenceIndex++),
                      imageDecoderFactory,
                      /* inputIndex= */ i,
                      /* enableDecoderFallback= */ transitionClippingEnabled)
                  : PreviewSequenceRenderersFactory.createForAudio(
                      context, editedMediaItemSequence, playbackAudioGraphWrapper, /* inputIndex= */ i);
      ExoPlayer.Builder playerBuilder =
          new ExoPlayer.Builder(context)
              .setLooper(getApplicationLooper())
              .setPlaybackLooper(playbackThread.getLooper())
              .setRenderersFactory(sequenceRenderersFactory)
              .setHandleAudioBecomingNoisy(true)
              .setClock(clock);
      // Paused READY children can read ahead across an empty clipped pad.
      // Wake on actual asynchronous codec availability while decoding preroll;
      // the normal one-second READY poll can otherwise starve the longer source.
      if (transitionClippingEnabled) playerBuilder.experimentalSetDynamicSchedulingEnabled(true);

      boolean disableVideoPlayback = false;
      for (int j = 0; j < editedMediaItemSequence.editedMediaItems.size(); j++) {
        if (editedMediaItemSequence.editedMediaItems.get(j).removeVideo) {
          disableVideoPlayback = true;
          break;
        }
      }
      playerBuilder.setTrackSelector(
          new CompositionTrackSelector(context, /* sequenceIndex= */ i, disableVideoPlayback));

      ExoPlayer player = playerBuilder.build();
      player.addListener(new PlayerListener(i));
      player.addAnalyticsListener(new EventLogger());
      final String decoderTracePrefix = "player." + System.identityHashCode(this) + ".sequence." + i;
      player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
        @Override
        public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
            long initializedTimestampMs, long initializationDurationMs) {
          com.videoeditor.core.media.preview.PreviewPhaseTrace.counter(
              decoderTracePrefix + ".decoderInitializedBootMs", initializedTimestampMs);
          com.videoeditor.core.media.preview.PreviewPhaseTrace.counter(
              decoderTracePrefix + ".decoderPreparationMs", initializationDurationMs);
        }
      });
      player.setPauseAtEndOfMediaItems(true);

      if (i == 0 || hasVisualTrack) {
        setPrimaryPlayerSequence(player, editedMediaItemSequence);
      } else {
        setSecondaryPlayerSequence(player, editedMediaItemSequence, primarySequenceDurationUs);
      }

      players.add(player);
      if (i == 0) {
        // Invalidate the player state before initializing the playlist to force SimpleBasePlayer
        // to collect a state while the playlist is null. Consequently, once the playlist is
        // initialized, SimpleBasePlayer will raise a timeline change callback with reason
        // TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED.
        invalidateState();
        playlist = createPlaylist();
      }
    }
    // From here after, composition player accessed the audio and video pipelines via the internal
    // player. The internal player ensures access to the components is done on the playback thread.

  }

  private void setPrimaryPlayerSequence(ExoPlayer player, EditedMediaItemSequence sequence) {
    ConcatenatingMediaSource2.Builder mediaSourceBuilder =
        new ConcatenatingMediaSource2.Builder().useDefaultMediaSourceFactory(context);

    for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
      EditedMediaItem editedMediaItem = sequence.editedMediaItems.get(i);
      checkArgument(editedMediaItem.durationUs != C.TIME_UNSET);
      long durationUs = editedMediaItem.getPresentationDurationUs();
      // Generate silence for primary sequence.
      DefaultMediaSourceFactory defaultMediaSourceFactory = new DefaultMediaSourceFactory(context);
      if (externalImageLoader != null) {
        defaultMediaSourceFactory.setExternalImageLoader(externalImageLoader);
      }
      MediaSource silenceMediaSource =
          new ClippingMediaSource(
              new SilenceMediaSource(editedMediaItem.durationUs),
              editedMediaItem.mediaItem.clippingConfiguration.startPositionUs,
              editedMediaItem.mediaItem.clippingConfiguration.endPositionUs);

      // The MediaSource that loads the MediaItem
      MediaSource mainMediaSource =
          androidx.media3.common.MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE.equals(
                  checkNotNull(editedMediaItem.mediaItem.localConfiguration).mimeType)
              ? new com.videoeditor.core.media.preview.SeekableImageMediaSource.Factory(
                      durationUs, checkNotNull(externalImageLoader))
                  .createMediaSource(editedMediaItem.mediaItem)
              : (transitionClippingEnabled
                  ? new com.videoeditor.core.media.composition.ReorderAwareMediaSourceFactory(defaultMediaSourceFactory)
                  : defaultMediaSourceFactory).createMediaSource(editedMediaItem.mediaItem);
      if (editedMediaItem.removeAudio) {
        mainMediaSource =
            new FilteringMediaSource(
                mainMediaSource, ImmutableSet.of(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_IMAGE));
      }

      MediaSource mergingMediaSource = new MergingMediaSource(/* adjustPeriodTimeOffsets= */ true, mainMediaSource, silenceMediaSource);
      MediaSource itemMediaSource =
          wrapWithVideoEffectsBasedMediaSources(
              mergingMediaSource, editedMediaItem.effects.videoEffects, durationUs);
      mediaSourceBuilder.add(
          itemMediaSource, /* initialPlaceholderDurationMs= */ transitionClippingEnabled ? C.TIME_UNSET : usToMs(durationUs));
    }
    player.setMediaSource(mediaSourceBuilder.build());
  }

  /** Whether every secondary can wait without requiring the primary audio format. */
  private static boolean hasSingleAudiblePrimary(Composition composition) {
    if (composition.sequences.get(0).editedMediaItems.size() != 1) return false;
    for (int i = 1; i < composition.sequences.size(); i++) {
      EditedMediaItemSequence sequence = composition.sequences.get(i);
      if (!isOverlayVisualSequence(sequence)) return false;
      for (EditedMediaItem item : sequence.editedMediaItems) {
        // Secondary PCM registration requires the primary's actual format.
        // Never defer only the primary while an audible input waits for it.
        if (!item.removeAudio) return false;
      }
    }
    return true;
  }

  /**
   * Returns whether a secondary sequence carries visual content. The builder
   * marks overlay layers by leaving their items' video track intact; upstream
   * secondary audio sequences always set {@code removeVideo}.
   */
  private static boolean isOverlayVisualSequence(EditedMediaItemSequence sequence) {
    for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
      if (!sequence.editedMediaItems.get(i).removeVideo) {
        return true;
      }
    }
    return false;
  }

  private void setSecondaryPlayerSequence(
      ExoPlayer player, EditedMediaItemSequence sequence, long primarySequenceDurationUs) {
    ConcatenatingMediaSource2.Builder mediaSourceBuilder =
        new ConcatenatingMediaSource2.Builder().useDefaultMediaSourceFactory(context);

    long accumulatedDurationUs = 0;
    int i = 0;
    do {
      EditedMediaItem editedMediaItem = sequence.editedMediaItems.get(i);
      long itemPresentationDurationUs = editedMediaItem.getPresentationDurationUs();
      checkArgument(itemPresentationDurationUs > 0);
      MediaSource source;
      if (externalImageLoader != null
          && MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE.equals(
              checkNotNull(editedMediaItem.mediaItem.localConfiguration).mimeType)) {
        // Photo overlays decode through the seek-aware external image source so
        // intra-item seeks replay the still, exactly like primary photos.
        source =
            new com.videoeditor.core.media.preview.SeekableImageMediaSource.Factory(
                    itemPresentationDurationUs, checkNotNull(externalImageLoader))
                .createMediaSource(editedMediaItem.mediaItem);
      } else {
        DefaultMediaSourceFactory defaultMediaSourceFactory = new DefaultMediaSourceFactory(context);
        if (externalImageLoader != null) {
          defaultMediaSourceFactory.setExternalImageLoader(externalImageLoader);
        }
        source = (transitionClippingEnabled
                  ? new com.videoeditor.core.media.composition.ReorderAwareMediaSourceFactory(defaultMediaSourceFactory)
                  : defaultMediaSourceFactory).createMediaSource(editedMediaItem.mediaItem);
      }
      source = wrapWithVideoEffectsBasedMediaSources(
          source, editedMediaItem.effects.videoEffects, itemPresentationDurationUs);
      mediaSourceBuilder.add(source, /* initialPlaceholderDurationMs= */ transitionClippingEnabled ? C.TIME_UNSET : usToMs(itemPresentationDurationUs));
      accumulatedDurationUs += itemPresentationDurationUs;
      i++;
      if (i == sequence.editedMediaItems.size()) {
        // Nonlooping sequences already contain explicit silence padding. Never
        // append a repeated item for a small model/Media3 microsecond rounding
        // difference: SequenceAudioRenderer maps period indices to these items.
        // An extra period would make its last-item lookup throw on every reset.
        if (!sequence.isLooping) break;
        i = 0;
      }
    } while (accumulatedDurationUs < primarySequenceDurationUs);
    player.setMediaSource(mediaSourceBuilder.build());
  }

  private MediaSource wrapWithVideoEffectsBasedMediaSources(
      MediaSource mediaSource, ImmutableList<Effect> videoEffects, long durationUs) {
    MediaSource newMediaSource = mediaSource;
    for (Effect videoEffect : videoEffects) {
      if (videoEffect instanceof InactiveTimestampAdjustment) {
        newMediaSource =
            wrapWithSpeedChangingMediaSource(
                newMediaSource,
                ((InactiveTimestampAdjustment) videoEffect).speedProvider,
                durationUs);
      }
    }
    return newMediaSource;
  }

  private MediaSource wrapWithSpeedChangingMediaSource(
      MediaSource mediaSource, SpeedProvider speedProvider, long durationUs) {
    // The app supports a constant speed per clip. Media3 1.5.1 retimes
    // duration but leaves a clipped period's source offset unscaled, yielding
    // negative period lengths after a late split and mismatched merge seeks.
    checkArgument(speedProvider.getNextSpeedChangeTimeUs(0) == C.TIME_UNSET);
    final float speed = speedProvider.getSpeed(0);
    return new WrappingMediaSource(mediaSource) {

      @Override
      public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
        return new SpeedProviderMediaPeriod(
            super.createPeriod(id, allocator, (long) (startPositionUs * speed)), speedProvider);
      }

      @Override
      public void releasePeriod(MediaPeriod mediaPeriod) {
        MediaPeriod wrappedPeriod = ((SpeedProviderMediaPeriod) mediaPeriod).mediaPeriod;
        super.releasePeriod(wrappedPeriod);
      }

      @Override
      protected void onChildSourceInfoRefreshed(Timeline newTimeline) {
        Timeline timeline =
            new ForwardingTimeline(newTimeline) {
              @Override
              public Window getWindow(
                  int windowIndex, Window window, long defaultPositionProjectionUs) {
                Window wrappedWindow =
                    newTimeline.getWindow(windowIndex, window, defaultPositionProjectionUs);
                wrappedWindow.durationUs = durationUs;
                wrappedWindow.positionInFirstPeriodUs =
                    (long) (wrappedWindow.positionInFirstPeriodUs / speed);
                return wrappedWindow;
              }

              @Override
              public Period getPeriod(int periodIndex, Period period, boolean setIds) {
                Timeline.Period wrappedPeriod = newTimeline.getPeriod(periodIndex, period, setIds);
                long positionInWindowUs = (long) (wrappedPeriod.getPositionInWindowUs() / speed);
                wrappedPeriod.set(wrappedPeriod.id, wrappedPeriod.uid, wrappedPeriod.windowIndex,
                    durationUs - positionInWindowUs, positionInWindowUs);
                return wrappedPeriod;
              }
            };
        super.onChildSourceInfoRefreshed(timeline);
      }
    };
  }

  private long getContentPositionMs() {
    return players.isEmpty() ? C.TIME_UNSET : players.get(0).getContentPosition();
  }

  private long getBufferedPositionMs() {
    if (players.isEmpty()) {
      return 0;
    }
    // Return the minimum buffered position among players.
    long minBufferedPositionMs = Integer.MAX_VALUE;
    for (int i = 0; i < players.size(); i++) {
      minBufferedPositionMs = min(minBufferedPositionMs, players.get(i).getBufferedPosition());
    }
    return minBufferedPositionMs;
  }

  private long getTotalBufferedDurationMs() {
    if (players.isEmpty()) {
      return 0;
    }
    // Return the minimum total buffered duration among players.
    long minTotalBufferedDurationMs = Integer.MAX_VALUE;
    for (int i = 0; i < players.size(); i++) {
      minTotalBufferedDurationMs =
          min(minTotalBufferedDurationMs, players.get(i).getTotalBufferedDuration());
    }
    return minTotalBufferedDurationMs;
  }

  private boolean getRenderedFirstFrameAndReset() {
    boolean value = renderedFirstFrame;
    renderedFirstFrame = false;
    return value;
  }

  private void maybeUpdatePlaybackError(
      String errorMessage, Exception cause, @PlaybackException.ErrorCode int errorCode) {
    if (playbackException == null) {
      playbackException = new PlaybackException(errorMessage, cause, errorCode);
      for (int i = 0; i < players.size(); i++) {
        players.get(i).stop();
      }
      invalidateState();
    } else {
      Log.w(TAG, errorMessage, cause);
    }
  }

  private void setVideoSurfaceHolderInternal(SurfaceHolder surfaceHolder) {
    removeSurfaceCallbacks();
    this.surfaceHolder = surfaceHolder;
    surfaceHolder.addCallback(this);
    Surface surface = surfaceHolder.getSurface();
    if (surface != null && surface.isValid()) {
      videoOutputSize =
          new Size(
              surfaceHolder.getSurfaceFrame().width(), surfaceHolder.getSurfaceFrame().height());
      setVideoSurfaceInternal(surface, videoOutputSize);
    } else {
      clearVideoSurfaceInternal();
    }
  }

  private void setVideoSurfaceInternal(Surface surface, Size videoOutputSize) {
    displaySurface = surface;
    maybeSetOutputSurfaceInfo(videoOutputSize.getWidth(), videoOutputSize.getHeight());
  }

  private void maybeSetOutputSurfaceInfo(int width, int height) {
    Surface surface = displaySurface;
    if (width == 0 || height == 0 || surface == null || compositionPlayerInternal == null) {
      return;
    }
    compositionPlayerInternal.setOutputSurfaceInfo(surface, new Size(width, height));
  }

  private void clearVideoSurfaceInternal() {
    displaySurface = null;
    if (compositionPlayerInternal != null) {
      compositionPlayerInternal.clearOutputSurface();
    }
  }

  private void removeSurfaceCallbacks() {
    if (surfaceHolder != null) {
      surfaceHolder.removeCallback(this);
      surfaceHolder = null;
    }
  }

  private void repeatCompositionPlayback() {
    repeatingCompositionSeekInProgress = true;
    seekTo(
        getCurrentMediaItemIndex(),
        /* positionMs= */ C.TIME_UNSET,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        /* isRepeatingCurrentItem= */ true);
  }

  private ImmutableList<MediaItemData> createPlaylist() {
    checkNotNull(compositionDurationUs != C.TIME_UNSET);
    return ImmutableList.of(
        new MediaItemData.Builder("CompositionTimeline")
            .setMediaItem(MediaItem.EMPTY)
            .setDurationUs(compositionDurationUs)
            .build());
  }

  private static long getCompositionDurationUs(Composition composition) {
    checkState(!composition.sequences.isEmpty());
    return getSequenceDurationUs(composition.sequences.get(0));
  }

  private static long getSequenceDurationUs(EditedMediaItemSequence sequence) {
    long compositionDurationUs = 0;
    for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
      compositionDurationUs += sequence.editedMediaItems.get(i).getPresentationDurationUs();
    }
    checkState(compositionDurationUs > 0, String.valueOf(compositionDurationUs));
    return compositionDurationUs;
  }

  /**
   * A {@link VideoFrameReleaseControl.FrameTimingEvaluator} for composition frames.
   *
   * <ul>
   *   <li>Signals to {@linkplain
   *       VideoFrameReleaseControl.FrameTimingEvaluator#shouldForceReleaseFrame(long, long) force
   *       release} a frame if the frame is late by more than {@link #FRAME_LATE_THRESHOLD_US} and
   *       the elapsed time since the previous frame release is greater than {@link
   *       #FRAME_RELEASE_THRESHOLD_US}.
   *   <li>Signals to {@linkplain
   *       VideoFrameReleaseControl.FrameTimingEvaluator#shouldDropFrame(long, long, boolean) drop a
   *       frame} if the frame is late by more than {@link #FRAME_LATE_THRESHOLD_US} and the frame
   *       is not marked as the last one.
   *   <li>Signals to never {@linkplain
   *       VideoFrameReleaseControl.FrameTimingEvaluator#shouldIgnoreFrame(long, long, long,
   *       boolean, boolean) ignore} a frame.
   * </ul>
   */
  private static final class CompositionFrameTimingEvaluator
      implements VideoFrameReleaseControl.FrameTimingEvaluator {
    private final boolean dropFrames;

    CompositionFrameTimingEvaluator(boolean dropFrames) { this.dropFrames = dropFrames; }

    /** The time threshold, in microseconds, after which a frame is considered late. */
    private static final long FRAME_LATE_THRESHOLD_US = -30_000;

    /**
     * The maximum elapsed time threshold, in microseconds, since last releasing a frame after which
     * a frame can be force released.
     */
    private static final long FRAME_RELEASE_THRESHOLD_US = 100_000;

    @Override
    public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
      return earlyUs < FRAME_LATE_THRESHOLD_US
          && elapsedSinceLastReleaseUs > FRAME_RELEASE_THRESHOLD_US;
    }

    @Override
    public boolean shouldDropFrame(long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
      // Multi-input output is already bounded and admitted by the primary
      // clock. Preserve its due composited frames; a vsync estimate after stream
      // reconfiguration must not starve all surface output. Input backpressure
      // remains bounded by processor/native texture capacity.
      return dropFrames && earlyUs < FRAME_LATE_THRESHOLD_US && !isLastFrame;
    }

    @Override
    public boolean shouldIgnoreFrame(
        long earlyUs,
        long positionUs,
        long elapsedRealtimeUs,
        boolean isLastFrame,
        boolean treatDroppedBuffersAsSkipped) {
      // TODO: b/293873191 - Handle very late buffers and drop to key frame.
      return false;
    }
  }

  private final class PlayerListener implements Player.Listener {
    private final int playerIndex;

    public PlayerListener(int playerIndex) {
      this.playerIndex = playerIndex;
    }

    @Override
    public void onEvents(Player player, Events events) {
      if (events.containsAny(SUPPORTED_LISTENER_EVENTS)) {
        invalidateState();
      }
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
      playWhenReadyChangeReason = reason;
      if (reason == PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
          && repeatMode != REPEAT_MODE_OFF
          && playerIndex == 0) {
        repeatCompositionPlayback();
      }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      if (releaseInProgress && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) {
        // A VFP image flush can wait indefinitely for a lost GL completion callback.
        // ExoPlayer has already timed out releasing this retired player. Interrupt
        // that wait so its queued release and graph cleanup can run; never leave
        // the application thread blocked in PreviewCompositionPlayerInternal.release().
        checkStateNotNull(playbackThread).interrupt();
        android.util.Log.e("CompositionPreview", "Interrupted timed-out preview release", error);
      }
      maybeUpdatePlaybackError("error from player " + playerIndex, error, error.errorCode);
    }
  }

  /**
   * A {@link DefaultTrackSelector} extension to de-select generated audio when the audio from the
   * media is playable.
   */
  private static final class CompositionTrackSelector extends DefaultTrackSelector {

    private static final String SILENCE_AUDIO_TRACK_GROUP_ID = "1:";
    private final int sequenceIndex;
    private final boolean disableVideoPlayback;

    public CompositionTrackSelector(
        Context context, int sequenceIndex, boolean disableVideoPlayback) {
      super(context);
      this.sequenceIndex = sequenceIndex;
      this.disableVideoPlayback = disableVideoPlayback;
    }

    @Nullable
    @Override
    protected Pair<ExoTrackSelection.Definition, Integer> selectAudioTrack(
        MappedTrackInfo mappedTrackInfo,
        @RendererCapabilities.Capabilities int[][][] rendererFormatSupports,
        @RendererCapabilities.AdaptiveSupport int[] rendererMixedMimeTypeAdaptationSupports,
        Parameters params)
        throws ExoPlaybackException {
      if (!disableVideoPlayback) {
        // Currently silence is only generated for the zero-indexed sequence.
        int audioRenderIndex = C.INDEX_UNSET;
        for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
          if (mappedTrackInfo.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
            audioRenderIndex = i;
            break;
          }
        }
        checkState(audioRenderIndex != C.INDEX_UNSET);

        TrackGroupArray audioTrackGroups = mappedTrackInfo.getTrackGroups(audioRenderIndex);
        // If there's only one audio TrackGroup, it'll be silence, there's no need to override track
        // selection.
        if (audioTrackGroups.length > 1) {
          boolean mediaAudioIsPlayable = false;
          int silenceAudioTrackGroupIndex = C.INDEX_UNSET;
          for (int i = 0; i < audioTrackGroups.length; i++) {
            if (audioTrackGroups.get(i).id.startsWith(SILENCE_AUDIO_TRACK_GROUP_ID)) {
              silenceAudioTrackGroupIndex = i;
              continue;
            }
            // For non-silence tracks
            for (int j = 0; j < audioTrackGroups.get(i).length; j++) {
              mediaAudioIsPlayable |=
                  RendererCapabilities.getFormatSupport(
                          rendererFormatSupports[audioRenderIndex][i][j])
                      == C.FORMAT_HANDLED;
            }
          }
          checkState(silenceAudioTrackGroupIndex != C.INDEX_UNSET);

          if (mediaAudioIsPlayable) {
            // Disable silence if the media's audio track is playable.
            int silenceAudioTrackIndex = audioTrackGroups.length - 1;
            rendererFormatSupports[audioRenderIndex][silenceAudioTrackIndex][0] =
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
          }
        }
      }

      return super.selectAudioTrack(
          mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params);
    }

    @Nullable
    @Override
    protected Pair<ExoTrackSelection.Definition, Integer> selectVideoTrack(
        MappedTrackInfo mappedTrackInfo,
        @RendererCapabilities.Capabilities int[][][] rendererFormatSupports,
        @RendererCapabilities.AdaptiveSupport int[] mixedMimeTypeSupports,
        Parameters params)
        throws ExoPlaybackException {
      if (disableVideoPlayback) {
        return null;
      }
      return super.selectVideoTrack(
          mappedTrackInfo, rendererFormatSupports, mixedMimeTypeSupports, params);
    }

    @Nullable
    @Override
    protected Pair<ExoTrackSelection.Definition, Integer> selectImageTrack(
        MappedTrackInfo mappedTrackInfo,
        @RendererCapabilities.Capabilities int[][][] rendererFormatSupports,
        Parameters params)
        throws ExoPlaybackException {
      if (disableVideoPlayback) {
        return null;
      }
      return super.selectImageTrack(mappedTrackInfo, rendererFormatSupports, params);
    }
  }
}
