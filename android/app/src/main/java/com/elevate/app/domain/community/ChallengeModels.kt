package com.elevate.app.domain.community

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Kotlin readers and writers for the friends and challenges schemas (@elevate/contracts 1.11.0,
 * D-261011-HW31): `challenge.v1`, `challenge.create.request.v1`, `challenge.accept.request.v1`,
 * `challenge.entry.request.v1`, `friends.response.v1`. Field names and nullability are checked
 * against the generated JSON Schemas by `ContractSchemaTest`.
 */
internal val challengeResponseJson = Json { ignoreUnknownKeys = true }

/** Strict producers: every field is written, nullable ones as explicit null (`pick`, `reaction_ms`). */
internal val challengeRequestJson = Json { encodeDefaults = true; explicitNulls = true }

/** One frozen shot. [ladderUs] are real decoded frame times of the creator's file. */
@Serializable
data class ChallengeRound(
    @SerialName("hit_event_id") val hitEventId: String,
    @SerialName("landing_event_id") val landingEventId: String,
    @SerialName("contact_ms") val contactMs: Long,
    @SerialName("contact_sigma_ms") val contactSigmaMs: Long,
    @SerialName("ladder_us") val ladderUs: List<Long>,
)

@Serializable
data class ChallengeRoundResult(
    @SerialName("hit_event_id") val hitEventId: String,
    val pick: String?,
    @SerialName("reaction_ms") val reactionMs: Long?,
    val verdict: String,
    val points: Int,
)

@Serializable
data class ChallengeCreateRequest(
    @SerialName("match_id") val matchId: String,
    @SerialName("display_name") val displayName: String? = null,
    val rounds: List<ChallengeRound>,
    val score: Int,
    @SerialName("round_results") val roundResults: List<ChallengeRoundResult>,
    @SerialName("rematch_of") val rematchOf: String? = null,
) {
    /** Optional fields the schema allows absent are left out, never sent as null. */
    fun toJson(): String = challengeRequestJson.encodeToJsonElement(serializer(), this).withoutNull("display_name", "rematch_of")
}

@Serializable
data class ChallengeAcceptRequest(@SerialName("display_name") val displayName: String? = null) {
    fun toJson(): String = challengeRequestJson.encodeToJsonElement(serializer(), this).withoutNull("display_name")
}

@Serializable
data class ChallengeEntryRequest(
    @SerialName("display_name") val displayName: String? = null,
    val score: Int,
    @SerialName("round_results") val roundResults: List<ChallengeRoundResult>,
) {
    fun toJson(): String = challengeRequestJson.encodeToJsonElement(serializer(), this).withoutNull("display_name")
}

/** The schema's optional (not nullable) fields: leave `"name":null` out when the value is absent. */
private fun JsonElement.withoutNull(vararg names: String): String =
    JsonObject(jsonObject.filterNot { (k, v) -> k in names && v is JsonNull }).toString()

@Serializable
data class ChallengePlayer(
    @SerialName("player_id") val playerId: String,
    @SerialName("display_name") val displayName: String,
    val score: Int,
    @SerialName("round_results") val roundResults: List<ChallengeRoundResult>,
)

@Serializable
data class ChallengeEntry(
    @SerialName("player_id") val playerId: String,
    @SerialName("display_name") val displayName: String,
    val score: Int?,
    @SerialName("round_results") val roundResults: List<ChallengeRoundResult>?,
    /** From the creator's side: `creator`, `opponent` or `tie`; null until played. */
    val outcome: String?,
    @SerialName("accepted_at") val acceptedAt: String,
    @SerialName("played_at") val playedAt: String?,
)

@Serializable
data class ChallengeViewer(@SerialName("player_id") val playerId: String, val role: String)

/** `challenge.v1`: a challenge as the caller may see it. */
@Serializable
data class Challenge(
    val id: String,
    @SerialName("match_id") val matchId: String,
    val status: String,
    @SerialName("video_sha256") val videoSha256: String,
    @SerialName("rematch_of") val rematchOf: String?,
    val rounds: List<ChallengeRound>,
    val creator: ChallengePlayer,
    val entries: List<ChallengeEntry>,
    val viewer: ChallengeViewer,
    @SerialName("created_at") val createdAt: String,
) {
    val isCreator: Boolean get() = viewer.role == "creator"

    /** The viewer's own entry when they are an opponent. */
    val myEntry: ChallengeEntry? get() = entries.firstOrNull { it.playerId == viewer.playerId }
}

@Serializable
data class Friend(
    @SerialName("player_id") val playerId: String,
    @SerialName("display_name") val displayName: String,
    val challenges: Int,
    val played: Int,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    @SerialName("last_challenge_at") val lastChallengeAt: String,
)

@Serializable
data class FriendsResponse(val items: List<Friend>)
