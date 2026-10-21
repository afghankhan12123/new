import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** Shell-only independent Android decode of an already exported consumer file.
 * Mirrors AacSourceIdentityDeviceTest.decodeNativeOutput; never invokes ExportEngine.
 * Run using app_process with the D8-compiled class on CLASSPATH.
 */
public final class DecodeConsumerAudio {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("input.mp4 output.pcm");
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    try (FileOutputStream pcm = new FileOutputStream(args[1])) {
      extractor.setDataSource(args[0]);
      MediaFormat format = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat candidate = extractor.getTrackFormat(i);
        if (candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
          if (format != null) throw new IllegalStateException("multiple audio tracks");
          format = candidate;
          extractor.selectTrack(i);
        }
      }
      if (format == null) throw new IllegalStateException("no audio track");
      System.out.println("extractor=" + format);
      codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
      codec.configure(format, null, null, 0);
      codec.start();
      boolean inputEnded = false, outputEnded = false;
      long samplesBytes = 0, deadline = SystemClock.elapsedRealtime() + 15000;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (!outputEnded) {
        if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("native decode timeout");
        if (!inputEnded) {
          int index = codec.dequeueInputBuffer(1000);
          if (index >= 0) {
            int count = extractor.readSampleData(codec.getInputBuffer(index), 0);
            inputEnded = count < 0;
            codec.queueInputBuffer(index, 0, Math.max(0, count), inputEnded ? 0 : extractor.getSampleTime(),
                inputEnded ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            if (!inputEnded) extractor.advance();
          }
        }
        int index = codec.dequeueOutputBuffer(info, 1000);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) System.out.println("decoder=" + codec.getOutputFormat());
        if (index >= 0) {
          ByteBuffer buffer = codec.getOutputBuffer(index).duplicate();
          buffer.position(info.offset);
          buffer.limit(info.offset + info.size);
          byte[] bytes = new byte[info.size];
          buffer.get(bytes);
          pcm.write(bytes);
          samplesBytes += bytes.length;
          codec.releaseOutputBuffer(index, false);
          outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        }
      }
      System.out.println("pcm_bytes=" + samplesBytes);
    } finally {
      if (codec != null) {
        try { codec.stop(); } finally { codec.release(); }
      }
      extractor.release();
    }
  }
}
