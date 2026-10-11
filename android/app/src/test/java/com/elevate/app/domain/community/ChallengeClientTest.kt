package com.elevate.app.domain.community

import com.elevate.app.data.auth.AccessTokenProvider
import com.elevate.app.data.session.ApiErrorKind
import com.elevate.app.data.session.ApiException
import com.elevate.app.data.session.ElevateHttp
import com.elevate.app.data.session.HttpRequest
import com.elevate.app.data.session.HttpResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ChallengeClientTest {
    private val requests = mutableListOf<HttpRequest>()
    private var next: Pair<Int, String> = 200 to "{}"

    private val auth = object : AccessTokenProvider {
        override suspend fun accessToken() = "tok"
        override suspend fun refreshAfterUnauthorized(rejected: String) = "tok2"
    }
    private val client = ChallengeClient(
        ElevateHttp("https://api.test", "pk", auth, { req -> requests += req; HttpResponse(next.first, emptyMap(), next.second.toByteArray()) }, Dispatchers.Unconfined, sleep = {}),
    )

    private val challengeJson = """
        {"id":"ch_abc","match_id":"m_1","status":"open","video_sha256":"${"a".repeat(64)}","rematch_of":null,
         "rounds":[{"hit_event_id":"e_1","landing_event_id":"e_2","contact_ms":3000,"contact_sigma_ms":12,"ladder_us":[2800000,2900000]}],
         "creator":{"player_id":"11111111-1111-4111-8111-111111111111","display_name":"Hetansh","score":1240,"round_results":[]},
         "entries":[{"player_id":"22222222-2222-4222-8222-222222222222","display_name":"Asha","score":null,"round_results":null,"outcome":null,"accepted_at":"2026-10-11T10:00:00Z","played_at":null}],
         "viewer":{"player_id":"22222222-2222-4222-8222-222222222222","role":"opponent"},"created_at":"2026-10-11T09:00:00Z","future_field":1}
    """.trimIndent()

    @Test fun `create posts the request with the Idempotency-Key and reads the answer`() = runBlocking<Unit> {
        next = 201 to challengeJson
        val c = client.create(Challenges.createRequest("m_1", CommunityTestData.finishedSession(), "Hetansh"), "run-1")
        val r = requests.single()
        assertThat(r.method).isEqualTo("POST")
        assertThat(r.url).isEqualTo("https://api.test/v1/challenges")
        assertThat(r.headers["Idempotency-Key"]).isEqualTo("run-1")
        assertThat(r.headers["Authorization"]).isEqualTo("Bearer tok")
        assertThat(c.id).isEqualTo("ch_abc")
        assertThat(c.creator.score).isEqualTo(1240)
        assertThat(c.myEntry?.score).isNull()
        assertThat(c.isCreator).isFalse()
    }

    @Test fun `accept entries get and friends use their routes`() = runBlocking<Unit> {
        next = 200 to challengeJson
        client.accept("ch_abc", "Asha")
        client.submitEntry("ch_abc", Challenges.entryRequest(CommunityTestData.finishedSession(), null))
        client.get("ch_abc")
        next = 200 to """{"items":[{"player_id":"22222222-2222-4222-8222-222222222222","display_name":"Asha","challenges":3,"played":2,"wins":1,"losses":1,"ties":0,"last_challenge_at":"2026-10-11T10:00:00Z"}]}"""
        val f = client.friends()
        assertThat(requests.map { it.method + " " + it.url.removePrefix("https://api.test") }).containsExactly(
            "POST /v1/challenges/ch_abc/accept", "POST /v1/challenges/ch_abc/entries", "GET /v1/challenges/ch_abc", "GET /v1/friends",
        ).inOrder()
        assertThat(requests[0].body).isNotNull()
        assertThat(f.items.single().wins).isEqualTo(1)
    }

    @Test fun `an error envelope becomes an ApiException with its kind`() = runBlocking<Unit> {
        next = 409 to """{"error":{"code":"conflict","message":"cannot accept your own challenge"}}"""
        val e = runCatching { client.accept("ch_abc", null) }.exceptionOrNull() as ApiException
        assertThat(e.kind).isEqualTo(ApiErrorKind.Conflict)
        assertThat(e.code).isEqualTo("conflict")
        next = 404 to """{"error":{"code":"not_found","message":"challenge not found"}}"""
        assertThat((runCatching { client.get("ch_abc") }.exceptionOrNull() as ApiException).kind).isEqualTo(ApiErrorKind.NotFound)
    }

    @Test fun `a body that does not match the schema is an unexpected_response error`() = runBlocking<Unit> {
        next = 200 to """{"id":"ch_abc"}"""
        val e = runCatching { client.get("ch_abc") }.exceptionOrNull() as ApiException
        assertThat(e.code).isEqualTo("unexpected_response")
    }

    @Test fun `a bad id never reaches the network`() = runBlocking<Unit> {
        val e = runCatching { client.get("m_1/../x") }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(requests).isEmpty()
    }
}
