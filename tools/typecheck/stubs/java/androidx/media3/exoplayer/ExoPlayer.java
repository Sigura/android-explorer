package androidx.media3.exoplayer;
import android.content.Context;
import android.os.Looper;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Player;
public interface ExoPlayer extends Player {
  final class Builder {
    public Builder(Context context) {}
    public Builder(Context context, RenderersFactory renderersFactory) {}
    public Builder setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) { return this; }
    public Builder setHandleAudioBecomingNoisy(boolean handleAudioBecomingNoisy) { return this; }
    public Builder setWakeMode(int wakeMode) { return this; }
    public Builder setSeekBackIncrementMs(long seekBackIncrementMs) { return this; }
    public Builder setSeekForwardIncrementMs(long seekForwardIncrementMs) { return this; }
    public Builder setLooper(Looper looper) { return this; }
    public Builder setMediaSourceFactory(androidx.media3.exoplayer.source.MediaSource.Factory mediaSourceFactory) { return this; }
    public ExoPlayer build() { return null; }
  }
  void setHandleAudioBecomingNoisy(boolean handleAudioBecomingNoisy);
  void setWakeMode(int wakeMode);
  boolean isReleased();
  void setSkipSilenceEnabled(boolean skipSilenceEnabled);
  boolean getSkipSilenceEnabled();
}
