package androidx.core.view;
public class WindowInsetsCompat {
  public static final class Type {
    public static int statusBars() { return 1; }
    public static int navigationBars() { return 2; }
    public static int captionBar() { return 4; }
    public static int ime() { return 8; }
    public static int systemBars() { return 7; }
    public static int displayCutout() { return 128; }
  }
}
