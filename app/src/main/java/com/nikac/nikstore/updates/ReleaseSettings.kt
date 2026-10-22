package com.nikac.nikstore.updates

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

class ReleaseSettings(context: Context) : ReleaseCheckState {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = preferences.getBoolean(ENABLED, false)
        set(value) { preferences.edit { putBoolean(ENABLED, value) } }

    override val knownVersions: Map<String, Long>?
        get() = preferences.getString(KNOWN_VERSIONS, null)?.let { json ->
            runCatching {
                val versions = JSONObject(json)
                versions.keys().asSequence().associateWith { versions.getLong(it) }
            }.getOrNull()
        }

    // The worker must confirm these records reached storage before fetching or
    // posting alerts, so a process restart cannot repeat the same attempt.
    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun claimCheckDay(day: String): Boolean = synchronized(LOCK) {
        if (!enabled || preferences.getString(LAST_ATTEMPT_DAY, null) == day) false
        else preferences.edit().putString(LAST_ATTEMPT_DAY, day).commit()
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun rememberVersions(versions: Map<String, Long>): Boolean =
        preferences.edit().putString(KNOWN_VERSIONS, JSONObject(versions).toString()).commit()

    fun catalogChecked() {
        preferences.edit { putLong(LAST_CATALOG_CHECK, System.currentTimeMillis()) }
    }

    val lastCatalogCheck: Long get() = preferences.getLong(LAST_CATALOG_CHECK, 0L)

    var scheduledZone: String?
        get() = preferences.getString(SCHEDULED_ZONE, null)
        set(value) { preferences.edit { putString(SCHEDULED_ZONE, value) } }

    companion object {
        private val LOCK = Any()
        private const val PREFERENCES = "release_settings"
        private const val ENABLED = "notifications_enabled"
        private const val KNOWN_VERSIONS = "known_versions"
        private const val LAST_ATTEMPT_DAY = "last_attempt_day"
        private const val SCHEDULED_ZONE = "scheduled_zone"
        const val LAST_CATALOG_CHECK = "last_catalog_check"
    }
}
