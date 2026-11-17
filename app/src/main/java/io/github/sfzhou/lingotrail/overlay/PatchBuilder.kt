package io.github.sfzhou.lingotrail.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the background patch that hides the original words.
 *
 * A flat colour fill is invisible on perfectly uniform paper, but real photos have
 * gradients, print texture and uneven lighting, where a flat rectangle shows up as
 * an obvious block. Instead this builder takes the actual pixels around the text,
 * removes the glyphs by propagating the surrounding colours into them (a small
 * on-device inpainting pass), and hands back that repaired crop, which is drawn at
 * the same place and scale as the photo.
 */
object PatchBuilder {

    private const val MAX_PATCH_DIM = 760
    private const val MIN_CONTRAST = 34f
    private const val MAX_CONTRAST = 150f
    private const val MAX_SMOOTH_PASSES = 2
    private const val MIN_MASK_DISTANCE = 16f
    private const val MASK_DISTANCE_RATIO = 0.15f
    private const val BLUR_RADIUS = 5
    private const val BLUR_PASSES = 2

    data class Patch(val bitmap: Bitmap, val rectVirtual: RectF)

    fun build(source: Bitmap, placement: InkPlacement): Patch? {
        if (source.width < 16 || source.height < 16) return null
        val pixelsPerVirtual = source.width / PreparedOverlay.VIRTUAL_WIDTH
        if (pixelsPerVirtual <= 0f) return null
        if (placement.coverage.width() < 2f || placement.coverage.height() < 2f) return null

        // Coverage is in the block's rotated local frame; map it to virtual image space.
        val virtualLeft = placement.centerX + placement.coverage.left
        val virtualTop = placement.centerY + placement.coverage.top
        val virtualRight = placement.centerX + placement.coverage.right
        val virtualBottom = placement.centerY + placement.coverage.bottom
        val left = (virtualLeft * pixelsPerVirtual).roundToInt().coerceIn(0, source.width - 1)
        val top = (virtualTop * pixelsPerVirtual).roundToInt().coerceIn(0, source.height - 1)
        val right = (virtualRight * pixelsPerVirtual).roundToInt().coerceIn(left + 2, source.width)
        val bottom = (virtualBottom * pixelsPerVirtual).roundToInt().coerceIn(top + 2, source.height)
        val sourceWidth = right - left
        val sourceHeight = bottom - top
        if (sourceWidth < 6 || sourceHeight < 6) return null

        val scale = min(1f, MAX_PATCH_DIM.toFloat() / max(sourceWidth, sourceHeight))
        val patchWidth = max(4, (sourceWidth * scale).roundToInt())
        val patchHeight = max(4, (sourceHeight * scale).roundToInt())
        val patch = Bitmap.createBitmap(patchWidth, patchHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(patch)
            canvas.drawBitmap(
                source,
                Rect(left, top, right, bottom),
                Rect(0, 0, patchWidth, patchHeight),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            inpaintText(patch)
        } catch (error: Throwable) {
            patch.recycle()
            return null
        }

        // Keep the patch exactly over the pixels it was taken from so untouched
        // background pixels line up with the photo underneath.
        val rectVirtual = RectF(
            left / pixelsPerVirtual,
            top / pixelsPerVirtual,
            right / pixelsPerVirtual,
            bottom / pixelsPerVirtual,
        )
        return Patch(patch, rectVirtual)
    }

    /** Separable box blur (approximates a Gaussian in a couple of passes). */
    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return source
        val window = radius * 2 + 1
        val horizontal = IntArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val colour = source[row + offset.coerceIn(0, width - 1)]
                red += (colour shr 16) and 0xFF
                green += (colour shr 8) and 0xFF
                blue += colour and 0xFF
            }
            for (x in 0 until width) {
                horizontal[row + x] = ColorAnalysis.argb(255, red / window, green / window, blue / window)
                val leaving = source[row + (x - radius).coerceIn(0, width - 1)]
                val entering = source[row + (x + radius + 1).coerceIn(0, width - 1)]
                red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                blue += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
        val result = IntArray(source.size)
        for (x in 0 until width) {
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val colour = horizontal[offset.coerceIn(0, height - 1) * width + x]
                red += (colour shr 16) and 0xFF
                green += (colour shr 8) and 0xFF
                blue += colour and 0xFF
            }
            for (y in 0 until height) {
                result[y * width + x] = ColorAnalysis.argb(255, red / window, green / window, blue / window)
                val leaving = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val entering = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                blue += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
        return result
    }

    /** Grows the glyph mask over antialiasing halos and soft shadows of the text. */
    private fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask
        val result = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask[y * width + x]) continue
                val top = max(0, y - radius)
                val bottom = min(height - 1, y + radius)
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                for (ny in top..bottom) {
                    val rowOffset = ny * width
                    for (nx in left..right) {
                        result[rowOffset + nx] = true
                    }
                }
            }
        }
        return result
    }

    /** Removes high-contrast glyph pixels and fills them from the surrounding paper. */
    private fun inpaintText(patch: Bitmap) {
        val width = patch.width
        val height = patch.height
        if (width < 4 || height < 4) return
        val pixels = IntArray(width * height)
        patch.getPixels(pixels, 0, width, 0, 0, width, height)

        val background = ColorAnalysis.dominantColor(pixels)
        val distances = FloatArray(pixels.size) { ColorAnalysis.colorDistance(pixels[it], background) }
        val sortedDistances = InkMath.sortedCopy(distances)
        val threshold = InkMath.percentile(sortedDistances, 0.9f)
            .coerceIn(MIN_CONTRAST, MAX_CONTRAST)
        // Glyph rims and printed shadows are only slightly different from the paper,
        // so a strict threshold leaves a ghost outline of the original words. Use a
        // soft cut, then dilate the mask over the halo around each glyph.
        val cut = max(MIN_MASK_DISTANCE, threshold * MASK_DISTANCE_RATIO)
        var mask = BooleanArray(pixels.size) { distances[it] > cut }
        val radius = (min(width, height) * 0.055f).roundToInt().coerceIn(2, 15)
        mask = dilate(mask, width, height, radius)
        val maskedCount = mask.count { it }
        if (maskedCount < 8 || maskedCount > pixels.size * 0.97f) return

        val colors = pixels.copyOf()
        val filled = BooleanArray(pixels.size) { !mask[it] }
        val queue = IntArray(pixels.size)
        var queueStart = 0
        var queueEnd = 0
        val neighbours = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1, -1, -1, 1, -1, -1, 1, 1, 1)

        fun tryEnqueue(index: Int) {
            if (filled[index]) return
            val x = index % width
            val y = index / width
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            var offset = 0
            while (offset < neighbours.size) {
                val nx = x + neighbours[offset]
                val ny = y + neighbours[offset + 1]
                offset += 2
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val neighbourIndex = ny * width + nx
                if (!filled[neighbourIndex]) continue
                val colour = colors[neighbourIndex]
                red += (colour shr 16) and 0xFF
                green += (colour shr 8) and 0xFF
                blue += colour and 0xFF
                count++
            }
            if (count == 0) return
            colors[index] = ColorAnalysis.argb(255, red / count, green / count, blue / count)
            filled[index] = true
            queue[queueEnd++] = index
        }

        // Seed with masked pixels that touch known background.
        for (index in pixels.indices) {
            if (mask[index]) tryEnqueue(index)
        }
        // Propagate colours inwards (multi-source flood fill, O(n)).
        while (queueStart < queueEnd) {
            val index = queue[queueStart++]
            val x = index % width
            val y = index / width
            var offset = 0
            while (offset < neighbours.size) {
                val nx = x + neighbours[offset]
                val ny = y + neighbours[offset + 1]
                offset += 2
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                tryEnqueue(ny * width + nx)
            }
        }

        // Flatten the low-frequency leftovers of shadows and JPEG halos by blending
        // the removed area towards a wide blur of the repaired patch.
        var flattened = colors
        repeat(BLUR_PASSES) { flattened = boxBlur(flattened, width, height, BLUR_RADIUS) }
        for (index in colors.indices) {
            if (mask[index]) colors[index] = flattened[index]
        }

        // Soften the fill so the removed glyphs do not look like a stamp.
        var pass = 0
        while (pass < MAX_SMOOTH_PASSES) {
            val next = colors.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    if (!mask[index]) continue
                    var red = 0
                    var green = 0
                    var blue = 0
                    var count = 0
                    var offset = 0
                    while (offset < neighbours.size) {
                        val nx = x + neighbours[offset]
                        val ny = y + neighbours[offset + 1]
                        offset += 2
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                        val colour = colors[ny * width + nx]
                        red += (colour shr 16) and 0xFF
                        green += (colour shr 8) and 0xFF
                        blue += colour and 0xFF
                        count++
                    }
                    if (count > 0) {
                        next[index] = ColorAnalysis.argb(255, red / count, green / count, blue / count)
                    }
                }
            }
            next.copyInto(colors)
            pass++
        }

        patch.setPixels(colors, 0, width, 0, 0, width, height)
    }
}
