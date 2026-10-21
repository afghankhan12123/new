package androidx.media3.effect;

import android.content.Context;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;

/** Rational project-time frame grid, with one pending input and one output lease. */
@UnstableApi
public final class FrameRateResampleEffect implements GlEffect {
  private final int fps;
  public FrameRateResampleEffect(int fps) {
    if (fps <= 0 || fps > 120) throw new IllegalArgumentException("Invalid output frame rate");
    this.fps = fps;
  }
  @Override public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException { return new Resampler(context, useHdr, fps); }
  @Override public boolean isNoOp(int width, int height) { return false; }

  private static final class Resampler extends FrameCacheGlShaderProgram {
    private final int fps;
    private final boolean useHdr;
    private GlTextureInfo held;
    private GlTextureInfo pending;
    private GlObjectsProvider provider;
    private long pendingUs;
    private long heldUs;
    private long nextIndex;
    private boolean initialized;
    private boolean ending;
    private boolean failed;

    Resampler(Context context, boolean useHdr, int fps) throws VideoFrameProcessingException {
      super(context, 1, useHdr);
      this.fps = fps; this.useHdr = useHdr;
    }
    private long timeAt(long index) {
      return (index / fps) * 1_000_000L + (index % fps) * 1_000_000L / fps;
    }
    @Override public void queueInputFrame(GlObjectsProvider provider, GlTextureInfo input, long timeUs) {
      if (pending != null) { onError(new IllegalStateException("Resampler input admission exceeded")); return; }
      this.provider = provider; pending = input; pendingUs = timeUs;
      drain();
    }
    private void drain() {
      if (failed) return;
      try {
        if (pending != null && !initialized) {
          nextIndex = (pendingUs / 1_000_000L) * fps +
              ((pendingUs % 1_000_000L) * fps + 999_999L) / 1_000_000L;
          copyPending(); initialized = true;
          if (timeAt(nextIndex) <= heldUs && outputTexturePool.freeTextureCount() > 0) emit();
        }
        // Output release drives continuation. Never consume more pool slots than are free,
        // including low-cadence photos, slowed clips and irregular decoder timestamps.
        while (pending != null && timeAt(nextIndex) < pendingUs) {
          if (outputTexturePool.freeTextureCount() == 0) return;
          emit();
        }
        if (pending != null) copyPending();
        if (ending) {
          while (initialized && timeAt(nextIndex) <= heldUs) {
            if (outputTexturePool.freeTextureCount() == 0) return;
            emit();
          }
          ending = false; initialized = false;
          getOutputListener().onCurrentOutputStreamEnded();
        } else if (pending == null && outputTexturePool.freeTextureCount() > 0) {
          getInputListener().onReadyToAcceptInputFrame();
        }
      } catch (Exception error) { failed = true; onError(error); }
    }
    private void copyPending() throws GlUtil.GlException, VideoFrameProcessingException {
      GlTextureInfo input = pending;
      if (held == null || held.width != input.width || held.height != input.height) {
        if (held != null) held.release();
        held = provider.createBuffersForTexture(GlUtil.createTexture(input.width, input.height, useHdr), input.width, input.height);
      }
      GlUtil.focusFramebufferUsingCurrentContext(held.fboId, held.width, held.height);
      GlUtil.clearFocusedBuffers();
      drawFrame(input.texId, pendingUs);
      heldUs = pendingUs; pending = null;
      getInputListener().onInputFrameProcessed(input);
    }
    private void emit() throws GlUtil.GlException, VideoFrameProcessingException {
      outputTexturePool.ensureConfigured(provider, held.width, held.height);
      GlTextureInfo output = outputTexturePool.useTexture();
      GlUtil.focusFramebufferUsingCurrentContext(output.fboId, output.width, output.height);
      GlUtil.clearFocusedBuffers();
      long timeUs = timeAt(nextIndex++);
      drawFrame(held.texId, timeUs);
      getOutputListener().onOutputFrameAvailable(output, timeUs);
    }
    @Override public void releaseOutputFrame(GlTextureInfo output) {
      if (!outputTexturePool.isUsingTexture(output)) return;
      outputTexturePool.freeTexture(output);
      drain();
    }
    @Override public void signalEndOfCurrentInputStream() { ending = true; drain(); }
    @Override public void flush() {
      pending = null; initialized = false; ending = false; failed = false;
      super.flush();
    }
    @Override public void release() throws VideoFrameProcessingException {
      try { if (held != null) held.release(); }
      catch (GlUtil.GlException error) { throw new VideoFrameProcessingException(error); }
      finally { held = null; pending = null; super.release(); }
    }
  }
}
