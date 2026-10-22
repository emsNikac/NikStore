package com.nikac.nikstore.install

import com.nikac.nikstore.catalog.CatalogParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

enum class InstallFailure { DOWNLOAD, UNAVAILABLE, SIZE, CHECKSUM, INVALID_APK, PACKAGE, VERSION, SIGNATURE, INCOMPATIBLE }

class ApkException(val failure: InstallFailure) : IOException(failure.name)

data class ApkSpec(
    val packageName: String,
    val versionCode: Long,
    val url: String,
    val sha256: String,
    val certificate: String? = null,
    val sizeBytes: Long = 0,
) {
    init {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(versionCode > 0 && sizeBytes >= 0)
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(certificate == null || certificate.matches(Regex("[a-fA-F0-9]{64}")))
        CatalogParser.requireHttpsUrl(url)
    }

    val filename: String get() = "$packageName-$versionCode-${sha256.lowercase()}.apk"
}

class ApkDownload(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val connection = AtomicReference<HttpURLConnection?>()

    fun cancel() { connection.getAndSet(null)?.disconnect() }

    fun download(spec: ApkSpec, partial: File, stopped: () -> Boolean,
                 progress: (Long, Long) -> Unit) {
        var url = URL(spec.url)
        try {
            if (spec.sizeBytes > MAX_BYTES) throw ApkException(InstallFailure.SIZE)
            for (redirect in 0..MAX_REDIRECTS) {
                checkRunning(stopped)
                CatalogParser.requireHttpsUrl(url.toString())
                val request = open(url)
                connection.set(request)
                try {
                    request.connectTimeout = 15_000
                    request.readTimeout = 30_000
                    request.instanceFollowRedirects = false
                    request.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                    request.setRequestProperty("Accept-Encoding", "identity")
                    checkRunning(stopped)
                    when (request.responseCode) {
                        301, 302, 303, 307, 308 -> {
                            val location = request.getHeaderField("Location") ?: throw ApkException(InstallFailure.DOWNLOAD)
                            url = URL(url, location)
                            // Check before opening the next connection, including cross-host GitHub redirects.
                            CatalogParser.requireHttpsUrl(url.toString())
                            continue
                        }
                        404, 410 -> throw ApkException(InstallFailure.UNAVAILABLE)
                        200 -> Unit
                        else -> throw ApkException(InstallFailure.DOWNLOAD)
                    }
                    val length = request.getHeaderFieldLong("Content-Length", -1)
                    if (length > MAX_BYTES || (spec.sizeBytes > 0 && length >= 0 && length != spec.sizeBytes)) {
                        throw ApkException(InstallFailure.SIZE)
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    request.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkRunning(stopped)
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                if (bytes > MAX_BYTES || (spec.sizeBytes > 0 && bytes > spec.sizeBytes)) {
                                    throw ApkException(InstallFailure.SIZE)
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                progress(bytes, if (spec.sizeBytes > 0) spec.sizeBytes else length)
                            }
                            output.fd.sync()
                        }
                    }
                    checkRunning(stopped)
                    if (bytes == 0L || (spec.sizeBytes > 0 && bytes != spec.sizeBytes) ||
                        (length >= 0 && bytes != length)) throw ApkException(InstallFailure.SIZE)
                    if (!digest.digest().hex().equals(spec.sha256, ignoreCase = true)) {
                        throw ApkException(InstallFailure.CHECKSUM)
                    }
                    return
                } finally {
                    connection.compareAndSet(request, null)
                    request.disconnect()
                }
            }
            throw ApkException(InstallFailure.DOWNLOAD)
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        private const val MAX_REDIRECTS = 5

        fun checksum(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw CancellationException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().hex()
        }

        private fun checkRunning(stopped: () -> Boolean) {
            if (stopped() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
    }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

data class ApkMetadata(val packageName: String, val versionCode: Long, val minSdk: Int,
                       val certificates: Set<String>, val debuggable: Boolean = false)

object ApkValidation {
    fun validate(spec: ApkSpec, apk: ApkMetadata, deviceSdk: Int, installed: ApkMetadata?) {
        if (apk.debuggable || apk.certificates.isEmpty()) throw ApkException(InstallFailure.INVALID_APK)
        if (apk.packageName != spec.packageName) throw ApkException(InstallFailure.PACKAGE)
        if (apk.versionCode != spec.versionCode) throw ApkException(InstallFailure.VERSION)
        if (apk.minSdk > deviceSdk) throw ApkException(InstallFailure.INCOMPATIBLE)
        if (spec.certificate != null && apk.certificates.map(String::lowercase).toSet() !=
            setOf(spec.certificate.lowercase())) throw ApkException(InstallFailure.SIGNATURE)
        if (installed != null && installed.certificates.map(String::lowercase).toSet() !=
            apk.certificates.map(String::lowercase).toSet()) throw ApkException(InstallFailure.SIGNATURE)
        if (installed != null && installed.versionCode > apk.versionCode) throw ApkException(InstallFailure.VERSION)
    }
}
