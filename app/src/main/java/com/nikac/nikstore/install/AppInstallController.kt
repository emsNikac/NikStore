package com.nikac.nikstore.install

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nikac.nikstore.R
import com.nikac.nikstore.catalog.AppState
import com.nikac.nikstore.catalog.CatalogApp
import java.util.concurrent.ExecutorService

class AppInstallController(
    private val activity: AppCompatActivity,
    private val executor: ExecutorService,
    savedState: Bundle?,
    private val controlsChanged: () -> Unit,
    private val installedChanged: () -> Unit,
) {
    private val apps = mutableMapOf<String, CatalogApp>()
    private var works = emptyList<WorkInfo>()
    private var pendingInstall = savedState?.getString(PENDING_INSTALL)
    private var pendingPermission = savedState?.getString(PENDING_PERMISSION)
    private var pendingWork = savedState?.getString(PENDING_WORK)
    private var preparing: String? = null
    private var resumed = false

    private val permissionSettings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val app = apps[pendingPermission]
        if (app != null) {
            pendingPermission = null
            if (canInstall()) request(app)
            else message(R.string.install_permission_denied)
        }
    }
    private val installer = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installedChanged()
    }

    init {
        WorkManager.getInstance(activity).getWorkInfosByTagLiveData(ApkDownloads.TAG).observe(activity) {
            works = it.orEmpty()
            controlsChanged()
            continuePendingInstall()
        }
        executor.execute { ApkFiles.cleanup(activity.applicationContext) }
    }

    fun saveState(state: Bundle) {
        state.putString(PENDING_INSTALL, pendingInstall)
        state.putString(PENDING_PERMISSION, pendingPermission)
        state.putString(PENDING_WORK, pendingWork)
    }

    fun resume() { resumed = true; continuePendingInstall() }
    fun pause() { resumed = false }

    fun bind(card: View, app: CatalogApp, installedVersion: Long?) {
        apps[app.packageName] = app
        val button = card.findViewById<MaterialButton>(R.id.card_install_button)
        val cancel = card.findViewById<MaterialButton>(R.id.card_cancel_download)
        val progress = card.findViewById<LinearProgressIndicator>(R.id.card_download_progress)
        val status = card.findViewById<TextView>(R.id.card_download_status)
        val state = app.state(installedVersion, Build.VERSION.SDK_INT)
        val work = matchingWork(app)
        val downloading = work != null && !work.state.isFinished
        val checking = preparing == app.packageName
        val ready = ApkFiles.file(activity, app.apkSpec()).isFile
        val installed = state == AppState.UP_TO_DATE || state == AppState.NEWER_INSTALLED
        val launch = if (installed) activity.packageManager.getLaunchIntentForPackage(app.packageName) else null
        button.isEnabled = !downloading && !checking && state != AppState.INCOMPATIBLE && (!installed || launch != null)
        button.setText(when {
            installed && launch != null -> R.string.open_app
            installed -> R.string.app_installed
            state == AppState.INCOMPATIBLE -> R.string.install_incompatible_button
            checking -> R.string.install_verifying
            downloading -> R.string.install_downloading
            state == AppState.UPDATE_AVAILABLE -> R.string.update_app
            else -> R.string.install_app
        })
        button.setOnClickListener {
            if (installed && launch != null) {
                try { activity.startActivity(launch) } catch (_: ActivityNotFoundException) { message(R.string.install_cannot_open) }
            } else request(app)
        }
        if (installed) {
            if (pendingInstall == app.packageName) { pendingInstall = null; pendingWork = null }
            if (ready) ApkFiles.file(activity, app.apkSpec()).delete()
        }
        cancel.visibility = if (downloading && !installed) View.VISIBLE else View.GONE
        cancel.setOnClickListener {
            if (pendingInstall == app.packageName) { pendingInstall = null; pendingWork = null }
            ApkDownloads.cancel(activity, app.packageName)
        }
        val bytes = work?.progress?.getLong(ApkDownloads.BYTES, 0) ?: 0
        val total = work?.progress?.getLong(ApkDownloads.TOTAL, -1) ?: -1
        val stage = downloadStatus(work?.state, connected = isConnected(), checking = checking,
            verifying = work?.progress?.getBoolean(ApkDownloads.VERIFYING, false) == true, ready = ready)
        val verifying = stage == DownloadStatus.VERIFYING
        val indeterminate = total <= 0 || verifying
        if (progress.isIndeterminate != indeterminate) {
            progress.visibility = View.INVISIBLE
            progress.isIndeterminate = indeterminate
        }
        progress.visibility = if ((downloading || checking) && !installed) View.VISIBLE else View.GONE
        if (!progress.isIndeterminate) progress.setProgressCompat((bytes * 100 / total).toInt().coerceIn(0, 100), false)
        val text = when {
            installed || state == AppState.INCOMPATIBLE -> null
            verifying -> activity.getString(R.string.install_verifying)
            stage == DownloadStatus.WAITING_FOR_NETWORK -> activity.getString(R.string.install_waiting)
            stage == DownloadStatus.QUEUED -> activity.getString(R.string.install_queued)
            downloading && total > 0 -> activity.getString(R.string.install_download_percent, bytes * 100 / total)
            downloading -> activity.getString(R.string.install_downloading)
            ready -> activity.getString(R.string.install_download_ready)
            work?.state == WorkInfo.State.FAILED -> activity.getString(failureMessage(work.outputData.getString(ApkDownloads.ERROR)))
            else -> null
        }
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun continuePendingInstall() {
        if (!resumed || preparing != null || activity.isDestroyed) return
        apps[pendingPermission]?.let { app ->
            pendingPermission = null
            if (canInstall()) request(app) else message(R.string.install_permission_denied)
            return
        }
        val app = apps[pendingInstall] ?: return
        val work = if (pendingWork == null) matchingWork(app) else works.firstOrNull { it.id.toString() == pendingWork }
        if (pendingWork != null && work == null) return
        if (work?.state == WorkInfo.State.FAILED || work?.state == WorkInfo.State.CANCELLED) {
            pendingInstall = null
            pendingWork = null
        } else if (ApkFiles.file(activity, app.apkSpec()).isFile && (work == null || work.state.isFinished)) {
            prepareInstaller(app)
        }
    }

    private fun matchingWork(app: CatalogApp): WorkInfo? {
        return works.filter { ApkDownloads.matches(it, app) }.maxByOrNull { work ->
            work.tags.firstOrNull { it.startsWith("apk-request:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    private fun isConnected(): Boolean {
        val manager = activity.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (Build.VERSION.SDK_INT < 26 || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    private fun request(app: CatalogApp) {
        if (!canInstall()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_explanation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.install_open_settings) { _, _ ->
                    pendingPermission = app.packageName
                    try {
                        permissionSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            ("package:" + activity.packageName).toUri()))
                    } catch (_: ActivityNotFoundException) { message(R.string.install_permission_denied) }
                }.show()
            return
        }
        pendingInstall = app.packageName
        pendingWork = null
        if (ApkFiles.file(activity, app.apkSpec()).isFile) prepareInstaller(app)
        else pendingWork = ApkDownloads.enqueue(activity, app).toString()
    }

    private fun prepareInstaller(app: CatalogApp) {
        if (!canInstall()) {
            pendingInstall = null
            request(app)
            return
        }
        if (preparing != null) return
        preparing = app.packageName
        controlsChanged()
        executor.execute {
            val file = ApkFiles.file(activity, app.apkSpec())
            val result = runCatching { ApkFiles.verify(activity, app.apkSpec(), file) }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                preparing = null
                if (result.isFailure) {
                    pendingInstall = null
                    pendingWork = null
                    file.delete()
                    message(failureMessage((result.exceptionOrNull() as? ApkException)?.failure?.name))
                } else if (resumed) {
                    pendingInstall = null
                    pendingWork = null
                    file.setLastModified(System.currentTimeMillis())
                    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".apk-provider", file)
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { installer.launch(intent) }
                    catch (_: ActivityNotFoundException) { message(R.string.install_no_installer) }
                    catch (_: SecurityException) { message(R.string.install_permission_denied) }
                }
                controlsChanged()
            }
        }
    }

    private fun message(string: Int) = Toast.makeText(activity, string, Toast.LENGTH_LONG).show()

    private fun failureMessage(code: String?) = when (code) {
        InstallFailure.UNAVAILABLE.name -> R.string.install_error_unavailable
        InstallFailure.SIZE.name -> R.string.install_error_size
        InstallFailure.CHECKSUM.name -> R.string.install_error_checksum
        InstallFailure.PACKAGE.name, InstallFailure.VERSION.name -> R.string.install_error_package
        InstallFailure.SIGNATURE.name -> R.string.install_error_signature
        InstallFailure.INCOMPATIBLE.name -> R.string.install_error_incompatible
        InstallFailure.INVALID_APK.name -> R.string.install_error_invalid
        else -> R.string.install_error_download
    }

    private companion object {
        const val PENDING_INSTALL = "pending_install"
        const val PENDING_PERMISSION = "pending_install_permission"
        const val PENDING_WORK = "pending_apk_work"
    }
}
