package androidx.media3.common;
import androidx.annotation.Nullable;
public class PlaybackException extends Exception {
  public final int errorCode = 0;
  public final long timestampMs = 0;
  public PlaybackException(@Nullable String message, @Nullable Throwable cause, int errorCode) { super(message, cause); }
  public final String getErrorCodeName() { return null; }
  public static String getErrorCodeName(int errorCode) { return null; }
}
