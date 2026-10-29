package androidx.media3.common;
public final class AudioAttributes {
  public static final AudioAttributes DEFAULT = null;
  public final int contentType = 0;
  public final int usage = 0;
  public static final class Builder {
    public Builder() {}
    public Builder setContentType(int contentType) { return this; }
    public Builder setUsage(int usage) { return this; }
    public Builder setFlags(int flags) { return this; }
    public AudioAttributes build() { return null; }
  }
}
