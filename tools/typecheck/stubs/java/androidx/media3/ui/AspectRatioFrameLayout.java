package androidx.media3.ui;
import android.content.Context;
import android.widget.FrameLayout;
public final class AspectRatioFrameLayout extends FrameLayout {
  public static final int RESIZE_MODE_FIT = 0, RESIZE_MODE_FIXED_WIDTH = 1, RESIZE_MODE_FIXED_HEIGHT = 2,
      RESIZE_MODE_FILL = 3, RESIZE_MODE_ZOOM = 4;
  public AspectRatioFrameLayout(Context context) { super(context); }
}
