package com.elevate.app.data.upload

import kotlin.random.Random

/**
 * What the upload is doing, from real events only. Byte counts come from the bytes actually written
 * to the socket and from parts the server accepted (an ETag came back); rate and ETA come from those
 * measurements; nothing advances on a timer.
 */
sealed interface UploadProgress {
    data object Idle : UploadProgress

    /** Staging, hashing or creating the session. */
    data class Preparing(val step: String) : UploadProgress

    data class Uploading(
        val totalBytes: Long,
        /** Bytes of parts the server accepted (ETag received). */
        val acceptedBytes: Long,
        /** Bytes written for parts still in flight (not yet accepted; lost if the part fails). */
        val inFlightBytes: Long,
        val partsDone: Int,
        val partCount: Int,
        /** Measured over the last [RateMeter.windowMs]; null until there is enough to measure. */
        val bytesPerSecond: Double?,
        /** From [bytesPerSecond] only; null when no rate is known. */
        val etaSeconds: Long?,
        val retrying: Retrying? = null,
    ) : UploadProgress

    /** Waiting before retry [attempt] of part [part] (at most [maxAttempts], G11). */
    data class Retrying(val part: Int, val attempt: Int, val maxAttempts: Int, val reason: String)

    /** No network; the upload stays resumable from the parts already accepted. */
    data class PausedOffline(val acceptedBytes: Long, val totalBytes: Long) : UploadProgress

    data object Completing : UploadProgress

    /** The server confirmed the stored object's size. */
    data class Completed(val serverBytes: Long) : UploadProgress

    data class Failed(val failure: UploadFailure, val acceptedBytes: Long, val totalBytes: Long) : UploadProgress

    data object Cancelled : UploadProgress
}

/** Throughput from (time, cumulative bytes written) samples over a sliding window. */
class RateMeter(private val nowMs: () -> Long, val windowMs: Long = 10_000, private val minSpanMs: Long = 1_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    @Synchronized
    fun record(totalBytesWritten: Long) {
        val now = nowMs()
        samples.addLast(now to totalBytesWritten)
        while (samples.size > 2 && now - samples.first().first > windowMs) samples.removeFirst()
    }

    /** Bytes per second, or null until samples span at least [minSpanMs]. */
    @Synchronized
    fun bytesPerSecond(): Double? {
        if (samples.size < 2) return null
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val span = t1 - t0
        if (span < minSpanMs) return null
        return (b1 - b0) * 1000.0 / span
    }

    @Synchronized
    fun reset() = samples.clear()
}

/**
 * Retry rule for one part: at most [maxAttempts] attempts in total (G11: the first try plus 3
 * retries), exponential backoff from [baseMs] capped at [capMs], "equal jitter" (half fixed, half
 * random) so parallel parts do not retry in lockstep.
 */
class RetryPolicy(
    val maxAttempts: Int = 4,
    private val baseMs: Long = 1_000,
    private val capMs: Long = 30_000,
    private val random: Random = Random.Default,
) {
    /** Delay before attempt [nextAttempt] (2..maxAttempts). */
    fun backoffMs(nextAttempt: Int): Long {
        val exp = minOf(capMs, baseMs shl (nextAttempt - 2).coerceIn(0, 20))
        return exp / 2 + (random.nextDouble() * (exp / 2)).toLong()
    }
}
