package androidx.core.view;
public final class WindowInsetsControllerCompat {
  public static final int BEHAVIOR_DEFAULT = 1;
  public static final int BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;
  public void show(int types) {}
  public void hide(int types) {}
  public int getSystemBarsBehavior() { return 0; }
  public void setSystemBarsBehavior(int behavior) {}
  public boolean isAppearanceLightStatusBars() { return false; }
  public void setAppearanceLightStatusBars(boolean isLight) {}
  public boolean isAppearanceLightNavigationBars() { return false; }
  public void setAppearanceLightNavigationBars(boolean isLight) {}
}
