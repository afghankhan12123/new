package com.videoeditor.core.media.composition;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Temporary instrumentation-only probe used to diagnose the NE2211 Fade Through White
 * failure. It is disabled by default and bounded, so it cannot affect ordinary playback
 * or export. It is removed once the investigation is complete.
 *
 * <p>Records are written to a file rather than logcat, because logcat dropped a large
 * fraction of the device's records during the investigation and the missing records
 * were indistinguishable from absent events.
 */
public final class TransitionProbe {

  /** Instrumentation-only opt-in. */
  public static volatile boolean enabled = false;

  /** Upper bound on emitted records per process. */
  public static volatile int limit = 20000;

  private static File target;
  private static int count;

  private TransitionProbe() {}

  public static synchronized void reset(File file) {
    target = file;
    count = 0;
    if (file != null) {
      try {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        //noinspection ResultOfMethodCallIgnored
        new FileWriter(file, true).close();
      } catch (IOException error) {
        target = null;
      }
    }
  }

  public static synchronized void log(String message) {
    if (!enabled || target == null) {
      return;
    }
    if (++count > limit) {
      return;
    }
    try (FileWriter writer = new FileWriter(target, true)) {
      writer.write(count + " " + message + "\n");
    } catch (IOException ignored) {
      // A probe must never affect the behaviour under test.
    }
  }
}
