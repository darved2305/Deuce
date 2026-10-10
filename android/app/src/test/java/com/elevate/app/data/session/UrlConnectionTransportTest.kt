package com.elevate.app.data.session

import com.elevate.app.data.upload.FilePartSource
import com.elevate.app.data.upload.PartPlan
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/** The production transport over a real local socket (JDK HttpServer). */
class UrlConnectionTransportTest {

    @get:Rule val tmp = TemporaryFolder()
    private var server: HttpServer? = null
    private val seenLength = AtomicReference<String?>()
    private val seenBody = AtomicReference<ByteArray?>()

    private fun serve(status: Int, etag: String?, body: String = ""): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            seenLength.set(ex.requestHeaders.getFirst("Content-Length"))
            seenBody.set(ex.requestBody.readBytes())
            etag?.let { ex.responseHeaders.add("ETag", it) }
            val b = body.toByteArray()
            ex.sendResponseHeaders(status, if (b.isEmpty()) -1 else b.size.toLong())
            if (b.isNotEmpty()) ex.responseBody.use { it.write(b) } else ex.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @After fun stop() { server?.stop(0) }

    @Test fun `PUT streams a byte range with an exact Content-Length and returns the ETag verbatim`() {
        val data = ByteArray(2500) { (it % 199).toByte() }
        val f = tmp.newFile().apply { writeBytes(data) }
        val plan = PartPlan(2500, 1000)
        val base = serve(200, "\"9b2cf535f27731c974343645a3985328\"")
        val body = object : RequestBody {
            override val contentLength = plan.length(3)
            override val contentType = "application/octet-stream"
            override fun writeTo(out: OutputStream) { FilePartSource(f).open(plan.offset(3), plan.length(3)).use { it.copyTo(out) } }
        }
        val res = UrlConnectionTransport().execute(HttpRequest("PUT", "$base/orig/s_1/source.mp4?partNumber=3&X-Amz-Signature=abc", emptyMap(), body))
        assertThat(res.status).isEqualTo(200)
        assertThat(res.header("etag")).isEqualTo("\"9b2cf535f27731c974343645a3985328\"")
        assertThat(seenLength.get()).isEqualTo("500")
        assertThat(seenBody.get()).isEqualTo(data.copyOfRange(2000, 2500))
    }

    @Test fun `4xx and 5xx come back as responses with their body`() {
        val base = serve(409, null, """{"error":{"code":"invalid_transition","message":"upload is completed"}}""")
        val res = UrlConnectionTransport().execute(HttpRequest("POST", "$base/v1/sessions/s_1/upload/abort", emptyMap(), BytesBody("{}".toByteArray())))
        assertThat(res.status).isEqualTo(409)
        assertThat(ApiException.from(res.status, res.text()).code).isEqualTo("invalid_transition")
    }

    @Test fun `a transport failure names the URL without its query and flags offline`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val e = runCatching {
            UrlConnectionTransport(connectTimeoutMs = 2_000).execute(HttpRequest("PUT", "http://127.0.0.1:$port/o?X-Amz-Signature=secret", emptyMap(), BytesBody(ByteArray(1))))
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(TransportException::class.java)
        assertThat(e!!.message).doesNotContain("secret")
        assertThat((e as TransportException).offline).isTrue()
    }
}
