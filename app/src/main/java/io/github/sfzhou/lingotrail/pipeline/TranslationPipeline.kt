package io.github.sfzhou.lingotrail.pipeline

import io.github.sfzhou.lingotrail.data.HistoryRepository
import io.github.sfzhou.lingotrail.data.ScanStatus
import io.github.sfzhou.lingotrail.data.SettingsStore
import io.github.sfzhou.lingotrail.data.TranslationBlock
import io.github.sfzhou.lingotrail.net.ApiErrorKind
import io.github.sfzhou.lingotrail.net.ApiException
import io.github.sfzhou.lingotrail.net.DeepSeekApi
import io.github.sfzhou.lingotrail.ocr.GroupGeometry
import io.github.sfzhou.lingotrail.ocr.LineOrder
import io.github.sfzhou.lingotrail.ocr.OcrMarkup
import io.github.sfzhou.lingotrail.ocr.OcrParser
import io.github.sfzhou.lingotrail.ocr.PixelRect
import io.github.sfzhou.lingotrail.util.ImageProcessingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates one scan: OCR → reading order → translation (with optional paragraph
 * grouping) → save the overlay blocks.
 *
 * Jobs live at application scope, so a translation keeps running while the user
 * goes back to the gallery, and the gallery item updates itself when it finishes.
 */
class TranslationPipeline(
    private val repository: HistoryRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Starts (or restarts) translation for [scanId]; repeated calls are ignored while running. */
    fun start(scanId: String) {
        synchronized(jobs) {
            if (jobs[scanId]?.isActive == true) return
            jobs[scanId] = scope.launch { run(scanId) }
                .also { job -> job.invokeOnCompletion { jobs.remove(scanId, job) } }
        }
    }

    fun cancel(scanId: String) {
        jobs.remove(scanId)?.cancel()
    }

    private suspend fun run(scanId: String) {
        val snapshot = settings.current
        val item = repository.find(scanId) ?: return

        if (!snapshot.hasApiKey) {
            repository.update(scanId) {
                it.copy(status = ScanStatus.FAILED, errorKind = ApiErrorKind.AUTH)
            }
            return
        }

        // Re-running a scan (or changing the language and retranslating) always uses
        // the language currently configured in Settings.
        val targetLanguage = snapshot.targetLanguage
        repository.update(scanId) {
            it.copy(
                status = ScanStatus.PROCESSING,
                errorKind = null,
                targetLanguage = targetLanguage,
            )
        }

        try {
            val api = DeepSeekApi(
                baseUrl = snapshot.baseUrl,
                apiKey = snapshot.apiKey,
                model = snapshot.model,
            )
            val imageBytes = withContext(Dispatchers.IO) { repository.imageFile(scanId).readBytes() }
            val pageText = api.recognize(imageBytes)
            val lines = LineOrder.sort(OcrParser.parse(pageText)).filter { it.text.isNotBlank() }

            if (lines.isEmpty()) {
                repository.update(scanId) {
                    it.copy(status = ScanStatus.DONE, blocks = emptyList(), errorKind = null)
                }
                return
            }

            val outcome = api.translateTexts(lines.map { it.translationInput }, targetLanguage)
            val units = GroupGeometry.buildUnits(
                lines = lines,
                lineTranslations = outcome.lines,
                groups = outcome.groups,
            )
            val blocks = units.map { unit ->
                val sourceLines = unit.indices.map { lines[it] }
                val translatedTable = sourceLines.singleOrNull()?.table
                    ?.let { _ -> OcrMarkup.parseTable(unit.translation) }
                TranslationBlock(
                    original = sourceLines.joinToString(separator = "\n") { it.text },
                    translation = if (translatedTable != null) {
                        OcrMarkup.plainText(translatedTable)
                    } else {
                        unit.translation
                    },
                    label = sourceLines.first().label,
                    boxes = sourceLines
                        .flatMap { it.boxes }
                        .map { box -> PixelRect.fromNormalized(box, item.width, item.height) },
                    lineCount = sourceLines.sumOf { it.sourceLineCount }.coerceAtLeast(1),
                    table = translatedTable,
                )
            }

            repository.update(scanId) {
                it.copy(
                    status = ScanStatus.DONE,
                    blocks = blocks,
                    sourceLanguage = outcome.sourceLanguage,
                    errorKind = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageProcessingException) {
            markFailed(scanId, ApiErrorKind.BAD_IMAGE)
        } catch (e: ApiException) {
            markFailed(scanId, e.kind)
        } catch (e: Exception) {
            markFailed(scanId, ApiErrorKind.UNKNOWN)
        }
    }

    private suspend fun markFailed(scanId: String, kind: ApiErrorKind) {
        repository.update(scanId) { item ->
            item.copy(status = ScanStatus.FAILED, errorKind = kind)
        }
    }
}
