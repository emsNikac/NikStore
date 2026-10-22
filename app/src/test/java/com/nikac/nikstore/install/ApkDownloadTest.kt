package com.nikac.nikstore.install

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ApkDownloadTest {
    // Keep all test files inside the project, including when Gradle redirects build outputs.
    @get:Rule val temporary = TemporaryFolder(File("."))
    private val bytes = "Signed APK bytes fixture".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun spec(size: Long = bytes.size.toLong(), hash: String = digest) =
        ApkSpec("example.app", 2, "https://github.com/example/app.apk", hash, sizeBytes = size)

    private class Response(url: URL, private val code: Int, private val body: ByteArray,
                           private val headers: Map<String, String>) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?) = headers[name]
    }

    @Test fun downloadsAcrossHttpsRedirectAndVerifiesSizeAndHash() {
        val opened = mutableListOf<Response>()
        val downloader = ApkDownload { url ->
            Response(url, if (opened.isEmpty()) 302 else 200, bytes,
                if (opened.isEmpty()) mapOf("Location" to "https://release-assets.githubusercontent.com/app.apk")
                else mapOf("Content-Length" to bytes.size.toString())).also(opened::add)
        }
        val file = temporary.newFile("release.part")
        var downloaded = 0L
        downloader.download(spec(), file, { false }) { count, total -> downloaded = count; assertEquals(bytes.size.toLong(), total) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), downloaded)
        assertEquals(digest, ApkDownload.checksum(file))
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.disconnected })
    }

    @Test fun refusesHttpAndCredentialBearingRedirectsBeforeConnecting() {
        listOf("http://example.com/app.apk", "https://secret@example.com/app.apk").forEach { redirect ->
            var requests = 0
            val downloader = ApkDownload { url ->
                requests++
                Response(url, 302, bytes, mapOf("Location" to redirect))
            }
            val file = temporary.newFile()
            assertThrows(IllegalArgumentException::class.java) { downloader.download(spec(), file, { false }) { _, _ -> } }
            assertEquals(1, requests)
            assertFalse(file.exists())
        }
    }

    @Test fun badChecksumRemovesThePartialFile() {
        val file = temporary.newFile()
        val downloader = ApkDownload { url -> Response(url, 200, bytes, emptyMap()) }
        val error = assertThrows(ApkException::class.java) {
            downloader.download(spec(hash = "0".repeat(64)), file, { false }) { _, _ -> }
        }
        assertEquals(InstallFailure.CHECKSUM, error.failure)
        assertFalse(file.exists())
    }

    @Test fun incompleteAndOversizedResponsesAreRejected() {
        listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(1)).forEach { response ->
            val file = temporary.newFile()
            val downloader = ApkDownload { url -> Response(url, 200, response, emptyMap()) }
            val error = assertThrows(ApkException::class.java) {
                downloader.download(spec(), file, { false }) { _, _ -> }
            }
            assertEquals(InstallFailure.SIZE, error.failure)
            assertFalse(file.exists())
        }
    }

    @Test fun unavailableAssetIsReportedAndDoesNotLeaveAFile() {
        val file = temporary.newFile()
        val downloader = ApkDownload { url -> Response(url, 404, byteArrayOf(), emptyMap()) }
        val error = assertThrows(ApkException::class.java) { downloader.download(spec(), file, { false }) { _, _ -> } }
        assertEquals(InstallFailure.UNAVAILABLE, error.failure)
        assertFalse(file.exists())
    }

    @Test fun cancelDuringDownloadDeletesPartialAndDisconnects() {
        val file = temporary.newFile()
        var stopped = false
        val response = Response(URL(spec().url), 200, bytes, emptyMap())
        val downloader = ApkDownload { response }
        assertThrows(CancellationException::class.java) {
            downloader.download(spec(), file, { stopped }) { _, _ -> stopped = true }
        }
        assertFalse(file.exists())
        assertTrue(response.disconnected)
    }

    @Test fun stoppedBeforeDownloadDoesNotOpenAConnection() {
        var requests = 0
        val file = temporary.newFile()
        val downloader = ApkDownload { url -> requests++; Response(url, 200, bytes, emptyMap()) }
        assertThrows(CancellationException::class.java) { downloader.download(spec(), file, { true }) { _, _ -> } }
        assertEquals(0, requests)
        assertFalse(file.exists())
    }

    @Test fun redirectLoopHasABoundedNumberOfRequests() {
        var requests = 0
        val file = temporary.newFile()
        val downloader = ApkDownload { url -> requests++; Response(url, 302, bytes, mapOf("Location" to "/again.apk")) }
        assertThrows(ApkException::class.java) { downloader.download(spec(), file, { false }) { _, _ -> } }
        assertEquals(6, requests)
        assertFalse(file.exists())
    }

    @Test fun invalidPackageNamesCannotBecomeCachePaths() {
        assertThrows(IllegalArgumentException::class.java) { ApkSpec("../escape", 2, spec().url, digest) }
        assertThrows(IllegalArgumentException::class.java) { ApkSpec("example.app", 0, spec().url, digest) }
    }
}
