package com.elevate.app.data.upload

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.elevate.app.MainActivity
import com.elevate.app.ui.upload.UploadText

/**
 * The upload notification (required by both the user-initiated job and the foreground worker).
 * Its numbers are [UploadText]'s: real bytes written/accepted, measured rate. On API 33+ it is shown
 * only if the user granted POST_NOTIFICATIONS (the job still runs and appears in Task Manager).
 */
object UploadNotifications {
    const val CHANNEL_ID = "uploads"
    const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Video uploads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun build(context: Context, p: UploadProgress): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: android.content.Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ongoing = p is UploadProgress.Uploading || p is UploadProgress.Preparing || p == UploadProgress.Completing
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(UploadText.title(p) ?: "Video upload")
            .setContentText(UploadText.retry(p) ?: UploadText.detail(p))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        // Determinate only from real bytes (scaled to KiB to fit an Int); no bar without byte progress.
        UploadText.fraction(p)?.let { b.setProgress(1000, (it * 1000).toInt(), false) }
        return b.build()
    }

    /** Posts or replaces the notification when allowed; returns whether it was posted. */
    fun notify(context: Context, p: UploadProgress): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(context)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, p))
        return true
    }
}
