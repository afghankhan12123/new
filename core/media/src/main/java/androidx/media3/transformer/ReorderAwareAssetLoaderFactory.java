package androidx.media3.transformer;

import com.videoeditor.core.media.composition.ReorderAwareMediaSourceFactory;

import android.content.Context;
import android.os.Looper;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.transformer.AssetLoader;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExoPlayerAssetLoader;

/** Qualified source-clock audio; transition video drain remains independently selected. */
public final class ReorderAwareAssetLoaderFactory implements AssetLoader.Factory {
  private final DefaultAssetLoaderFactory images;
  private final ExoPlayerAssetLoader.Factory media;
  private final Context context;
  private final Codec.DecoderFactory decoderFactory;
  private final boolean videoReorderDrainEnabled;
  public ReorderAwareAssetLoaderFactory(Context context, Codec.DecoderFactory decoderFactory) {
    this(context,decoderFactory,true);
  }
  public ReorderAwareAssetLoaderFactory(Context context, Codec.DecoderFactory decoderFactory, boolean videoReorderDrainEnabled) {
    this.videoReorderDrainEnabled = videoReorderDrainEnabled;
    this.context = context;
    this.decoderFactory = decoderFactory;
    images = new DefaultAssetLoaderFactory(context, decoderFactory, Clock.DEFAULT);
    media = new ExoPlayerAssetLoader.Factory(context, decoderFactory, Clock.DEFAULT,
        videoReorderDrainEnabled ? new ReorderAwareMediaSourceFactory(new DefaultMediaSourceFactory(context))
            : new DefaultMediaSourceFactory(context));
  }
  @Override public AssetLoader createAssetLoader(EditedMediaItem item, Looper looper,
      AssetLoader.Listener listener, AssetLoader.CompositionSettings settings) {
    String mime = item.mediaItem.localConfiguration == null ? null : item.mediaItem.localConfiguration.mimeType;
    if (MimeTypes.isImage(mime)) return images.createAssetLoader(item, looper, listener, settings);
    if (!com.videoeditor.core.media.audio.SourceClockAacDecoder.isEnabled())
      return media.createAssetLoader(item, looper, listener, settings);
    Codec.DecoderFactory sourceAware = new Codec.DecoderFactory() {
      @Override public Codec createForAudioDecoding(androidx.media3.common.Format format) throws ExportException {
        Codec source = com.videoeditor.core.media.audio.SourceClockAacDecoder.create(context, item, format);
        return source != null ? source : decoderFactory.createForAudioDecoding(format);
      }
      @Override public Codec createForVideoDecoding(androidx.media3.common.Format format,
          android.view.Surface surface, boolean toneMap) throws ExportException {
        return decoderFactory.createForVideoDecoding(format, surface, toneMap);
      }
    };
    return new ExoPlayerAssetLoader.Factory(context, sourceAware, Clock.DEFAULT,
        videoReorderDrainEnabled ? new ReorderAwareMediaSourceFactory(new DefaultMediaSourceFactory(context))
            : new DefaultMediaSourceFactory(context))
        .createAssetLoader(item, looper, listener, settings);
  }
}
