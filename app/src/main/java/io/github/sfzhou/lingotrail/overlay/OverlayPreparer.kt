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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A laid-out piece of translated text, positioned relative to its block's pivot. */
class PreparedText(
    val layout: StaticLayout,
    val left: Float,
    val top: Float,
)

/**
 * A translation unit baked into drawable geometry.
 *
 * All coordinates are local to ([pivotX], [pivotY]) and are drawn after rotating by
 * [rotationDegrees], so slanted text on a photo gets a slanted patch that covers
 * exactly the original line instead of a huge axis-aligned rectangle.
 */
class PreparedBlock(
    val pivotX: Float,
    val pivotY: Float,
    val rotationDegrees: Float,
    val backgroundRect: RectF,
    val cornerRadius: Float,
    val backgroundPaint: Paint,
    val texts: List<PreparedText>,
    /** Repaired crop of the photo, drawn instead of a flat colour when available. */
    val patchBitmap: Bitmap? = null,
    /** Where the patch goes, in axis-aligned virtual image space. */
    val patchRect: RectF? = null,
    val patchPaint: Paint? = null,
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
 * Turns translated blocks into drawable geometry.
 *
 * The size of the translation is derived from the ink of the original text, not
 * from the OCR box: the text direction is measured with PCA, the glyph height from
 * the ink bands, and the patch is the tight rotated band plus a small safety
 * margin. This keeps a translation visually the same size as the words it replaces,
 * even when the photo was taken at an angle.
 *
 * Runs on a background thread; the result is cached by the viewer and reused for
 * sharing the composited image.
 */
object OverlayPreparer {

    private const val MAX_SAMPLES = 2_400
    private const val MIN_TEXT_SIZE = 4f
    private const val MAX_ABSOLUTE_FONT_SIZE = 140f
    private const val TEXT_INSET_RATIO = 0.05f
    private const val BACKGROUND_PADDING_RATIO = 0.30f
    private const val TABLE_PADDING_X_RATIO = 0.05f
    private const val TABLE_PADDING_Y_RATIO = 0.05f
    private const val TABLE_RIGHT_COLUMN_SHARE = 0.36f
    private const val NUMERIC_CELL_MAX_LENGTH = 16

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

        val ocrUnion = RectF(
            block.boxes.minOf { it.left } * scale,
            block.boxes.minOf { it.top } * scale,
            block.boxes.maxOf { it.right } * scale,
            block.boxes.maxOf { it.bottom } * scale,
        )
        if (ocrUnion.width() < 2f || ocrUnion.height() < 2f) return null
        if (ocrUnion.top >= virtualHeight) return null

        // Already in the target language: leave the original photo untouched.
        if (block.table == null && translation.equals(block.original.trim(), ignoreCase = true)) {
            return null
        }

        val placement = InkAnalyzer.analyze(bitmap, ocrUnion, block.lineCount)
            ?: fallbackPlacement(ocrUnion, block.lineCount)
        // Curved logo lettering cannot be straightened into a readable overlay; leave
        // those pixels untouched rather than drawing a garbled rotated label.
        if (!placement.isLinear) return null
        val colors = sampleColors(bitmap, item, placement.axisBounds)
        val bold = block.label.lowercase() in TITLE_LABELS

        val texts = if (block.table != null) {
            prepareTableTexts(block, placement, colors, bold)
        } else {
            prepareFlowTexts(block, placement, colors, bold)
        }
        if (texts.isEmpty()) return null

        val padding = max(2f, placement.glyphHeight * BACKGROUND_PADDING_RATIO)
        val backgroundRect = RectF(placement.coverage).apply {
            inset(-padding, -padding)
        }
        val cornerRadius = min(backgroundRect.height() * 0.16f, 10f)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.background
            style = Paint.Style.FILL
        }
        // Textured patches are axis-aligned crops, which only line up with the text
        // when the photo is not tilted; rotated blocks keep the rotated flat patch.
        val patch = if (abs(placement.rotationDegrees) < 1f) {
            PatchBuilder.build(bitmap, placement)
        } else {
            null
        }
        return PreparedBlock(
            pivotX = placement.centerX,
            pivotY = placement.centerY,
            rotationDegrees = placement.rotationDegrees,
            backgroundRect = backgroundRect,
            cornerRadius = cornerRadius,
            backgroundPaint = backgroundPaint,
            texts = texts,
            patchBitmap = patch?.bitmap,
            patchRect = patch?.rectVirtual,
            patchPaint = patch?.let { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) },
        )
    }

    /** Lays out a line or a wrapped paragraph inside the measured text band. */
    private fun prepareFlowTexts(
        block: TranslationBlock,
        placement: InkPlacement,
        colors: ColorAnalysis.SampledColors,
        bold: Boolean,
    ): List<PreparedText> {
        val text = block.translation.trim()
        if (text.isEmpty()) return emptyList()

        val glyphHeight = placement.glyphHeight
        val inset = max(1f, glyphHeight * TEXT_INSET_RATIO)
        val availableWidth = placement.halfLength * 2f - inset * 2f
        val availableHeight = placement.halfHeight * 2f
        if (availableWidth <= 2f || availableHeight <= 2f) return emptyList()

        val minTextSize = max(MIN_TEXT_SIZE, glyphHeight * 0.22f)
        val maxTextSize = min(
            InkMath.targetFontSize(glyphHeight),
            MAX_ABSOLUTE_FONT_SIZE,
        ).coerceAtLeast(minTextSize)

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
                left = -placement.halfLength + inset,
                top = -placement.halfHeight + (availableHeight - layout.height) / 2f,
            ),
        )
    }

    /**
     * Re-draws a recognised table in the measured band: rows are spread evenly
     * across the band and cells are fitted into columns, with price/number columns
     * right-aligned. The whole table is rotated with the original when the photo is
     * taken at an angle.
     */
    private fun prepareTableTexts(
        block: TranslationBlock,
        placement: InkPlacement,
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

        val totalWidth = placement.halfLength * 2f
        val rowHeight = placement.halfHeight * 2f / rowCount
        val columnWidths = if (columnCount == 2) {
            listOf(totalWidth * (1f - TABLE_RIGHT_COLUMN_SHARE), totalWidth * TABLE_RIGHT_COLUMN_SHARE)
        } else {
            List(columnCount) { totalWidth / columnCount }
        }
        val insetX = max(1f, rowHeight * TABLE_PADDING_X_RATIO)
        val insetY = max(0.5f, rowHeight * TABLE_PADDING_Y_RATIO)
        val availableHeight = (rowHeight - insetY * 2f).coerceAtLeast(2f)
        val minTextSize = max(MIN_TEXT_SIZE, rowHeight * 0.18f)
        val maxTextSize = min(
            rowHeight * 0.85f,
            InkMath.targetFontSize(placement.glyphHeight),
        ).coerceAtLeast(minTextSize)

        val texts = mutableListOf<PreparedText>()
        rows.forEachIndexed { rowIndex, cells ->
            cells.forEachIndexed { columnIndex, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                val columnLeft = -placement.halfLength + columnWidths.take(columnIndex).sum()
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
                    top = -placement.halfHeight + rowHeight * rowIndex + insetY +
                        (availableHeight - layout.height) / 2f,
                )
            }
        }
        return texts
    }

    private fun fallbackPlacement(ocrUnion: RectF, lineCount: Int): InkPlacement {
        val safeLineCount = lineCount.coerceAtLeast(1)
        val glyphHeight = when {
            safeLineCount <= 1 -> ocrUnion.height() * 0.8f
            else -> InkMath.glyphHeightFromLineCount(ocrUnion.height(), safeLineCount)
        }
        return InkPlacement(
            centerX = ocrUnion.centerX(),
            centerY = ocrUnion.centerY(),
            rotationDegrees = 0f,
            halfLength = ocrUnion.width() / 2f,
            halfHeight = ocrUnion.height() / 2f,
            glyphHeight = glyphHeight.coerceAtLeast(3f),
            axisBounds = RectF(ocrUnion),
            coverage = RectF(
                -ocrUnion.width() / 2f,
                -ocrUnion.height() / 2f,
                ocrUnion.width() / 2f,
                ocrUnion.height() / 2f,
            ),
        )
    }

    private fun looksNumeric(cell: String): Boolean {
        if (cell.length > NUMERIC_CELL_MAX_LENGTH) return false
        if (cell.none { it.isDigit() }) return false
        return cell.none { it.isLetter() && it.code > 0x2E80 }
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
        val virtualScaleX = item.width.toFloat() / PreparedOverlay.VIRTUAL_WIDTH
        val virtualScaleY = item.height.toFloat() /
            (PreparedOverlay.VIRTUAL_WIDTH * item.height / item.width.coerceAtLeast(1))
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
        // Pass 1: cover the original words. Patches are axis-aligned crops of the
        // photo, so a rotated block's patch is larger than its text band; drawing all
        // patches first keeps later patches from covering earlier translations.
        for (block in overlay.blocks) {
            val patch = block.patchBitmap
            val patchRect = block.patchRect
            if (patch != null && patchRect != null) {
                canvas.drawBitmap(patch, null, patchRect, block.patchPaint)
            } else {
                canvas.save()
                canvas.translate(block.pivotX, block.pivotY)
                if (block.rotationDegrees != 0f) {
                    canvas.rotate(block.rotationDegrees)
                }
                canvas.drawRoundRect(
                    block.backgroundRect,
                    block.cornerRadius,
                    block.cornerRadius,
                    block.backgroundPaint,
                )
                canvas.restore()
            }
        }
        // Pass 2: draw the translations on top of every patch.
        for (block in overlay.blocks) {
            canvas.save()
            canvas.translate(block.pivotX, block.pivotY)
            if (block.rotationDegrees != 0f) {
                canvas.rotate(block.rotationDegrees)
            }
            for (text in block.texts) {
                canvas.save()
                canvas.translate(text.left, text.top)
                text.layout.draw(canvas)
                canvas.restore()
            }
            canvas.restore()
        }
    }

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
