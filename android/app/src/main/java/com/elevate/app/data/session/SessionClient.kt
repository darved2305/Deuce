package com.elevate.app.data.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The session and upload routes (contracts §2, 1.4.0), as the uploader needs them. */
interface SessionApi {
    suspend fun createSession(request: SessionCreateRequest, idempotencyKey: String): SessionCreateResponse
    suspend fun getSession(sessionId: String): SessionView

    /** Presigned PUT URLs, one per requested part. At most [MAX_PARTS_PER_CALL] per call. */
    suspend fun partUrls(sessionId: String, partNumbers: List<Int>): List<PartUrl>
    suspend fun complete(sessionId: String, parts: List<CompletedPart>): UploadCompleteResponse
    suspend fun abort(sessionId: String): UploadAbortResponse

    companion object {
        const val MAX_PARTS_PER_CALL = 50
    }
}

/**
 * [SessionApi] over [ElevateHttp], to `docs/plan/contracts.md` §2 and the 1.4.0 schemas
 * (`session.*.v1`, `upload.*.v1`).
 */
class SessionClient(
    private val http: ElevateHttp,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SessionApi {

    override suspend fun createSession(request: SessionCreateRequest, idempotencyKey: String): SessionCreateResponse {
        require(idempotencyKey.length in 1..255) { "Idempotency-Key must be 1..255 characters" }
        val body = http.call("POST", "/v1/sessions", request.toJson(), idempotencyKey)
        return decode(SessionCreateResponse.serializer(), body)
    }

    override suspend fun getSession(sessionId: String): SessionView =
        decode(SessionView.serializer(), http.call("GET", "/v1/sessions/${checkId(sessionId)}"))

    override suspend fun partUrls(sessionId: String, partNumbers: List<Int>): List<PartUrl> {
        require(partNumbers.size in 1..SessionApi.MAX_PARTS_PER_CALL) { "1..${SessionApi.MAX_PARTS_PER_CALL} part numbers per call" }
        val body = requestJson.encodeToString(UploadPartsRequest.serializer(), UploadPartsRequest(partNumbers))
        val text = http.call("POST", "/v1/sessions/${checkId(sessionId)}/upload/parts", body)
        val parts = try {
            parsePartUrls(text, partNumbers, nowMs())
        } catch (e: SerializationException) {
            throw ApiException(200, "unexpected_response", "part URLs response does not match upload.parts.response.v1")
        }
        if (parts.map { it.n }.sorted() != partNumbers.sorted()) {
            throw ApiException(200, "unexpected_response", "asked for parts $partNumbers, got ${parts.map { it.n }}")
        }
        return parts
    }

    override suspend fun complete(sessionId: String, parts: List<CompletedPart>): UploadCompleteResponse {
        val body = requestJson.encodeToString(UploadCompleteRequest.serializer(), UploadCompleteRequest(parts.sortedBy { it.n }))
        return decode(
            UploadCompleteResponse.serializer(),
            http.call("POST", "/v1/sessions/${checkId(sessionId)}/upload/complete", body),
        )
    }

    override suspend fun abort(sessionId: String): UploadAbortResponse =
        decode(
            UploadAbortResponse.serializer(),
            http.call("POST", "/v1/sessions/${checkId(sessionId)}/upload/abort", "{}"),
        )

    /** A 2xx body that does not match its schema is an [ApiException] (never an unchecked crash). */
    private fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
        responseJson.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw ApiException(200, "unexpected_response", "response does not match ${serializer.descriptor.serialName.substringAfterLast('.')}")
    } catch (e: IllegalArgumentException) {
        throw ApiException(200, "unexpected_response", "response does not match ${serializer.descriptor.serialName.substringAfterLast('.')}")
    }

    private fun checkId(id: String): String {
        require(id.matches(Regex("^s_[A-Za-z0-9_-]+$"))) { "not a session id" }
        return id
    }

    companion object {
        /**
         * Reads `upload.parts.response.v1` (`{parts: [{n, url, expires_at}]}`, contracts 1.4.0).
         *
         * LEGACY SHIM: the API deployed on 2026-10-11 still answers the pre-1.4.0 shape, a bare array
         * of URLs in request order (its OpenAPI says "presigned PUT URLs (600 s)"). That array is read
         * as parts in request order with an `expires_at` of [issuedAtMs] + [LEGACY_URL_LIFETIME_S]
         * (the route's stated lifetime, never longer than G6's 10 min). Remove once Person 3 deploys
         * the 1.4.0 shape (android/UPLOAD_CLIENT.md, "Contract vs deployed API").
         */
        fun parsePartUrls(text: String, requested: List<Int>, issuedAtMs: Long): List<PartUrl> =
            when (val el = responseJson.parseToJsonElement(text)) {
                is JsonObject -> responseJson.decodeFromJsonElement(UploadPartsResponse.serializer(), el).parts
                is JsonArray -> {
                    if (el.size != requested.size) {
                        throw ApiException(200, "unexpected_response", "asked for ${requested.size} URLs, got ${el.size}")
                    }
                    val expires = java.time.Instant.ofEpochMilli(issuedAtMs + LEGACY_URL_LIFETIME_S * 1000).toString()
                    el.mapIndexed { i, u ->
                        val url = (u as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                            ?: throw ApiException(200, "unexpected_response", "part URL is not a string")
                        PartUrl(requested[i], url, expires)
                    }
                }
                else -> throw ApiException(200, "unexpected_response", "part URLs response is not an object")
            }

        const val LEGACY_URL_LIFETIME_S = 600L
    }
}
