package com.nikac.nikstore.install

import androidx.work.WorkInfo

enum class DownloadStatus { NONE, QUEUED, WAITING_FOR_NETWORK, DOWNLOADING, VERIFYING, READY, FAILED }

fun downloadStatus(state: WorkInfo.State?, connected: Boolean, checking: Boolean,
                   verifying: Boolean, ready: Boolean): DownloadStatus = when {
    checking -> DownloadStatus.VERIFYING
    state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED ->
        if (connected) DownloadStatus.QUEUED else DownloadStatus.WAITING_FOR_NETWORK
    state == WorkInfo.State.RUNNING -> if (verifying) DownloadStatus.VERIFYING else DownloadStatus.DOWNLOADING
    ready -> DownloadStatus.READY
    state == WorkInfo.State.FAILED -> DownloadStatus.FAILED
    else -> DownloadStatus.NONE
}
