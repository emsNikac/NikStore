package com.nikac.nikstore.updates

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nikac.nikstore.BuildConfig
import com.nikac.nikstore.catalog.CatalogRepository

class ReleaseCheckWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val repository = CatalogRepository(context, BuildConfig.CATALOG_URL)

    override fun doWork(): Result {
        val settings = ReleaseSettings(applicationContext)
        if (!settings.enabled || !ReleaseNotifications.allowed(applicationContext)) {
            DailyReleaseScheduler.sync(applicationContext)
            return Result.success()
        }
        try {
            DailyReleaseCheck(settings,
                notificationsAllowed = { ReleaseNotifications.allowed(applicationContext) },
                stopped = { isStopped }, cachedCatalog = repository::cached,
                fetchCatalog = { allowed -> repository.refresh(allowed) },
                catalogChanged = settings::catalogChecked,
                publish = { releases -> ReleaseNotifications.publish(applicationContext, releases) },
            ).run(DailyReleasePolicy.checkDay(System.currentTimeMillis()))
        } catch (_: Exception) {
            // A failed or interrupted attempt waits for the next daily window;
            // it must not turn into repeated network retries throughout the day.
        } finally {
            if (!isStopped) {
                DailyReleaseScheduler.sync(applicationContext, realign = true).result.get()
            }
        }
        return Result.success()
    }

    override fun onStopped() {
        repository.cancel()
        super.onStopped()
    }
}
