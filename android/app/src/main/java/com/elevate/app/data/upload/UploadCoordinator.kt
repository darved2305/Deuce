package com.elevate.app.data.upload

import com.elevate.app.data.auth.AuthException
import com.elevate.app.data.session.ApiException
import com.elevate.app.data.session.HttpTransport
import com.elevate.app.data.session.SessionApi
import com.elevate.app.data.session.SessionCreateRequest
import com.elevate.app.data.session.SessionCreateResponse
import com.elevate.app.domain.media.Preflight
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * One upload from "user tapped Analyse" to "server confirmed the object", persisted at every step so
 * the background job can resume it after process death. One upload at a time.
 *
 * - [prepare]: validates size (1 byte .. 300 MiB, G1), saves the request with a fresh Idempotency-Key.
 *   No network: the background job ([resume]) creates the session, so a create interrupted by process
 *   death is retried with the same key and the server replays the same session.
 * - [resume]: creates the session if needed, then [MultipartUploader.run].
 * - [cancel]: stops the run, aborts the upload on the server, deletes local state and staging copy.
 */
class UploadCoordinator(
    private val api: SessionApi,
    private val partTransport: HttpTransport,
    private val store: UploadStateStore,
    private val sourceFor: (UploadState) -> PartSource,
    private val policy: RetryPolicy = RetryPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _progress = MutableStateFlow(initialProgress(store.load()))
    val progress: StateFlow<UploadProgress> = _progress.asStateFlow()

    private val running = Mutex()
    @Volatile private var runJob: Job? = null

    fun current(): UploadState? = store.load()

    /** Saves a new upload. Throws [IllegalStateException] while another upload is unfinished. */
    fun prepare(request: SessionCreateRequest, sourceUri: String, stagedPath: String? = null): UploadState {
        val bytes = request.source.bytes
        require(bytes >= 1) { "an empty file cannot be uploaded" }
        require(bytes <= Preflight.MAX_UPLOAD_BYTES) { "uploads are limited to 300 MiB (G1)" }
        store.load()?.let { check(it.phase == UploadPhase.Completed || it.phase == UploadPhase.Aborted) { "another upload is unfinished" } }
        val id = newId()
        val s = UploadState(
            phase = UploadPhase.Creating,
            localId = id,
            idempotencyKey = id,
            sourceUri = sourceUri,
            stagedPath = stagedPath,
            bytes = bytes,
            sha256 = request.source.sha256,
            createRequestJson = request.toJson(),
        )
        store.save(s)
        _progress.value = UploadProgress.Preparing("Waiting to upload")
        return s
    }

    /**
     * Runs the saved upload to completion or failure. Returns null when nothing is saved, or when
     * another call is already running it (the job and the UI may both ask).
     */
    suspend fun resume(): UploadState? {
        if (!running.tryLock()) return null
        try {
            runJob = currentCoroutineContext()[Job]
            var s = store.load() ?: return null
            when (s.phase) {
                UploadPhase.Completed -> { _progress.value = UploadProgress.Completed(s.confirmedBytes ?: s.bytes); return s }
                UploadPhase.Aborted -> return s
                else -> Unit
            }
            if (s.sessionId == null) {
                s = createSession(s) ?: return store.load()
            }
            val result = MultipartUploader(api, partTransport, store, _progress, policy, nowMs, sleep, io).run(s, sourceFor(s))
            if (result.phase == UploadPhase.Completed) deleteStaged(result)
            return result
        } finally {
            runJob = null
            running.unlock()
        }
    }

    private suspend fun createSession(s: UploadState): UploadState? {
        _progress.value = UploadProgress.Preparing("Creating session")
        val request = com.elevate.app.data.session.requestJson.decodeFromString(SessionCreateRequest.serializer(), s.createRequestJson)
        // A dropped connection (seen on the A55: SSLHandshakeException right after Wi-Fi came back) or
        // a transient 5xx/429 is retried here with backoff; the Idempotency-Key makes a retried create
        // return the same session.
        var attempt = 0
        var created: SessionCreateResponse? = null
        while (created == null) {
            attempt++
            try {
                created = api.createSession(request, s.idempotencyKey)
                break
            } catch (e: ApiException) {
                if (!e.transient || attempt >= policy.maxAttempts) {
                    failBeforeSession(s, MultipartUploader.failureOf(e, null))
                    return null
                }
            } catch (e: AuthException) {
                failBeforeSession(s, UploadFailure("auth", e.message ?: "sign-in failed", retryable = true))
                return null
            } catch (e: IOException) {
                val f = MultipartUploader.networkFailure(e, null)
                if (f.code == MultipartUploader.OFFLINE || attempt >= policy.maxAttempts) {
                    failBeforeSession(s, f)
                    return null
                }
            }
            _progress.value = UploadProgress.Preparing("Creating session · retry ${attempt + 1}/${policy.maxAttempts}")
            sleep(policy.backoffMs(attempt + 1))
        }
        val r: SessionCreateResponse = created ?: return null
        // Re-validate the server's plan (part size, part count) before using it.
        PartPlan(s.bytes, r.upload.partSize)
        val next = s.copy(
            phase = UploadPhase.Uploading,
            sessionId = r.sessionId,
            matchId = r.matchId,
            uploadId = r.upload.uploadId,
            partSize = r.upload.partSize,
            maxConcurrency = r.upload.maxConcurrency,
            failure = null,
        )
        store.save(next)
        return next
    }

    private fun failBeforeSession(s: UploadState, f: UploadFailure) {
        store.save(s.copy(failure = f))
        _progress.value = if (f.code == MultipartUploader.OFFLINE) UploadProgress.PausedOffline(0, s.bytes) else UploadProgress.Failed(f, 0, s.bytes)
    }

    /**
     * Stops any run, aborts the server-side upload (frees the multipart upload in R2), and deletes the
     * local state and staging copy. A completed upload is not aborted (only its local state is cleared).
     */
    suspend fun cancel() {
        runJob?.cancelAndJoin()
        val s = store.load()
        if (s?.sessionId != null && s.phase != UploadPhase.Completed && s.phase != UploadPhase.Aborted) {
            try {
                api.abort(s.sessionId)
            } catch (e: ApiException) {
                // 409 invalid_transition: it completed meanwhile; 404: gone. Either way nothing to free.
                if (e.status != 409 && e.status != 404) throw e
            }
        }
        if (s != null) deleteStaged(s)
        store.clear()
        _progress.value = UploadProgress.Cancelled
    }

    /** Forgets a finished upload (after the app has moved on to analysis). */
    fun dismiss() {
        val s = store.load() ?: return
        if (s.phase == UploadPhase.Completed || s.phase == UploadPhase.Aborted) {
            deleteStaged(s)
            store.clear()
            _progress.value = UploadProgress.Idle
        }
    }

    private fun deleteStaged(s: UploadState) {
        s.stagedPath?.let { File(it).delete() }
    }

    private companion object {
        fun initialProgress(s: UploadState?): UploadProgress = when {
            s == null -> UploadProgress.Idle
            s.phase == UploadPhase.Completed -> UploadProgress.Completed(s.confirmedBytes ?: s.bytes)
            s.failure?.code == MultipartUploader.OFFLINE -> UploadProgress.PausedOffline(s.acceptedBytes, s.bytes)
            s.failure != null -> UploadProgress.Failed(s.failure, s.acceptedBytes, s.bytes)
            else -> UploadProgress.Preparing("Waiting to resume")
        }
    }
}
