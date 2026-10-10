package com.elevate.app.data.upload

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * API 26–33 fallback (no user-initiated jobs there): a WorkManager 2.11.2 long-running worker in a
 * `dataSync` foreground service, as the Android docs recommend for UIDT backward compatibility.
 * Why not a hand-written dataSync service: WorkManager already persists the request across process
 * death and reboots, applies the network constraint and backoff, and runs `setForeground` for us.
 *
 * Same contract as [UploadJobService]: real-bytes notification, `Result.retry()` only when offline.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val coordinator = UploadRuntime.get(applicationContext).coordinator
        UploadNotifications.ensureChannel(applicationContext)
        try {
            setForeground(foregroundInfo(coordinator.progress.value))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground start from the background; the upload still runs
            // as ordinary work (it may be stopped sooner). UNVERIFIED on a device.
        }
        val watcher = launch {
            var lastMs = 0L
            coordinator.progress.collect { p ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastMs >= 1_000) {
                    lastMs = now
                    UploadNotifications.notify(applicationContext, p)
                }
            }
        }
        val result = try {
            coordinator.resume()
        } finally {
            watcher.cancel()
        }
        UploadNotifications.notify(applicationContext, coordinator.progress.value)
        when {
            result == null -> Result.success() // nothing saved, or another run owns it
            result.phase == UploadPhase.Completed -> Result.success()
            result.failure?.code == MultipartUploader.OFFLINE -> Result.retry()
            else -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(UploadRuntime.get(applicationContext).coordinator.progress.value)

    private fun foregroundInfo(p: UploadProgress): ForegroundInfo {
        val n = UploadNotifications.build(applicationContext, p)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(UploadNotifications.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(UploadNotifications.NOTIFICATION_ID, n)
        }
    }
}
