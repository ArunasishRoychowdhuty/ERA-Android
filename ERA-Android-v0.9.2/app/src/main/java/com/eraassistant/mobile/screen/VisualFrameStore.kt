package com.eraassistant.mobile.screen

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Holds one explicitly captured, downscaled visual frame in process memory only.
 * Frames are never serialized, written to cache/files, or uploaded by this store.
 */
data class VisualFrame(
    val bitmap: Bitmap,
    val capturedAtEpochMs: Long,
    val sourcePackage: String?
)

object VisualFrameStore {
    @Volatile
    private var current: VisualFrame? = null

    @Synchronized
    fun replace(bitmap: Bitmap, capturedAtEpochMs: Long, sourcePackage: String?) {
        val boundedBitmap = downscale(bitmap)
        val previous = current
        current = VisualFrame(boundedBitmap, capturedAtEpochMs, sourcePackage)
        if (previous?.bitmap !== boundedBitmap) {
            runCatching { previous?.bitmap?.recycle() }
        }
    }

    @Synchronized
    fun current(): VisualFrame? = current

    /** Returns an independent RAM copy for an explicit user share operation. */
    @Synchronized
    fun copyCurrentBitmap(): Bitmap? = current?.bitmap?.let { bitmap ->
        runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    @Synchronized
    fun clear() {
        val previous = current
        current = null
        runCatching { previous?.bitmap?.recycle() }
    }

    private fun downscale(source: Bitmap): Bitmap {
        val largestSide = maxOf(source.width, source.height)
        if (largestSide <= MAX_DIMENSION) return source
        val ratio = MAX_DIMENSION.toFloat() / largestSide.toFloat()
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) runCatching { source.recycle() }
        return scaled
    }

    private const val MAX_DIMENSION = 1440
}
