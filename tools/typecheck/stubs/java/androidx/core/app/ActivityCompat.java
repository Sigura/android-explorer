package androidx.core.app;
import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
public class ActivityCompat extends ContextCompat {
  protected ActivityCompat() {}
  public static boolean shouldShowRequestPermissionRationale(@NonNull Activity activity, @NonNull String permission) { return false; }
  public static void requestPermissions(@NonNull Activity activity, @NonNull String[] permissions, int requestCode) {}
  public static void finishAffinity(@NonNull Activity activity) {}
  public static void recreate(@NonNull Activity activity) {}
}
