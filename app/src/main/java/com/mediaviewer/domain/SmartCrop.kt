package com.mediaviewer.domain

import kotlin.math.min

enum class CropMode { SMART, CROP, FIT }

/** README §4.2: crop when cropping loses at most [thresholdPct] of the image, otherwise fit centered. */
object SmartCrop {
    fun lostFraction(srcW: Int, srcH: Int, tileW: Float, tileH: Float): Float {
        if (srcW <= 0 || srcH <= 0 || tileW <= 0f || tileH <= 0f) return 0f
        val aImg = srcW.toFloat() / srcH
        val aTile = tileW / tileH
        return 1f - min(aImg / aTile, aTile / aImg)
    }

    fun shouldCrop(srcW: Int, srcH: Int, tileW: Float, tileH: Float, mode: CropMode, thresholdPct: Int): Boolean =
        when (mode) {
            CropMode.CROP -> true
            CropMode.FIT -> false
            // Small epsilon so a value printed as "10 %" counts as within the threshold.
            CropMode.SMART -> lostFraction(srcW, srcH, tileW, tileH) <= thresholdPct / 100f + 1e-4f
        }
}
