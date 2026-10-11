package com.elevate.app.data.upload

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * API 34+: a user-initiated data transfer job runs [UploadCoordinator.resume]
 * (developer.android.com, "User-initiated data transfer"; signatures checked against the
 * android-37 `android.jar` and AOSP `JobService.java`).
 *
 * - `setNotification` is called in [onStartJob] (required), then updated from real progress at most
 *   once a second together with `updateTransferredNetworkBytes(params, download = 0, upload = sent)`.
 * - Finishes with `jobFinished(params, reschedule)`; reschedules only when the network dropped
 *   (no bytes are sent while offline). A part that ran out of its 4 attempts (G11) is not
 *   rescheduled: the user retries.
 * - [onStopJob] (constraint lost, thermal, timeout) cancels the run and asks to be rescheduled; state
 *   is already saved after every accepted part, so the next run sends only missing parts.
 */
class UploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var run: Job? = null
    @Volatile private var stopped = false

    // Scheduled only on API 34+ (UploadScheduler); the system calls it, never app code.
    @RequiresApi(34)
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        val runtime = UploadRuntime.get(this)
        val coordinator = runtime.coordinator
        UploadNotifications.ensureChannel(this)
        setNotification(
            params,
            UploadNotifications.NOTIFICATION_ID,
            UploadNotifications.build(this, coordinator.progress.value),
            JobService.JOB_END_NOTIFICATION_POLICY_DETACH,
        )
        run = scope.launch {
            val watcher = launch {
                var lastMs = 0L
                var lastKind: Class<*>? = null
                coordinator.progress.collect { p ->
                    val now = SystemClock.elapsedRealtime()
                    if (p.javaClass != lastKind || now - lastMs >= 1_000) {
                        lastKind = p.javaClass
                        lastMs = now
                        if (stopped) return@collect
                        setNotification(params, UploadNotifications.NOTIFICATION_ID, UploadNotifications.build(this@UploadJobService, p), JobService.JOB_END_NOTIFICATION_POLICY_DETACH)
                        if (p is UploadProgress.Uploading) updateTransferredNetworkBytes(params, 0, p.acceptedBytes + p.inFlightBytes)
                    }
                }
            }
            val result = try {
                coordinator.resume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unexpected (a bug, or the source file vanished): stop without rescheduling.
                null
            } finally {
                watcher.cancel()
            }
            if (stopped) return@launch
            UploadNotifications.notify(this@UploadJobService, coordinator.progress.value)
            // Any retryable stop (offline, connection lost after the in-run retries) is rescheduled by
            // JobScheduler with its backoff, so the upload resumes without the user tapping Retry.
            val reschedule = result?.phase == UploadPhase.Failed && result.failure?.retryable == true
            jobFinished(params, reschedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        run?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
