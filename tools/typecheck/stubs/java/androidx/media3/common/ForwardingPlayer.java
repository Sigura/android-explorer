package androidx.media3.common;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import androidx.annotation.Nullable;
import java.util.List;
public class ForwardingPlayer implements Player {
  public ForwardingPlayer(Player player) {}
  public Player getWrappedPlayer() { return null; }
  @Override public void addListener(Listener listener) {  }
  @Override public void removeListener(Listener listener) {  }
  @Override public void setMediaItems(List<MediaItem> mediaItems) {  }
  @Override public void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition) {  }
  @Override public void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {  }
  @Override public void setMediaItem(MediaItem mediaItem) {  }
  @Override public void setMediaItem(MediaItem mediaItem, long startPositionMs) {  }
  @Override public void setMediaItem(MediaItem mediaItem, boolean resetPosition) {  }
  @Override public void addMediaItem(MediaItem mediaItem) {  }
  @Override public void addMediaItems(List<MediaItem> mediaItems) {  }
  @Override public void moveMediaItem(int currentIndex, int newIndex) {  }
  @Override public void removeMediaItem(int index) {  }
  @Override public void clearMediaItems() {  }
  @Override public void prepare() {  }
  @Override public int getPlaybackState() { return 0; }
  @Override public @Nullable PlaybackException getPlayerError() { return null; }
  @Override public void play() {  }
  @Override public void pause() {  }
  @Override public void setPlayWhenReady(boolean playWhenReady) {  }
  @Override public boolean getPlayWhenReady() { return false; }
  @Override public void setRepeatMode(int repeatMode) {  }
  @Override public int getRepeatMode() { return 0; }
  @Override public void setShuffleModeEnabled(boolean shuffleModeEnabled) {  }
  @Override public boolean getShuffleModeEnabled() { return false; }
  @Override public boolean isLoading() { return false; }
  @Override public boolean isPlaying() { return false; }
  @Override public void seekToDefaultPosition() {  }
  @Override public void seekTo(long positionMs) {  }
  @Override public void seekTo(int mediaItemIndex, long positionMs) {  }
  @Override public long getSeekBackIncrement() { return 0; }
  @Override public long getSeekForwardIncrement() { return 0; }
  @Override public void seekBack() {  }
  @Override public void seekForward() {  }
  @Override public boolean hasPreviousMediaItem() { return false; }
  @Override public void seekToPreviousMediaItem() {  }
  @Override public void seekToPrevious() {  }
  @Override public boolean hasNextMediaItem() { return false; }
  @Override public void seekToNextMediaItem() {  }
  @Override public void seekToNext() {  }
  @Override public void setPlaybackParameters(PlaybackParameters playbackParameters) {  }
  @Override public void setPlaybackSpeed(float speed) {  }
  @Override public PlaybackParameters getPlaybackParameters() { return null; }
  @Override public void stop() {  }
  @Override public void release() {  }
  @Override public TrackSelectionParameters getTrackSelectionParameters() { return null; }
  @Override public void setTrackSelectionParameters(TrackSelectionParameters parameters) {  }
  @Override public MediaMetadata getMediaMetadata() { return null; }
  @Override public int getCurrentMediaItemIndex() { return 0; }
  @Override public @Nullable MediaItem getCurrentMediaItem() { return null; }
  @Override public int getMediaItemCount() { return 0; }
  @Override public MediaItem getMediaItemAt(int index) { return null; }
  @Override public long getDuration() { return 0; }
  @Override public long getCurrentPosition() { return 0; }
  @Override public long getBufferedPosition() { return 0; }
  @Override public int getBufferedPercentage() { return 0; }
  @Override public long getContentPosition() { return 0; }
  @Override public AudioAttributes getAudioAttributes() { return null; }
  @Override public void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {  }
  @Override public void setVolume(float volume) {  }
  @Override public float getVolume() { return 0f; }
  @Override public void clearVideoSurface() {  }
  @Override public void setVideoSurface(@Nullable Surface surface) {  }
  @Override public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {  }
  @Override public void clearVideoSurfaceView(@Nullable SurfaceView surfaceView) {  }
  @Override public void setVideoTextureView(@Nullable TextureView textureView) {  }
  @Override public void clearVideoTextureView(@Nullable TextureView textureView) {  }
  @Override public VideoSize getVideoSize() { return null; }
  @Override public boolean isCommandAvailable(int command) { return false; }
}
