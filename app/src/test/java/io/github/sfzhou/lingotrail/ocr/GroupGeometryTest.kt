package io.github.sfzhou.lingotrail.ocr

import io.github.sfzhou.lingotrail.net.TranslationGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupGeometryTest {

    private fun line(
        text: String,
        top: Float,
        left: Float = 0.1f,
        width: Float = 0.8f,
        height: Float = 0.03f,
    ): OcrLine = OcrLine(
        label = "text",
        text = text,
        boxes = listOf(NormRect(left, top, left + width, top + height)),
    )

    private val paragraph = listOf(
        line("The palace was first built in 1395 and", top = 0.10f),
        line("served as the main royal residence of the", top = 0.14f),
        line("Joseon dynasty for more than 500 years.", top = 0.18f),
    )

    @Test
    fun acceptsGeometricallyContinuousGroups() {
        val units = GroupGeometry.buildUnits(
            lines = paragraph,
            lineTranslations = listOf("A", "B", "C"),
            groups = listOf(TranslationGroup(listOf(0, 1, 2), "combined paragraph")),
        )

        assertEquals(1, units.size)
        assertEquals(listOf(0, 1, 2), units[0].indices)
        assertEquals("combined paragraph", units[0].translation)
    }

    @Test
    fun rejectsGroupsWhoseLinesAreFarApart() {
        val lines = listOf(
            line("Line one", top = 0.10f),
            line("Line two", top = 0.60f),
        )
        val units = GroupGeometry.buildUnits(
            lines = lines,
            lineTranslations = listOf("one", "two"),
            groups = listOf(TranslationGroup(listOf(0, 1), "far apart")),
        )

        assertEquals(2, units.size)
        assertEquals(listOf("one", "two"), units.map { it.translation })
    }

    @Test
    fun rejectsNonConsecutiveGroups() {
        val units = GroupGeometry.buildUnits(
            lines = paragraph,
            lineTranslations = listOf("A", "B", "C"),
            groups = listOf(TranslationGroup(listOf(0, 2), "skipped line")),
        )

        assertEquals(3, units.size)
        assertEquals(listOf("A", "B", "C"), units.map { it.translation })
    }

    @Test
    fun rejectsOverlappingGroupsAndKeepsFirstOne() {
        val units = GroupGeometry.buildUnits(
            lines = paragraph,
            lineTranslations = listOf("A", "B", "C"),
            groups = listOf(
                TranslationGroup(listOf(0, 1), "first group"),
                TranslationGroup(listOf(1, 2), "overlap"),
            ),
        )

        val translations = units.map { it.translation }
        assertTrue(translations.contains("first group"))
        assertTrue(translations.contains("C"))
        assertEquals(2, units.size)
    }

    @Test
    fun coversEveryLineExactlyOnceInReadingOrder() {
        val units = GroupGeometry.buildUnits(
            lines = paragraph,
            lineTranslations = listOf("A", "B", "C"),
            groups = listOf(TranslationGroup(listOf(1, 2), "B and C")),
        )

        val indices = units.flatMap { it.indices }
        assertEquals(listOf(0, 1, 2), indices)
        assertEquals(listOf("A", "B and C"), units.map { it.translation })
    }

    @Test
    fun fallsBackToOriginalTextWhenTranslationIsBlank() {
        val units = GroupGeometry.buildUnits(
            lines = paragraph,
            lineTranslations = listOf("", "B", "C"),
            groups = emptyList(),
        )

        assertEquals("The palace was first built in 1395 and", units[0].translation)
        assertEquals("B", units[1].translation)
        assertEquals("C", units[2].translation)
    }
}
