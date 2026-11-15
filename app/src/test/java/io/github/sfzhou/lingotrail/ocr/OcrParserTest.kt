package io.github.sfzhou.lingotrail.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrParserTest {

    /** Excerpt of a real response from POST /v1/tools/ocr. */
    private val realResponse = """
        <｜ref｜>sub_title<｜/ref｜><｜box｜>[[56,60,556,112]]<｜/box｜> CITY MUSEUM 
        <｜ref｜>text<｜/ref｜><｜box｜>[[56,127,290,159]]<｜/box｜> Opening Hours 
        <｜ref｜>text<｜/ref｜><｜box｜>[[56,241,653,273]]<｜/box｜> Monday - Friday: 9:00 AM - 5:00 PM 
        <｜ref｜>text<｜/ref｜><｜box｜>[[81,533,513,575]]<｜/box｜> 本日は休館日です 
        <｜ref｜>text<｜/ref｜><｜box｜>[[56,871,376,903]]<｜/box｜> Café & Gift Shop →
    """.trimIndent()

    @Test
    fun parsesRealApiLayoutTokens() {
        val lines = OcrParser.parse(realResponse)

        assertEquals(5, lines.size)
        assertEquals("sub_title", lines[0].label)
        assertEquals("CITY MUSEUM", lines[0].text)
        assertEquals(listOf("text", "text", "text", "text"), lines.drop(1).map { it.label })
        assertEquals("本日は休館日です", lines[3].text)

        val first = lines[0].boxes.single()
        assertEquals(0.056f, first.left, 0.0001f)
        assertEquals(0.060f, first.top, 0.0001f)
        assertEquals(0.556f, first.right, 0.0001f)
        assertEquals(0.112f, first.bottom, 0.0001f)
    }

    @Test
    fun acceptsAsciiPipeDelimitersAndMultipleRects() {
        val page = "<|ref|>text<|/ref|><|box|>[[100,100,200,200],[300,300,400,400]]<|/box|> Hello world"
        val lines = OcrParser.parse(page)

        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)
        assertEquals(2, lines[0].boxes.size)
        assertEquals(0.3f, lines[0].boxes[1].left, 0.0001f)
        assertEquals(0.4f, lines[0].boxes[1].bottom, 0.0001f)
    }

    @Test
    fun fallsBackToPlainTextWithoutTokens() {
        val lines = OcrParser.parse("  Just some recognised text \n\n")
        assertEquals(1, lines.size)
        assertEquals("Just some recognised text", lines[0].text)
        assertTrue(lines[0].boxes.isEmpty())
        assertEquals(null, lines[0].bounds)
    }

    @Test
    fun dropsDegenerateBoxesAndKeepsDocumentOrder() {
        val page = listOf(
            "<｜ref｜>text<｜/ref｜><｜box｜>[[500,500,560,560]]<｜/box｜> second",
            "<｜ref｜>text<｜/ref｜><｜box｜>[[100,100,100.4,100.4]]<｜/box｜> tiny",
            "<｜ref｜>text<｜/ref｜><｜box｜>[[100,100,400,160]]<｜/box｜> first",
        ).joinToString("\n")

        val lines = OcrParser.parse(page)
        assertEquals(3, lines.size)
        // Parsing keeps document order and keeps text whose box was unusable,
        // so it can still be translated; reading order is applied separately.
        assertEquals(listOf("second", "tiny", "first"), lines.map { it.text })
        assertTrue(lines[1].boxes.isEmpty())
        assertEquals(
            listOf("tiny", "first", "second"),
            LineOrder.sort(lines).map { it.text },
        )
    }

    @Test
    fun handlesEmptyAndWhitespaceInput() {
        assertTrue(OcrParser.parse("").isEmpty())
        assertTrue(OcrParser.parse("   \n  ").isEmpty())
    }

    @Test
    fun clampsOutOfRangeCoordinates() {
        val line = OcrParser.parse("<｜ref｜>text<｜/ref｜><｜box｜>[[-50,-50,1200,1100]]<｜/box｜> hi").single()
        val box = line.boxes.single()
        assertEquals(0f, box.left, 0.0001f)
        assertEquals(1f, box.bottom, 0.0001f)
    }
}
