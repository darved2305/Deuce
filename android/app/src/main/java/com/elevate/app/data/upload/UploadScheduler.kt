package com.elevate.app.data.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Starts (or restarts) the background run of the saved upload. Call it from the foreground (a tap
 * on "Analyse" or "Retry"): user-initiated jobs must be scheduled while the app is visible.
 */
object UploadScheduler {
    const val JOB_ID = 4201
    /** API 34 job namespace: keeps [JOB_ID] apart from WorkManager's JobScheduler ids. */
    const val JOB_NAMESPACE = "elevate.upload"
    const val WORK_NAME = "elevate-upload"

    /**
     * API 34+: a user-initiated data transfer job (any network, [estimatedUploadBytes] declared).
     * API 26–33: unique WorkManager work in a dataSync foreground service ([UploadWorker]).
     * Returns false when the system refused the job (e.g. the app was not visible).
     */
    fun start(context: Context, estimatedUploadBytes: Long): Boolean {
        if (!UploadRuntime.isConfigured) return false
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(app, UploadJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                // (downloadBytes, uploadBytes): API answers are a few KiB.
                .setEstimatedNetworkBytes(64L * 1024, estimatedUploadBytes)
                .build()
            app.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE).schedule(job) == JobScheduler.RESULT_SUCCESS
        } else {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            true
        }
    }

    /** Stops the background run (call before [UploadCoordinator.cancel]'s abort, or after it). */
    fun stop(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            app.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE).cancel(JOB_ID)
        } else {
            WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        }
    }
}
