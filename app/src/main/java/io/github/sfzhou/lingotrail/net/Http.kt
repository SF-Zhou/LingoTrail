package io.github.sfzhou.lingotrail.net

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

/**
 * Minimal HTTP client built on HttpURLConnection — no third-party networking stack
 * is needed for the two JSON endpoints and one multipart upload the app talks to.
 */
internal object Http {

    private const val USER_AGENT = "LingoTrail/1.0 (Android)"

    fun getJson(url: String, apiKey: String, timeoutMs: Int = 30_000): String =
        execute(url, apiKey, method = "GET", body = null, contentType = null, timeoutMs = timeoutMs)

    fun postJson(
        url: String,
        apiKey: String,
        body: ByteArray,
        timeoutMs: Int = 120_000,
    ): String = execute(
        url = url,
        apiKey = apiKey,
        method = "POST",
        body = body,
        contentType = "application/json; charset=utf-8",
        timeoutMs = timeoutMs,
    )

    fun postMultipart(
        url: String,
        apiKey: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        timeoutMs: Int = 180_000,
    ): String {
        val boundary = "LingoTrail-${UUID.randomUUID()}"
        val buffer = ByteArrayOutputStream(fileBytes.size + 4096)
        val out = BufferedOutputStream(buffer)
        fields.forEach { (name, value) ->
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write(value.toByteArray())
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n".toByteArray(),
        )
        out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
        out.write(fileBytes)
        out.write("\r\n".toByteArray())
        out.write("--$boundary--\r\n".toByteArray())
        out.flush()
        return execute(
            url = url,
            apiKey = apiKey,
            method = "POST",
            body = buffer.toByteArray(),
            contentType = "multipart/form-data; boundary=$boundary",
            timeoutMs = timeoutMs,
        )
    }

    private fun execute(
        url: String,
        apiKey: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Int,
    ): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = timeoutMs
                doInput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                if (body != null) {
                    doOutput = true
                    contentType?.let { setRequestProperty("Content-Type", it) }
                    setFixedLengthStreamingMode(body.size)
                    outputStream.use { it.write(body) }
                }
            }
            val status = connection.responseCode
            val responseBody = readBody(connection, status)
            if (status !in 200..299) {
                throw ApiException.fromHttpStatus(status, responseBody)
            }
            return responseBody.orEmpty()
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException(ApiErrorKind.TIMEOUT, "Request timed out", e)
        } catch (e: UnknownHostException) {
            throw ApiException(ApiErrorKind.NETWORK, "Host not reachable", e)
        } catch (e: SSLException) {
            throw ApiException(ApiErrorKind.NETWORK, "TLS failure", e)
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.NETWORK, "Network I/O failure", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String? {
        val stream: InputStream? = try {
            if (status in 200..299) connection.inputStream else connection.errorStream
        } catch (_: IOException) {
            return null
        }
        return stream?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
