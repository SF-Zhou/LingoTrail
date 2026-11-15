package io.github.sfzhou.lingotrail.data

import android.content.Context
import io.github.sfzhou.lingotrail.net.ApiErrorKind
import io.github.sfzhou.lingotrail.ocr.PixelRect
import io.github.sfzhou.lingotrail.ocr.TableContent
import io.github.sfzhou.lingotrail.util.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * File-backed history store.
 *
 * Layout: `filesDir/scans/<id>/original.jpg`, `thumb.jpg` and `meta.json`.
 * A scan is fully self-contained in its folder, which keeps deletion trivial and
 * lets the gallery be rebuilt by scanning the directory on launch.
 */
class HistoryRepository(context: Context) {

    private val root = File(context.filesDir, "scans")
    private val writeMutex = Mutex()

    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!root.exists()) root.mkdirs()
        val loaded = root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> readMeta(dir) }
            .map { item ->
                // A scan left processing by a killed process is not running anymore.
                if (item.status == ScanStatus.PROCESSING) {
                    item.copy(status = ScanStatus.FAILED, errorKind = ApiErrorKind.UNKNOWN)
                } else {
                    item
                }
            }
            .sortedByDescending { it.createdAt }
        _items.value = loaded
    }

    fun find(id: String): HistoryItem? = _items.value.firstOrNull { it.id == id }

    fun imageFile(id: String): File = File(File(root, id), ORIGINAL_FILE)

    fun thumbFile(id: String): File = File(File(root, id), THUMB_FILE)

    /** Decodes, orients and stores an image; the returned item is ready for processing. */
    suspend fun importImage(input: InputStream, targetLanguage: TargetLanguage): HistoryItem =
        withContext(Dispatchers.IO) {
            val processed = ImageProcessor.process(input)
            val id = UUID.randomUUID().toString()
            val dir = File(root, id).apply { mkdirs() }
            File(dir, ORIGINAL_FILE).writeBytes(processed.jpeg)
            File(dir, THUMB_FILE).writeBytes(processed.thumbnailJpeg)
            val item = HistoryItem(
                id = id,
                createdAt = System.currentTimeMillis(),
                targetLanguage = targetLanguage,
                status = ScanStatus.PROCESSING,
                width = processed.width,
                height = processed.height,
            )
            writeMeta(dir, item)
            _items.update { current ->
                (listOf(item) + current).sortedByDescending { it.createdAt }
            }
            item
        }

    suspend fun update(id: String, transform: (HistoryItem) -> HistoryItem): HistoryItem? =
        writeMutex.withLock {
            val current = find(id) ?: return@withLock null
            val updated = transform(current)
            withContext(Dispatchers.IO) { writeMeta(File(root, id), updated) }
            _items.update { list -> list.map { if (it.id == id) updated else it } }
            updated
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        _items.update { list -> list.filterNot { it.id == id } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
        root.mkdirs()
        _items.value = emptyList()
    }

    // ------------------------------------------------------------ persistence

    private fun writeMeta(dir: File, item: HistoryItem) {
        val json = JSONObject().apply {
            put("version", META_VERSION)
            put("id", item.id)
            put("createdAt", item.createdAt)
            put("targetLanguage", item.targetLanguage.tag)
            put("sourceLanguage", item.sourceLanguage.orEmpty())
            put("status", item.status.name)
            put("errorKind", item.errorKind?.name.orEmpty())
            put("width", item.width)
            put("height", item.height)
            put(
                "blocks",
                JSONArray().apply {
                    item.blocks.forEach { block ->
                        put(
                            JSONObject().apply {
                                put("original", block.original)
                                put("translation", block.translation)
                                put("label", block.label)
                                put("lineCount", block.lineCount)
                                block.table?.let { table ->
                                    put(
                                        "table",
                                        JSONArray().apply {
                                            table.rows.forEach { row ->
                                                put(JSONArray().apply { row.forEach { cell -> put(cell) } })
                                            }
                                        },
                                    )
                                }
                                put(
                                    "boxes",
                                    JSONArray().apply {
                                        block.boxes.forEach { box ->
                                            put(
                                                JSONArray().apply {
                                                    put(box.left)
                                                    put(box.top)
                                                    put(box.right)
                                                    put(box.bottom)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        File(dir, META_FILE).writeText(json.toString())
    }

    private fun readMeta(dir: File): HistoryItem? = runCatching {
        val meta = File(dir, META_FILE)
        if (!meta.exists() || !File(dir, ORIGINAL_FILE).exists()) return null
        val json = JSONObject(meta.readText())
        HistoryItem(
            id = json.getString("id"),
            createdAt = json.getLong("createdAt"),
            targetLanguage = TargetLanguage.fromTag(json.optString("targetLanguage"))
                ?: TargetLanguage.default,
            status = ScanStatus.valueOf(json.getString("status")),
            width = json.getInt("width"),
            height = json.getInt("height"),
            sourceLanguage = json.optString("sourceLanguage").takeIf { it.isNotBlank() },
            errorKind = json.optString("errorKind").takeIf { it.isNotBlank() }
                ?.let { name -> runCatching { ApiErrorKind.valueOf(name) }.getOrNull() },
            blocks = parseBlocks(json.optJSONArray("blocks")),
        )
    }.getOrNull()

    private fun parseBlocks(array: JSONArray?): List<TranslationBlock> {
        if (array == null) return emptyList()
        val blocks = mutableListOf<TranslationBlock>()
        for (index in 0 until array.length()) {
            val block = array.optJSONObject(index) ?: continue
            val boxes = mutableListOf<PixelRect>()
            val boxesArray = block.optJSONArray("boxes")
            if (boxesArray != null) {
                for (boxIndex in 0 until boxesArray.length()) {
                    val box = boxesArray.optJSONArray(boxIndex) ?: continue
                    if (box.length() >= 4) {
                        boxes += PixelRect(
                            left = box.optInt(0),
                            top = box.optInt(1),
                            right = box.optInt(2),
                            bottom = box.optInt(3),
                        )
                    }
                }
            }
            blocks += TranslationBlock(
                original = block.optString("original"),
                translation = block.optString("translation"),
                label = block.optString("label").ifBlank { "text" },
                boxes = boxes,
                lineCount = block.optInt("lineCount", 1).coerceAtLeast(1),
                table = parseTable(block.optJSONArray("table")),
            )
        }
        return blocks
    }

    private fun parseTable(array: JSONArray?): TableContent? {
        if (array == null) return null
        val rows = mutableListOf<List<String>>()
        for (rowIndex in 0 until array.length()) {
            val row = array.optJSONArray(rowIndex) ?: continue
            val cells = mutableListOf<String>()
            for (cellIndex in 0 until row.length()) {
                cells += row.optString(cellIndex)
            }
            if (cells.isNotEmpty()) rows += cells
        }
        return rows.takeIf { it.isNotEmpty() }?.let { TableContent(it) }
    }

    private companion object {
        const val META_VERSION = 1
        const val META_FILE = "meta.json"
        const val ORIGINAL_FILE = "original.jpg"
        const val THUMB_FILE = "thumb.jpg"
    }
}
