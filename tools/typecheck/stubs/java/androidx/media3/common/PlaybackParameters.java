package androidx.media3.common;
public final class PlaybackParameters {
  public static final PlaybackParameters DEFAULT = null;
  public final float speed = 1f;
  public final float pitch = 1f;
  public PlaybackParameters(float speed) {}
  public PlaybackParameters(float speed, float pitch) {}
  public PlaybackParameters withSpeed(float speed) { return this; }
}
