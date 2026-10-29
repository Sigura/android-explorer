package androidx.media3.ui;
import android.content.Context;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
public class PlayerView extends FrameLayout {
  public PlayerView(Context context) { super(context); }
  @Nullable public Player getPlayer() { return null; }
  public void setPlayer(@Nullable Player player) {}
  public void setResizeMode(int resizeMode) {}
  public int getResizeMode() { return 0; }
  public boolean getUseController() { return false; }
  public void setUseController(boolean useController) {}
  public void setShutterBackgroundColor(int color) {}
  public void setKeepContentOnPlayerReset(boolean keepContentOnPlayerReset) {}
}
