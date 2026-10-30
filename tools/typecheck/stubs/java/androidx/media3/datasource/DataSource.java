package androidx.media3.datasource;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.DataReader;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
public interface DataSource extends DataReader {
  interface Factory {
    DataSource createDataSource();
  }
  void addTransferListener(TransferListener transferListener);
  long open(DataSpec dataSpec) throws IOException;
  @Nullable Uri getUri();
  default Map<String, List<String>> getResponseHeaders() { return Collections.emptyMap(); }
  void close() throws IOException;
}
