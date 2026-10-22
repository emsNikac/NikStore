package com.nikac.nikstore.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.nikac.nikstore.MainActivity
import com.nikac.nikstore.R
import com.nikac.nikstore.catalog.CatalogApp

object ReleaseNotifications {
    const val CHANNEL_ID = "new_releases"
    const val EXTRA_PACKAGE = "release_package"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.release_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.release_channel_description)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE
    }

    fun publish(context: Context, releases: List<CatalogApp>) {
        createChannel(context)
        val settings = ReleaseSettings(context)
        releases.forEach { app ->
            if (!settings.enabled || !allowed(context)) return
            if (app.minSdk > Build.VERSION.SDK_INT) return@forEach
            val installedVersion = try {
                @Suppress("DEPRECATION")
                PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(app.packageName, 0))
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            if (installedVersion != null && installedVersion >= app.versionCode) return@forEach
            val intent = Intent(context, MainActivity::class.java)
                .setData(("nikstore://release/" + app.packageName).toUri())
                .putExtra(EXTRA_PACKAGE, app.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_store)
                .setColor(ContextCompat.getColor(context, R.color.store_primary))
                .setContentTitle(context.getString(R.string.release_notification_title, app.name))
                .setContentText(context.getString(R.string.release_notification_body, app.versionName))
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify("release:" + app.packageName, 0, notification)
            } catch (_: SecurityException) {
                // Permission may be revoked between checking it and posting.
                return
            }
        }
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancelAll()
}
