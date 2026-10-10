package com.elevate.app.data

import com.elevate.app.data.api.ApiSource
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/** ApiSource against a real local HTTP server (JDK), answering with contract-shaped bodies. */
class ApiSourceTest {

    private var server: HttpServer? = null

    private fun serve(status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/functions/v1/api/v1/health") { ex ->
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/functions/v1/api"
    }

    @After fun stop() { server?.stop(0) }

    @Test fun `blank base url is not configured, and no request is made`() {
        assertThat(ApiSource("").health()).isEqualTo(ApiSource.Health.NotConfigured)
    }

    @Test fun `ok body is online with a measured latency`() {
        val h = ApiSource(serve(200, """{"status":"ok"}""")).health()
        assertThat(h).isInstanceOf(ApiSource.Health.Online::class.java)
        assertThat((h as ApiSource.Health.Online).latencyMs).isAtLeast(0)
    }

    @Test fun `error envelope is surfaced with its code`() {
        val h = ApiSource(serve(503, """{"error":{"code":"unavailable","message":"db down"}}""")).health()
        assertThat(h).isEqualTo(ApiSource.Health.ApiError(503, "unavailable", "db down"))
    }

    @Test fun `unexpected bodies are not reported as online`() {
        assertThat(ApiSource(serve(200, """{"status":"degraded"}""")).health()).isInstanceOf(ApiSource.Health.Unreachable::class.java)
        stop()
        assertThat(ApiSource(serve(500, "<html>")).health()).isEqualTo(ApiSource.Health.Unreachable("HTTP 500"))
    }

    @Test fun `closed port is unreachable`() {
        val port = ServerSocket(0).use { it.localPort }
        val h = ApiSource("http://127.0.0.1:$port/functions/v1/api", timeoutMs = 1_000).health()
        assertThat(h).isInstanceOf(ApiSource.Health.Unreachable::class.java)
    }
}
