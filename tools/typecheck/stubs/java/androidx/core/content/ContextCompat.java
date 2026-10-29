package androidx.core.content;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.util.concurrent.Executor;
public class ContextCompat {
  public static final int RECEIVER_VISIBLE_TO_INSTANT_APPS = 0x1;
  public static final int RECEIVER_EXPORTED = 0x2;
  public static final int RECEIVER_NOT_EXPORTED = 0x4;
  protected ContextCompat() {}
  public static int checkSelfPermission(@NonNull Context context, @NonNull String permission) { return 0; }
  @NonNull public static Executor getMainExecutor(@NonNull Context context) { return null; }
  @Nullable public static Intent registerReceiver(@NonNull Context context, @Nullable BroadcastReceiver receiver, @NonNull IntentFilter filter, int flags) { return null; }
  @Nullable public static Intent registerReceiver(@NonNull Context context, @Nullable BroadcastReceiver receiver, @NonNull IntentFilter filter, @Nullable String broadcastPermission, @Nullable android.os.Handler scheduler, int flags) { return null; }
  public static int getColor(@NonNull Context context, int id) { return 0; }
  @Nullable public static Drawable getDrawable(@NonNull Context context, int id) { return null; }
  @NonNull public static File[] getExternalFilesDirs(@NonNull Context context, @Nullable String type) { return null; }
  @Nullable public static <T> T getSystemService(@NonNull Context context, @NonNull Class<T> serviceClass) { return null; }
  public static void startActivity(@NonNull Context context, @NonNull Intent intent, @Nullable android.os.Bundle options) {}
  @Nullable public static android.content.ComponentName startForegroundService(@NonNull Context context, @NonNull Intent intent) { return null; }
}
