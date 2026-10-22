package com.nikac.nikstore.install

import android.content.Context
import android.app.ForegroundServiceStartNotAllowedException
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nikac.nikstore.catalog.CatalogApp
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.UUID

fun CatalogApp.apkSpec() = ApkSpec(packageName, versionCode, apkUrl, sha256, signingCertificateSha256, sizeBytes)

object ApkFiles {
    fun directory(context: Context) = context.cacheDir.resolve("apks")

    fun file(context: Context, spec: ApkSpec) = directory(context).resolve(spec.filename)

    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        directory(context).listFiles().orEmpty().forEach { file ->
            val maxAge = if (file.name.endsWith(".part")) 24 * 60 * 60 * 1000L else 7 * 24 * 60 * 60 * 1000L
            if (now - file.lastModified() > maxAge) file.delete()
        }
    }

    fun verify(context: Context, spec: ApkSpec, file: File) {
        if (!file.isFile || file.length() == 0L || file.length() > ApkDownload.MAX_BYTES ||
            (spec.sizeBytes > 0 && file.length() != spec.sizeBytes)) throw ApkException(InstallFailure.SIZE)
        if (!ApkDownload.checksum(file).equals(spec.sha256, ignoreCase = true)) throw ApkException(InstallFailure.CHECKSUM)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val apk = context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: throw ApkException(InstallFailure.INVALID_APK)
        val installed = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(spec.packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) { null }
        ApkValidation.validate(spec, metadata(apk), Build.VERSION.SDK_INT, installed?.let(::metadata))
    }

    private fun metadata(info: PackageInfo): ApkMetadata {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val certificates = signers.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }.toSet()
        return ApkMetadata(info.packageName, PackageInfoCompat.getLongVersionCode(info),
            info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, certificates,
            info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }
}

object ApkDownloads {
    const val TAG = "apk-download"
    const val BYTES = "bytes"
    const val TOTAL = "total"
    const val VERIFYING = "verifying"
    const val ERROR = "error"

    fun enqueue(context: Context, app: CatalogApp): UUID {
        val spec = app.apkSpec()
        val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(workDataOf("package" to spec.packageName, "name" to app.name, "version" to spec.versionCode,
                "url" to spec.url, "sha256" to spec.sha256, "certificate" to spec.certificate, "size" to spec.sizeBytes))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG).addTag(releaseTag(app)).addTag("apk-request:${System.currentTimeMillis()}").build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(app.packageName), ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun cancel(context: Context, packageName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(packageName))
    }

    fun matches(work: WorkInfo, app: CatalogApp) = releaseTag(app) in work.tags
    private fun releaseTag(app: CatalogApp) = "apk:${app.packageName}:${app.versionCode}:${app.sha256.lowercase()}"
    private fun workName(packageName: String) = "apk-download:$packageName"

    fun spec(data: Data) = ApkSpec(requireNotNull(data.getString("package")), data.getLong("version", 0),
        requireNotNull(data.getString("url")), requireNotNull(data.getString("sha256")),
        data.getString("certificate"), data.getLong("size", 0))
}

class ApkDownloadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val downloader = ApkDownload()

    override fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(bytes: Long = 0, total: Long = 0, verifying: Boolean = false) =
        DownloadNotifications.foregroundInfo(applicationContext, id,
            inputData.getString("name") ?: inputData.getString("package").orEmpty(),
            inputData.getString("package").orEmpty(), bytes, total, verifying)

    override fun doWork(): Result {
        var partial: File? = null
        try {
            val spec = ApkDownloads.spec(inputData)
            // Promote before opening the connection so leaving the activity does
            // not turn a user-requested transfer into deferrable background work.
            setForegroundAsync(foregroundInfo()).get()
            if (isStopped) throw CancellationException()
            ApkFiles.cleanup(applicationContext)
            val target = ApkFiles.file(applicationContext, spec)
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            if (target.exists()) {
                try {
                    ApkFiles.verify(applicationContext, spec, target)
                    return Result.success()
                } catch (_: ApkException) { target.delete() }
            }
            val output = File(target.parentFile, spec.filename + ".$id.part")
            partial = output
            var lastProgress = 0L
            var lastNotification = 0L
            downloader.download(spec, output, { isStopped }) { bytes, total ->
                val now = System.currentTimeMillis()
                if (now - lastProgress >= 300 || bytes == total) {
                    setProgressAsync(workDataOf(ApkDownloads.BYTES to bytes, ApkDownloads.TOTAL to total))
                    lastProgress = now
                }
                if (now - lastNotification >= 1_000 || bytes == total) {
                    setForegroundAsync(foregroundInfo(bytes, total))
                    lastNotification = now
                }
            }
            if (isStopped) throw CancellationException()
            setProgressAsync(workDataOf(ApkDownloads.VERIFYING to true))
            setForegroundAsync(foregroundInfo(verifying = true)).get()
            ApkFiles.verify(applicationContext, spec, output)
            if (isStopped) throw CancellationException()
            if (!output.renameTo(target)) throw ApkException(InstallFailure.DOWNLOAD)
            // Keep one completed version per app. Partial downloads are never shared with the installer.
            target.parentFile?.listFiles().orEmpty().filter {
                it != target && it.name.startsWith(spec.packageName + "-") && it.name.endsWith(".apk")
            }.forEach(File::delete)
            return Result.success()
        } catch (error: Exception) {
            if (Build.VERSION.SDK_INT >= 31 &&
                generateSequence<Throwable>(error) { it.cause }.any { it is ForegroundServiceStartNotAllowedException }
            ) {
                // If Android defers foreground startup, keep the requested work
                // queued for another attempt instead of declaring the APK broken.
                return Result.retry()
            }
            val failure = (error as? ApkException)?.failure ?: InstallFailure.DOWNLOAD
            return Result.failure(workDataOf(ApkDownloads.ERROR to failure.name))
        } finally {
            partial?.delete()
        }
    }

    override fun onStopped() {
        downloader.cancel()
        super.onStopped()
    }
}
