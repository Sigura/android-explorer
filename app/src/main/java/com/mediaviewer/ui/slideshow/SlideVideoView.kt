package com.mediaviewer.ui.slideshow

import android.content.Context
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * A video slide's picture. A TextureView, not the SurfaceView of PlayerView: a SurfaceView is a
 * separate window surface, so the slide or fade transition doesn't move or fade it with the slide;
 * it lags behind and flashes black on every step. The video is fitted and centred in the slide.
 */
class SlideVideoView(context: Context) : FrameLayout(context), Player.Listener {
    private val texture = TextureView(context)
    private val frame = AspectRatioFrameLayout(context).apply {
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The player drawing into this slide; it may hand the video over to another slide's view. */
    var player: Player? = null
        set(p) {
            if (field === p) return
            field?.let { old ->
                old.removeListener(this)
                // Only lets go of the player if it still draws here, not into the slide it moved to.
                old.clearVideoTextureView(texture)
            }
            field = p
            p?.let {
                it.setVideoTextureView(texture)
                it.addListener(this)
                onVideoSizeChanged(it.videoSize)
            }
        }

    init {
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            frame.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
        }
    }
}
