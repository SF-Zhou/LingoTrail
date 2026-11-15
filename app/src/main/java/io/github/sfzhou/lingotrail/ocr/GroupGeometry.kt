package io.github.sfzhou.lingotrail.ocr

import io.github.sfzhou.lingotrail.net.TranslationGroup
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the translation model's optional paragraph grouping into drawable units.
 *
 * The model groups consecutive lines that are wrapped fragments of one sentence so
 * they can be translated as a passage. Because the grouping itself is a language
 * guess, every proposed group is checked against the geometry: its lines must be
 * visually adjacent. A group that fails the check is dropped and its lines fall back
 * to their individual translations, which are always present.
 */
object GroupGeometry {

    /** One drawable translation unit: one or more consecutive OCR lines. */
    data class Unit(
        val indices: List<Int>,
        val translation: String,
    )

    /**
     * Builds the final list of units, covering every line exactly once.
     *
     * @param lines OCR lines in reading order.
     * @param lineTranslations per-line translations aligned with [lines].
     * @param groups model-proposed groups of consecutive line indices (0-based).
     */
    fun buildUnits(
        lines: List<OcrLine>,
        lineTranslations: List<String>,
        groups: List<TranslationGroup>,
    ): List<Unit> {
        require(lineTranslations.size == lines.size) { "lineTranslations must align with lines" }

        val used = BooleanArray(lines.size)
        val units = mutableListOf<Unit>()

        groups.forEach { group ->
            val indices = group.indices
            val translation = group.text.trim()
            if (translation.isEmpty()) return@forEach
            if (indices.size < 2) return@forEach
            if (indices.any { it !in lines.indices }) return@forEach
            if (indices.any { used[it] }) return@forEach
            // Only strictly ascending consecutive lines form a wrapped paragraph.
            if (indices.zipWithNext().any { (current, next) -> next != current + 1 }) return@forEach
            if (!isGeometricallyContinuous(lines, indices)) return@forEach

            indices.forEach { used[it] = true }
            units += Unit(indices, translation)
        }

        lines.indices.forEach { index ->
            if (!used[index]) {
                units += Unit(
                    indices = listOf(index),
                    translation = lineTranslations[index].ifBlank { lines[index].text },
                )
            }
        }
        return units.sortedBy { unit -> unit.indices.first() }
    }

    private fun isGeometricallyContinuous(lines: List<OcrLine>, indices: List<Int>): Boolean {
        for ((previousIndex, nextIndex) in indices.zipWithNext()) {
            val previous = lines[previousIndex].bounds ?: return false
            val next = lines[nextIndex].bounds ?: return false
            val lineHeight = max(previous.height, next.height)
            if (lineHeight <= 0f) return false
            val gap = next.top - previous.bottom
            if (gap > 1.15f * lineHeight || gap < -1.0f * lineHeight) return false
            val overlap = previous.horizontalOverlap(next)
            if (overlap < 0.25f * min(previous.width, next.width)) return false
        }
        return true
    }
}
