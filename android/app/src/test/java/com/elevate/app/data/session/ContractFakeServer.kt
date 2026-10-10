package com.elevate.app.data.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TEST FAKE, not a server and not data: an in-memory [HttpTransport] that mirrors the behaviour of
 * Person 3's routes (`supabase/functions/api/routes/sessions.ts` on `p3/sessions-upload`, contracts
 * 1.4.0 shapes), GoTrue's anonymous sign-up / refresh, and R2 presigned part PUTs. Bytes uploaded
 * here are whatever the test feeds in; nothing from it is ever shown as a real result.
 *
 * Fault injection: [putFaults] (per part, consumed in order), [apiFaults] (per path suffix).
 */
class ContractFakeServer(
    private val nowMs: () -> Long = System::currentTimeMillis,
    val partSize: Long = 8L * 1024 * 1024,
    private val urlLifetimeS: Long = 600,
    /** Respond to `upload/parts` with the pre-1.4.0 bare URL array (the 2026-10-11 deployed shape). */
    var legacyPartsShape: Boolean = false,
    /** Sleep inside each R2 PUT, to make overlapping PUTs observable. */
    var putDelayMs: Long = 0,
) : HttpTransport {

    companion object {
        const val SUPABASE = "https://sb.fake"
        const val API = "https://sb.fake/functions/v1/api"
        const val R2 = "https://r2.fake/elevate"
        const val KEY = "sb_publishable_test"
        const val MAX_BYTES = 300L * 1024 * 1024
    }

    sealed interface Fault {
        data class Status(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap()) : Fault
        data class Throw(val offline: Boolean) : Fault
        /** Answer 200 without an ETag. */
        data object NoEtag : Fault
    }

    class Upload(val sessionId: String, val matchId: String, val uploadId: String, val bytes: Long, val source: JsonObject, val partSize: Long) {
        var status = "initiated"
        val stored = ConcurrentHashMap<Int, ByteArray>()
        val etags = ConcurrentHashMap<Int, String>()
        var urlsIssued = 0
        var completedBytes: Long? = null
        var completedAt: String? = null
        val createdAt: String = Instant.now().toString()
        val partCount get() = ((bytes + partSize - 1) / partSize).toInt()
    }

    val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())
    val uploads = ConcurrentHashMap<String, Upload>()
    val putFaults = ConcurrentHashMap<Int, ArrayDeque<Fault>>()
    val apiFaults = ConcurrentHashMap<String, ArrayDeque<Fault>>()
    private val idem = ConcurrentHashMap<String, Pair<String, String>>()
    private val seq = AtomicInteger()
    private val inFlight = AtomicInteger()
    val maxConcurrentPuts = AtomicInteger()
    val putCount = AtomicInteger()
    val signUps = AtomicInteger()
    val refreshes = AtomicInteger()

    /** Access tokens the "auth server" accepts; [expireTokens] invalidates them (API answers 401). */
    private val validTokens = ConcurrentHashMap.newKeySet<String>()
    var tokenLifetimeS = 3600L

    fun expireTokens() = validTokens.clear()
    fun fault(part: Int, vararg f: Fault) = putFaults.getOrPut(part) { ArrayDeque() }.addAll(f)
    fun apiFault(pathSuffix: String, vararg f: Fault) = apiFaults.getOrPut(pathSuffix) { ArrayDeque() }.addAll(f)
    fun puts(): List<HttpRequest> = requests.filter { it.method == "PUT" }
    fun calls(suffix: String) = requests.count { it.url.substringBefore('?').endsWith(suffix) }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val url = request.url
        return when {
            url.startsWith("$SUPABASE/auth/v1/") -> auth(request)
            url.startsWith("$API/v1/") -> api(request)
            url.startsWith(R2) -> r2(request)
            else -> HttpResponse(404, emptyMap(), ByteArray(0))
        }
    }

    // ---- GoTrue -----------------------------------------------------------------------------

    private fun auth(r: HttpRequest): HttpResponse {
        if (r.headers["apikey"] != KEY) return json(401, """{"message":"Invalid API key"}""")
        val n = seq.incrementAndGet()
        val path = r.url.removePrefix("$SUPABASE/auth/v1/")
        when {
            path == "signup" -> signUps.incrementAndGet()
            path.startsWith("token?grant_type=refresh_token") -> {
                val rt = Json.parseToJsonElement(body(r)).jsonObject["refresh_token"]?.jsonPrimitive?.content
                if (rt == null || !rt.startsWith("rt_")) return json(400, """{"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""")
                refreshes.incrementAndGet()
            }
            else -> return json(404, "{}")
        }
        val access = "at_$n"
        validTokens += access
        return json(
            200,
            buildJsonObject {
                put("access_token", access)
                put("refresh_token", "rt_$n")
                put("expires_in", tokenLifetimeS)
                put("expires_at", nowMs() / 1000 + tokenLifetimeS)
                putJsonObject("user") { put("id", "user-1"); put("is_anonymous", true) }
            }.toString(),
        )
    }

    // ---- API ---------------------------------------------------------------------------------

    private fun err(status: Int, code: String, message: String, details: JsonElement? = null, retryAfter: Double? = null) =
        json(
            status,
            buildJsonObject {
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                    if (details != null) put("details", details)
                }
                if (retryAfter != null) put("retry_after_s", retryAfter)
            }.toString(),
        )

    private fun api(r: HttpRequest): HttpResponse {
        val token = r.headers["Authorization"]?.removePrefix("Bearer ")
        if (r.headers["apikey"] != KEY || token == null || token !in validTokens) return err(401, "unauthorized", "missing or invalid JWT")
        val path = r.url.removePrefix(API).substringBefore('?')
        apiFaults.entries.firstOrNull { path.endsWith(it.key) && it.value.isNotEmpty() }?.let { (_, q) ->
            when (val f = q.removeFirst()) {
                is Fault.Status -> return HttpResponse(f.status, mapOf("Content-Type" to "application/json") + f.headers, f.body.toByteArray())
                is Fault.Throw -> throw TransportException("fake transport failure", f.offline)
                Fault.NoEtag -> Unit
            }
        }
        val m = Regex("^/v1/sessions/(s_[A-Za-z0-9_-]+)(/upload/(parts|complete|abort))?$").find(path)
        return when {
            r.method == "POST" && path == "/v1/sessions" -> create(r)
            m != null && r.method == "GET" && m.groupValues[2].isEmpty() -> getSession(m.groupValues[1])
            m != null && r.method == "POST" && m.groupValues[3] == "parts" -> parts(m.groupValues[1], r)
            m != null && r.method == "POST" && m.groupValues[3] == "complete" -> complete(m.groupValues[1], r)
            m != null && r.method == "POST" && m.groupValues[3] == "abort" -> abort(m.groupValues[1])
            else -> err(404, "not_found", "route not found")
        }
    }

    private fun create(r: HttpRequest): HttpResponse {
        val key = r.headers["Idempotency-Key"] ?: return err(422, "validation_failed", "Idempotency-Key header required")
        val text = body(r)
        idem[key]?.let { (hash, resp) -> return if (hash == text) json(201, resp) else err(409, "idempotency_conflict", "Idempotency-Key was used with a different request") }
        val req = Json.parseToJsonElement(text).jsonObject
        val source = req["source"]!!.jsonObject
        val bytes = source["bytes"]!!.jsonPrimitive.long
        if (bytes > MAX_BYTES) return err(413, "upload_too_large", "video exceeds $MAX_BYTES bytes")
        if (bytes < 1) return err(422, "validation_failed", "source.bytes must be at least 1")
        val n = seq.incrementAndGet()
        val u = Upload("s_fake$n", "m_fake$n", "upl-$n", bytes, source, partSize)
        uploads[u.sessionId] = u
        val resp = buildJsonObject {
            put("session_id", u.sessionId)
            put("match_id", u.matchId)
            putJsonObject("upload") {
                put("upload_id", u.uploadId)
                put("r2_key", "orig/${u.sessionId}/source.mp4")
                put("part_size", partSize)
                put("max_concurrency", 2)
            }
        }.toString()
        idem[key] = text to resp
        return json(201, resp)
    }

    private fun getSession(id: String): HttpResponse {
        val u = uploads[id] ?: return err(404, "not_found", "session not found")
        return json(
            200,
            buildJsonObject {
                put("session_id", u.sessionId)
                put("match_id", u.matchId)
                put("mode", "practice")
                put("upload_mode", "multipart")
                put("source", u.source)
                put("created_at", u.createdAt)
                putJsonObject("upload") {
                    put("status", u.status)
                    put("part_size", partSize)
                    put("bytes", u.completedBytes ?: u.bytes)
                    put("part_count", u.partCount)
                    put("completed_at", u.completedAt?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                }
            }.toString(),
        )
    }

    private fun parts(id: String, r: HttpRequest): HttpResponse {
        val u = uploads[id] ?: return err(404, "not_found", "session not found")
        val nums = Json.parseToJsonElement(body(r)).jsonObject["part_numbers"]!!.jsonArray.map { it.jsonPrimitive.int }
        if (nums.size !in 1..50) return err(422, "validation_failed", "part_numbers must hold 1 to 50 entries")
        if (u.status != "initiated") return err(409, "invalid_transition", "upload is ${u.status}")
        if (nums.any { it !in 1..u.partCount }) return err(400, "invalid_part_number", "part numbers must be in 1..${u.partCount}")
        // G4: at most 3 x part count URLs per session.
        if (u.urlsIssued + nums.size > 3 * u.partCount) return err(429, "too_many_part_requests", "part URL allowance spent")
        u.urlsIssued += nums.size
        val exp = Instant.ofEpochMilli(nowMs() + urlLifetimeS * 1000).toString()
        val urls = nums.map { n -> n to "$R2/orig/$id/source.mp4?partNumber=$n&uploadId=${u.uploadId}&X-Amz-Expires=$urlLifetimeS&exp=${nowMs() + urlLifetimeS * 1000}&X-Amz-Signature=secret$n" }
        return if (legacyPartsShape) json(200, buildJsonArray { urls.forEach { add(JsonPrimitive(it.second)) } }.toString())
        else json(
            200,
            buildJsonObject {
                putJsonArray("parts") {
                    urls.forEach { (n, url) -> add(buildJsonObject { put("n", n); put("url", url); put("expires_at", exp) }) }
                }
            }.toString(),
        )
    }

    private fun complete(id: String, r: HttpRequest): HttpResponse {
        val u = uploads[id] ?: return err(404, "not_found", "session not found")
        if (u.status == "completed") return json(200, """{"status":"completed","bytes":${u.completedBytes}}""")
        if (u.status == "aborted") return err(409, "invalid_transition", "upload was aborted")
        val parts = Json.parseToJsonElement(body(r)).jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
        val ns = parts.map { it["n"]!!.jsonPrimitive.int }
        if (ns != ns.sorted()) return err(409, "upload_parts_invalid", "InvalidPartOrder")
        val missing = (1..u.partCount).filter { it !in ns }
        if (missing.isNotEmpty() || ns.toSet().size != ns.size) {
            return err(409, "upload_parts_invalid", "parts must list every part 1..N exactly once", buildJsonObject { put("expected_parts", u.partCount); putJsonArray("missing") { missing.forEach { add(JsonPrimitive(it)) } } })
        }
        for (p in parts) {
            val n = p["n"]!!.jsonPrimitive.int
            if (u.etags[n] != p["etag"]!!.jsonPrimitive.content) return err(409, "upload_parts_invalid", "InvalidPart")
        }
        u.completedBytes = (1..u.partCount).sumOf { u.stored[it]!!.size.toLong() }
        u.completedAt = Instant.ofEpochMilli(nowMs()).toString()
        u.status = "completed"
        return json(200, """{"status":"completed","bytes":${u.completedBytes}}""")
    }

    private fun abort(id: String): HttpResponse {
        val u = uploads[id] ?: return err(404, "not_found", "session not found")
        if (u.status == "completed") return err(409, "invalid_transition", "upload is completed")
        u.status = "aborted"
        u.stored.clear()
        return json(200, """{"status":"aborted"}""")
    }

    // ---- R2 ----------------------------------------------------------------------------------

    private fun r2(r: HttpRequest): HttpResponse {
        if (r.method != "PUT") return HttpResponse(405, emptyMap(), ByteArray(0))
        val q = r.url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val n = q.getValue("partNumber").toInt()
        val id = r.url.substringAfter("/orig/").substringBefore('/')
        val now = inFlight.incrementAndGet()
        maxConcurrentPuts.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        putCount.incrementAndGet()
        try {
            val body = r.body ?: return HttpResponse(411, emptyMap(), ByteArray(0))
            val out = ByteArrayOutputStream()
            body.writeTo(out)
            if (putDelayMs > 0) Thread.sleep(putDelayMs)
            check(out.size().toLong() == body.contentLength) { "Content-Length ${body.contentLength} but ${out.size()} bytes written" }
            putFaults[n]?.removeFirstOrNull()?.let { f ->
                when (f) {
                    is Fault.Status -> return HttpResponse(f.status, f.headers, f.body.toByteArray())
                    is Fault.Throw -> throw TransportException("fake PUT failure", f.offline)
                    Fault.NoEtag -> return HttpResponse(200, emptyMap(), ByteArray(0))
                }
            }
            if (q.getValue("exp").toLong() <= nowMs()) return HttpResponse(403, emptyMap(), "<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>".toByteArray())
            val u = uploads[id] ?: return HttpResponse(404, emptyMap(), "<Error><Code>NoSuchUpload</Code></Error>".toByteArray())
            if (u.status != "initiated") return HttpResponse(404, emptyMap(), "<Error><Code>NoSuchUpload</Code></Error>".toByteArray())
            val bytes = out.toByteArray()
            val etag = "\"" + MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } + "\""
            u.stored[n] = bytes
            u.etags[n] = etag
            return HttpResponse(200, mapOf("ETag" to etag), ByteArray(0))
        } finally {
            inFlight.decrementAndGet()
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun body(r: HttpRequest): String {
        val out = ByteArrayOutputStream()
        r.body?.writeTo(out)
        return out.toString(Charsets.UTF_8.name())
    }

    private fun json(status: Int, text: String) = HttpResponse(status, mapOf("Content-Type" to "application/json"), text.toByteArray())

    /** The assembled object (parts in order) the fake R2 holds for [sessionId]. */
    fun assembled(sessionId: String): ByteArray {
        val u = uploads.getValue(sessionId)
        val out = ByteArrayOutputStream()
        for (n in 1..u.partCount) out.write(u.stored.getValue(n))
        return out.toByteArray()
    }
}
