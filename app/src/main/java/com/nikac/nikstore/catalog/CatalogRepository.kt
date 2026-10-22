package com.nikac.nikstore.catalog

import android.content.Context
import android.util.AtomicFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

class CatalogRepository(context: Context, private val url: String) {
    private val cache = AtomicFile(context.filesDir.resolve("catalog.json"))
    private val connectionInFlight = AtomicReference<HttpURLConnection?>()

    fun cached(): List<CatalogApp>? = synchronized(CACHE_LOCK) {
        try {
            CatalogParser.parse(cache.readFully().toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun cancel() { connectionInFlight.getAndSet(null)?.disconnect() }

    fun refresh(fetchAllowed: () -> Boolean = { true }): List<CatalogApp> = synchronized(REFRESH_LOCK) {
        ensureAllowed(fetchAllowed)
        CatalogParser.requireHttpsUrl(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        connectionInFlight.set(connection)
        val json = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            ensureAllowed(fetchAllowed)
            when (val status = connection.responseCode) {
                200 -> connection.inputStream.use { stream ->
                    val bytes = stream.readBytesLimited(MAX_CATALOG_BYTES, fetchAllowed)
                    bytes.toString(Charsets.UTF_8)
                }
                404 -> throw CatalogUnavailableException()
                else -> throw IOException("Catalog request failed ($status)")
            }
        } finally {
            connectionInFlight.compareAndSet(connection, null)
            connection.disconnect()
        }
        ensureAllowed(fetchAllowed)
        val apps = CatalogParser.parse(json)
        synchronized(CACHE_LOCK) {
            var output: java.io.FileOutputStream? = null
            try {
                output = cache.startWrite()
                output.write(json.toByteArray(Charsets.UTF_8))
                cache.finishWrite(output)
            } catch (error: IOException) {
                cache.failWrite(output)
                // A successful refresh is useful even if local storage is unavailable.
            }
        }
        apps
    }

    private fun ensureAllowed(fetchAllowed: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || !fetchAllowed()) throw CancellationException()
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int, fetchAllowed: () -> Boolean): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            ensureAllowed(fetchAllowed)
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("Catalog is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        val CACHE_LOCK = Any()
        val REFRESH_LOCK = Any()
        const val MAX_CATALOG_BYTES = 1024 * 1024
    }
}

class CatalogUnavailableException : IOException("Catalog has not been published")
