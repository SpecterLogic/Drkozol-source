package com.v2ray.ang.ui.checkupdate

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.File

// Test-only Android main-dispatcher control. Reevaluate when kotlinx-coroutines-test makes this API stable.
@OptIn(ExperimentalCoroutinesApi::class)
class CheckUpdateViewModelTest {
    private fun model(check: suspend (Boolean) -> CheckUpdateResult,
        download: suspend (CheckUpdateResult, (Float) -> Unit) -> File = { _, _ -> error("unexpected download") }) =
        CheckUpdateViewModel(mock<Application>(), false, check, download, {}, { _, _ -> })

    @Test fun availableUpdateDownloadsOnceAndOnlyVerifiedFilesBecomeInstallable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val result = CheckUpdateResult(true, "1.4.9")
            val complete = CompletableDeferred<File>()
            var downloads = 0
            val vm = model({ result }) { _, progress -> downloads++; progress(.5f); complete.await() }
            assertNull(vm.updateResult.value)
            assertNull(vm.downloadedFile.value)
            assertNull(vm.downloadProgress.value)
            vm.downloadUpdate(); assertNull(vm.downloadProgress.value)
            vm.checkForUpdates(); advanceUntilIdle()
            assertTrue(vm.showUpdateDialog.value)
            vm.downloadUpdate(); vm.downloadUpdate(); runCurrent()
            assertEquals(1, downloads)
            assertEquals(.5f, vm.downloadProgress.value)
            assertNull(vm.downloadedFile.value)
            complete.complete(File("verified.apk")); advanceUntilIdle()
            assertNull(vm.downloadProgress.value)
            assertEquals(File("verified.apk"), vm.downloadedFile.value)
            assertEquals(R.string.dr_update_ready, vm.notice.value)
            vm.installPermissionMissing(); assertEquals(R.string.dr_update_install_permission, vm.notice.value)
            vm.installFailure(SecurityException("installer unavailable")); assertEquals(R.string.dr_update_failed, vm.notice.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun noUpdateNeverOffersDownloadOrInstallation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = model({ CheckUpdateResult(false) })
            vm.checkForUpdates(); advanceUntilIdle()
            assertFalse(vm.showUpdateDialog.value)
            assertNull(vm.downloadedFile.value)
            assertEquals(R.string.update_already_latest_version, vm.notice.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun checkAndVerificationFailuresCannotLeaveAnInstallableFile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val failedCheck = model({ throw IllegalStateException("network unavailable") })
            failedCheck.checkForUpdates(); advanceUntilIdle()
            assertFalse(failedCheck.showUpdateDialog.value)
            assertEquals(R.string.dr_update_failed, failedCheck.notice.value)
            val failedDownload = model({ CheckUpdateResult(true, "1.4.9") }) { _, _ -> throw IllegalArgumentException("invalid package") }
            failedDownload.checkForUpdates(); advanceUntilIdle(); failedDownload.downloadUpdate(); advanceUntilIdle()
            assertNull(failedDownload.downloadedFile.value)
            assertNull(failedDownload.downloadProgress.value)
            assertEquals(R.string.dr_update_failed, failedDownload.notice.value)
        } finally { Dispatchers.resetMain() }
    }
}
