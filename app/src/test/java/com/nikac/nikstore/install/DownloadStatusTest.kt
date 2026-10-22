package com.nikac.nikstore.install

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStatusTest {
    @Test fun queuedWhileOnlineIsNotReportedAsConnectionLoss() {
        listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED).forEach { state ->
            assertEquals(DownloadStatus.QUEUED, downloadStatus(state, true, false, false, false))
            assertEquals(DownloadStatus.WAITING_FOR_NETWORK, downloadStatus(state, false, false, false, false))
        }
    }

    @Test fun rescheduledWorkDoesNotKeepAStaleVerifyingLabel() {
        assertEquals(DownloadStatus.QUEUED,
            downloadStatus(WorkInfo.State.ENQUEUED, true, false, true, false))
    }

    @Test fun activeAndCompletedDownloadsRestoreTheirActualPhase() {
        assertEquals(DownloadStatus.DOWNLOADING,
            downloadStatus(WorkInfo.State.RUNNING, true, false, false, false))
        assertEquals(DownloadStatus.VERIFYING,
            downloadStatus(WorkInfo.State.RUNNING, true, false, true, false))
        assertEquals(DownloadStatus.READY,
            downloadStatus(WorkInfo.State.SUCCEEDED, true, false, false, true))
        assertEquals(DownloadStatus.VERIFYING,
            downloadStatus(WorkInfo.State.SUCCEEDED, true, true, false, true))
    }

    @Test fun failedAndCancelledDownloadsDoNotReportNetworkWaiting() {
        assertEquals(DownloadStatus.FAILED,
            downloadStatus(WorkInfo.State.FAILED, false, false, false, false))
        assertEquals(DownloadStatus.NONE,
            downloadStatus(WorkInfo.State.CANCELLED, false, false, false, false))
        assertEquals(DownloadStatus.READY,
            downloadStatus(WorkInfo.State.CANCELLED, false, false, false, true))
    }
}
