package androidx.media3.exoplayer.source;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;

/** Source PTS end, carried with clipped AVC format while dependency packets drain. */
public final class VideoClipEndMetadata implements Metadata.Entry {
  public final long endUs;
  public VideoClipEndMetadata(long endUs) { this.endUs = endUs; }
  public static long from(@Nullable Metadata metadata) {
    if (metadata != null) for (int i = 0; i < metadata.length(); i++) {
      if (metadata.get(i) instanceof VideoClipEndMetadata) return ((VideoClipEndMetadata) metadata.get(i)).endUs;
    }
    return Long.MAX_VALUE;
  }
  @Override public boolean equals(@Nullable Object other) {
    return other instanceof VideoClipEndMetadata && ((VideoClipEndMetadata) other).endUs == endUs;
  }
  @Override public int hashCode() { return Long.hashCode(endUs); }
  @Override public int describeContents() { return 0; }
  @Override public void writeToParcel(Parcel parcel, int flags) { parcel.writeLong(endUs); }
  public static final Parcelable.Creator<VideoClipEndMetadata> CREATOR = new Parcelable.Creator<VideoClipEndMetadata>() {
    @Override public VideoClipEndMetadata createFromParcel(Parcel parcel) { return new VideoClipEndMetadata(parcel.readLong()); }
    @Override public VideoClipEndMetadata[] newArray(int size) { return new VideoClipEndMetadata[size]; }
  };
}
