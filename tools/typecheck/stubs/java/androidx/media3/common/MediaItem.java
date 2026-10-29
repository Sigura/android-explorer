package androidx.media3.common;
import android.net.Uri;
import androidx.annotation.Nullable;
public final class MediaItem {
  public static final String DEFAULT_MEDIA_ID = "";
  public static final MediaItem EMPTY = null;
  public final String mediaId = null;
  @Nullable public final LocalConfiguration localConfiguration = null;
  public final MediaMetadata mediaMetadata = null;
  public static MediaItem fromUri(String uri) { return null; }
  public static MediaItem fromUri(Uri uri) { return null; }
  public Builder buildUpon() { return null; }
  public static final class LocalConfiguration {
    public final Uri uri = null;
    @Nullable public final String mimeType = null;
  }
  public static final class Builder {
    public Builder() {}
    public Builder setMediaId(String mediaId) { return this; }
    public Builder setUri(@Nullable String uri) { return this; }
    public Builder setUri(@Nullable Uri uri) { return this; }
    public Builder setMimeType(@Nullable String mimeType) { return this; }
    public Builder setMediaMetadata(MediaMetadata mediaMetadata) { return this; }
    public MediaItem build() { return null; }
  }
}
