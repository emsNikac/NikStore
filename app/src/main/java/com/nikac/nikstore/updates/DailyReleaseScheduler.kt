package com.nikac.nikstore.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

object DailyReleaseScheduler {
    const val WORK_NAME = "daily-release-check"

    fun sync(context: Context, realign: Boolean = false): Operation {
        val settings = ReleaseSettings(context)
        val manager = WorkManager.getInstance(context)
        if (!settings.enabled || !ReleaseNotifications.allowed(context)) {
            return manager.cancelUniqueWork(WORK_NAME)
        }
        val zone = TimeZone.getDefault()
        val update = realign || settings.scheduledZone != zone.id
        val request = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setNextScheduleTimeOverride(DailyReleasePolicy.nextCheckAt(System.currentTimeMillis(), zone))
            .build()
        settings.scheduledZone = zone.id
        return manager.enqueueUniquePeriodicWork(WORK_NAME,
            if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun disable(context: Context) {
        ReleaseSettings(context).enabled = false
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        ReleaseNotifications.clear(context)
    }
}

class ReleaseScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending = goAsync()
        try {
            DailyReleaseScheduler.sync(context, realign = true).result.addListener(
                { pending.finish() }, Executor { it.run() },
            )
        } catch (error: RuntimeException) {
            pending.finish()
            throw error
        }
    }
}
