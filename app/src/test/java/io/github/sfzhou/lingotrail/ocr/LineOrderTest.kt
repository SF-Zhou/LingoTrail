package io.github.sfzhou.lingotrail.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineOrderTest {

    private fun line(text: String, left: Float, top: Float, width: Float = 0.3f): OcrLine =
        OcrLine("text", text, listOf(NormRect(left, top, left + width, top + 0.03f)))

    @Test
    fun sortsLinesLeftToRightInsideARow() {
        val lines = listOf(
            line("right", left = 0.6f, top = 0.1f),
            line("left", left = 0.1f, top = 0.1f),
            line("middle", left = 0.35f, top = 0.1f),
        )
        assertEquals(listOf("left", "middle", "right"), LineOrder.sort(lines).map { it.text })
    }

    @Test
    fun sortsRowsTopToBottom() {
        val lines = listOf(
            line("bottom", left = 0.1f, top = 0.5f),
            line("top", left = 0.1f, top = 0.1f),
            line("second", left = 0.1f, top = 0.3f),
        )
        assertEquals(listOf("top", "second", "bottom"), LineOrder.sort(lines).map { it.text })
    }

    @Test
    fun keepsLinesWithoutGeometryStable() {
        val lines = listOf(
            OcrLine("text", "first"),
            OcrLine("text", "second"),
        )
        assertEquals(listOf("first", "second"), LineOrder.sort(lines).map { it.text })
    }

    @Test
    fun handlesEmptyAndSingleLineLists() {
        assertTrue(LineOrder.sort(emptyList()).isEmpty())
        val single = line("only", 0.1f, 0.1f)
        assertEquals(listOf(single), LineOrder.sort(listOf(single)))
    }
}
