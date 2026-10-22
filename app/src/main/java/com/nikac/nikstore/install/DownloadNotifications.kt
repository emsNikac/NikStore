package com.nikac.nikstore.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.nikac.nikstore.MainActivity
import com.nikac.nikstore.R
import com.nikac.nikstore.updates.ReleaseNotifications
import java.util.UUID

object DownloadNotifications {
    const val CHANNEL_ID = "apk_downloads"

    fun foregroundInfo(context: Context, id: UUID, name: String, packageName: String,
                       bytes: Long = 0, total: Long = 0, verifying: Boolean = false): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.download_channel_description)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notificationId = (id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        val openApp = Intent(context, MainActivity::class.java)
            .setData(("nikstore://download/" + packageName).toUri())
            .putExtra(ReleaseNotifications.EXTRA_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(context, notificationId, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
        val text = when {
            verifying -> context.getString(R.string.install_verifying)
            total > 0 -> context.getString(R.string.install_download_percent, percent)
            else -> context.getString(R.string.install_downloading)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_store)
            .setContentTitle(context.getString(R.string.download_notification_title, name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent, total <= 0 || verifying)
            .addAction(0, context.getString(android.R.string.cancel), WorkManager.getInstance(context).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }
}
