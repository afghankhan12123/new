package com.videoeditor.core.media.preview;

import android.os.Build;
import android.os.Trace;

/** Passive, shell-profileable phase observations. Submission is not display presentation. */
public final class PreviewPhaseTrace {
  private PreviewPhaseTrace() {}

  /** Instrumentation-only opt-in; disabled in ordinary playback and export. */
  public static volatile boolean identityDiagnostic;
  public static volatile boolean frameDeliveryDiagnostic;
  /** Additional end-of-project packet evidence, enabled only by the strict tail test. */
  public static volatile boolean tailIdentityDiagnostic;
  public static volatile String identitySession = "instrumentation";
  public static volatile java.util.function.Function<androidx.media3.transformer.Codec, androidx.media3.transformer.Codec> diagnosticVideoDecoder;
  public static volatile java.util.function.Function<androidx.media3.transformer.Codec, androidx.media3.transformer.Codec> diagnosticVideoEncoder;
  private static final java.util.Map<Object, DiagnosticOwner> diagnosticOwners = new java.util.IdentityHashMap<>();
  private static final class DiagnosticOwner {
    final long generation;
    final String session;
    DiagnosticOwner(long generation, String session) { this.generation = generation; this.session = session; }
  }
  private static long nextDiagnosticOwner;
  private static final java.util.ArrayDeque<String> frameDeliveryEvents = new java.util.ArrayDeque<>();
  private static int droppedFrameDeliveryEvents;

  public static synchronized void clearFrameDeliveryTrace() {
    frameDeliveryEvents.clear(); droppedFrameDeliveryEvents = 0;
  }

  /** Test-owned bounded capture: logcat can rate-limit a decoder's preroll burst. */
  public static synchronized void writeFrameDeliveryTrace(java.io.File file) {
    checkTraceDirectory(file.getParentFile());
    try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
      writer.write("droppedEvents=" + droppedFrameDeliveryEvents); writer.newLine();
      for (String event : frameDeliveryEvents) { writer.write(event); writer.newLine(); }
    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    android.util.Log.i("FrameTraceArtifact", "path=" + file.getAbsolutePath());
    clearFrameDeliveryTrace();
  }

  private static void checkTraceDirectory(java.io.File directory) {
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create test trace directory");
  }

  private static synchronized void retainFrameDeliveryEvent(String event) {
    if (frameDeliveryEvents.size() == 64000) { frameDeliveryEvents.remove(); droppedFrameDeliveryEvents++; }
    frameDeliveryEvents.add(event);
  }

  public static synchronized void setIdentityDiagnostic(boolean enabled) {
    identityDiagnostic = enabled;
    if (!enabled) {
      tailIdentityDiagnostic = false;
      diagnosticOwners.clear();
      diagnosticVideoDecoder = null;
      diagnosticVideoEncoder = null;
    }
  }

  private static synchronized DiagnosticOwner diagnosticOwner(Object owner) {
    DiagnosticOwner entry = diagnosticOwners.get(owner);
    if (entry == null) {
      entry = new DiagnosticOwner(++nextDiagnosticOwner, identitySession);
      diagnosticOwners.put(owner, entry);
    }
    return entry;
  }

  public static void identity(Object owner, String event, int input, long pts, String detail) {
    if (frameDeliveryDiagnostic || (identityDiagnostic && (pts == androidx.media3.common.C.TIME_UNSET
        || ((pts >= 2_350_000 && pts <= 2_600_000) || (pts >= 1_900_000 && pts <= 2_100_000) || (pts >= 1_450_000 && pts <= 1_550_000)
            || (tailIdentityDiagnostic && pts >= 3_500_000 && pts <= 4_100_000))))) {
      DiagnosticOwner entry = diagnosticOwner(owner);
      String record = "session=" + entry.session + " generation=" + entry.generation + " owner=" + System.identityHashCode(owner)
          + " bootNs=" + android.os.SystemClock.elapsedRealtimeNanos() + " event=" + event + " input=" + input + " pts=" + pts + " " + detail;
      if (frameDeliveryDiagnostic) retainFrameDeliveryEvent(record);
      android.util.Log.i("DissolveIdentity", record);
    }
  }

  /** Instrumentation-only observation of the compositor before encoder conversion. */
  public static void captureCompositorFrame(Object owner, long pts, int width, int height) {
    if (!identityDiagnostic || Math.abs(pts - 2_466_666L) > 1) return;
    java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocateDirect(width * height * 4);
    android.opengl.GLES20.glReadPixels(0,0,width,height,android.opengl.GLES20.GL_RGBA,android.opengl.GLES20.GL_UNSIGNED_BYTE,bytes);
    int[] pixels = new int[width*height];
    for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
      int offset=((height-1-y)*width+x)*4;
      pixels[y*width+x]=android.graphics.Color.argb(bytes.get(offset+3)&255,bytes.get(offset)&255,bytes.get(offset+1)&255,bytes.get(offset+2)&255);
    }
    android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(pixels,width,height,android.graphics.Bitmap.Config.ARGB_8888);
    java.io.File session=new java.io.File(identitySession);
    java.io.File file=new java.io.File(session.getParentFile(),session.getName()+"-compositor-"+System.identityHashCode(owner)+"-74.png");
    try (java.io.FileOutputStream stream=new java.io.FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream); }
    catch (java.io.IOException error) { throw new IllegalStateException(error); }
    finally { bitmap.recycle(); }
  }

  public static void counter(String name, long value) {
    if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
      Trace.setCounter("Preview." + name, value);
    }
  }

  /** Lets an external screen observer await the actual final gesture target. */
  public static void request(long targetUs, Object player, int generation) {
    if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
      Trace.setCounter("Preview.requestedTargetUs", targetUs);
      android.util.Log.i("PreviewPhaseRequest", "targetUs=" + targetUs
          + " player=" + (player == null ? 0 : System.identityHashCode(player))
          + " generation=" + generation);
    }
  }

  public static void graph(Object graph, String phase, long value) {
    if (frameDeliveryDiagnostic || (identityDiagnostic && tailIdentityDiagnostic)) identity(graph, phase, -1, value, "graph=true");
    if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
      Trace.setCounter("Preview.graph." + System.identityHashCode(graph) + "." + phase, value);
    }
  }

  public static void input(Object graph, int sequenceIndex, int compositorIndex,
      long rendererPtsUs, long bufferPtsUs) {
    if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
      String prefix = "Preview.graph." + System.identityHashCode(graph) + ".input." + sequenceIndex;
      Trace.setCounter(prefix + ".acceptedRendererPtsUs", rendererPtsUs);
      Trace.setCounter(prefix + ".acceptedBufferPtsUs", bufferPtsUs);
      Trace.setCounter(prefix + ".compositorIndex", compositorIndex);
    }
  }

  public static void composite(Object compositor, int inputIndex, long inputPtsUs, long outputPtsUs) {
    if (Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()) {
      String prefix = "Preview.compositor." + System.identityHashCode(compositor);
      Trace.setCounter(prefix + ".input." + inputIndex + ".selectedPtsUs", inputPtsUs);
      Trace.setCounter(prefix + ".outputPtsUs", outputPtsUs);
    }
  }
}
