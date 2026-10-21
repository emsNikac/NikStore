package com.nikac.nikstore.catalog

import android.content.Context
import android.util.AtomicFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CatalogRepository(context: Context, private val url: String) {
    private val cache = AtomicFile(context.filesDir.resolve("catalog.json"))

    fun cached(): List<CatalogApp>? = try {
        CatalogParser.parse(cache.readFully().toString(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    fun refresh(): List<CatalogApp> {
        CatalogParser.requireHttpsUrl(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        val json = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            when (val status = connection.responseCode) {
                200 -> connection.inputStream.use { stream ->
                    val bytes = stream.readBytesLimited(MAX_CATALOG_BYTES)
                    bytes.toString(Charsets.UTF_8)
                }
                404 -> throw CatalogUnavailableException()
                else -> throw IOException("Catalog request failed ($status)")
            }
        } finally {
            connection.disconnect()
        }
        val apps = CatalogParser.parse(json)
        var output: java.io.FileOutputStream? = null
        try {
            output = cache.startWrite()
            output.write(json.toByteArray(Charsets.UTF_8))
            cache.finishWrite(output)
        } catch (error: IOException) {
            cache.failWrite(output)
            // A successful refresh is useful even if local storage is unavailable.
        }
        return apps
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("Catalog is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_CATALOG_BYTES = 1024 * 1024
    }
}

class CatalogUnavailableException : IOException("Catalog has not been published")
