package io.github.sfzhou.lingotrail.net

import io.github.sfzhou.lingotrail.data.AppSettings
import io.github.sfzhou.lingotrail.data.TargetLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin client for the DeepSeek Platform APIs used by the app:
 *
 *  - `POST /v1/tools/ocr`  — recognise text (with layout boxes) in a photo
 *  - `POST /chat/completions` — translate recognised lines, with optional grouping
 *  - `GET  /models` — validate the user's API key during setup
 */
class DeepSeekApi(
    baseUrl: String,
    private val apiKey: String,
    private val model: String = AppSettings.DEFAULT_MODEL,
    private val maxConcurrentTranslations: Int = 3,
) {

    private val root: String = baseUrl.trim().trimEnd('/').ifBlank { AppSettings.DEFAULT_BASE_URL }

    /** Throws [ApiException] when the key is missing, invalid or the platform is unreachable. */
    suspend fun verifyKey(): Unit = withContext(Dispatchers.IO) {
        withRetry(attempts = 0) {
            Http.getJson("$root/models", apiKey, timeoutMs = 30_000)
        }
        Unit
    }

    /**
     * Uploads a JPEG and returns the raw page text of the OCR result, layout tokens
     * included. Parse it with `OcrParser`.
     */
    suspend fun recognize(imageBytes: ByteArray, mimeType: String = "image/jpeg"): String =
        withContext(Dispatchers.IO) {
            val body = withRetry(attempts = 1) {
                try {
                    Http.postMultipart(
                        url = "$root/v1/tools/ocr",
                        apiKey = apiKey,
                        fields = mapOf("purpose" to "rich_layout_recognition"),
                        fileField = "file",
                        fileName = "scan.jpg",
                        mimeType = mimeType,
                        fileBytes = imageBytes,
                        timeoutMs = 180_000,
                    )
                } catch (e: ApiException) {
                    // A 400 from this endpoint almost always means the image itself is unusable.
                    if (e.kind == ApiErrorKind.BAD_REQUEST) {
                        throw ApiException(ApiErrorKind.BAD_IMAGE, e.message, e, e.httpStatus)
                    }
                    throw e
                }
            }
            parseOcrBody(body)
        }

    /**
     * Translates [texts] into [target]. Each line gets its own translation, and the
     * model may additionally report groups of consecutive lines that are wrapped
     * fragments of one sentence; those groups carry a single combined translation.
     * Lines the model skipped fall back to their original text.
     */
    suspend fun translateTexts(
        texts: List<String>,
        target: TargetLanguage,
    ): TranslationOutcome = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext TranslationOutcome(emptyList(), emptyList(), null)

        val chunks = buildChunks(texts)
        val semaphore = Semaphore(maxConcurrentTranslations)
        val partials = coroutineScope {
            chunks.map { indices ->
                async { semaphore.withPermit { translateChunk(indices, texts, target) } }
            }.awaitAll()
        }

        val lineTranslations = arrayOfNulls<String>(texts.size)
        val groups = mutableListOf<TranslationGroup>()
        var sourceLanguage: String? = null
        for (partial in partials) {
            if (sourceLanguage.isNullOrBlank() && !partial.sourceLanguage.isNullOrBlank()) {
                sourceLanguage = partial.sourceLanguage
            }
            partial.lines.forEach { (index, text) ->
                if (index in lineTranslations.indices) lineTranslations[index] = text
            }
            partial.groups.forEach { group ->
                if (group.indices.isNotEmpty() && group.text.isNotBlank()) groups += group
            }
        }

        TranslationOutcome(
            lines = texts.indices.map { index -> lineTranslations[index] ?: texts[index] },
            groups = groups,
            sourceLanguage = sourceLanguage,
        )
    }

    // ---------------------------------------------------------------- parsing

    internal fun parseOcrBody(body: String): String {
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw ApiException(ApiErrorKind.PARSE, "Unreadable OCR response", it) }
        json.optJSONObject("error")?.let { error ->
            val code = error.optString("code")
            val message = error.optString("message")
            val kind = when {
                code.contains("timeout", ignoreCase = true) ||
                    message.contains("in time", ignoreCase = true) -> ApiErrorKind.TIMEOUT
                message.contains("could not be parsed", ignoreCase = true) ||
                    message.contains("could not be decoded", ignoreCase = true) ||
                    message.contains("not supported", ignoreCase = true) ||
                    message.contains("too large", ignoreCase = true) -> ApiErrorKind.BAD_IMAGE
                message.contains("busy", ignoreCase = true) ||
                    message.contains("temporarily unavailable", ignoreCase = true) -> ApiErrorKind.SERVER
                else -> ApiErrorKind.UNKNOWN
            }
            throw ApiException(kind, message.ifBlank { code })
        }
        val pages = json.optJSONArray("pages")
            ?: throw ApiException(ApiErrorKind.PARSE, "OCR response has no pages")
        return buildString {
            for (index in 0 until pages.length()) {
                val page = pages.optJSONObject(index) ?: continue
                val pageText = page.optString("text")
                if (pageText.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(pageText)
                }
            }
        }
    }

    internal fun parseTranslation(body: String): ChunkTranslation {
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw ApiException(ApiErrorKind.PARSE, "Unreadable translation response", it) }
        json.optJSONObject("error")?.let { error ->
            throw ApiException(ApiErrorKind.UNKNOWN, error.optString("message"))
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw ApiException(ApiErrorKind.PARSE, "Translation response has no choices")
        val finishReason = choice.optString("finish_reason")
        val content = choice.optJSONObject("message")?.optString("content").orEmpty().trim()
        if (content.isBlank()) {
            throw ApiException(
                ApiErrorKind.PARSE,
                if (finishReason == "length") "Translation output was truncated" else "Translation output was empty",
            )
        }
        val payload = runCatching { JSONObject(stripCodeFences(content)) }.getOrElse {
            throw ApiException(ApiErrorKind.PARSE, "Translation output is not JSON", it)
        }
        val sourceLanguage = payload.optString("source_language").takeIf { it.isNotBlank() }

        val lineArray = payload.optJSONArray("lines") ?: payload.optJSONArray("translations")
        if (lineArray == null) {
            throw ApiException(ApiErrorKind.PARSE, "Translation JSON has no lines array")
        }
        val lines = parseLineTranslations(lineArray)
        if (lines.isEmpty()) {
            throw ApiException(ApiErrorKind.PARSE, "Translation JSON has no usable lines")
        }

        val groups = mutableListOf<TranslationGroup>()
        payload.optJSONArray("groups")?.let { groupArray ->
            for (index in 0 until groupArray.length()) {
                val group = groupArray.optJSONObject(index) ?: continue
                val text = group.optString("text").trim()
                if (text.isEmpty()) continue
                val ids = group.optJSONArray("ids") ?: continue
                val groupIndices = buildList {
                    for (idIndex in 0 until ids.length()) {
                        val id = ids.optInt(idIndex, -1)
                        if (id > 0) add(id - 1) // ids are 1-based global line numbers
                    }
                }.distinct()
                if (groupIndices.size >= 2) groups += TranslationGroup(groupIndices, text)
            }
        }
        return ChunkTranslation(lines, groups, sourceLanguage)
    }

    private fun parseLineTranslations(array: JSONArray): Map<Int, String> {
        val lines = mutableMapOf<Int, String>()
        for (index in 0 until array.length()) {
            val line = array.optJSONObject(index) ?: continue
            if (line.has("id")) {
                val id = line.optInt("id", -1)
                if (id <= 0) continue
                lines[id - 1] = line.optString("text") // ids are 1-based global line numbers
            } else if (line.has("index")) {
                lines[line.optInt("index", -1)] = line.optString("text")
            }
        }
        return lines
    }

    // ---------------------------------------------------------------- internals

    private suspend fun translateChunk(
        indices: List<Int>,
        texts: List<String>,
        target: TargetLanguage,
    ): ChunkTranslation {
        val lines = JSONArray().apply {
            indices.forEach { index ->
                put(JSONObject().put("id", index + 1).put("text", texts[index]))
            }
        }
        val userContent = JSONObject()
            .put("target_language", target.promptName)
            .put("lines", lines)
            .toString()

        val payload = JSONObject().apply {
            put("model", model)
            // Translation does not benefit from a reasoning pass; it only adds latency.
            put("thinking", JSONObject().put("type", "disabled"))
            put("response_format", JSONObject().put("type", "json_object"))
            put("temperature", 0.2)
            put("max_tokens", 4096)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt(target)))
                    put(JSONObject().put("role", "user").put("content", userContent))
                },
            )
        }

        val body = withRetry(attempts = 2, retryOnParse = true) {
            Http.postJson(
                url = "$root/chat/completions",
                apiKey = apiKey,
                body = payload.toString().toByteArray(Charsets.UTF_8),
                timeoutMs = 120_000,
            )
        }
        return parseTranslation(body)
    }

    private fun buildChunks(texts: List<String>): List<List<Int>> {
        val chunks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentChars = 0
        texts.forEachIndexed { index, text ->
            val length = text.length
            if (current.isNotEmpty() && (current.size >= MAX_LINES_PER_CHUNK || currentChars + length > MAX_CHARS_PER_CHUNK)) {
                chunks += current
                current = mutableListOf()
                currentChars = 0
            }
            current += index
            currentChars += length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private suspend fun <T> withRetry(
        attempts: Int,
        retryOnParse: Boolean = false,
        block: suspend () -> T,
    ): T {
        var lastError: ApiException? = null
        for (attempt in 0..attempts) {
            try {
                return block()
            } catch (error: ApiException) {
                val retryable = error.isRetryable || (retryOnParse && error.kind == ApiErrorKind.PARSE)
                if (!retryable || attempt == attempts) throw error
                lastError = error
                val backoff = when (error.kind) {
                    ApiErrorKind.RATE_LIMIT -> 1_500L * (attempt + 1)
                    else -> 600L * (attempt + 1)
                }
                delay(backoff)
            }
        }
        throw lastError ?: ApiException(ApiErrorKind.UNKNOWN)
    }

    private fun systemPrompt(target: TargetLanguage): String = """
        You are the translation engine inside a travel camera app. You receive lines of text
        recognised from one photo, in reading order, each with an id: signs, menus, notices,
        tickets and labels. Translate every line into ${target.promptName}.

        Return ONLY a JSON object with this shape:
        {"source_language":"<dominant source language in English>","lines":[{"id":1,"text":"<translation>"}],"groups":[{"ids":[1,2],"text":"<combined translation>"}]}

        Rules:
        - "lines": exactly one entry per input id, in input order. Every id must appear.
        - "groups": optional, may be omitted or empty. Add a group ONLY when two or more consecutive lines are wrapped fragments of the same sentence or paragraph, so that translating them separately would read badly. The group text replaces the individual translations of those lines and will be drawn over their combined area. Every id may appear in at most one group. When in doubt, do not group.
        - Translate naturally and concisely for the scene. Preserve numbers, prices, currencies, times, units and symbols.
        - Some lines are HTML tables (<table><tr><td>…). Translate only the readable text inside the cells, and keep the tags, rows and columns exactly as they are.
        - If a line is already in ${target.promptName}, or is only a number or symbol, repeat it unchanged.
        - Do not add explanations, notes, transliterations or markdown.
    """.trimIndent()

    private fun stripCodeFences(content: String): String {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            text = text.removeSuffix("```")
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text.trim()
    }

    companion object {
        private const val MAX_LINES_PER_CHUNK = 25
        private const val MAX_CHARS_PER_CHUNK = 1_500
    }
}

/**
 * Aligned translation result.
 *
 * `lines[i]` is the translation of input line `i`; `groups` additionally carries
 * optional combined translations for runs of consecutive lines.
 */
data class TranslationOutcome(
    val lines: List<String>,
    val groups: List<TranslationGroup>,
    val sourceLanguage: String?,
)

/** A run of consecutive input lines (0-based indices) translated as one passage. */
data class TranslationGroup(
    val indices: List<Int>,
    val text: String,
)

/** Result of translating one chunk. */
internal data class ChunkTranslation(
    val lines: Map<Int, String>,
    val groups: List<TranslationGroup>,
    val sourceLanguage: String?,
)
