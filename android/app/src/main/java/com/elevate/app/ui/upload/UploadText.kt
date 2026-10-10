package com.elevate.app.ui.upload

import com.elevate.app.data.upload.UploadProgress
import java.util.Locale

/**
 * Words and numbers for an [UploadProgress], shared by [UploadProgressCard] and the upload
 * notification. Every number is a measured one: bytes written/accepted, a rate measured over a
 * sliding window, an ETA from that rate. Nothing is estimated from a timer.
 */
object UploadText {
    private const val MIB = 1024.0 * 1024.0

    fun mib(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / MIB)

    /** "12.3 of 41.0 MiB". */
    fun ofTotal(done: Long, total: Long) = "${mib(done)} of ${mib(total)} MiB"

    /** "1.8 MiB/s", or null until a rate has been measured. */
    fun rate(bytesPerSecond: Double?): String? =
        bytesPerSecond?.let { String.format(Locale.ROOT, "%.1f MiB/s", it / MIB) }

    /** "2:05 left" / "1:02:05 left". */
    fun eta(seconds: Long?): String? = seconds?.let {
        val h = it / 3600
        val m = (it % 3600) / 60
        val s = it % 60
        if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d left", h, m, s) else String.format(Locale.ROOT, "%d:%02d left", m, s)
    }

    /** "7,717,311 bytes": the server-confirmed size, exactly. */
    fun exactBytes(bytes: Long): String = String.format(Locale.ROOT, "%,d bytes", bytes)

    /** Bytes written so far (accepted parts + bytes written for parts in flight). */
    fun sentBytes(p: UploadProgress.Uploading) = p.acceptedBytes + p.inFlightBytes

    /** One-line title. Null for [UploadProgress.Idle]. */
    fun title(p: UploadProgress): String? = when (p) {
        UploadProgress.Idle -> null
        is UploadProgress.Preparing -> "Preparing upload"
        is UploadProgress.Uploading -> "Uploading · ${p.partsDone}/${p.partCount} parts"
        is UploadProgress.PausedOffline -> "Upload paused · offline"
        UploadProgress.Completing -> "Finishing upload"
        is UploadProgress.Completed -> "Uploaded · server confirmed ${exactBytes(p.serverBytes)}"
        is UploadProgress.Failed -> if (p.failure.retryable) "Upload paused" else "Upload failed"
        UploadProgress.Cancelled -> "Upload cancelled"
    }

    /** Detail line. */
    fun detail(p: UploadProgress): String? = when (p) {
        UploadProgress.Idle, UploadProgress.Cancelled -> null
        is UploadProgress.Preparing -> p.step
        is UploadProgress.Uploading ->
            listOfNotNull("${ofTotal(p.acceptedBytes, p.totalBytes)} confirmed", rate(p.bytesPerSecond), eta(p.etaSeconds) ?: "time left after the first part").joinToString(" · ")
        is UploadProgress.PausedOffline -> "${ofTotal(p.acceptedBytes, p.totalBytes)} kept · resumes when online"
        UploadProgress.Completing -> "Asking the server to assemble the parts"
        is UploadProgress.Completed -> "${mib(p.serverBytes)} MiB stored"
        is UploadProgress.Failed -> "${p.failure.message} · ${ofTotal(p.acceptedBytes, p.totalBytes)} kept"
    }

    /** "Retrying part 3 · attempt 2/4 · connection lost", while a part is being retried. */
    fun retry(p: UploadProgress): String? = (p as? UploadProgress.Uploading)?.retrying?.let {
        "Retrying part ${it.part} · attempt ${it.attempt}/${it.maxAttempts} · ${it.reason}"
    }

    /** Fraction from real bytes, or null when there is no byte progress to show. */
    fun fraction(p: UploadProgress): Float? = when (p) {
        is UploadProgress.Uploading -> frac(p.acceptedBytes, p.totalBytes)
        is UploadProgress.PausedOffline -> frac(p.acceptedBytes, p.totalBytes)
        is UploadProgress.Failed -> frac(p.acceptedBytes, p.totalBytes)
        else -> null
    }

    private fun frac(done: Long, total: Long) = if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
}
