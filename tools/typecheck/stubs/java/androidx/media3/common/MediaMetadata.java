package androidx.media3.common;
import androidx.annotation.Nullable;
public final class MediaMetadata {
  public static final MediaMetadata EMPTY = null;
  @Nullable public final CharSequence title = null;
  @Nullable public final CharSequence artist = null;
  @Nullable public final CharSequence albumTitle = null;
  @Nullable public final CharSequence albumArtist = null;
  @Nullable public final CharSequence displayTitle = null;
  @Nullable public final byte[] artworkData = null;
  @Nullable public final Boolean isPlayable = null;
  @Nullable public final Boolean isBrowsable = null;
  public Builder buildUpon() { return null; }
  public static final class Builder {
    public Builder() {}
    public Builder setTitle(@Nullable CharSequence title) { return this; }
    public Builder setArtist(@Nullable CharSequence artist) { return this; }
    public Builder setAlbumTitle(@Nullable CharSequence albumTitle) { return this; }
    public Builder setAlbumArtist(@Nullable CharSequence albumArtist) { return this; }
    public Builder setDisplayTitle(@Nullable CharSequence displayTitle) { return this; }
    public Builder setArtworkData(@Nullable byte[] artworkData, @Nullable Integer artworkDataType) { return this; }
    public Builder setArtworkUri(@Nullable android.net.Uri artworkUri) { return this; }
    public Builder setIsPlayable(@Nullable Boolean isPlayable) { return this; }
    public Builder setIsBrowsable(@Nullable Boolean isBrowsable) { return this; }
    public Builder setDurationMs(@Nullable Long durationMs) { return this; }
    public MediaMetadata build() { return null; }
  }
}
