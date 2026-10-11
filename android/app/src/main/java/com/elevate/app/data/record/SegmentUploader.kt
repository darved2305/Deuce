package com.elevate.app.data.record

import com.elevate.app.data.session.ApiException
import com.elevate.app.data.session.ApiErrorKind
import com.elevate.app.data.session.TransportException
import com.elevate.app.data.upload.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.io.IOException

/** A finished, valid piece on disk, ready to send. [ptsStartUs]..[ptsEndUs] are on the recording's timeline. */
data class RecordedSegment(
    val seq: Int,
    val file: File,
    val bytes: Long,
    val sha256: String,
    val ptsStartUs: Long,
    val ptsEndUs: Long,
    /** CameraX starts every recording with a keyframe, so each piece is decodable on its own. */
    val startsOnSyncSample: Boolean = true,
)

sealed interface PutResult {
    data object Ok : PutResult
    data class Rejected(val status: Int) : PutResult
}

/** Sends one piece's bytes to its presigned URL. Throws [IOException] for transport failures. */
fun interface SegmentPutter {
    suspend fun put(url: String, segment: RecordedSegment): PutResult
}

data class SegmentUploadFailure(val code: String, val message: String, val seq: Int?, val retryable: Boolean)

/** What the live status line shows. Every number is a count of server-confirmed events. */
data class SegmentUploadStatus(
    val recorded: Int = 0,
    /** Pieces the server confirmed stored (`complete` answered). */
    val sent: Int = 0,
    val bytesSent: Long = 0,
    val analysisStarted: Boolean = false,
    /** Pieces are waiting for the network policy; null when sending is allowed. */
    val waiting: Boolean = false,
    val failure: SegmentUploadFailure? = null,
    /** `POST /v1/analyses` failed after its tries; uploads are not affected. Retried at the end and by [SegmentUploader.resume]. */
    val analysisError: SegmentUploadFailure? = null,
    /** The recording has stopped and every piece, the final one included, is stored. */
    val finished: Boolean = false,
    /** The last piece was registered with `final: true`. */
    val finalRegistered: Boolean = false,
)

/**
 * Sends rolling pieces through the segment routes (contracts §2) while the camera is still
 * recording. States of one piece: held -> registered -> uploaded -> completed.
 *
 * Rules that come from the server:
 * - `seq` is registered in order without gaps (409 `segment_gap` otherwise), so registration is
 *   sequential; the PUTs and `complete` calls run up to [maxConcurrency] at a time.
 * - `final: true` is part of the piece's registration and cannot change later (409 on a conflict).
 *   The last piece is only known at Stop, so each piece is held back until the next one has
 *   finished (or Stop arrives): the cost is one piece (about 6 s) of delay, the gain is that
 *   `final` is always right, even when the recording ends on an empty tail.
 * - After piece 0 is `complete`, `POST /v1/analyses` starts the analysis ([startAnalysis]); the
 *   engine then polls the stored pieces, so analysis runs while recording continues.
 * - The server hands out at most [MAX_URL_REQUESTS] upload URLs per piece (G4): a URL is reused
 *   for every retry until it expires, and a 403 or expiry is the only reason to ask again.
 * - Each piece is tried at most [RetryPolicy.maxAttempts] times (G11: the first try and 3 retries).
 *   Time spent offline or waiting for Wi-Fi does not use attempts.
 */
class SegmentUploader(
    private val api: SegmentApi,
    private val putter: SegmentPutter,
    private val sessionId: String,
    maxConcurrency: Int,
    private val scope: CoroutineScope,
    /** True when the network policy allows sending now. */
    private val networkAllowed: () -> Boolean = { true },
    /** Starts the analysis (`POST /v1/analyses`), idempotent. Called once piece 0 is stored. */
    private val startAnalysis: suspend () -> Unit,
    private val retry: RetryPolicy = RetryPolicy(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Ready(val seg: RecordedSegment, val final: Boolean) {
        var urlRequests = 0
    }

    private val limit = maxConcurrency.coerceIn(1, MAX_CONCURRENCY)
    private val permits = Semaphore(limit)
    private val lock = Any()
    private val queue = ArrayDeque<Ready>()
    private val stuck = ArrayList<Ready>()
    private var held: RecordedSegment? = null
    private var stopped = false
    private var running: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _status = MutableStateFlow(SegmentUploadStatus())
    val status: StateFlow<SegmentUploadStatus> = _status.asStateFlow()

    /** Starts the sender. Call once, before the first piece. */
    fun start() = launchRun()

    /** A piece finished recording. The one before it is now known not to be the last and is released. */
    fun onSegmentRecorded(seg: RecordedSegment) {
        synchronized(lock) {
            check(!stopped) { "recording already stopped" }
            held?.let { queue.addLast(Ready(it, final = false)) }
            held = seg
        }
        _status.update { it.copy(recorded = it.recorded + 1) }
        wake.trySend(Unit)
    }

    /** The recording ended: the held piece is the last one. */
    fun onStopped() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            held?.let { queue.addLast(Ready(it, final = true)) }
            held = null
        }
        wake.trySend(Unit)
    }

    /** Tries again after a failure (the user tapped Retry, or the network came back). */
    fun resume() {
        if (running?.isActive == true) return
        synchronized(lock) {
            if (stuck.isNotEmpty()) {
                queue.addAll(stuck)
                stuck.clear()
                val sorted = queue.sortedBy { it.seg.seq }
                queue.clear()
                queue.addAll(sorted)
                // A stuck piece gets a new try budget; the server's URL limit still applies.
            }
        }
        _status.update { it.copy(failure = null) }
        launchRun()
    }

    /** Suspends until everything is sent, or the sender stopped on a failure. */
    suspend fun join() { running?.join() }

    private fun launchRun() {
        running = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        val children = mutableListOf<Job>()
        while (true) {
            if (_status.value.failure != null) break
            val next = synchronized(lock) { queue.removeFirstOrNull() }
            if (next == null) {
                val done = synchronized(lock) { stopped && queue.isEmpty() }
                if (done) break
                wake.receive()
                continue
            }
            val reg = register(next)
            if (reg == null) {
                synchronized(lock) { stuck.add(next) }
                break
            }
            permits.acquire()
            children += scope.launch {
                try {
                    if (!sendAndComplete(next, reg)) synchronized(lock) { stuck.add(next) }
                } finally {
                    permits.release()
                }
            }
        }
        children.joinAll()
        val allSent = synchronized(lock) { stopped && queue.isEmpty() && stuck.isEmpty() }
        if (allSent && _status.value.failure == null) {
            if (!_status.value.analysisStarted) startAnalysisWithRetry()
            _status.update { it.copy(finished = true) }
        }
    }

    // ---- register ----

    private suspend fun register(r: Ready): SegmentRegisterResponse? {
        var attempt = 1
        while (true) {
            if (!awaitNetwork()) return null
            if (r.urlRequests >= MAX_URL_REQUESTS) {
                fail(r, "url_limit", "The server's limit of upload links for this piece was reached.", retryable = false)
                return null
            }
            try {
                val res = api.register(sessionId, r.request())
                r.urlRequests++
                if (r.final) _status.update { it.copy(finalRegistered = true) }
                return res
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (!e.transient) {
                    fail(r, e.code, apiMessage(e), retryable = e.kind == ApiErrorKind.Paused)
                    return null
                }
            } catch (e: IOException) {
                if (e.isOffline()) continue
            }
            if (++attempt > retry.maxAttempts) {
                fail(r, "register_failed", "Could not register piece ${r.seg.seq}.", retryable = true)
                return null
            }
            sleep(retry.backoffMs(attempt))
        }
    }

    // ---- put + complete ----

    /** True when the piece is stored and completed; false when it needs a [resume]. */
    private suspend fun sendAndComplete(r: Ready, first: SegmentRegisterResponse): Boolean {
        var reg = first
        var attempt = 1
        var stored = false
        while (true) {
            if (!awaitNetwork()) return false
            var failed = false
            if (!stored) {
                if (expired(reg)) {
                    reg = register(r) ?: return false
                }
                try {
                    when (val res = putter.put(reg.putUrl, r.seg)) {
                        PutResult.Ok -> stored = true
                        is PutResult.Rejected -> {
                            failed = true
                            if (res.status == 403) {
                                // Expired or refused link: the one case for asking again.
                                reg = register(r) ?: return false
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (e.isOffline()) continue
                    failed = true
                }
            }
            if (stored) {
                try {
                    val done = api.complete(sessionId, r.seg.seq)
                    onCompleted(r, done)
                    return true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    when {
                        e.status == 409 -> { stored = false; failed = true } // object missing or wrong size: send it again
                        !e.transient -> { fail(r, e.code, apiMessage(e), retryable = false); return false }
                        else -> failed = true
                    }
                } catch (e: IOException) {
                    if (e.isOffline()) continue
                    failed = true
                }
            }
            if (failed) {
                if (++attempt > retry.maxAttempts) {
                    fail(r, "part_failed", "Piece ${r.seg.seq} could not be sent after ${retry.maxAttempts} tries.", retryable = true)
                    return false
                }
                sleep(retry.backoffMs(attempt))
            }
        }
    }

    private suspend fun onCompleted(r: Ready, done: SegmentCompleteResponse) {
        _status.update { it.copy(sent = it.sent + 1, bytesSent = it.bytesSent + done.bytes) }
        if (r.seg.seq == 0) startAnalysisWithRetry()
    }

    private suspend fun startAnalysisWithRetry() {
        var attempt = 1
        while (true) {
            if (!awaitNetwork(haltOnFailure = false)) return
            try {
                startAnalysis()
                _status.update { it.copy(analysisStarted = true, analysisError = null) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (!e.transient) {
                    _status.update { it.copy(analysisError = SegmentUploadFailure(e.code, apiMessage(e), null, retryable = true)) }
                    return
                }
            } catch (e: IOException) {
                if (e.isOffline()) continue
            }
            if (++attempt > retry.maxAttempts) {
                _status.update { it.copy(analysisError = SegmentUploadFailure("analysis_start_failed", "Could not start the analysis.", null, retryable = true)) }
                return
            }
            sleep(retry.backoffMs(attempt))
        }
    }

    // ---- helpers ----

    /** Waits (without using attempts) until the policy allows sending. False if a failure has already halted the run. */
    private suspend fun awaitNetwork(haltOnFailure: Boolean = true): Boolean {
        var waited = false
        while (!networkAllowed()) {
            if (haltOnFailure && _status.value.failure != null) return false
            waited = true
            _status.update { it.copy(waiting = true) }
            sleep(NETWORK_POLL_MS)
        }
        if (waited || _status.value.waiting) _status.update { it.copy(waiting = false) }
        return !haltOnFailure || _status.value.failure == null
    }

    private fun fail(r: Ready, code: String, message: String, retryable: Boolean) {
        _status.update { it.copy(failure = it.failure ?: SegmentUploadFailure(code, message, r.seg.seq, retryable)) }
        wake.trySend(Unit)
    }

    private fun expired(reg: SegmentRegisterResponse): Boolean {
        val at = reg.expiresAtMs ?: return false
        return nowMs() >= at - URL_EXPIRY_MARGIN_MS
    }

    private fun IOException.isOffline() = this is TransportException && offline

    private fun apiMessage(e: ApiException) = when (e.code) {
        "uploads_paused" -> "Uploads are paused on the server. Try again later."
        "storage_budget_reached", "daily_upload_limit" -> "The upload limit was reached for now. The recording is still saved on this phone."
        "upload_too_large" -> "The recording passed the 300 MB limit."
        else -> "The server refused a piece (${e.code})."
    }

    private fun Ready.request() = SegmentRegisterRequest(
        seq = seg.seq, ptsStartUs = seg.ptsStartUs, ptsEndUs = seg.ptsEndUs,
        startsOnSyncSample = seg.startsOnSyncSample, bytes = seg.bytes, sha256 = seg.sha256, final = final,
    )

    companion object {
        /** G4: the server gives out at most 3 upload links per piece. */
        const val MAX_URL_REQUESTS = 3
        const val MAX_CONCURRENCY = 2
        const val NETWORK_POLL_MS = 1_000L
        const val URL_EXPIRY_MARGIN_MS = 60_000L
    }
}
