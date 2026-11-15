package io.github.sfzhou.lingotrail.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in normalized page coordinates: both axes run from 0 (top/left)
 * to 1 (bottom/right), independent of the source resolution.
 */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    init {
        require(right >= left && bottom >= top) { "NormRect must be ordered" }
    }

    fun union(other: NormRect): NormRect = NormRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )

    fun horizontalOverlap(other: NormRect): Float {
        val overlap = min(right, other.right) - max(left, other.left)
        return max(0f, overlap)
    }

    companion object {
        val UNIT = NormRect(0f, 0f, 1f, 1f)

        /** Builds a rectangle from arbitrary coordinates, clamping and ordering it. */
        fun of(x1: Float, y1: Float, x2: Float, y2: Float): NormRect {
            val left = min(x1, x2).coerceIn(0f, 1f)
            val right = max(x1, x2).coerceIn(0f, 1f)
            val top = min(y1, y2).coerceIn(0f, 1f)
            val bottom = max(y1, y2).coerceIn(0f, 1f)
            return NormRect(left, top, right, bottom)
        }
    }
}

/** Integer rectangle in the pixel space of a stored scan. */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        fun fromNormalized(rect: NormRect, imageWidth: Int, imageHeight: Int): PixelRect =
            PixelRect(
                left = (rect.left * imageWidth).toInt().coerceIn(0, imageWidth),
                top = (rect.top * imageHeight).toInt().coerceIn(0, imageHeight),
                right = (rect.right * imageWidth).toInt().coerceIn(0, imageWidth),
                bottom = (rect.bottom * imageHeight).toInt().coerceIn(0, imageHeight),
            )
    }
}
