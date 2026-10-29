package androidx.core.content;
import android.content.ContentProvider;
import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import java.io.File;
public class FileProvider extends ContentProvider {
  public FileProvider() {}
  @NonNull public static Uri getUriForFile(@NonNull Context context, @NonNull String authority, @NonNull File file) { return null; }
  @NonNull public static Uri getUriForFile(@NonNull Context context, @NonNull String authority, @NonNull File file, @NonNull String displayName) { return null; }
}
