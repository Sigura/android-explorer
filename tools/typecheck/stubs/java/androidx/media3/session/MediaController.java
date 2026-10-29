package androidx.media3.session;
import android.content.Context;
import androidx.media3.common.Player;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Future;
public class MediaController implements Player {
  public static final class Builder {
    public Builder(Context context, SessionToken token) {}
    public Builder setApplicationLooper(android.os.Looper looper) { return this; }
    public ListenableFuture<MediaController> buildAsync() { return null; }
  }
  public static void releaseFuture(Future<? extends MediaController> controllerFuture) {}
  public final boolean isConnected() { return false; }
  // Player members: declared by the interface; this stub class is never compiled by javac.
}
