package androidx.media3.common;
public interface DataReader {
  int read(byte[] buffer, int offset, int length) throws java.io.IOException;
}
