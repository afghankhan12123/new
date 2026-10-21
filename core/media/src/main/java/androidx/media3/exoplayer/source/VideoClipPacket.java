package androidx.media3.exoplayer.source;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.decoder.DecoderInputBuffer;
import java.util.Map;
import java.util.WeakHashMap;

/** Packet identity captured before concatenation/retiming changes the buffer timestamp. */
public final class VideoClipPacket {
  private static final Map<DecoderInputBuffer, VideoClipPacket> PACKETS = new WeakHashMap<>();
  public final MediaItem mediaItem;
  public final long sourcePtsUs;
  public final boolean preroll;
  public final boolean postroll;
  private VideoClipPacket(MediaItem item, long pts, long start, long end) {
    mediaItem = item; sourcePtsUs = pts; preroll = pts < start; postroll = pts >= end;
  }
  static synchronized void mark(DecoderInputBuffer buffer, MediaItem item, long pts, long start, long end) {
    PACKETS.put(buffer, new VideoClipPacket(item, pts, start, end));
  }
  public static synchronized void discard(DecoderInputBuffer buffer) { PACKETS.remove(buffer); }
  @Nullable public static synchronized VideoClipPacket peek(DecoderInputBuffer buffer) { return PACKETS.get(buffer); }
  @Nullable public static synchronized VideoClipPacket take(DecoderInputBuffer buffer) { return PACKETS.remove(buffer); }
}
