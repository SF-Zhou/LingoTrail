package io.github.sfzhou.lingotrail.overlay

import kotlin.math.ceil
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitterTest {

    /** Deterministic stand-in for StaticLayout: monospace-ish metrics. */
    private class MonospaceMeasurer(
        private val charWidthRatio: Float = 0.55f,
        private val lineHeightRatio: Float = 1.2f,
    ) : TextFitter.TextMeasurer {
        override fun measure(text: String, textSize: Float, maxWidth: Float): TextFitter.FitMetrics {
            val charWidth = charWidthRatio * textSize
            val charsPerLine = max(1, (maxWidth / charWidth).toInt())
            val lineCount = max(1, ceil(text.length.toFloat() / charsPerLine).toInt())
            return TextFitter.FitMetrics(
                height = lineCount * lineHeightRatio * textSize,
                lineCount = lineCount,
            )
        }
    }

    private val measurer = MonospaceMeasurer()

    @Test
    fun returnsMaxSizeWhenEverythingFits() {
        val size = TextFitter.fit(
            text = "Hi",
            maxWidth = 200f,
            maxHeight = 100f,
            minTextSize = 6f,
            maxTextSize = 40f,
            measurer = measurer,
        )
        assertEquals(40f, size!!, 0.001f)
    }

    @Test
    fun shrinksUntilTheTextFitsTheBox() {
        val text = "The palace was first built in 1395 and served as the main royal residence of the Joseon dynasty."
        val size = TextFitter.fit(
            text = text,
            maxWidth = 200f,
            maxHeight = 100f,
            minTextSize = 4f,
            maxTextSize = 100f,
            measurer = measurer,
        )
        assertNotNull(size)
        assertTrue("expected shrinkage, got $size", size!! < 100f)
        assertTrue("expected a usable size, got $size", size > 8f)
        assertTrue(measurer.measure(text, size, 200f).height <= 100f)
    }

    @Test
    fun returnsNullWhenEvenTheMinimumDoesNotFit() {
        val size = TextFitter.fit(
            text = "x".repeat(400),
            maxWidth = 50f,
            maxHeight = 20f,
            minTextSize = 8f,
            maxTextSize = 40f,
            measurer = measurer,
        )
        assertNull(size)
    }

    @Test
    fun rejectsDegenerateInputs() {
        assertNull(TextFitter.fit("text", 0f, 100f, 4f, 40f, measurer))
        assertNull(TextFitter.fit("text", 100f, 0f, 4f, 40f, measurer))
        assertNull(TextFitter.fit("", 100f, 100f, 4f, 40f, measurer))
        assertNull(TextFitter.fit("text", 100f, 100f, 40f, 4f, measurer))
    }

    @Test
    fun unchangedTextDetectionIgnoresCaseAndWhitespace() {
        assertTrue(TextFitter.isUnchanged("  Hello ", "hello"))
        assertTrue(!TextFitter.isUnchanged("Hello", "World"))
    }
}
