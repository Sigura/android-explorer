package androidx.media3.session;
import android.app.PendingIntent;
import android.content.Context;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
public class MediaSession {
  public static final class Builder {
    public Builder(Context context, Player player) {}
    public Builder setCallback(Callback callback) { return this; }
    public Builder setSessionActivity(PendingIntent pendingIntent) { return this; }
    public Builder setId(String id) { return this; }
    public MediaSession build() { return null; }
  }
  public static final class ControllerInfo {
    public String getPackageName() { return null; }
  }
  public static final class MediaItemsWithStartPosition {
    public final List<MediaItem> mediaItems = null;
    public final int startIndex = 0;
    public final long startPositionMs = 0;
    public MediaItemsWithStartPosition(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {}
  }
  public interface Callback {
    default ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession mediaSession, ControllerInfo controller, List<MediaItem> mediaItems) { return null; }
    default ListenableFuture<MediaItemsWithStartPosition> onSetMediaItems(MediaSession mediaSession, ControllerInfo controller, List<MediaItem> mediaItems, int startIndex, long startPositionMs) { return null; }
    default ListenableFuture<MediaItemsWithStartPosition> onPlaybackResumption(MediaSession mediaSession, ControllerInfo controller) { return null; }
    default void onDisconnected(MediaSession session, ControllerInfo controller) {}
  }
  public final Player getPlayer() { return null; }
  public final void setPlayer(Player player) {}
  public final void release() {}
}
