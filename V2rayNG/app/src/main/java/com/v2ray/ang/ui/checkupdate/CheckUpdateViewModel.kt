package com.v2ray.ang.ui.checkupdate

import android.app.Application
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CheckUpdateViewModel internal constructor(
    application: Application,
    initialPreRelease: Boolean,
    private val checkUpdate: suspend (Boolean) -> CheckUpdateResult,
    private val fetchUpdate: suspend (CheckUpdateResult, (Float) -> Unit) -> File,
    private val persistPreRelease: (Boolean) -> Unit,
    private val reportError: (String, Exception) -> Unit,
) : BaseViewModel(application) {
    constructor(application: Application) : this(application,
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false),
        { UpdateCheckerManager.checkForUpdate(it) },
        { result, progress -> UpdateCheckerManager.downloadUpdate(application, result, progress) },
        { MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, it); Unit },
        { message, error -> LogUtil.e(AppConfig.TAG, message, IllegalStateException(error.javaClass.simpleName)) })

    private val _checkPreRelease = MutableStateFlow(initialPreRelease)
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        persistPreRelease(enabled)
    }

    private val _notice = MutableStateFlow<Int?>(null)
    val notice: StateFlow<Int?> = _notice.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()
    private val _downloadedFile = MutableStateFlow<File?>(null)
    val downloadedFile: StateFlow<File?> = _downloadedFile.asStateFlow()

    fun checkForUpdates() {
        if (_isLoading.value || _downloadProgress.value != null) return
        _notice.value = null
        _updateResult.value = null
        _showUpdateDialog.value = false
        _isLoading.value = true
        launchLoading {
            try {
                val result = checkUpdate(_checkPreRelease.value)
                if (result.hasUpdate) {
                    _updateResult.value = result
                    _showUpdateDialog.value = true
                } else {
                    _notice.value = R.string.update_already_latest_version
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportError("Failed to check for updates", e)
                _notice.value = R.string.dr_update_failed
            }
        }
    }

    fun downloadUpdate() {
        val result = _updateResult.value ?: return
        if (_downloadProgress.value != null) return
        _showUpdateDialog.value = false
        _downloadProgress.value = 0f
        _downloadedFile.value = null
        _notice.value = null
        viewModelScope.launch {
            try {
                _downloadedFile.value = fetchUpdate(result) { _downloadProgress.value = it }
                _notice.value = R.string.dr_update_ready
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportError("App update verification/download failed", error)
                _notice.value = R.string.dr_update_failed
            } finally {
                _downloadProgress.value = null
            }
        }
    }

    fun installFailure(error: Exception) {
        reportError("App update installer launch failed", error)
        _notice.value = R.string.dr_update_failed
    }

    fun installPermissionMissing() { _notice.value = R.string.dr_update_install_permission }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }
}
