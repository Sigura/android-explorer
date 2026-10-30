package androidx.media3.datasource;
public abstract class BaseDataSource implements DataSource {
  protected BaseDataSource(boolean isNetwork) {}
  @Override public final void addTransferListener(TransferListener transferListener) {}
  protected final void transferInitializing(DataSpec dataSpec) {}
  protected final void transferStarted(DataSpec dataSpec) {}
  protected final void bytesTransferred(int bytesTransferred) {}
  protected final void transferEnded() {}
}
