package androidx.media3.common;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import androidx.annotation.Nullable;
import java.util.List;
/** Stub of media3 1.6 Player: only what the app uses, with the real names and nullability. */
public interface Player {
  int STATE_IDLE = 1, STATE_BUFFERING = 2, STATE_READY = 3, STATE_ENDED = 4;
  int REPEAT_MODE_OFF = 0, REPEAT_MODE_ONE = 1, REPEAT_MODE_ALL = 2;
  int MEDIA_ITEM_TRANSITION_REASON_REPEAT = 0, MEDIA_ITEM_TRANSITION_REASON_AUTO = 1,
      MEDIA_ITEM_TRANSITION_REASON_SEEK = 2, MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 3;
  int DISCONTINUITY_REASON_AUTO_TRANSITION = 0, DISCONTINUITY_REASON_SEEK = 1,
      DISCONTINUITY_REASON_SEEK_ADJUSTMENT = 2, DISCONTINUITY_REASON_SKIP = 3,
      DISCONTINUITY_REASON_REMOVE = 4, DISCONTINUITY_REASON_INTERNAL = 5, DISCONTINUITY_REASON_SILENCE_SKIP = 6;
  int PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST = 1;
  int EVENT_TIMELINE_CHANGED = 0, EVENT_MEDIA_ITEM_TRANSITION = 1, EVENT_PLAYBACK_STATE_CHANGED = 4,
      EVENT_PLAY_WHEN_READY_CHANGED = 5, EVENT_IS_PLAYING_CHANGED = 7, EVENT_POSITION_DISCONTINUITY = 11,
      EVENT_PLAYBACK_PARAMETERS_CHANGED = 12, EVENT_MEDIA_METADATA_CHANGED = 14;

  final class Events {
    public boolean contains(int event) { return false; }
    public boolean containsAny(int... events) { return false; }
    public int size() { return 0; }
    public int get(int index) { return 0; }
  }

  final class PositionInfo {
    @Nullable public final Object windowUid = null;
    public final int mediaItemIndex = 0;
    @Nullable public final MediaItem mediaItem = null;
    public final long positionMs = 0;
    public final long contentPositionMs = 0;
  }

  interface Listener {
    default void onEvents(Player player, Events events) {}
    default void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {}
    default void onIsPlayingChanged(boolean isPlaying) {}
    default void onIsLoadingChanged(boolean isLoading) {}
    default void onPlaybackStateChanged(int playbackState) {}
    default void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {}
    default void onRepeatModeChanged(int repeatMode) {}
    default void onPlayerError(PlaybackException error) {}
    default void onPlayerErrorChanged(@Nullable PlaybackException error) {}
    default void onPositionDiscontinuity(PositionInfo oldPosition, PositionInfo newPosition, int reason) {}
    default void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {}
    default void onMediaMetadataChanged(MediaMetadata mediaMetadata) {}
    default void onVolumeChanged(float volume) {}
    default void onVideoSizeChanged(VideoSize videoSize) {}
    default void onRenderedFirstFrame() {}
  }

  void addListener(Listener listener);
  void removeListener(Listener listener);
  void setMediaItems(List<MediaItem> mediaItems);
  void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition);
  void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs);
  void setMediaItem(MediaItem mediaItem);
  void setMediaItem(MediaItem mediaItem, long startPositionMs);
  void setMediaItem(MediaItem mediaItem, boolean resetPosition);
  void addMediaItem(MediaItem mediaItem);
  void addMediaItems(List<MediaItem> mediaItems);
  void moveMediaItem(int currentIndex, int newIndex);
  void removeMediaItem(int index);
  void clearMediaItems();
  void prepare();
  int getPlaybackState();
  @Nullable PlaybackException getPlayerError();
  void play();
  void pause();
  void setPlayWhenReady(boolean playWhenReady);
  boolean getPlayWhenReady();
  void setRepeatMode(int repeatMode);
  int getRepeatMode();
  void setShuffleModeEnabled(boolean shuffleModeEnabled);
  boolean getShuffleModeEnabled();
  boolean isLoading();
  boolean isPlaying();
  void seekToDefaultPosition();
  void seekTo(long positionMs);
  void seekTo(int mediaItemIndex, long positionMs);
  long getSeekBackIncrement();
  long getSeekForwardIncrement();
  void seekBack();
  void seekForward();
  boolean hasPreviousMediaItem();
  void seekToPreviousMediaItem();
  void seekToPrevious();
  boolean hasNextMediaItem();
  void seekToNextMediaItem();
  void seekToNext();
  void setPlaybackParameters(PlaybackParameters playbackParameters);
  void setPlaybackSpeed(float speed);
  PlaybackParameters getPlaybackParameters();
  void stop();
  void release();
  TrackSelectionParameters getTrackSelectionParameters();
  void setTrackSelectionParameters(TrackSelectionParameters parameters);
  MediaMetadata getMediaMetadata();
  int getCurrentMediaItemIndex();
  @Nullable MediaItem getCurrentMediaItem();
  int getMediaItemCount();
  MediaItem getMediaItemAt(int index);
  long getDuration();
  long getCurrentPosition();
  long getBufferedPosition();
  int getBufferedPercentage();
  long getContentPosition();
  AudioAttributes getAudioAttributes();
  void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus);
  void setVolume(float volume);
  float getVolume();
  void clearVideoSurface();
  void setVideoSurface(@Nullable Surface surface);
  void setVideoSurfaceView(@Nullable SurfaceView surfaceView);
  void clearVideoSurfaceView(@Nullable SurfaceView surfaceView);
  void setVideoTextureView(@Nullable TextureView textureView);
  void clearVideoTextureView(@Nullable TextureView textureView);
  VideoSize getVideoSize();
  boolean isCommandAvailable(int command);
}
