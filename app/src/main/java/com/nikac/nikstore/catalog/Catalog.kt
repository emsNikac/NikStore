package com.nikac.nikstore.catalog

import org.json.JSONObject
import java.net.URI

data class CatalogApp(
    val packageName: String,
    val name: String,
    val description: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String,
    val signingCertificateSha256: String? = null,
    val sizeBytes: Long = 0,
) {
    fun state(installedVersionCode: Long?, deviceSdk: Int): AppState = when {
        deviceSdk < minSdk -> AppState.INCOMPATIBLE
        installedVersionCode == null -> AppState.NOT_INSTALLED
        installedVersionCode < versionCode -> AppState.UPDATE_AVAILABLE
        installedVersionCode > versionCode -> AppState.NEWER_INSTALLED
        else -> AppState.UP_TO_DATE
    }
}

enum class AppState { NOT_INSTALLED, UPDATE_AVAILABLE, UP_TO_DATE, NEWER_INSTALLED, INCOMPATIBLE }

object CatalogParser {
    fun parse(json: String): List<CatalogApp> {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "Unsupported catalog format" }
        val apps = root.getJSONArray("apps")
        val packages = mutableSetOf<String>()
        return List(apps.length()) { index ->
            val entry = apps.getJSONObject(index)
            val app = CatalogApp(
                packageName = entry.requiredText("packageName"),
                name = entry.requiredText("name"),
                description = entry.requiredText("description"),
                versionName = entry.requiredText("versionName"),
                versionCode = entry.getLong("versionCode"),
                minSdk = entry.getInt("minSdk"),
                apkUrl = entry.requiredText("apkUrl"),
                sha256 = entry.requiredText("sha256"),
                releaseNotes = entry.optString("releaseNotes", ""),
                signingCertificateSha256 = entry.optString("signingCertificateSha256", "").takeIf { it.isNotEmpty() },
                sizeBytes = entry.optLong("sizeBytes", 0),
            )
            require(app.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
                "Invalid package name"
            }
            require(packages.add(app.packageName)) { "Duplicate app in catalog" }
            require(app.versionCode > 0 && app.minSdk > 0) { "Invalid Android version" }
            requireHttpsUrl(app.apkUrl)
            require(app.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid APK checksum" }
            require(app.signingCertificateSha256 == null ||
                app.signingCertificateSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid signing certificate" }
            require(app.sizeBytes >= 0) { "Invalid APK size" }
            app
        }
    }

    fun requireHttpsUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "An HTTPS URL without credentials is required"
        }
    }

    private fun JSONObject.requiredText(key: String): String {
        require(!isNull(key)) { "Missing $key" }
        return getString(key).also { require(it.isNotBlank()) { "Empty $key" } }
    }
}
