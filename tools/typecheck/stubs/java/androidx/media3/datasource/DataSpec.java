package androidx.media3.datasource;
import android.net.Uri;
public final class DataSpec {
  public final Uri uri;
  public final long position;
  public final long length;
  public final String key;
  public final int flags;
  public DataSpec(Uri uri) { this.uri = uri; this.position = 0; this.length = -1; this.key = null; this.flags = 0; }
}
