package com.nikac.nikstore.updates

import com.nikac.nikstore.catalog.CatalogApp
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object DailyReleasePolicy {
    const val CHECK_HOUR = 23

    fun nextCheckAt(now: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, CHECK_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

    // Each check belongs to the local day ending at 23:00. A check delayed past
    // midnight still belongs to the previous night's scheduling window.
    fun checkDay(now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = now
            if (get(Calendar.HOUR_OF_DAY) < CHECK_HOUR) add(Calendar.DAY_OF_MONTH, -1)
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d", calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
    }
}

interface ReleaseCheckState {
    val enabled: Boolean
    val knownVersions: Map<String, Long>?
    fun claimCheckDay(day: String): Boolean
    fun rememberVersions(versions: Map<String, Long>): Boolean
}

class DailyReleaseCheck(
    private val state: ReleaseCheckState,
    private val notificationsAllowed: () -> Boolean,
    private val stopped: () -> Boolean,
    private val cachedCatalog: () -> List<CatalogApp>?,
    private val fetchCatalog: (() -> Boolean) -> List<CatalogApp>,
    private val catalogChanged: () -> Unit,
    private val publish: (List<CatalogApp>) -> Unit,
) {
    private fun allowed() = state.enabled && !stopped() && notificationsAllowed()

    fun run(day: String): Boolean {
        if (!allowed() || !state.claimCheckDay(day) || !allowed()) return false
        val previous = state.knownVersions ?: cachedCatalog()?.associate { it.packageName to it.versionCode }
        if (!allowed()) return false
        val apps = fetchCatalog(::allowed)
        if (!allowed()) return false
        catalogChanged()
        // The first successful check establishes a baseline if no catalog has
        // been seen yet. It doesn't send notifications for historical releases.
        val releases = if (previous == null) emptyList() else apps.filter {
            it.versionCode > (previous[it.packageName] ?: 0L)
        }
        val versions = previous.orEmpty().toMutableMap()
        apps.forEach { versions[it.packageName] = maxOf(versions[it.packageName] ?: 0L, it.versionCode) }
        if (state.rememberVersions(versions) && allowed()) publish(releases)
        return true
    }
}
