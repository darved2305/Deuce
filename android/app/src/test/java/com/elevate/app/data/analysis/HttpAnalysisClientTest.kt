package com.elevate.app.data.analysis

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress

/** HttpAnalysisClient against a local JDK HTTP server answering with contract-shaped bodies. */
class HttpAnalysisClientTest {

    private var server: HttpServer? = null
    private val seen = mutableListOf<Map<String, String?>>()

    private fun serve(routes: Map<String, (HttpExchange) -> Pair<Int, String>>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/functions/v1/api")
            seen += mapOf(
                "method" to ex.requestMethod,
                "path" to path,
                "query" to ex.requestURI.query,
                "auth" to ex.requestHeaders.getFirst("Authorization"),
                "apikey" to ex.requestHeaders.getFirst("apikey"),
                "idem" to ex.requestHeaders.getFirst("Idempotency-Key"),
                "body" to ex.requestBody.bufferedReader().readText(),
            )
            val (status, body) = routes[path]?.invoke(ex) ?: (404 to """{"error":{"code":"not_found","message":"route not found"}}""")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/functions/v1/api"
    }

    @After fun stop() { server?.stop(0) }

    private fun client(base: String) = HttpAnalysisClient(base, "pk_test", { "jwt_test" }, requireHttpsDownloads = false)

    @Test fun `start analysis sends the key and auth, and tells 202 from 200`() = runTest {
        var status = 202
        val c = client(serve(mapOf("/v1/analyses" to { _ -> status to """{"analysis_id":"a_1","state":"queued"}""" })))
        val first = c.startAnalysis("s_1", "k-1")
        assertThat(first.dispatched).isTrue()
        assertThat(first.response.analysisId).isEqualTo("a_1")
        assertThat(seen.last()["idem"]).isEqualTo("k-1")
        assertThat(seen.last()["auth"]).isEqualTo("Bearer jwt_test")
        assertThat(seen.last()["apikey"]).isEqualTo("pk_test")
        assertThat(seen.last()["body"]).isEqualTo("""{"session_id":"s_1"}""")
        status = 200
        assertThat(c.startAnalysis("s_1", "k-1").dispatched).isFalse()
    }

    @Test fun `contract errors keep their code`() = runTest {
        var answer = 409 to """{"error":{"code":"upload_not_complete","message":"upload is not complete"}}"""
        val c = client(serve(mapOf("/v1/analyses" to { _ -> answer })))
        val e = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { c.startAnalysis("s_1", "k") } }
        assertThat(e.httpStatus).isEqualTo(409)
        assertThat(e.code).isEqualTo("upload_not_complete")
        answer = 502 to """{"error":{"code":"engine_unavailable","message":"engine is unavailable"}}"""
        val e2 = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { c.startAnalysis("s_1", "k") } }
        assertThat(e2.code).isEqualTo("engine_unavailable")
    }

    @Test fun `events cursor caps the limit at 500 and decodes events without engine_version`() = runTest {
        val body = """{"events":[{"event_id":"e_1","analysis_id":"a_1","match_id":"m_1","seq":1,"schema_version":1,"type":"analysis.queued","created_at":"2026-10-11T10:00:00Z"}],"next_after_seq":1,"has_more":false,"state":"queued"}"""
        val c = client(serve(mapOf("/v1/analyses/a_1/events" to { _ -> 200 to body })))
        val page = c.events("a_1", 0, 10_000)
        assertThat(seen.last()["query"]).isEqualTo("after_seq=0&limit=500")
        assertThat(page.events.single().engineVersion).isNull()
        assertThat(page.state).isEqualTo("queued")
    }

    @Test fun `get analysis reads analysis get response v1 with nulls`() = runTest {
        val body = """{"analysis_id":"a_1","session_id":"s_1","match_id":"m_1","state":"failed","engine_version":null,"last_seq":2,"failure_reason":"dispatch_failed","created_at":"2026-10-11T10:00:00Z","updated_at":"2026-10-11T10:00:05Z","finalized_at":null}"""
        val c = client(serve(mapOf("/v1/analyses/a_1" to { _ -> 200 to body })))
        val a = c.getAnalysis("a_1")
        assertThat(a.engineVersion).isNull()
        assertThat(a.failureReason).isEqualTo("dispatch_failed")
        assertThat(a.finalizedAt).isNull()
    }

    @Test fun `a body that breaks the contract is a contract error`() = runTest {
        val c = client(serve(mapOf("/v1/analyses/a_1" to { _ -> 200 to """{"analysis_id":"a_1"}""" })))
        val e = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { c.getAnalysis("a_1") } }
        assertThat(e.kind).isEqualTo(AnalysisApiException.Kind.CONTRACT)
    }

    @Test fun `missing match and artifact routes are reported as 404, not filled in`() = runTest {
        val c = client(serve(emptyMap()))
        val m = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { c.getMatch("m_1") } }
        assertThat(m.httpStatus).isEqualTo(404)
        val a = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { c.artifactUrl("art_1") } }
        assertThat(a.code).isEqualTo("not_found")
    }

    @Test fun `window result download never puts the signed url in errors or toString`() = runTest {
        val base = serve(mapOf("/r2/w.json" to { _ -> 403 to "denied" }))
        val url = SignedUrl(base.removeSuffix("/functions/v1/api") + "/functions/v1/api/r2/w.json?X-Amz-Signature=secret")
        assertThat(url.toString()).doesNotContain("secret")
        val e = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { client(base).downloadWindowResult(url) } }
        assertThat(e.message).doesNotContain("secret")
        assertThat(e.httpStatus).isEqualTo(403)
        // No API credentials go to the storage host.
        assertThat(seen.last()["auth"]).isNull()
        assertThat(seen.last()["apikey"]).isNull()
    }

    @Test fun `https is required for signed downloads by default`() = runTest {
        val c = HttpAnalysisClient("http://127.0.0.1:1/x", "pk", { "jwt" })
        val e = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { c.downloadWindowResult(SignedUrl("http://127.0.0.1:1/w.json")) } }
        assertThat(e.kind).isEqualTo(AnalysisApiException.Kind.CONTRACT)
    }

    @Test fun `not configured makes no request`() = runTest {
        val e = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { HttpAnalysisClient("", "", { "jwt" }).events("a_1", 0) } }
        assertThat(e.kind).isEqualTo(AnalysisApiException.Kind.NOT_CONFIGURED)
    }

    /** A55 crash: a timed-out sign-in refresh escaped as AuthException and killed the app on the main thread. */
    @Test fun `failed token refresh is a network error, not a crash`() = runTest {
        val c = HttpAnalysisClient("http://127.0.0.1:1/x", "pk", { throw com.elevate.app.data.auth.AuthException(0, "token refresh failed: SocketTimeoutException") })
        val e = assertThrows(AnalysisApiException::class.java) { kotlinx.coroutines.runBlocking { c.events("a_1", 0) } }
        assertThat(e.kind).isEqualTo(AnalysisApiException.Kind.NETWORK)
    }
}
