package io.github.sfzhou.lingotrail.net

/**
 * Coarse error categories the UI can explain to the user.
 * HTTP status codes and transport failures are folded into these.
 */
enum class ApiErrorKind {
    NETWORK,
    TIMEOUT,
    AUTH,
    BALANCE,
    RATE_LIMIT,
    SERVER,
    BAD_IMAGE,
    BAD_REQUEST,
    PARSE,
    UNKNOWN,
}

class ApiException(
    val kind: ApiErrorKind,
    message: String? = null,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : Exception(message, cause) {

    val isRetryable: Boolean
        get() = kind in setOf(ApiErrorKind.NETWORK, ApiErrorKind.TIMEOUT, ApiErrorKind.RATE_LIMIT, ApiErrorKind.SERVER)

    companion object {
        fun fromHttpStatus(status: Int, body: String?, includeBody: Boolean = true): ApiException {
            val serverMessage = if (includeBody) extractErrorMessage(body) else null
            val kind = when {
                status == 401 || status == 403 -> ApiErrorKind.AUTH
                status == 402 -> ApiErrorKind.BALANCE
                status == 408 || status == 504 -> ApiErrorKind.TIMEOUT
                status == 413 -> ApiErrorKind.BAD_IMAGE
                status == 429 -> ApiErrorKind.RATE_LIMIT
                status in 500..599 -> ApiErrorKind.SERVER
                status == 400 || status == 404 || status == 422 -> ApiErrorKind.BAD_REQUEST
                else -> ApiErrorKind.UNKNOWN
            }
            // The OCR endpoint reports "the file could not be parsed" style failures with 400.
            val resolved = if (kind == ApiErrorKind.BAD_REQUEST && serverMessage != null &&
                serverMessage.contains("recogn", ignoreCase = true)
            ) {
                ApiErrorKind.BAD_IMAGE
            } else {
                kind
            }
            return ApiException(resolved, serverMessage ?: "HTTP $status", httpStatus = status)
        }

        /** Pulls the message out of an OpenAI-style `{"error": {"message": ...}}` body. */
        fun extractErrorMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            return runCatching {
                val json = org.json.JSONObject(body)
                json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
