package io.github.sfzhou.lingotrail.ocr

import kotlin.math.max

/**
 * A table recognised on the page, as rows of cells.
 * Cells are plain strings with inline markup removed.
 */
data class TableContent(val rows: List<List<String>>)

/**
 * The OCR tool returns light markdown/HTML structure alongside plain lines:
 * headings carry `#` prefixes, tables are serialised as `<table>` HTML, and
 * paragraphs arrive as several visual lines inside one text block.
 *
 * This helper converts that markup into plain text for translation, and keeps
 * tables structured so they can be re-drawn in place.
 */
object OcrMarkup {

    private val headingPrefix = Regex("^#{1,6}\\s+")
    private val htmlTag = Regex("<[^>]+>")
    private val tableRow = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    private val tableCell = Regex("<t[dh][^>]*>([\\s\\S]*?)</t[dh]>", RegexOption.IGNORE_CASE)

    private val entities = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&nbsp;" to " ",
    )

    /** True when a recognised line carries table markup instead of plain text. */
    fun containsTable(raw: String): Boolean =
        raw.contains("<table", ignoreCase = true) && raw.contains("<tr", ignoreCase = true)

    /** Parses `<table>` markup into rows of clean cell strings, or null when there is none. */
    fun parseTable(raw: String): TableContent? {
        if (!containsTable(raw)) return null
        val rows = mutableListOf<List<String>>()
        for (rowMatch in tableRow.findAll(raw)) {
            val cells = tableCell.findAll(rowMatch.groupValues[1])
                .map { cellMatch -> cleanInline(cellMatch.groupValues[1], keepLineBreaks = false) }
                .filter { it.isNotEmpty() }
                .toList()
            if (cells.isNotEmpty()) rows += cells
        }
        return rows.takeIf { it.isNotEmpty() }?.let { TableContent(it) }
    }

    /** Plain, translatable text for a table: one visual row per line. */
    fun plainText(table: TableContent): String =
        table.rows.joinToString(separator = "\n") { row -> row.joinToString(separator = " ") }

    /**
     * Removes heading markers and inline HTML tags, normalises whitespace inside
     * each visual line but keeps the line breaks of multi-line paragraphs.
     */
    fun cleanInline(raw: String, keepLineBreaks: Boolean = true): String {
        val cleaned = raw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .let { text -> htmlTag.replace(text, " ") }
            .let(::decodeEntities)
            .replace('\u00A0', ' ')
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")

        val lines = cleaned
            .split('\n')
            .map { line -> headingPrefix.replace(line.trim(), "") }
            .map { line -> line.replace(Regex("[ \\t\\x0B\\f]+"), " ").trim() }
        return if (keepLineBreaks) {
            lines.filter { it.isNotEmpty() }.joinToString("\n")
        } else {
            lines.filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    private fun decodeEntities(text: String): String {
        var result = text
        entities.forEach { (entity, replacement) -> result = result.replace(entity, replacement) }
        return Regex("&#(\\d+);").replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.let { code -> code.toChar().toString() } ?: match.value
        }
    }
}
