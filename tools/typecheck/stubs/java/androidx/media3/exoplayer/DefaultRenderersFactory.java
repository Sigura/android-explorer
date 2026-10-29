package androidx.media3.exoplayer;
import android.content.Context;
public class DefaultRenderersFactory implements RenderersFactory {
  public static final int EXTENSION_RENDERER_MODE_OFF = 0;
  public static final int EXTENSION_RENDERER_MODE_ON = 1;
  public static final int EXTENSION_RENDERER_MODE_PREFER = 2;
  public DefaultRenderersFactory(Context context) {}
  public final DefaultRenderersFactory setExtensionRendererMode(int extensionRendererMode) { return this; }
  public final DefaultRenderersFactory setEnableDecoderFallback(boolean enableDecoderFallback) { return this; }
  public final DefaultRenderersFactory setEnableAudioFloatOutput(boolean enableFloatOutput) { return this; }
}
