package androidx.media3.session;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
public abstract class MediaSessionService extends Service {
  public MediaSessionService() {}
  @Override public void onCreate() {}
  @Nullable public abstract MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo);
  public final void addSession(MediaSession session) {}
  public final void removeSession(MediaSession session) {}
  public boolean isPlaybackOngoing() { return false; }
  public void pauseAllPlayersAndStopSelf() {}
  @Override @Nullable public IBinder onBind(@Nullable Intent intent) { return null; }
  @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) { return 0; }
  @Override public void onTaskRemoved(@Nullable Intent rootIntent) {}
  @Override public void onDestroy() {}
}
