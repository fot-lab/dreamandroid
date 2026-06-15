package io.github.dreamandroid.local.service.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import io.github.dreamandroid.local.R

/**
 * Shared notification utilities for queue processing.
 * Used by both [GenerationWorker] (WorkManager) and [QueueProcessingService] (fallback).
 */
object QueueNotificationHelper {

    private const val CHANNEL_ID = "queue_processing_channel"
    const val NOTIFICATION_ID = 5

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Queue Processing",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background queue processing"
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun createForegroundInfo(
        context: Context,
        title: String,
        progress: Int,
    ): ForegroundInfo {
        ensureChannel(context)

        val pendingIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
        val contentIntent = PendingIntent.getActivity(
            context, 0, pendingIntent, PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("DreamHub Queue")
            .setContentText(title)
            .setProgress(100, progress, progress == 0)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    fun createNotification(
        context: Context,
        title: String,
        progress: Int,
        stopPendingIntent: PendingIntent? = null,
    ): Notification {
        ensureChannel(context)

        val pendingIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
        val contentIntent = PendingIntent.getActivity(
            context, 0, pendingIntent, PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("DreamHub Queue")
            .setContentText(title)
            .setProgress(100, progress, progress == 0)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(contentIntent)
            .setOngoing(true)

        if (stopPendingIntent != null) {
            builder.addAction(
                android.R.drawable.ic_media_pause, "Stop", stopPendingIntent,
            )
        }

        return builder.build()
    }
}
