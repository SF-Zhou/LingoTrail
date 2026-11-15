package io.github.sfzhou.lingotrail.data

import io.github.sfzhou.lingotrail.net.ApiErrorKind
import io.github.sfzhou.lingotrail.ocr.PixelRect
import io.github.sfzhou.lingotrail.ocr.TableContent

enum class ScanStatus { PROCESSING, DONE, FAILED }

/**
 * One translated unit as shown over the photo: a line, a wrapped paragraph, or a
 * table with translated cells.
 *
 * [boxes] are pixel rectangles in the stored image's coordinate system. A block
 * normally has exactly one box; wrapped paragraphs have one per visual line.
 */
data class TranslationBlock(
    val original: String,
    val translation: String,
    val label: String,
    val boxes: List<PixelRect>,
    /** Visual lines the original block occupied; keeps paragraph type size honest. */
    val lineCount: Int = 1,
    /** Translated table rows, when the block was recognised as a table. */
    val table: TableContent? = null,
)

/**
 * A scan in the user's history: the stored photo plus its translation.
 */
data class HistoryItem(
    val id: String,
    val createdAt: Long,
    val targetLanguage: TargetLanguage,
    val status: ScanStatus,
    val width: Int,
    val height: Int,
    val sourceLanguage: String? = null,
    val errorKind: ApiErrorKind? = null,
    val blocks: List<TranslationBlock> = emptyList(),
) {
    val hasOverlay: Boolean
        get() = blocks.any { it.boxes.isNotEmpty() && (it.translation.isNotBlank() || it.table != null) }

    val isBusy: Boolean get() = status == ScanStatus.PROCESSING
}
