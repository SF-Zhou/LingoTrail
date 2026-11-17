package io.github.sfzhou.lingotrail.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMarkupTest {

    @Test
    fun stripsHeadingMarkersAndInlineTags() {
        assertEquals("THE CORNER CAFÉ", OcrMarkup.cleanInline("# THE CORNER CAFÉ"))
        assertEquals("本日のスープ", OcrMarkup.cleanInline("##  本日のスープ  "))
        assertEquals("Bold text", OcrMarkup.cleanInline("<b>Bold</b> text"))
        assertEquals("A & B", OcrMarkup.cleanInline("A &amp; B"))
    }

    @Test
    fun keepsParagraphLineBreaksButCollapsesSpaces() {
        val cleaned = OcrMarkup.cleanInline(
            "The palace was first built in 1395 and\nserved as the main royal residence of the\nJoseon dynasty.",
        )
        assertEquals(3, cleaned.count { it == '\n' } + 1)
        assertTrue(cleaned.startsWith("The palace was first built in 1395 and"))
    }

    @Test
    fun parsesHtmlTablesIntoRows() {
        val table = OcrMarkup.parseTable(
            "<table><tr><td>Espresso</td><td>2.50</td></tr>" +
                "<tr><td>Flat White</td><td>3.80</td></tr></table>",
        )
        assertNotNull(table)
        assertEquals(2, table!!.rows.size)
        assertEquals(listOf("Espresso", "2.50"), table.rows[0])
        assertEquals("Espresso 2.50\nFlat White 3.80", OcrMarkup.plainText(table))
    }

    @Test
    fun decodesEntitiesInsideCellsAndDropsEmptyRows() {
        val table = OcrMarkup.parseTable(
            "<table><tr><td>Caf&#233; corner</td><td>A&amp;B</td></tr><tr><td> </td></tr></table>",
        )
        assertNotNull(table)
        assertEquals(1, table!!.rows.size)
        assertEquals(listOf("Café corner", "A&B"), table.rows[0])
    }

    @Test
    fun returnsNullWithoutTableMarkup() {
        assertNull(OcrMarkup.parseTable("just a line of text"))
        assertNull(OcrMarkup.parseTable("<table></table>"))
    }

    @Test
    fun parsesPageWithHeadingTableAndParagraph() {
        val page = listOf(
            "<｜ref｜>sub_title<｜/ref｜><｜box｜>[[56,60,556,112]]<｜/box｜> # CITY MUSEUM",
            "<｜ref｜>table<｜/ref｜><｜box｜>[[52,188,895,540]]<｜/box｜> " +
                "<table><tr><td>Adult</td><td>12</td></tr><tr><td>Student</td><td>8</td></tr></table>",
            "<｜ref｜>text<｜/ref｜><｜box｜>[[52,600,895,780]]<｜/box｜> First line of a paragraph\n" +
                "second line of the paragraph\nthird line.",
        ).joinToString("\n")

        val lines = OcrParser.parse(page)
        assertEquals(3, lines.size)
        assertEquals("CITY MUSEUM", lines[0].text)
        assertEquals("sub_title", lines[0].label)
        assertEquals(1, lines[0].sourceLineCount)

        assertEquals("table", lines[1].label)
        assertNotNull(lines[1].table)
        assertEquals(listOf("Adult", "12"), lines[1].table!!.rows[0])
        assertTrue(lines[1].translationInput.contains("<table"))

        assertEquals("text", lines[2].label)
        assertEquals(3, lines[2].sourceLineCount)
        assertEquals(1, lines[2].boxes.size)
    }

    @Test
    fun paragraphSourceLineCountForSingleLineIsOne() {
        val line = OcrLine("text", "single line", listOf(NormRect(0f, 0f, 1f, 0.1f)))
        assertEquals(1, line.sourceLineCount)
    }
}
