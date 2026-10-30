package androidx.media3.datasource;
import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import java.io.IOException;
public final class DefaultDataSource implements DataSource {
  public static final class Factory implements DataSource.Factory {
    public Factory(Context context) {}
    @Override public DefaultDataSource createDataSource() { return null; }
  }
  public DefaultDataSource(Context context, boolean allowCrossProtocolRedirects) {}
  @Override public void addTransferListener(TransferListener transferListener) {}
  @Override public long open(DataSpec dataSpec) throws IOException { return 0; }
  @Override public int read(byte[] buffer, int offset, int length) throws IOException { return 0; }
  @Override @Nullable public Uri getUri() { return null; }
  @Override public void close() throws IOException {}
}
