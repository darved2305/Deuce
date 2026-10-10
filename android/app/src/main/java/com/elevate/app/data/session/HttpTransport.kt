package com.elevate.app.data.session

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

/** A request body that is written once, streaming, with a known length (no whole-file buffers). */
interface RequestBody {
    val contentLength: Long
    val contentType: String?

    /** Writes exactly [contentLength] bytes. */
    fun writeTo(out: OutputStream)
}

class BytesBody(private val bytes: ByteArray, override val contentType: String? = "application/json") : RequestBody {
    override val contentLength: Long get() = bytes.size.toLong()
    override fun writeTo(out: OutputStream) = out.write(bytes)
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: RequestBody? = null,
)

class HttpResponse(val status: Int, headers: Map<String, String>, val body: ByteArray) {
    /** Header names compare case-insensitively (HTTP/2 lower-cases them; HttpURLConnection does not). */
    val headers: Map<String, String> = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(headers) }
    fun header(name: String): String? = headers[name]
    fun text(): String = body.toString(Charsets.UTF_8)
}

/**
 * The one network seam: production uses [UrlConnectionTransport], tests inject a fake. Blocking:
 * callers run it on an IO dispatcher. Throws [IOException] for transport failures only; every HTTP
 * status (including 4xx/5xx) is returned as an [HttpResponse].
 */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/** A transport failure. [offline] = the host could not be resolved or reached (no network). */
class TransportException(message: String, val offline: Boolean) : IOException(message)

/** Strips the query (presigned signatures, tokens) from a URL before it reaches any message or log. */
fun redactUrl(url: String): String = url.substringBefore('?').let { if (it.length < url.length) "$it?…" else it }

/** [HttpURLConnection] transport (no extra dependency, like `data/api/ApiSource`). */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        val conn = (URL(request.url).openConnection() as HttpURLConnection)
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = request.method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val body = request.body
            if (body != null) {
                conn.doOutput = true
                body.contentType?.let { conn.setRequestProperty("Content-Type", it) }
                // Fixed-length streaming: the part is written straight to the socket, never buffered whole,
                // and HttpURLConnection sends exactly this Content-Length.
                conn.setFixedLengthStreamingMode(body.contentLength)
                conn.outputStream.use { body.writeTo(it) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = conn.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapNotNull { (k, v) -> v?.firstOrNull()?.let { k!! to it } }
                .toMap()
            return HttpResponse(status, headers, bytes)
        } catch (e: IOException) {
            // Rethrown with a message that names the URL without its query (signatures never reach logs).
            throw TransportException(
                "${e.javaClass.simpleName} on ${request.method} ${redactUrl(request.url)}",
                offline = e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException,
            )
        } finally {
            conn.disconnect()
        }
    }
}
