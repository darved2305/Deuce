package com.elevate.app.data.session

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * The session/upload client against `packages/contracts/schemas` (contracts 1.4.0): every request
 * body the client sends validates against its schema, every response schema's fields match the
 * Kotlin reader (names, required = non-optional, nullable only where the schema allows null), and
 * a schema-valid response is read.
 */
class SessionContractTest {

    private fun schema(name: String) =
        Json.parseToJsonElement(File(TestRequests.repo, "packages/contracts/schemas/$name.schema.json").readText()).jsonObject

    private fun variants(n: JsonObject): List<JsonObject> =
        n["anyOf"]?.jsonArray?.map { it.jsonObject }?.flatMap(::variants) ?: listOf(n)

    private fun allowsNull(n: JsonObject) = variants(n).any { it["type"] == JsonPrimitive("null") }
    private fun objectVariant(n: JsonObject) = variants(n).first { it["type"] == JsonPrimitive("object") }

    /** Field names equal; required fields are non-optional; nullable exactly where null is allowed. */
    private fun check(node: JsonObject, d: SerialDescriptor, what: String) {
        val o = objectVariant(node)
        val props = o["properties"]!!.jsonObject
        val required = o["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
        val names = (0 until d.elementsCount).associateBy { d.getElementName(it) }
        assertWithMessage("$what: fields").that(names.keys).containsExactlyElementsIn(props.keys)
        for ((k, i) in names) {
            val p = props.getValue(k).jsonObject
            if (k in required) assertWithMessage("$what.$k is required").that(d.isElementOptional(i)).isFalse()
            if (d.getElementDescriptor(i).isNullable) assertWithMessage("$what.$k is nullable but the schema forbids null").that(allowsNull(p)).isTrue()
        }
    }

    private fun prop(node: JsonObject, vararg path: String): JsonObject {
        var n = node
        for (p in path) {
            n = if (p == "[]") variants(n).first { it["type"] == JsonPrimitive("array") }["items"]!!.jsonObject
            else objectVariant(n)["properties"]!!.jsonObject[p]!!.jsonObject
        }
        return n
    }

    private fun <T> roundTrip(name: String, s: KSerializer<T>, value: T) {
        val errs = MiniSchema.validate(schema(name), Json.parseToJsonElement(requestJson.encodeToString(s, value)))
        assertWithMessage("$name violations").that(errs).isEmpty()
    }

    @Test fun `create request from caller pieces validates against session_create_request_v1`() {
        val src = TestRequests.eurogamesSource(7_717_311, "3669343e1837befefe9f77ae3b6a740767e4b6e59054d54b9cbbac1042d786ed")
        // Minimal pieces: no calibration, no rally candidates.
        val minimal = TestRequests.request(src)
        assertThat(MiniSchema.validate(schema("session.create.request.v1"), Json.parseToJsonElement(minimal.toJson()))).isEmpty()
        // Full pieces, as in contracts §2.
        val full = TestRequests.request(src, TestRequests.exampleCalibration, TestRequests.exampleRallies)
        val json = Json.parseToJsonElement(full.toJson()).jsonObject
        assertThat(MiniSchema.validate(schema("session.create.request.v1"), json)).isEmpty()
        // Nullable required keys are written as explicit nulls, not dropped.
        assertThat(json.keys).containsAtLeast("calibration_input", "free_play_seed", "rally_candidates", "upload_mode")
        assertThat(json["upload_mode"]).isEqualTo(JsonPrimitive("multipart"))
    }

    @Test fun `create request serializers match the schema field by field`() {
        val s = schema("session.create.request.v1")
        check(s, SessionCreateRequest.serializer().descriptor, "session.create.request.v1")
        check(prop(s, "source"), SessionSource.serializer().descriptor, "source")
        check(prop(s, "source", "pts"), SourcePts.serializer().descriptor, "source.pts")
        check(prop(s, "ruleset"), Ruleset.serializer().descriptor, "ruleset")
    }

    @Test fun `request pieces outside the contract are refused before sending`() {
        val src = TestRequests.eurogamesSource(10, "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { TestRequests.request(src).copy(sport = "squash") }
        assertThrows(IllegalArgumentException::class.java) { TestRequests.request(src).copy(whoIsMe = kotlinx.serialization.json.JsonNull) }
        assertThrows(IllegalArgumentException::class.java) { TestRequests.eurogamesSource(10, "not-a-hash") }
        assertThrows(IllegalArgumentException::class.java) { TestRequests.eurogamesSource(0, "a".repeat(64)) }
    }

    @Test fun `parts, complete requests validate`() {
        roundTrip("upload.parts.request.v1", UploadPartsRequest.serializer(), UploadPartsRequest(listOf(1, 2, 3)))
        roundTrip("upload.complete.request.v1", UploadCompleteRequest.serializer(), UploadCompleteRequest(listOf(CompletedPart(1, "\"abc\""), CompletedPart(2, "\"def\""))))
        check(schema("upload.parts.request.v1"), UploadPartsRequest.serializer().descriptor, "upload.parts.request.v1")
        check(schema("upload.complete.request.v1"), UploadCompleteRequest.serializer().descriptor, "upload.complete.request.v1")
        check(prop(schema("upload.complete.request.v1"), "parts", "[]"), CompletedPart.serializer().descriptor, "upload.complete.request.v1.parts[]")
    }

    @Test fun `response readers match the 1_4_0 response schemas`() {
        check(schema("session.create.response.v1"), SessionCreateResponse.serializer().descriptor, "session.create.response.v1")
        check(prop(schema("session.create.response.v1"), "upload"), UploadTicket.serializer().descriptor, "session.create.response.v1.upload")
        val parts = schema("upload.parts.response.v1")
        check(parts, UploadPartsResponse.serializer().descriptor, "upload.parts.response.v1")
        check(prop(parts, "parts", "[]"), PartUrl.serializer().descriptor, "upload.parts.response.v1.parts[]")
        check(schema("upload.complete.response.v1"), UploadCompleteResponse.serializer().descriptor, "upload.complete.response.v1")
        check(schema("upload.abort.response.v1"), UploadAbortResponse.serializer().descriptor, "upload.abort.response.v1")
        val get = schema("session.get.response.v1")
        check(get, SessionView.serializer().descriptor, "session.get.response.v1")
        check(prop(get, "upload"), SessionUploadView.serializer().descriptor, "session.get.response.v1.upload")
        check(prop(get, "source"), SessionSource.serializer().descriptor, "session.get.response.v1.source")
    }

    @Test fun `the contract fake's answers are schema-valid (so the fake tests the real shapes)`() = runBlocking<Unit> {
        val fake = ContractFakeServer()
        val (client, _) = clientFor(fake)
        val bytes = ByteArray(10) { it.toByte() }
        val src = TestRequests.eurogamesSource(10, TestRequests.sha256(bytes))
        val created = client.createSession(TestRequests.request(src), "k1")
        val fakeCreate = fake.requests.last()
        assertThat(fakeCreate.headers["Idempotency-Key"]).isEqualTo("k1")
        val raw = { suffix: String -> fake.requests.last { it.url.endsWith(suffix) } }
        assertThat(raw("/v1/sessions").method).isEqualTo("POST")
        // Re-issue each call directly through the fake to see its raw body, then validate.
        fun body(r: HttpRequest) = Json.parseToJsonElement(fake.execute(r).text())
        val get = body(HttpRequest("GET", "${ContractFakeServer.API}/v1/sessions/${created.sessionId}", authHeaders(fake)))
        assertThat(MiniSchema.validate(schema("session.get.response.v1"), get)).isEmpty()
        val parts = body(HttpRequest("POST", "${ContractFakeServer.API}/v1/sessions/${created.sessionId}/upload/parts", authHeaders(fake), BytesBody("""{"part_numbers":[1]}""".toByteArray())))
        assertThat(MiniSchema.validate(schema("upload.parts.response.v1"), parts)).isEmpty()
        val abort = body(HttpRequest("POST", "${ContractFakeServer.API}/v1/sessions/${created.sessionId}/upload/abort", authHeaders(fake), BytesBody("{}".toByteArray())))
        assertThat(MiniSchema.validate(schema("upload.abort.response.v1"), abort)).isEmpty()
        val err = body(HttpRequest("GET", "${ContractFakeServer.API}/v1/sessions/s_nope", authHeaders(fake)))
        assertThat(MiniSchema.validate(schema("error.envelope.v1"), err)).isEmpty()
    }

    @Test fun `parts response - 1_4_0 shape is read with n, url and expiry`() {
        val text = """{"parts":[{"n":2,"url":"https://r2.example/p?partNumber=2&X-Amz-Signature=s","expires_at":"2026-10-11T12:10:00Z"},{"n":1,"url":"https://r2.example/p?partNumber=1","expires_at":"2026-10-11T12:10:00.000+00:00"}]}"""
        assertThat(MiniSchema.validate(schema("upload.parts.response.v1"), Json.parseToJsonElement(text))).isEmpty()
        val parts = SessionClient.parsePartUrls(text, listOf(2, 1), issuedAtMs = 0)
        assertThat(parts.map { it.n }).containsExactly(2, 1).inOrder()
        assertThat(parts[0].expiresAtMs).isEqualTo(java.time.Instant.parse("2026-10-11T12:10:00Z").toEpochMilli())
        assertThat(parts[1].expiresAtMs).isEqualTo(parts[0].expiresAtMs)
        // URLs never print their signature.
        assertThat(parts[0].toString()).doesNotContain("Signature")
    }

    @Test fun `parts response - legacy bare array (deployed pre-1_4_0) maps to request order with a 600 s expiry`() {
        val parts = SessionClient.parsePartUrls("""["https://r2.example/a","https://r2.example/b"]""", listOf(3, 4), issuedAtMs = 1_000)
        assertThat(parts.map { it.n }).containsExactly(3, 4).inOrder()
        assertThat(parts[0].expiresAtMs).isEqualTo(601_000)
        assertThrows(ApiException::class.java) { SessionClient.parsePartUrls("""["https://r2.example/a"]""", listOf(3, 4), 0) }
    }

    @Test fun `error envelope is parsed into code, message, details and retry_after_s`() {
        val text = """{"error":{"code":"upload_parts_invalid","message":"parts must list every part 1..N exactly once","details":{"expected_parts":3,"missing":[2]}}}"""
        assertThat(MiniSchema.validate(schema("error.envelope.v1"), Json.parseToJsonElement(text))).isEmpty()
        val e = ApiException.from(409, text)
        assertThat(e.code).isEqualTo("upload_parts_invalid")
        assertThat(e.kind).isEqualTo(ApiErrorKind.Conflict)
        assertThat(e.details!!.jsonObject["missing"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("2")
        val q = ApiException.from(429, """{"error":{"code":"storage_budget_reached","message":"try later"},"retry_after_s":3600}""")
        assertThat(q.retryAfterS).isEqualTo(3600.0)
        assertThat(q.transient).isFalse()
        val plain = ApiException.from(502, "<html>bad gateway</html>")
        assertThat(plain.code).isEqualTo("http_502")
        assertThat(plain.transient).isTrue()
        assertThat(ApiException.from(503, """{"error":{"code":"uploads_paused","message":"paused"}}""").kind).isEqualTo(ApiErrorKind.Paused)
        assertThat(ApiException.from(413, """{"error":{"code":"upload_too_large","message":"too big"}}""").kind).isEqualTo(ApiErrorKind.TooLarge)
    }

    @Test fun `a schema-violating 2xx body is an ApiException, not a crash`() = runBlocking<Unit> {
        val fake = ContractFakeServer()
        val (client, _) = clientFor(fake)
        fake.apiFault("/upload/abort", ContractFakeServer.Fault.Status(200, """{"state":"gone"}"""))
        val e = runCatching { client.abort("s_fake1") }.exceptionOrNull()
        assertThat(e).isInstanceOf(ApiException::class.java)
        assertThat((e as ApiException).code).isEqualTo("unexpected_response")
    }

    private fun authHeaders(fake: ContractFakeServer): Map<String, String> {
        val token = fake.requests.last { it.url.startsWith(ContractFakeServer.API) }.headers.getValue("Authorization")
        return mapOf("Authorization" to token, "apikey" to ContractFakeServer.KEY)
    }
}
