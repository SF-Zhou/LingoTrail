package io.github.sfzhou.lingotrail.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekApiParsingTest {

    private val api = DeepSeekApi(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
    )

    private fun completionResponse(content: String, finishReason: String = "stop"): String =
        JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("finish_reason", finishReason)
                    .put("message", JSONObject().put("content", content)),
            ),
        ).toString()

    private fun translationJson(
        lines: List<Pair<Int, String>>,
        groups: List<Triple<List<Int>, String, Unit>> = emptyList(),
    ): String {
        val linesArray = JSONArray()
        lines.forEach { (id, text) -> linesArray.put(JSONObject().put("id", id).put("text", text)) }
        val payload = JSONObject()
            .put("source_language", "English")
            .put("lines", linesArray)
        if (groups.isNotEmpty()) {
            val groupsArray = JSONArray()
            groups.forEach { (ids, text, _) ->
                groupsArray.put(
                    JSONObject()
                        .put("ids", JSONArray(ids))
                        .put("text", text),
                )
            }
            payload.put("groups", groupsArray)
        }
        return completionResponse(payload.toString())
    }

    @Test
    fun parsesLinesAndParagraphGroups() {
        val parsed = api.parseTranslation(
            translationJson(
                lines = listOf(1 to "你好", 2 to "世界", 3 to "欢迎"),
                groups = listOf(Triple(listOf(1, 2), "你好，世界", Unit)),
            ),
        )

        assertEquals("English", parsed.sourceLanguage)
        assertEquals(mapOf(0 to "你好", 1 to "世界", 2 to "欢迎"), parsed.lines)
        assertEquals(1, parsed.groups.size)
        assertEquals(listOf(0, 1), parsed.groups[0].indices)
        assertEquals("你好，世界", parsed.groups[0].text)
    }

    @Test
    fun dropsSingleLineGroups() {
        val parsed = api.parseTranslation(
            translationJson(
                lines = listOf(1 to "a", 2 to "b"),
                groups = listOf(Triple(listOf(1), "only one", Unit)),
            ),
        )
        assertTrue(parsed.groups.isEmpty())
    }

    @Test
    fun acceptsLegacyTranslationsArray() {
        val content = JSONObject()
            .put("source_language", "Japanese")
            .put("translations", JSONArray().put(JSONObject().put("id", 1).put("text", "こんにちは")))
            .toString()
        val parsed = api.parseTranslation(completionResponse(content))

        assertEquals(mapOf(0 to "こんにちは"), parsed.lines)
        assertEquals("Japanese", parsed.sourceLanguage)
    }

    @Test
    fun stripsMarkdownCodeFences() {
        val content = "```json\n" +
            JSONObject()
                .put("lines", JSONArray().put(JSONObject().put("id", 1).put("text", "ok")))
                .toString() + "\n```"
        val parsed = api.parseTranslation(completionResponse(content))
        assertEquals(mapOf(0 to "ok"), parsed.lines)
    }

    @Test
    fun rejectsBlankOrInvalidContent() {
        assertThrows(ApiException::class.java) { api.parseTranslation(completionResponse("   ")) }
        assertThrows(ApiException::class.java) { api.parseTranslation(completionResponse("not json")) }
        assertThrows(ApiException::class.java) {
            api.parseTranslation(completionResponse("{\"lines\":[]}"))
        }
        assertThrows(ApiException::class.java) {
            api.parseTranslation("{}")
        }
    }

    @Test
    fun reportsParseErrorForTruncatedOutput() {
        val error = assertThrows(ApiException::class.java) {
            api.parseTranslation(completionResponse("", finishReason = "length"))
        }
        assertEquals(ApiErrorKind.PARSE, error.kind)
    }

    @Test
    fun parsesOcrPagesJoinedByNewline() {
        val body = JSONObject()
            .put("object", "ocr.result")
            .put(
                "pages",
                JSONArray()
                    .put(JSONObject().put("index", 0).put("text", "first page"))
                    .put(JSONObject().put("index", 1).put("text", "second page")),
            )
            .toString()

        assertEquals("first page\nsecond page", api.parseOcrBody(body))
    }

    @Test
    fun mapsOcrErrorBodiesToErrorKinds() {
        val timeout = assertThrows(ApiException::class.java) {
            api.parseOcrBody(
                """{"error":{"message":"OCR did not finish in time","type":"gateway_timeout_error","code":"gateway_timeout_error"}}""",
            )
        }
        assertEquals(ApiErrorKind.TIMEOUT, timeout.kind)

        val badImage = assertThrows(ApiException::class.java) {
            api.parseOcrBody(
                """{"error":{"message":"Failed to recognize the file: the image could not be decoded"}}""",
            )
        }
        assertEquals(ApiErrorKind.BAD_IMAGE, badImage.kind)

        val busy = assertThrows(ApiException::class.java) {
            api.parseOcrBody(
                """{"error":{"message":"The OCR service is temporarily unavailable. Please try again later."}}""",
            )
        }
        assertEquals(ApiErrorKind.SERVER, busy.kind)
    }

    @Test
    fun mapsHttpStatusesToErrorKinds() {
        assertEquals(ApiErrorKind.AUTH, ApiException.fromHttpStatus(401, null).kind)
        assertEquals(ApiErrorKind.BALANCE, ApiException.fromHttpStatus(402, null).kind)
        assertEquals(ApiErrorKind.RATE_LIMIT, ApiException.fromHttpStatus(429, null).kind)
        assertEquals(ApiErrorKind.SERVER, ApiException.fromHttpStatus(503, null).kind)
        assertEquals(ApiErrorKind.TIMEOUT, ApiException.fromHttpStatus(504, null).kind)
        assertEquals(ApiErrorKind.BAD_IMAGE, ApiException.fromHttpStatus(413, null).kind)
        assertEquals(
            ApiErrorKind.BAD_REQUEST,
            ApiException.fromHttpStatus(400, """{"error":{"message":"Invalid form"}}""").kind,
        )
    }

    @Test
    fun retryabilityIsLimitedToTransientFailures() {
        assertTrue(ApiException(ApiErrorKind.NETWORK).isRetryable)
        assertTrue(ApiException(ApiErrorKind.TIMEOUT).isRetryable)
        assertTrue(ApiException(ApiErrorKind.RATE_LIMIT).isRetryable)
        assertTrue(ApiException(ApiErrorKind.SERVER).isRetryable)
        assertTrue(!ApiException(ApiErrorKind.AUTH).isRetryable)
        assertTrue(!ApiException(ApiErrorKind.BALANCE).isRetryable)
        assertTrue(!ApiException(ApiErrorKind.BAD_IMAGE).isRetryable)
    }
}
