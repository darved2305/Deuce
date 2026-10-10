package com.elevate.app.data.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Lifecycle of one persisted upload. */
@Serializable
enum class UploadPhase {
    /** Session requested with [UploadState.idempotencyKey]; a retry after process death reuses the key. */
    @SerialName("creating") Creating,
    @SerialName("uploading") Uploading,
    /** Every part has an ETag; `upload/complete` not yet confirmed. */
    @SerialName("completing") Completing,
    /** The server confirmed the object ([UploadState.confirmedBytes]). */
    @SerialName("completed") Completed,
    /** Stopped; resumable by the user ([UploadState.failure] says why). */
    @SerialName("failed") Failed,
    @SerialName("aborted") Aborted,
}

/**
 * Everything needed to resume an upload after process death. Written atomically after every
 * accepted part. Holds no tokens and no presigned URLs (those are short-lived and re-requested).
 */
@Serializable
data class UploadState(
    val phase: UploadPhase,
    /** Locally generated; also the `Idempotency-Key` of `POST /v1/sessions`. */
    @SerialName("local_id") val localId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    /** The content Uri (or `file:` staging path) the bytes are read from. */
    @SerialName("source_uri") val sourceUri: String,
    /** App-private staging copy to delete when the upload ends, if one was made. */
    @SerialName("staged_path") val stagedPath: String? = null,
    val bytes: Long,
    val sha256: String,
    /** `session.create.request.v1` body, kept so a create retried after process death is identical. */
    @SerialName("create_request_json") val createRequestJson: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("match_id") val matchId: String? = null,
    @SerialName("upload_id") val uploadId: String? = null,
    @SerialName("part_size") val partSize: Long? = null,
    @SerialName("max_concurrency") val maxConcurrency: Int? = null,
    /** Accepted parts: part number → ETag exactly as R2 returned it (quotes kept). */
    val etags: Map<Int, String> = emptyMap(),
    @SerialName("confirmed_bytes") val confirmedBytes: Long? = null,
    val failure: UploadFailure? = null,
) {
    val plan: PartPlan? get() = partSize?.let { PartPlan(bytes, it) }
    val acceptedBytes: Long get() = plan?.let { p -> etags.keys.sumOf { p.length(it) } } ?: 0L
    fun missingParts(): List<Int> = plan?.let { p -> (1..p.count).filter { it !in etags } }.orEmpty()
}

/** Why an upload stopped. [code] is an API error code or a client reason; [retryable] = a Retry may succeed. */
@Serializable
data class UploadFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
    /** Part that ran out of attempts (G11), if that was the reason. */
    val part: Int? = null,
)

/** One upload's state on disk (`filesDir/uploads/current.json`). One upload at a time. */
class UploadStateStore(dir: File) {
    private val file = AtomicFile(File(dir, "current.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): UploadState? =
        file.readText()?.let { runCatching { json.decodeFromString(UploadState.serializer(), it) }.getOrNull() }

    fun save(state: UploadState) = file.writeText(json.encodeToString(UploadState.serializer(), state))

    fun clear() = file.delete()
}
