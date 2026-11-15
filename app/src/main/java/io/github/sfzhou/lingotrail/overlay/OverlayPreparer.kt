package io.github.sfzhou.lingotrail.overlay

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import io.github.sfzhou.lingotrail.data.HistoryItem
import io.github.sfzhou.lingotrail.data.TranslationBlock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A laid-out piece of translated text positioned in virtual image space. */
class PreparedText(
    val layout: StaticLayout,
    val left: Float,
    val top: Float,
)

/** A translation unit baked into drawable geometry, in image-independent "virtual" pixels. */
class PreparedBlock(
    val backgroundRect: RectF,
    val cornerRadius: Float,
    val backgroundPaint: Paint,
    val texts: List<PreparedText>,
)

/**
 * All overlay geometry for one scan.
 *
 * Coordinates use a virtual image space that is always 1000 units wide, so the
 * exact same overlay can be drawn in the viewer at any zoom level and into the
 * full-resolution share export.
 */
class PreparedOverlay(
    val blocks: List<PreparedBlock>,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val virtualWidth: Float = VIRTUAL_WIDTH
    val virtualHeight: Float = VIRTUAL_WIDTH * imageHeight / imageWidth.coerceAtLeast(1)

    companion object {
        const val VIRTUAL_WIDTH = 1000f
    }
}

/**
 * Turns translated blocks into drawable geometry: samples the original colours,
 * picks the largest legible type size, and builds the text layouts.
 *
 * Plain lines and wrapped paragraphs are drawn as one fitted text block. Tables
 * keep their structure: rows are spread evenly over the original table area and
 * cells are laid out in columns, with numeric right-hand columns aligned right.
 *
 * Runs on a background thread; the result is cached by the viewer and reused for
 * sharing the composited image.
 */
object OverlayPreparer {

    private const val MAX_SAMPLES = 2_400
    private const val MIN_TEXT_SIZE = 4f
    private const val TEXT_INSET_RATIO = 0.04f
    private const val BACKGROUND_PADDING_RATIO = 0.3f
    private const val TABLE_PADDING_X_RATIO = 0.05f
    private const val TABLE_PADDING_Y_RATIO = 0.06f
    private const val TABLE_RIGHT_COLUMN_SHARE = 0.36f
    private const val NUMERIC_CELL_MAX_LENGTH = 16
    private const val INK_DISTANCE = 42f

    private val TITLE_LABELS = setOf("title", "sub_title", "doctitle", "page_title")

    fun prepare(bitmap: Bitmap, item: HistoryItem): PreparedOverlay {
        val scale = PreparedOverlay.VIRTUAL_WIDTH / item.width.coerceAtLeast(1)
        val virtualHeight = item.height * scale
        val blocks = item.blocks.mapNotNull { block ->
            prepareBlock(bitmap, item, block, scale, virtualHeight)
        }
        return PreparedOverlay(blocks = blocks, imageWidth = item.width, imageHeight = item.height)
    }

    private fun prepareBlock(
        bitmap: Bitmap,
        item: HistoryItem,
        block: TranslationBlock,
        scale: Float,
        virtualHeight: Float,
    ): PreparedBlock? {
        val translation = block.translation.trim()
        if (translation.isEmpty() && block.table == null) return null
        if (block.boxes.isEmpty()) return null

        val rects = block.boxes.map { box ->
            RectF(
                box.left * scale,
                box.top * scale,
                box.right * scale,
                box.bottom * scale,
            )
        }.filter { it.width() > 1f && it.height() > 1f }
        if (rects.isEmpty()) return null

        val ocrUnion = RectF(rects.first())
        rects.drop(1).forEach { rect -> ocrUnion.union(rect) }
        if (ocrUnion.width() < 2f || ocrUnion.height() < 2f) return null
        if (ocrUnion.top >= virtualHeight) return null

        // Already in the target language: leave the original photo untouched.
        if (block.table == null && translation.equals(block.original.trim(), ignoreCase = true)) return null

        // OCR boxes are usually close but not pixel-exact; snapping a single line to
        // the actual ink lets the translated text sit exactly where the original
        // glyphs were, while the background patch covers both the OCR box and the
        // measured ink so old text can never peek out. Multi-line unions
        // (paragraphs, tables) are accurate enough and snapping them is riskier,
        // so they keep the OCR geometry.
        val snap = if (block.table == null) {
            snapToInk(bitmap, ocrUnion, virtualHeight, block)
        } else {
            null
        }
        val textRect = snap?.textRect ?: ocrUnion
        val coverageRect = snap?.coverageRect ?: ocrUnion
        if (coverageRect.width() < 2f || coverageRect.height() < 2f) return null
        if (coverageRect.top >= virtualHeight) return null

        val colors = sampleColors(bitmap, item, textRect)
        val bold = block.label.lowercase() in TITLE_LABELS
        val texts = if (block.table != null) {
            prepareTableTexts(block, textRect, colors, bold)
        } else {
            prepareFlowTexts(block, textRect, colors, bold)
        }
        if (texts.isEmpty()) return null

        val averageLineHeight = (textRect.height() / block.lineCount.coerceAtLeast(1))
        val padding = max(2f, averageLineHeight * BACKGROUND_PADDING_RATIO)
        val backgroundRect = RectF(
            floor(coverageRect.left - padding),
            floor(coverageRect.top - padding),
            ceil(coverageRect.right + padding),
            ceil(coverageRect.bottom + padding),
        )
        val cornerRadius = min(backgroundRect.height() * 0.16f, 10f)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.background
            style = Paint.Style.FILL
        }
        return PreparedBlock(
            backgroundRect = backgroundRect,
            cornerRadius = cornerRadius,
            backgroundPaint = backgroundPaint,
            texts = texts,
        )
    }

    /** Lays out a line or a wrapped paragraph, fitted to the original text area. */
    private fun prepareFlowTexts(
        block: TranslationBlock,
        union: RectF,
        colors: ColorAnalysis.SampledColors,
        bold: Boolean,
    ): List<PreparedText> {
        val text = block.translation.trim()
        if (text.isEmpty()) return emptyList()

        val lineCount = block.lineCount.coerceAtLeast(1)
        val averageLineHeight = union.height() / lineCount
        val maxTextSize = min(union.height() * 0.96f, averageLineHeight * 1.4f)
        val minTextSize = max(MIN_TEXT_SIZE, averageLineHeight * 0.22f)
        val horizontalInset = max(1.5f, averageLineHeight * TEXT_INSET_RATIO)
        val availableWidth = union.width() - horizontalInset * 2f
        val availableHeight = union.height()
        if (availableWidth <= 2f || availableHeight <= 2f || maxTextSize <= minTextSize) return emptyList()

        val paint = textPaint(colors.foreground, bold)
        val measurer = staticLayoutMeasurer(paint, text, Layout.Alignment.ALIGN_NORMAL)
        val textSize = TextFitter.fit(
            text = text,
            maxWidth = availableWidth,
            maxHeight = availableHeight,
            minTextSize = minTextSize,
            maxTextSize = maxTextSize,
            measurer = measurer,
        ) ?: return emptyList()

        paint.textSize = textSize
        val layout = buildLayout(text, paint, availableWidth, Layout.Alignment.ALIGN_NORMAL)
        if (layout.height <= 0) return emptyList()
        return listOf(
            PreparedText(
                layout = layout,
                left = union.left + horizontalInset,
                top = union.top + (union.height() - layout.height) / 2f,
            ),
        )
    }

    /**
     * Re-draws a recognised table: rows are distributed evenly over the table box and
     * each cell is auto-fitted into its column. Price/number columns are right-aligned
     * so the result keeps the original table's reading rhythm.
     */
    private fun prepareTableTexts(
        block: TranslationBlock,
        union: RectF,
        colors: ColorAnalysis.SampledColors,
        bold: Boolean,
    ): List<PreparedText> {
        val rows = block.table?.rows?.map { row -> row.map { it.trim() } }
            ?.filter { row -> row.any { it.isNotEmpty() } }
            ?: return emptyList()
        if (rows.isEmpty()) return emptyList()

        val rowCount = rows.size
        val columnCount = rows.maxOf { it.size }
        if (columnCount == 0) return emptyList()

        val rowHeight = union.height() / rowCount
        val columnWidths = if (columnCount == 2) {
            listOf(union.width() * (1f - TABLE_RIGHT_COLUMN_SHARE), union.width() * TABLE_RIGHT_COLUMN_SHARE)
        } else {
            List(columnCount) { union.width() / columnCount }
        }
        val insetX = max(1f, rowHeight * TABLE_PADDING_X_RATIO)
        val insetY = max(0.5f, rowHeight * TABLE_PADDING_Y_RATIO)
        val availableHeight = (rowHeight - insetY * 2f).coerceAtLeast(2f)
        val minTextSize = max(MIN_TEXT_SIZE, rowHeight * 0.2f)
        val maxTextSize = (rowHeight * 0.95f).coerceAtLeast(minTextSize)

        val texts = mutableListOf<PreparedText>()
        rows.forEachIndexed { rowIndex, cells ->
            cells.forEachIndexed { columnIndex, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                val columnLeft = union.left + columnWidths.take(columnIndex).sum()
                val availableWidth = (columnWidths[columnIndex] - insetX * 2f).coerceAtLeast(2f)
                val rightAlign = columnIndex == cells.lastIndex &&
                    columnCount >= 2 &&
                    looksNumeric(cell)
                val alignment = if (rightAlign) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                val paint = textPaint(colors.foreground, bold)
                val measurer = staticLayoutMeasurer(paint, cell, alignment)
                val textSize = TextFitter.fit(
                    text = cell,
                    maxWidth = availableWidth,
                    maxHeight = availableHeight,
                    minTextSize = minTextSize,
                    maxTextSize = maxTextSize,
                    measurer = measurer,
                ) ?: return@forEachIndexed

                paint.textSize = textSize
                val layout = buildLayout(cell, paint, availableWidth, alignment)
                if (layout.height <= 0) return@forEachIndexed
                texts += PreparedText(
                    layout = layout,
                    left = columnLeft + insetX,
                    top = union.top + rowHeight * rowIndex + insetY +
                        (availableHeight - layout.height) / 2f,
                )
            }
        }
        return texts
    }

    private fun looksNumeric(cell: String): Boolean {
        if (cell.length > NUMERIC_CELL_MAX_LENGTH) return false
        if (cell.none { it.isDigit() }) return false
        return cell.none { it.isLetter() && it.code > 0x2E80 } // no CJK letters in numeric columns
    }

    private fun textPaint(color: Int, bold: Boolean): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun staticLayoutMeasurer(
        paint: TextPaint,
        text: String,
        alignment: Layout.Alignment,
    ): TextFitter.TextMeasurer = TextFitter.TextMeasurer { _, textSize, maxWidth ->
        paint.textSize = textSize
        val layout = buildLayout(text, paint, maxWidth, alignment)
        TextFitter.FitMetrics(height = layout.height.toFloat(), lineCount = layout.lineCount)
    }

    // Layout.BREAK_STRATEGY_HIGH_QUALITY is a valid StaticLayout value (lint's
    // WrongConstant check wants the API 29 LineBreaker constant instead).
    @SuppressLint("WrongConstant")
    private fun buildLayout(
        text: String,
        paint: TextPaint,
        width: Float,
        alignment: Layout.Alignment,
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
        .setAlignment(alignment)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setLineSpacing(0f, 1.02f)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setMaxLines(16)
        .build()

    private data class InkSnap(val textRect: RectF, val coverageRect: RectF)

    /**
     * Refines an OCR box to the actual ink around it.
     *
     * OCR layout boxes can be a few dozen pixels off, and glyph ascenders such as
     * accents can sit outside the reported box. This scans a vertical window around
     * the box across the full image width, estimates the local paper colour, and
     * locates the ink line that belongs to this block. The returned [InkSnap]
     * carries two rectangles: the measured ink (where the translation is typeset)
     * and the union with the OCR box (the area the patch must cover).
     */
    private fun snapToInk(
        bitmap: Bitmap,
        ocrRect: RectF,
        virtualHeight: Float,
        block: TranslationBlock,
    ): InkSnap? {
        val pxPerVirtualX = bitmap.width / PreparedOverlay.VIRTUAL_WIDTH
        val pxPerVirtualY = bitmap.height / virtualHeight
        val base = RectF(
            ocrRect.left * pxPerVirtualX,
            ocrRect.top * pxPerVirtualY,
            ocrRect.right * pxPerVirtualX,
            ocrRect.bottom * pxPerVirtualY,
        )
        if (base.width() < 2f || base.height() < 2f) return null

        val averageLineHeight =
            (base.height() / block.lineCount.coerceAtLeast(1)).coerceAtLeast(2f)
        val margin = if (block.lineCount > 1) averageLineHeight * 1.5f else averageLineHeight * 0.9f
        val top = (base.top - margin).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (base.bottom + margin).toInt().coerceIn(top + 1, bitmap.height)
        val windowHeight = bottom - top
        if (windowHeight < 4) return null

        val step = max(1, ceil(kotlin.math.sqrt((bitmap.width * windowHeight).toFloat() / 45_000f)).toInt())
        val columns = ceil(bitmap.width.toFloat() / step).toInt().coerceAtLeast(1)
        val rows = ceil(windowHeight.toFloat() / step).toInt().coerceAtLeast(1)
        val pixels = IntArray(columns * rows)
        var index = 0
        for (row in 0 until rows) {
            val y = (top + row * step).coerceAtMost(bottom - 1)
            for (column in 0 until columns) {
                val x = (column * step).coerceAtMost(bitmap.width - 1)
                pixels[index++] = bitmap.getPixel(x, y)
            }
        }
        val background = ColorAnalysis.dominantColor(pixels)

        // ---- ink rows ----
        val rowInk = IntArray(rows)
        index = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (ColorAnalysis.colorDistance(pixels[index++], background) > INK_DISTANCE) {
                    rowInk[row]++
                }
            }
        }
        val minInkPerRow = max(1, (columns * 0.005f).toInt())
        val inkRows = (0 until rows).filter { rowInk[it] >= minInkPerRow }
        if (inkRows.isEmpty()) return null
        val inkRuns = runsOf(inkRows)

        val baseTopRow = ((base.top - top) / step).toInt().coerceIn(0, rows - 1)
        val baseBottomRow = ((base.bottom - top) / step).toInt().coerceIn(0, rows - 1)
        val baseRowCenter = (baseTopRow + baseBottomRow) / 2f

        fun rowOverlap(run: IntRange): Int =
            max(0, min(run.last, baseBottomRow) - max(run.first, baseTopRow) + 1)

        fun rowDistance(run: IntRange): Float =
            kotlin.math.abs((run.first + run.last) / 2f - baseRowCenter)

        val selectedRuns: List<IntRange> = if (block.lineCount <= 1) {
            var best = inkRuns.first()
            for (run in inkRuns.drop(1)) {
                if (rowOverlap(run) > rowOverlap(best) ||
                    (rowOverlap(run) == rowOverlap(best) && rowDistance(run) < rowDistance(best))
                ) {
                    best = run
                }
            }
            listOf(best)
        } else {
            // Paragraphs are recognised as one block spanning several visual lines.
            // Start from the runs the OCR box covers, then chain neighbouring runs
            // while the line spacing stays paragraph-like.
            val seeds = inkRuns.filter { rowOverlap(it) > 0 }
                .ifEmpty { listOf(inkRuns.minByOrNull { rowDistance(it) } ?: inkRuns.first()) }
                .sortedBy { it.first }
            val maxRunGap = (averageLineHeight * 1.2f / step).toInt().coerceAtLeast(2)
            val maxRuns = (block.lineCount + 1).coerceAtLeast(2)
            val selected = seeds.toMutableList()
            var changed = true
            while (changed && selected.size < maxRuns) {
                changed = false
                val firstSelected = selected.first().first
                val lastSelected = selected.last().last
                val previousRun = inkRuns.lastOrNull { it.last < firstSelected }
                if (previousRun != null && firstSelected - previousRun.last <= maxRunGap &&
                    selected.size < maxRuns
                ) {
                    selected.add(0, previousRun)
                    changed = true
                }
                val nextRun = inkRuns.firstOrNull { it.first > lastSelected }
                if (nextRun != null && nextRun.first - lastSelected <= maxRunGap &&
                    selected.size < maxRuns
                ) {
                    selected.add(nextRun)
                    changed = true
                }
            }
            selected.distinctBy { it.first }.sortedBy { it.first }
        }

        val bandTop = selectedRuns.first().first
        val bandBottom = selectedRuns.last().last

        // ---- ink columns inside the selected line band ----
        val columnInk = IntArray(columns)
        index = 0
        for (row in 0 until rows) {
            val inBand = row in bandTop..bandBottom
            for (column in 0 until columns) {
                if (inBand && ColorAnalysis.colorDistance(pixels[index], background) > INK_DISTANCE) {
                    columnInk[column]++
                }
                index++
            }
        }
        val inkedColumns = (0 until columns).filter { columnInk[it] > 0 }
        if (inkedColumns.isEmpty()) return null

        val refinedLeftColumn: Int
        val refinedRightColumn: Int
        if (block.lineCount <= 1) {
            // Bridge word gaps but not the gap to a separate column.
            val columnGap = max(2, (averageLineHeight * 1.1f / step).toInt())
            val columnRuns = mutableListOf<IntRange>()
            var runStart = inkedColumns.first()
            var previousColumn = inkedColumns.first()
            for (column in inkedColumns.drop(1)) {
                if (column - previousColumn > columnGap) {
                    columnRuns += runStart..previousColumn
                    runStart = column
                }
                previousColumn = column
            }
            columnRuns += runStart..previousColumn

            val baseLeftColumn = (base.left / step).toInt().coerceIn(0, columns - 1)
            val baseRightColumn = (base.right / step).toInt().coerceIn(0, columns - 1)
            val baseColumnCenter = (baseLeftColumn + baseRightColumn) / 2f
            fun columnOverlap(run: IntRange): Int =
                max(0, min(run.last, baseRightColumn) - max(run.first, baseLeftColumn) + 1)
            fun columnDistance(run: IntRange): Float =
                kotlin.math.abs((run.first + run.last) / 2f - baseColumnCenter)

            var best = columnRuns.first()
            for (run in columnRuns.drop(1)) {
                if (columnOverlap(run) > columnOverlap(best) ||
                    (columnOverlap(run) == columnOverlap(best) && columnDistance(run) < columnDistance(best))
                ) {
                    best = run
                }
            }
            refinedLeftColumn = best.first
            refinedRightColumn = best.last
        } else {
            refinedLeftColumn = inkedColumns.first()
            refinedRightColumn = inkedColumns.last()
        }

        val refinedTop = (top + bandTop * step - step).toFloat().coerceAtLeast(0f)
        val refinedBottom = (top + bandBottom * step + step * 2).toFloat()
            .coerceAtMost(bitmap.height.toFloat())
        val refinedLeft = (refinedLeftColumn * step - step).toFloat().coerceAtLeast(0f)
        val refinedRight = (refinedRightColumn * step + step * 2).toFloat()
            .coerceAtMost(bitmap.width.toFloat())
        if (refinedRight - refinedLeft < 2f || refinedBottom - refinedTop < 2f) return null

        // The ink is where the translation is typeset; the patch additionally covers
        // the area the OCR box claimed, so nothing of the original sticks out.
        val coverageTop = min(refinedTop, base.top)
        val coverageBottom = max(refinedBottom, base.bottom)
        val coverageLeft = min(refinedLeft, base.left)
        val coverageRight = max(refinedRight, base.right)

        fun toVirtual(rect: RectF) = RectF(
            rect.left / pxPerVirtualX,
            rect.top / pxPerVirtualY,
            rect.right / pxPerVirtualX,
            rect.bottom / pxPerVirtualY,
        )
        return InkSnap(
            textRect = toVirtual(RectF(refinedLeft, refinedTop, refinedRight, refinedBottom)),
            coverageRect = toVirtual(
                RectF(coverageLeft, coverageTop, coverageRight, coverageBottom),
            ),
        )
    }

    private fun runsOf(indices: List<Int>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = indices.first()
        var previous = indices.first()
        for (index in indices.drop(1)) {
            if (index - previous > 1) {
                runs += start..previous
                start = index
            }
            previous = index
        }
        runs += start..previous
        return runs
    }

    /**
     * Samples the photo under the original text and derives the patch colour and a
     * matching ink colour. The sampling grid is small, so this stays cheap even for
     * a 2560 px photo.
     */
    private fun sampleColors(
        bitmap: Bitmap,
        item: HistoryItem,
        virtualRect: RectF,
    ): ColorAnalysis.SampledColors {
        val bitmapScaleX = bitmap.width.toFloat() / item.width.coerceAtLeast(1)
        val bitmapScaleY = bitmap.height.toFloat() / item.height.coerceAtLeast(1)
        // virtualRect is in 1000-wide virtual space; convert through image pixels.
        val virtualScaleX = item.width.toFloat() / PreparedOverlay.VIRTUAL_WIDTH
        val virtualScaleY = item.height.toFloat() / (PreparedOverlay.VIRTUAL_WIDTH * item.height / item.width.coerceAtLeast(1))
        val left = (virtualRect.left * virtualScaleX * bitmapScaleX).toInt().coerceIn(0, bitmap.width - 1)
        val top = (virtualRect.top * virtualScaleY * bitmapScaleY).toInt().coerceIn(0, bitmap.height - 1)
        val right = (virtualRect.right * virtualScaleX * bitmapScaleX).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (virtualRect.bottom * virtualScaleY * bitmapScaleY).toInt().coerceIn(top + 1, bitmap.height)

        val width = right - left
        val height = bottom - top
        val step = max(1, ceil(kotlin.math.sqrt((width * height).toFloat() / MAX_SAMPLES)).toInt())
        val columns = ceil(width.toFloat() / step).toInt().coerceAtLeast(1)
        val rows = ceil(height.toFloat() / step).toInt().coerceAtLeast(1)
        val pixels = IntArray(columns * rows)
        var index = 0
        for (row in 0 until rows) {
            val y = (top + row * step).coerceAtMost(bottom - 1)
            for (column in 0 until columns) {
                val x = (left + column * step).coerceAtMost(right - 1)
                pixels[index++] = bitmap.getPixel(x, y)
            }
        }
        return ColorAnalysis.analyze(pixels)
    }
}

/** Draws a prepared overlay; the canvas must already be in virtual image space. */
object PhotoOverlayRenderer {

    fun draw(canvas: Canvas, overlay: PreparedOverlay) {
        for (block in overlay.blocks) {
            canvas.drawRoundRect(
                block.backgroundRect,
                block.cornerRadius,
                block.cornerRadius,
                block.backgroundPaint,
            )
            for (text in block.texts) {
                canvas.save()
                canvas.translate(text.left, text.top)
                text.layout.draw(canvas)
                canvas.restore()
            }
        }
    }

    /**
     * Renders photo + overlay into one bitmap at the photo's stored resolution,
     * used by the share sheet.
     */
    fun renderComposite(photo: Bitmap, overlay: PreparedOverlay): Bitmap {
        val result = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(photo, null, RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()), null)
        val scale = photo.width.toFloat() / overlay.virtualWidth
        canvas.save()
        canvas.scale(scale, scale)
        draw(canvas, overlay)
        canvas.restore()
        return result
    }
}
