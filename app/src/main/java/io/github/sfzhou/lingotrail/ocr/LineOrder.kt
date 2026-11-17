package io.github.sfzhou.lingotrail.ocr

import kotlin.math.max

/**
 * Puts OCR lines into reading order.
 *
 * The service usually returns them correctly ordered already; sorting by a
 * quantized top coordinate (plus left) repairs the occasional stray line without
 * breaking multi-column layouts.
 */
object LineOrder {

    fun sort(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size < 2) return lines
        val heights = lines.mapNotNull { it.bounds?.height }.sorted()
        val medianHeight = heights.getOrNull(heights.size / 2) ?: 0.01f
        val tolerance = max(medianHeight * 0.6f, 0.004f)
        return lines.sortedWith(
            compareBy(
                { line -> ((line.bounds?.top ?: 0f) / tolerance).toInt() },
                { line -> line.bounds?.left ?: 0f },
            ),
        )
    }
}
