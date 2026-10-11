package com.elevate.app.domain.community

import com.elevate.app.data.session.ApiException
import com.elevate.app.data.session.ElevateHttp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** The challenge and friends routes (contracts 1.11.0). */
interface ChallengeApi {
    suspend fun create(request: ChallengeCreateRequest, idempotencyKey: String): Challenge
    suspend fun accept(id: String, displayName: String?): Challenge
    suspend fun submitEntry(id: String, request: ChallengeEntryRequest): Challenge
    suspend fun get(id: String): Challenge
    suspend fun friends(): FriendsResponse
}

/** [ChallengeApi] over [ElevateHttp]; a 2xx body that does not match its schema is an [ApiException]. */
class ChallengeClient(private val http: ElevateHttp) : ChallengeApi {

    override suspend fun create(request: ChallengeCreateRequest, idempotencyKey: String): Challenge {
        require(idempotencyKey.length in 1..255) { "Idempotency-Key must be 1..255 characters" }
        return decode(Challenge.serializer(), http.call("POST", "/v1/challenges", request.toJson(), idempotencyKey))
    }

    override suspend fun accept(id: String, displayName: String?): Challenge =
        decode(Challenge.serializer(), http.call("POST", "/v1/challenges/${checkId(id)}/accept", ChallengeAcceptRequest(displayName).toJson()))

    override suspend fun submitEntry(id: String, request: ChallengeEntryRequest): Challenge =
        decode(Challenge.serializer(), http.call("POST", "/v1/challenges/${checkId(id)}/entries", request.toJson()))

    override suspend fun get(id: String): Challenge =
        decode(Challenge.serializer(), http.call("GET", "/v1/challenges/${checkId(id)}"))

    override suspend fun friends(): FriendsResponse =
        decode(FriendsResponse.serializer(), http.call("GET", "/v1/friends"))

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
        challengeResponseJson.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw ApiException(200, "unexpected_response", "response does not match ${serializer.descriptor.serialName.substringAfterLast('.')}")
    } catch (e: IllegalArgumentException) {
        throw ApiException(200, "unexpected_response", "response does not match ${serializer.descriptor.serialName.substringAfterLast('.')}")
    }

    private fun checkId(id: String): String {
        require(id.matches(ChallengeLink.ID)) { "not a challenge id" }
        return id
    }
}
