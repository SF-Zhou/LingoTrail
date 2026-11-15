package io.github.sfzhou.lingotrail.ocr

/**
 * Parses the raw page text returned by the DeepSeek OCR tool.
 *
 * The tool interleaves layout tokens with the recognised text, for example:
 *
 * ```
 * <｜ref｜>text<｜/ref｜><｜box｜>[[56,241,653,273]]<｜/box｜> Monday - Friday: 9:00 AM - 5:00 PM
 * ```
 *
 * The token delimiters use U+FF5C (fullwidth vertical line); a few defensive
 * fallbacks accept the ASCII pipe as well. Coordinates are normalized to
 * 0..1000 by the service and converted to 0..1 here.
 *
 * Text blocks can carry light markup (headings, HTML tables, multi-line
 * paragraphs); [OcrMarkup] cleans that up.
 */
object OcrParser {

    private const val BAR = "[\uFF5C|]"

    private val refToken = Regex("<$BAR" + "ref$BAR>([\\s\\S]*?)<$BAR" + "/ref$BAR>")
    private val boxToken = Regex("<$BAR" + "box$BAR>([\\s\\S]*?)<$BAR" + "/box$BAR>")
    private val numberToken = Regex("-?\\d+(?:\\.\\d+)?")

    private const val MIN_BOX_SIZE = 0.0015f
    private const val MAX_BOXES_PER_LINE = 8

    /**
     * Parses one OCR page into lines in reading order. Text without a layout box is
     * returned as well (it is translatable, just not overlayable).
     */
    fun parse(pageText: String): List<OcrLine> {
        if (pageText.isBlank()) return emptyList()

        val refMatches = refToken.findAll(pageText).toList()
        if (refMatches.isEmpty()) {
            val plain = OcrMarkup.cleanInline(pageText)
            return if (plain.isNotEmpty()) listOf(OcrLine(label = "text", text = plain)) else emptyList()
        }

        val lines = mutableListOf<OcrLine>()
        refMatches.forEachIndexed { index, match ->
            val label = match.groupValues[1].trim().lowercase().ifBlank { "text" }
            val segmentStart = match.range.last + 1
            val segmentEnd = refMatches.getOrNull(index + 1)?.range?.first ?: pageText.length
            val segment = pageText.substring(segmentStart.coerceAtMost(segmentEnd), segmentEnd)

            val boxes = extractBoxes(segment)
            val rawText = boxToken.replace(segment, " ")
            val table = OcrMarkup.parseTable(rawText)
            val text = if (table != null) {
                OcrMarkup.plainText(table)
            } else {
                OcrMarkup.cleanInline(rawText)
            }
            if (text.isEmpty() && boxes.isEmpty()) return@forEachIndexed
            lines += OcrLine(
                label = label,
                text = text,
                boxes = boxes,
                table = table,
                // Tables keep their markup for the model, so the rows come back structured.
                translationInput = if (table != null) rawText.trim() else text,
            )
        }
        return lines
    }

    /** Extracts every box token from a segment and turns them into normalized rects. */
    private fun extractBoxes(segment: String): List<NormRect> {
        val rects = mutableListOf<NormRect>()
        for (match in boxToken.findAll(segment)) {
            val numbers = numberToken.findAll(match.groupValues[1])
                .map { it.value.toFloatOrNull() ?: Float.NaN }
                .filter { !it.isNaN() }
                .toList()
            // A box token may hold several rectangles: [[x1,y1,x2,y2],[x1,y1,x2,y2]].
            numbers.chunked(4).forEach { chunk ->
                if (chunk.size == 4) {
                    val rect = NormRect.of(
                        chunk[0] / 1000f,
                        chunk[1] / 1000f,
                        chunk[2] / 1000f,
                        chunk[3] / 1000f,
                    )
                    if (rect.width >= MIN_BOX_SIZE && rect.height >= MIN_BOX_SIZE) {
                        rects += rect
                    }
                }
            }
        }
        return rects
            .sortedWith(compareBy({ it.top }, { it.left }))
            .take(MAX_BOXES_PER_LINE)
    }
}

/**
 * One recognised text block together with the area(s) it occupies.
 *
 * A block can be a single line, a wrapped paragraph (text contains newlines and the
 * box covers the whole paragraph), or a table.
 */
data class OcrLine(
    val label: String,
    val text: String,
    val boxes: List<NormRect> = emptyList(),
    val table: TableContent? = null,
    /** Text sent to the translation model; tables keep their HTML markup here. */
    val translationInput: String = text,
) {
    /** The rectangle used for geometry; the union when a block has several boxes. */
    val bounds: NormRect? = boxes.reduceOrNull { acc, rect -> acc.union(rect) }

    /** Number of visual lines the block originally occupied, used to size the overlay type. */
    val sourceLineCount: Int
        get() = table?.rows?.size?.coerceAtLeast(1) ?: (text.count { it == '\n' } + 1)
}
