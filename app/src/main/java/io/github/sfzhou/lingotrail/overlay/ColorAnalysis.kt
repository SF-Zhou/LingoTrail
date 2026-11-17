package io.github.sfzhou.lingotrail.overlay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure colour maths for the overlay: no Android dependencies, so it is unit-testable.
 */
object ColorAnalysis {

    private const val ALPHA_MASK = 0xFF000000.toInt()
    private const val INK_DISTANCE = 58f
    private const val MIN_INK_RATIO = 0.008f
    private const val MAX_INK_RATIO = 0.72f
    private const val MIN_CONTRAST = 0.30f

    data class SampledColors(
        /** Opaque ARGB fill that hides the original glyphs. */
        val background: Int,
        /** Ink colour matched to the original lettering, or a high-contrast fallback. */
        val foreground: Int,
    )

    /**
     * Estimates the paper and ink colours of a text region.
     *
     * The background is the per-channel median (robust against the minority ink
     * pixels), the ink colour is the average of pixels that clearly differ from it.
     * If the two end up too similar, a black/white fallback keeps the translation
     * readable — matching the original colour is a nicety, legibility is not.
     */
    fun analyze(pixels: IntArray): SampledColors {
        val opaque = pixels.filter { (it ushr 24) and 0xFF >= 128 }.toIntArray()
        if (opaque.isEmpty()) return SampledColors(WHITE, BLACK)

        val background = dominantColor(opaque)
        val bgLuminance = luminance(background)

        // Antialiased glyph edges pull a plain average towards the paper colour, so
        // only the strongest ink samples (top quartile by distance) are averaged.
        val inkCandidates = opaque.filter { colorDistance(it, background) > INK_DISTANCE }
        val inkRatio = inkCandidates.size.toFloat() / opaque.size
        var foreground = if (inkRatio in MIN_INK_RATIO..MAX_INK_RATIO && inkCandidates.isNotEmpty()) {
            val distances = inkCandidates
                .map { colorDistance(it, background) }
                .sorted()
            val strongThreshold = distances[(distances.size * 3) / 4]
            val strongInk = inkCandidates.filter { colorDistance(it, background) >= strongThreshold }
            var red = 0L
            var green = 0L
            var blue = 0L
            strongInk.forEach { color ->
                red += (color shr 16) and 0xFF
                green += (color shr 8) and 0xFF
                blue += color and 0xFF
            }
            argb(255, (red / strongInk.size).toInt(), (green / strongInk.size).toInt(), (blue / strongInk.size).toInt())
        } else {
            contrastColor(bgLuminance)
        }

        if (contrastRatio(background, foreground) < MIN_CONTRAST) {
            foreground = contrastColor(bgLuminance)
        }
        return SampledColors(
            background = background or ALPHA_MASK,
            foreground = foreground or ALPHA_MASK,
        )
    }

    fun contrastColor(background: Int): Int = contrastColor(luminance(background))

    private fun contrastColor(backgroundLuminance: Float): Int {
        // 0.18 is the luminance where near-black and near-white ink give the same
        // WCAG contrast; below it, white text wins, above it black text does.
        return if (backgroundLuminance > 0.18f) DARK_INK else LIGHT_INK
    }

    fun medianColor(colors: IntArray): Int {
        if (colors.isEmpty()) return WHITE
        val reds = IntArray(colors.size)
        val greens = IntArray(colors.size)
        val blues = IntArray(colors.size)
        colors.forEachIndexed { index, color ->
            reds[index] = (color shr 16) and 0xFF
            greens[index] = (color shr 8) and 0xFF
            blues[index] = color and 0xFF
        }
        reds.sort()
        greens.sort()
        blues.sort()
        val middle = colors.size / 2
        return argb(255, reds[middle], greens[middle], blues[middle])
    }

    /**
     * The most common colour in the sample.
     *
     * A median is pulled towards the minority text colour (and its JPEG ringing),
     * which leaves a faintly visible patch over a solid background. The dominant
     * quantised bucket stays on the paper colour instead.
     */
    fun dominantColor(colors: IntArray): Int {
        if (colors.isEmpty()) return WHITE
        val histogram = IntArray(32 * 32 * 32)
        colors.forEach { color ->
            val key = (((color shr 16) and 0xFF) shr 3 shl 10) or
                ((((color shr 8) and 0xFF) shr 3) shl 5) or
                ((color and 0xFF) shr 3)
            histogram[key]++
        }
        var bestKey = 0
        var bestCount = -1
        for (key in histogram.indices) {
            if (histogram[key] > bestCount) {
                bestCount = histogram[key]
                bestKey = key
            }
        }
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        colors.forEach { color ->
            val key = (((color shr 16) and 0xFF) shr 3 shl 10) or
                ((((color shr 8) and 0xFF) shr 3) shl 5) or
                ((color and 0xFF) shr 3)
            if (key == bestKey) {
                red += (color shr 16) and 0xFF
                green += (color shr 8) and 0xFF
                blue += color and 0xFF
                count++
            }
        }
        if (count == 0) return WHITE
        return argb(255, (red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    fun colorDistance(first: Int, second: Int): Float {
        val dr = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val dg = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val db = (first and 0xFF) - (second and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    /** WCAG relative luminance, 0 (black) .. 1 (white). */
    fun luminance(color: Int): Float {
        val r = linearize(((color shr 16) and 0xFF) / 255f)
        val g = linearize(((color shr 8) and 0xFF) / 255f)
        val b = linearize((color and 0xFF) / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** WCAG contrast ratio between two colours, 1..21. */
    fun contrastRatio(first: Int, second: Int): Float {
        val l1 = luminance(first)
        val l2 = luminance(second)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun linearize(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()
    private val DARK_INK = 0xFF121212.toInt()
    private val LIGHT_INK = 0xFFF7F7F5.toInt()
}

/**
 * Fits a translated string into the rectangle the original text occupied.
 *
 * A [TextMeasurer] implementation is supplied by the UI layer (it wraps a
 * StaticLayout); this class stays pure so the binary search can be unit-tested.
 */
object TextFitter {

    fun interface TextMeasurer {
        /** Lays [text] out at [textSize] within [maxWidth] and reports its height. */
        fun measure(text: String, textSize: Float, maxWidth: Float): FitMetrics
    }

    data class FitMetrics(val height: Float, val lineCount: Int)

    /**
     * Returns the largest text size in `[minTextSize, maxTextSize]` that fits into
     * `maxWidth × maxHeight`, or `null` when even [minTextSize] overflows — in that
     * case the block is left untouched rather than covering the original with an
     * unreadable smear.
     */
    fun fit(
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        minTextSize: Float,
        maxTextSize: Float,
        measurer: TextMeasurer,
    ): Float? {
        if (text.isBlank() || maxWidth <= 1f || maxHeight <= 1f) return null
        if (maxTextSize < minTextSize) return null

        val atMin = measurer.measure(text, minTextSize, maxWidth)
        if (atMin.height > maxHeight || atMin.lineCount == 0) return null

        val atMax = measurer.measure(text, maxTextSize, maxWidth)
        if (atMax.height <= maxHeight) return maxTextSize

        var low = minTextSize
        var high = maxTextSize
        repeat(14) {
            val middle = (low + high) / 2f
            if (middle - low < 0.05f) return@repeat
            if (measurer.measure(text, middle, maxWidth).height <= maxHeight) {
                low = middle
            } else {
                high = middle
            }
        }
        return low
    }

    /** True when two strings are the same apart from case or surrounding whitespace. */
    fun isUnchanged(a: String, b: String): Boolean =
        a.trim().equals(b.trim(), ignoreCase = true)
}
