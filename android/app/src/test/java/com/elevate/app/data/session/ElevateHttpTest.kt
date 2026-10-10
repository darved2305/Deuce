package com.elevate.app.data.session

import com.elevate.app.data.session.ContractFakeServer.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Auth headers, 401 → refresh → retry, retries on 429/5xx/transport errors, no retry on 4xx. */
class ElevateHttpTest {

    private val fake = ContractFakeServer()
    private val sleeps = mutableListOf<Long>()
    private val client = clientFor(fake, sleeps).first
    private val src = TestRequests.eurogamesSource(20, "b".repeat(64))

    private fun apiCalls() = fake.requests.filter { it.url.startsWith(ContractFakeServer.API) }

    @Test fun `every API call sends Bearer JWT, apikey and the Idempotency-Key`() = runBlocking<Unit> {
        val r = client.createSession(TestRequests.request(src), "idem-1")
        client.getSession(r.sessionId)
        for (call in apiCalls()) {
            assertThat(call.headers["Authorization"]).matches("Bearer at_\\d+")
            assertThat(call.headers["apikey"]).isEqualTo(ContractFakeServer.KEY)
        }
        assertThat(apiCalls().first().headers["Idempotency-Key"]).isEqualTo("idem-1")
        assertThat(fake.signUps.get()).isEqualTo(1)
    }

    @Test fun `401 refreshes the token once and retries the same request`() = runBlocking<Unit> {
        val r = client.createSession(TestRequests.request(src), "idem-1")
        fake.expireTokens()
        val view = client.getSession(r.sessionId)
        assertThat(view.sessionId).isEqualTo(r.sessionId)
        assertThat(fake.refreshes.get()).isEqualTo(1)
        val gets = apiCalls().filter { it.method == "GET" }
        assertThat(gets).hasSize(2)
        assertThat(gets[0].headers["Authorization"]).isNotEqualTo(gets[1].headers["Authorization"])
    }

    @Test fun `401 after the refresh is not retried again`() = runBlocking<Unit> {
        fake.apiFault("/v1/sessions/s_x", Fault.Status(401, """{"error":{"code":"unauthorized","message":"no"}}"""), Fault.Status(401, """{"error":{"code":"unauthorized","message":"no"}}"""))
        val e = runCatching { client.getSession("s_x") }.exceptionOrNull() as ApiException
        assertThat(e.kind).isEqualTo(ApiErrorKind.Unauthorized)
        assertThat(fake.refreshes.get()).isEqualTo(1)
    }

    @Test fun `same Idempotency-Key replays the same session, a different body conflicts`() = runBlocking<Unit> {
        val a = client.createSession(TestRequests.request(src), "idem-1")
        val b = client.createSession(TestRequests.request(src), "idem-1")
        assertThat(b).isEqualTo(a)
        val e = runCatching { client.createSession(TestRequests.request(src.copy(device = "other")), "idem-1") }.exceptionOrNull() as ApiException
        assertThat(e.status).isEqualTo(409)
        assertThat(e.code).isEqualTo("idempotency_conflict")
    }

    @Test fun `413 upload_too_large and 422 are not retried`() = runBlocking<Unit> {
        val big = TestRequests.eurogamesSource(300L * 1024 * 1024 + 1, "c".repeat(64))
        val e = runCatching { client.createSession(TestRequests.request(big), "k") }.exceptionOrNull() as ApiException
        assertThat(e.status).isEqualTo(413)
        assertThat(e.code).isEqualTo("upload_too_large")
        fake.apiFault("/v1/sessions", Fault.Status(422, """{"error":{"code":"validation_failed","message":"bad"}}"""))
        val v = runCatching { client.createSession(TestRequests.request(src), "k2") }.exceptionOrNull() as ApiException
        assertThat(v.kind).isEqualTo(ApiErrorKind.Invalid)
        assertThat(sleeps).isEmpty()
    }

    @Test fun `500 and 503 are retried with backoff, then succeed`() = runBlocking<Unit> {
        fake.apiFault("/v1/sessions", Fault.Status(500, """{"error":{"code":"internal","message":"x"}}"""), Fault.Status(503, "<html>unavailable</html>"))
        val r = client.createSession(TestRequests.request(src), "k")
        assertThat(r.sessionId).startsWith("s_")
        assertThat(sleeps).containsExactly(500L, 1000L).inOrder()
    }

    @Test fun `5xx gives up after 3 attempts`() = runBlocking<Unit> {
        repeat(3) { fake.apiFault("/v1/sessions", Fault.Status(500, """{"error":{"code":"internal","message":"x"}}""")) }
        val e = runCatching { client.createSession(TestRequests.request(src), "k") }.exceptionOrNull() as ApiException
        assertThat(e.code).isEqualTo("internal")
        assertThat(apiCalls()).hasSize(3)
    }

    @Test fun `transient 429 waits retry_after_s, quota 429 is returned at once`() = runBlocking<Unit> {
        fake.apiFault("/v1/sessions", Fault.Status(429, """{"error":{"code":"rate_limited","message":"slow down"},"retry_after_s":2}"""))
        client.createSession(TestRequests.request(src), "k")
        assertThat(sleeps).containsExactly(2000L)
        sleeps.clear()
        fake.apiFault("/v1/sessions", Fault.Status(429, """{"error":{"code":"daily_upload_limit","message":"limit"},"retry_after_s":40000}"""))
        val e = runCatching { client.createSession(TestRequests.request(src), "k9") }.exceptionOrNull() as ApiException
        assertThat(e.code).isEqualTo("daily_upload_limit")
        assertThat(e.retryAfterS).isEqualTo(40000.0)
        assertThat(sleeps).isEmpty()
    }

    @Test fun `503 uploads_paused is reported as Paused`() = runBlocking<Unit> {
        repeat(3) { fake.apiFault("/v1/sessions", Fault.Status(503, """{"error":{"code":"uploads_paused","message":"paused"}}""")) }
        val e = runCatching { client.createSession(TestRequests.request(src), "k") }.exceptionOrNull() as ApiException
        assertThat(e.kind).isEqualTo(ApiErrorKind.Paused)
    }

    @Test fun `transport failures are retried`() = runBlocking<Unit> {
        fake.apiFault("/v1/sessions", Fault.Throw(offline = true))
        assertThat(client.createSession(TestRequests.request(src), "k").sessionId).startsWith("s_")
        assertThat(sleeps).containsExactly(500L)
    }

    @Test fun `404 not_found and 409 invalid_transition surface their codes`() = runBlocking<Unit> {
        val nf = runCatching { client.getSession("s_missing") }.exceptionOrNull() as ApiException
        assertThat(nf.code).isEqualTo("not_found")
        assertThat(nf.kind).isEqualTo(ApiErrorKind.NotFound)
        val r = client.createSession(TestRequests.request(src), "k")
        client.abort(r.sessionId)
        val it = runCatching { client.partUrls(r.sessionId, listOf(1)) }.exceptionOrNull() as ApiException
        assertThat(it.code).isEqualTo("invalid_transition")
    }

    @Test fun `redactUrl strips presigned query strings`() {
        assertThat(redactUrl("https://r2.example/b/o?X-Amz-Signature=secret&partNumber=1")).isEqualTo("https://r2.example/b/o?…")
        assertThat(redactUrl("https://api.example/v1/sessions")).isEqualTo("https://api.example/v1/sessions")
    }
}
