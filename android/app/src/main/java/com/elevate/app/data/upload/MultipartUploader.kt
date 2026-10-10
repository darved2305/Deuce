package com.elevate.app.data.upload

import com.elevate.app.data.auth.AuthException
import com.elevate.app.data.session.ApiErrorKind
import com.elevate.app.data.session.ApiException
import com.elevate.app.data.session.CompletedPart
import com.elevate.app.data.session.HttpRequest
import com.elevate.app.data.session.HttpResponse
import com.elevate.app.data.session.HttpTransport
import com.elevate.app.data.session.RequestBody
import com.elevate.app.data.session.SessionApi
import com.elevate.app.data.session.TransportException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.OutputStream

/** A part or request failed in a way this run cannot recover from. */
internal class UploadStopped(val failure: UploadFailure) : Exception(failure.message)

/**
 * Uploads the missing parts of one multipart upload to their presigned R2 URLs, then completes it.
 *
 * - Streams each part from [PartSource] (bounded 64 KiB buffer); `Content-Length` = the part length.
 * - At most `min(max_concurrency, 2)` parts in flight.
 * - Captures the `ETag` response header exactly as returned (quotes kept; it is not a sha256).
 * - Each part: at most [RetryPolicy.maxAttempts] attempts (G11). Transport errors, 5xx and 429 back
 *   off with jitter; 403 means the URL expired or is invalid and a fresh URL is requested (that
 *   counts as an attempt). A URL is reused until [urlMarginMs] before its `expires_at`, so a retry
 *   does not spend another URL from the session's allowance (G4); an expired one is re-requested.
 * - After every accepted part the state is saved atomically, so a new instance resumes with only
 *   the missing parts.
 * - Complete sends every part in order; a repeated complete is harmless (the route is idempotent).
 */
class MultipartUploader(
    private val api: SessionApi,
    /** For the R2 PUTs: presigned URLs carry their own signature, so no auth headers are added. */
    private val transport: HttpTransport,
    private val store: UploadStateStore,
    private val progress: MutableStateFlow<UploadProgress>,
    private val policy: RetryPolicy = RetryPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** A cached URL is reused only while it has more than this left before its `expires_at`. */
    private val urlMarginMs: Long = 60_000,
    private val rate: RateMeter = RateMeter(nowMs),
) {
    private val lock = Mutex()
    @Volatile private lateinit var state: UploadState
    private val inFlight = HashMap<Int, Long>()
    private val urls = HashMap<Int, Pair<String, Long>>()  // part -> (url, expires at epoch ms)
    private var written = 0L
    /**
     * Rate and ETA come from bytes the server confirmed (an ETag came back), never from bytes written
     * to the socket: on a slow uplink the kernel's send buffer takes megabytes at once (seen on the
     * A55 on LTE: "0.4 MiB/s" shown while parts were confirmed at about 0.02 MiB/s).
     */
    private val confirmed = RateMeter(nowMs, windowMs = 300_000)
    private var retrying: UploadProgress.Retrying? = null

    /** Uploads what is missing and completes. Returns the final saved state (Completed or Failed). */
    suspend fun run(initial: UploadState, source: PartSource): UploadState {
        require(initial.sessionId != null && initial.partSize != null) { "no session yet" }
        state = initial
        rate.reset()
        confirmed.reset()
        confirmed.record(initial.acceptedBytes)
        return try {
            var repairs = 0
            var done = false
            while (!done) {
                if (state.phase != UploadPhase.Completing) {
                    setState(state.copy(phase = UploadPhase.Uploading, failure = null))
                    uploadMissing(source)
                    setState(state.copy(phase = UploadPhase.Completing))
                }
                val missing = completeOrMissing()
                done = missing.isEmpty()
                if (!done) {
                    // The server lists parts it does not have: drop their ETags and upload them once more.
                    if (++repairs > 1) throw UploadStopped(UploadFailure("upload_parts_invalid", "server still misses parts $missing", retryable = false))
                    setState(state.copy(phase = UploadPhase.Uploading, etags = state.etags - missing.toSet()))
                }
            }
            state
        } catch (e: UploadStopped) {
            fail(e.failure)
        }
    }

    private suspend fun uploadMissing(source: PartSource) {
        val plan = state.plan!!
        val queue = ArrayDeque(state.missingParts())
        val workers = minOf(state.maxConcurrency ?: 2, MAX_CONCURRENCY).coerceAtLeast(1)
        emitUploading()
        // When a part fails for good, no new part is started, but a part already in flight is
        // allowed to finish and its ETag is saved: cancelling it could drop an ETag R2 already
        // accepted, and the part would be uploaded again on resume.
        var stopped: UploadStopped? = null
        coroutineScope {
            repeat(workers) {
                launch {
                    while (true) {
                        val n = lock.withLock { if (stopped != null) null else queue.removeFirstOrNull() } ?: break
                        val etag = try {
                            uploadPart(n, plan, source)
                        } catch (e: UploadStopped) {
                            lock.withLock { if (stopped == null) stopped = e }
                            break
                        }
                        synchronized(inFlight) {
                            inFlight.remove(n)
                            if (retrying?.part == n) retrying = null
                        }
                        lock.withLock { setState(state.copy(etags = state.etags + (n to etag))) }
                        confirmed.record(state.acceptedBytes)
                        emitUploading()
                    }
                }
            }
        }
        stopped?.let { throw it }
    }

    /** One part, up to [RetryPolicy.maxAttempts] attempts. Returns its ETag. */
    private suspend fun uploadPart(n: Int, plan: PartPlan, source: PartSource): String {
        var attempt = 0
        while (true) {
            attempt++
            var waitMs: Long
            var reason: String
            var offline = false
            try {
                // Inside the retry: a connection dropped while fetching the part URL costs one attempt
                // (seen on the A55 on LTE: SSLHandshakeException on upload/parts ended the whole upload).
                val url = urlFor(n)
                val res = put(url, n, plan, source)
                when {
                    res.status in 200..299 -> {
                        return res.header("ETag")?.takeIf { it.isNotBlank() }
                            ?: throw UploadStopped(UploadFailure("no_etag", "storage accepted part $n without an ETag", retryable = true, part = n))
                    }
                    res.status == 403 -> {
                        lock.withLock { urls.remove(n) }
                        reason = "upload link expired"
                        waitMs = 0
                    }
                    res.status == 429 || res.status >= 500 -> {
                        reason = "storage answered HTTP ${res.status}"
                        waitMs = -1
                    }
                    else -> throw UploadStopped(UploadFailure("part_rejected", "storage rejected part $n (HTTP ${res.status})", retryable = false, part = n))
                }
            } catch (e: IOException) {
                offline = e is TransportException && e.offline
                reason = if (offline) "no network" else "connection lost"
                waitMs = -1
            }
            synchronized(inFlight) { inFlight.remove(n) }
            if (attempt >= policy.maxAttempts) {
                throw UploadStopped(
                    UploadFailure(
                        code = if (offline) OFFLINE else "part_failed",
                        message = "part $n failed $attempt times ($reason)",
                        retryable = true,
                        part = n,
                    ),
                )
            }
            if (waitMs < 0) waitMs = policy.backoffMs(attempt + 1)
            synchronized(inFlight) { retrying = UploadProgress.Retrying(n, attempt + 1, policy.maxAttempts, reason) }
            emitUploading()
            if (waitMs > 0) sleep(waitMs)
        }
    }

    private suspend fun urlFor(n: Int): String {
        lock.withLock { urls[n]?.takeIf { it.second - nowMs() > urlMarginMs }?.let { return it.first } }
        val part = try {
            api.partUrls(state.sessionId!!, listOf(n)).single()
        } catch (e: ApiException) {
            throw UploadStopped(failureOf(e, n))
        } catch (e: AuthException) {
            throw UploadStopped(UploadFailure("auth", e.message ?: "sign-in failed", retryable = true, part = n))
        }
        // IOException propagates to uploadPart's retry loop.
        // An unparseable expires_at means "use once": it is not cached.
        part.expiresAtMs?.let { exp -> lock.withLock { urls[n] = part.url to exp } }
        return part.url
    }

    private suspend fun put(url: String, n: Int, plan: PartPlan, source: PartSource): HttpResponse = withContext(io) {
        val length = plan.length(n)
        val body = object : RequestBody {
            override val contentLength = length
            override val contentType = "application/octet-stream"
            override fun writeTo(out: OutputStream) {
                source.open(plan.offset(n), length).use { input ->
                    copyCounting(input, out) { k -> onWritten(n, k) }
                }
            }
        }
        transport.execute(HttpRequest("PUT", url, emptyMap(), body))
    }

    private fun onWritten(n: Int, k: Int) {
        synchronized(inFlight) {
            inFlight[n] = (inFlight[n] ?: 0L) + k
            written += k
        }
        rate.record(synchronized(inFlight) { written })
        emitUploading()
    }

    /** Completes; returns the parts the server says are missing (empty when completed). */
    private suspend fun completeOrMissing(): List<Int> {
        progress.value = UploadProgress.Completing
        val sid = state.sessionId!!
        val parts = state.etags.toSortedMap().map { (n, e) -> CompletedPart(n, e) }
        try {
            val r = api.complete(sid, parts)
            setState(state.copy(phase = UploadPhase.Completed, confirmedBytes = r.bytes, failure = null))
            progress.value = UploadProgress.Completed(r.bytes)
            return emptyList()
        } catch (e: ApiException) {
            if (e.status == 409 && e.code == "upload_parts_invalid") {
                val missing = (e.details as? JsonObject)?.get("missing")?.let { m -> runCatching { m.jsonArray.map { it.jsonPrimitive.int } }.getOrNull() }
                if (!missing.isNullOrEmpty()) return missing
            }
            if (e.status == 409 && e.code == "invalid_transition") {
                val view = runCatching { api.getSession(sid) }.getOrNull()?.upload
                if (view?.status == "completed") {
                    setState(state.copy(phase = UploadPhase.Completed, confirmedBytes = view.bytes, failure = null))
                    progress.value = UploadProgress.Completed(view.bytes)
                    return emptyList()
                }
            }
            throw UploadStopped(failureOf(e, null))
        } catch (e: AuthException) {
            throw UploadStopped(UploadFailure("auth", e.message ?: "sign-in failed", retryable = true))
        } catch (e: IOException) {
            throw UploadStopped(networkFailure(e, null))
        }
    }

    private fun fail(f: UploadFailure): UploadState {
        setState(state.copy(phase = UploadPhase.Failed, failure = f))
        val total = state.bytes
        progress.value = if (f.code == OFFLINE) UploadProgress.PausedOffline(state.acceptedBytes, total)
        else UploadProgress.Failed(f, state.acceptedBytes, total)
        return state
    }

    private fun setState(s: UploadState) {
        state = s
        store.save(s)
    }

    private fun emitUploading() {
        val plan = state.plan ?: return
        val (inflight, retry) = synchronized(inFlight) { inFlight.values.sum() to retrying }
        val accepted = state.acceptedBytes
        val bps = confirmed.bytesPerSecond()?.takeIf { it > 0 }
        val remaining = (state.bytes - accepted).coerceAtLeast(0)
        progress.value = UploadProgress.Uploading(
            totalBytes = state.bytes,
            acceptedBytes = accepted,
            inFlightBytes = inflight,
            partsDone = state.etags.size,
            partCount = plan.count,
            bytesPerSecond = bps,
            etaSeconds = bps?.let { Math.ceil(remaining / it).toLong() },
            retrying = retry,
        )
    }

    companion object {
        /** Budget doc G11 / contracts §2: two parts in flight at most. */
        const val MAX_CONCURRENCY = 2
        const val OFFLINE = "offline"

        internal fun networkFailure(e: IOException, part: Int?) =
            if (e is TransportException && e.offline) UploadFailure(OFFLINE, "no network", retryable = true, part = part)
            else UploadFailure("network", e.message ?: "connection lost", retryable = true, part = part)

        internal fun failureOf(e: ApiException, part: Int?): UploadFailure {
            val retryable = when (e.kind) {
                ApiErrorKind.Paused, ApiErrorKind.Server, ApiErrorKind.Unauthorized -> true
                ApiErrorKind.RateLimited -> e.code != "too_many_part_requests"
                else -> false
            }
            return UploadFailure(e.code, e.message ?: e.code, retryable, part)
        }
    }
}
