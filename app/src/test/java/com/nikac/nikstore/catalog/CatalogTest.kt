package com.nikac.nikstore.catalog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTest {
    private val release = """
        {"schemaVersion":1,"apps":[{
          "packageName":"mk.jsp.live","name":"JspTime","description":"Bus arrivals",
          "versionName":"2.0.0","versionCode":2,"minSdk":26,
          "apkUrl":"https://github.com/emsNikac/NikStore/releases/download/jsptime-2/JspTime.apk",
          "sha256":"${"a".repeat(64)}","releaseNotes":"New release"
        }]}
    """.trimIndent()

    @Test fun comparesNumericVersionsAndDeviceCompatibility() {
        val app = CatalogParser.parse(release).single()
        assertEquals(AppState.UPDATE_AVAILABLE, app.state(1, 36))
        assertEquals(AppState.UP_TO_DATE, app.state(2, 36))
        assertEquals(AppState.NEWER_INSTALLED, app.state(3, 36))
        assertEquals(AppState.NOT_INSTALLED, app.state(null, 36))
        assertEquals(AppState.INCOMPATIBLE, app.state(null, 25))
    }

    @Test fun supportsLongVersionCodes() {
        val updated = release.replace("\"versionCode\":2", "\"versionCode\":4294967297")
        assertEquals(4294967297L, CatalogParser.parse(updated).single().versionCode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnpublishedDraft() {
        val root = JSONObject(release)
        root.getJSONArray("apps").getJSONObject(0).put("apkUrl", JSONObject.NULL)
        CatalogParser.parse(root.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicatePackages() {
        val root = JSONObject(release)
        val apps = root.getJSONArray("apps")
        apps.put(apps.getJSONObject(0))
        CatalogParser.parse(root.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttpDownload() {
        CatalogParser.parse(release.replace("https://", "http://"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchema() {
        CatalogParser.parse(release.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidChecksum() {
        CatalogParser.parse(release.replace("a".repeat(64), "invalid"))
    }

    @Test fun acceptsEmptyCatalog() {
        assertEquals(emptyList<CatalogApp>(), CatalogParser.parse("""{"schemaVersion":1,"apps":[]}"""))
    }

    @Test fun readsOptionalInstallMetadata() {
        val root = JSONObject(release)
        root.getJSONArray("apps").getJSONObject(0).put("signingCertificateSha256", "b".repeat(64)).put("sizeBytes", 1234)
        val app = CatalogParser.parse(root.toString()).single()
        assertEquals("b".repeat(64), app.signingCertificateSha256)
        assertEquals(1234L, app.sizeBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSigningCertificate() {
        val root = JSONObject(release)
        root.getJSONArray("apps").getJSONObject(0).put("signingCertificateSha256", "invalid")
        CatalogParser.parse(root.toString())
    }
}
