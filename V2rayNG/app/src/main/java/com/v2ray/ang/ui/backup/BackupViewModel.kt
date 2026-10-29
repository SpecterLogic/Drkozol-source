package com.v2ray.ang.ui.backup

import android.app.Application
import android.net.Uri
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.base.ViewModelEvent
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class BackupViewModel(application: Application) : BaseViewModel(application) {
    private var archivePassword = charArrayOf()

    fun setArchivePassword(value: String) {
        archivePassword.fill('\u0000')
        archivePassword = value.toCharArray()
    }

    override fun onCleared() {
        archivePassword.fill('\u0000')
        super.onCleared()
    }

    private val _webDavConfig = MutableStateFlow(MmkvManager.decodeWebDavConfig())
    val webDavConfig: StateFlow<WebDavConfig?> = _webDavConfig.asStateFlow()

    sealed interface BackupViewModelEvent : ViewModelEvent {
        data class ShareFile(val filePath: String) : BackupViewModelEvent
        data class ExportLocal(val cachePath: String, val targetUri: Uri) : BackupViewModelEvent
        object RestoreSuccess : BackupViewModelEvent
    }

    fun saveWebDavConfig(config: WebDavConfig) {
        MmkvManager.encodeWebDavConfig(config)
        _webDavConfig.value = config
        toastSuccess(R.string.toast_success)
    }

    fun cleanupProfileStorage() {
        launchLoading {
            try {
                val removed = withContext(Dispatchers.IO) {
                    MmkvManager.removeOrphanedServerProfiles()
                }
                if (removed == null) {
                    toastError(R.string.toast_profile_storage_cleanup_skipped)
                } else {
                    toastSuccess(getString(R.string.toast_profile_storage_cleanup, removed))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to clean up profile storage", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun shareBackup(cacheDir: File, appName: String) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName)
            if (ret.first) {
                _viewModelEvent.send(BackupViewModelEvent.ShareFile(ret.second))
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun prepareBackupForUri(cacheDir: File, appName: String, targetUri: Uri) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName)
            if (ret.first) {
                _viewModelEvent.send(BackupViewModelEvent.ExportLocal(ret.second, targetUri))
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun restoreConfiguration(cacheDir: File, zipFile: File) {
        launchLoading {
            val success = performRestore(cacheDir, zipFile)
            if (success) {
                toastSuccess(R.string.toast_success)
                _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun backupViaWebDav(cacheDir: File, appName: String) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var tempFile: File? = null
            try {
                val ret = backupConfigurationToCache(cacheDir, appName)
                if (!ret.first) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                tempFile = File(ret.second)
                WebDavManager.init(config)

                val ok = try {
                    WebDavManager.uploadFile(tempFile, AppConfig.WEBDAV_BACKUP_FILE_NAME)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "WebDAV upload error", e)
                    false
                }

                if (ok) {
                    toastSuccess(R.string.toast_success)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV backup error", e)
                toastError(R.string.toast_failure)
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun restoreViaWebDav(cacheDir: File) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var target: File? = null
            try {
                target = File(cacheDir, "download_${System.currentTimeMillis()}.zip")
                WebDavManager.init(config)
                val ok = WebDavManager.downloadFile(AppConfig.WEBDAV_BACKUP_FILE_NAME, target)
                if (!ok) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                if (performRestore(cacheDir, target)) {
                    toastSuccess(R.string.toast_success)
                    _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV download error", e)
                toastError(R.string.toast_failure)
            } finally {
                target?.delete()
            }
        }
    }

    private suspend fun backupConfigurationToCache(cacheDir: File, appName: String): Pair<Boolean, String> {
        val password = archivePassword.copyOf()
        return try { withContext(Dispatchers.IO) { createArchive(cacheDir, appName, password) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Backup archive encoding failed", IllegalStateException(error.javaClass.simpleName))
            Pair(false, "")
        } finally { password.fill('\u0000') }
    }

    private fun createArchive(cacheDir: File, appName: String, password: CharArray): Pair<Boolean, String> {
        val dateFormatted = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss",
            Locale.getDefault()
        ).format(System.currentTimeMillis())
        val folderName = "${appName}_$dateFormatted"
        val backupDir = cacheDir.absolutePath + "/$folderName"
        val outputZipFilePath = "${cacheDir.absolutePath}/$folderName.zip"

        val output = File(outputZipFilePath)
        val protected = File(outputZipFilePath + ".drbackup")
        try {
            if (password.isNotEmpty() && password.size < 8) return Pair(false, "")
            if (MMKV.backupAllToDirectory(backupDir) <= 0) return Pair(false, "")
            val assetDirectory = File(com.v2ray.ang.util.Utils.userAssetPath(getApplication()))
            val assets = MmkvManager.decodeSubscriptions().flatMap { provider ->
                com.v2ray.ang.handler.HappRoutingStore.state(provider.guid).profiles.flatMap { it.assetFiles.values }
            }.distinct()
            for (name in assets) {
                require(name.matches(Regex("dr_happ_[a-f0-9]{32}\\.dat")))
                val file = File(assetDirectory, name)
                require(file.isFile && file.length() <= com.v2ray.ang.handler.BackupArchiveCodec.MAX_BYTES)
                file.copyTo(File(backupDir, name))
            }
            if (!ZipUtil.zipFromFolder(backupDir, outputZipFilePath))
                return Pair(false, "").also { output.delete() }
            require(output.length() <= com.v2ray.ang.handler.BackupArchiveCodec.MAX_BYTES)
            if (password.isEmpty()) return Pair(true, outputZipFilePath)
            com.v2ray.ang.handler.BackupArchiveCodec.encodeFile(output, protected, password)
            output.delete()
            return Pair(true, protected.absolutePath)
        } catch (error: Exception) {
            output.delete(); protected.delete()
            throw error
        } finally { File(backupDir).deleteRecursively() }
    }

    private suspend fun performRestore(cacheDir: File, zipFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val backupDir = File(cacheDir, "restore_${System.nanoTime()}")
            val decoded = File(cacheDir, "restore_${System.nanoTime()}.zip")
            val rollback = File(cacheDir, "rollback_${System.nanoTime()}")
            val restoredAssets = mutableListOf<File>()
            var changingStorage = false
            var committed = false
            val password = archivePassword.copyOf()
            try {
                require(zipFile.length() <= com.v2ray.ang.handler.BackupArchiveCodec.MAX_BYTES + 128)
                com.v2ray.ang.handler.BackupArchiveCodec.decodeFile(zipFile, decoded, password)
                ZipUtil.extractArchive(decoded, backupDir, ZipUtil.ExtractionLimits(
                    maxArchiveBytes = (com.v2ray.ang.handler.BackupArchiveCodec.MAX_BYTES + 128).toLong(),
                    maxEntries = 128, maxEntryBytes = com.v2ray.ang.handler.BackupArchiveCodec.MAX_BYTES.toLong(),
                    maxTotalBytes = 128L * 1024 * 1024, maxCompressionRatio = 1000.0))
                for (file in backupDir.listFiles().orEmpty().filter { it.name.startsWith("dr_happ_") }) {
                    require(file.isFile && file.name.matches(Regex("dr_happ_[a-f0-9]{32}\\.dat")))
                    val target = File(com.v2ray.ang.util.Utils.userAssetPath(getApplication()), file.name)
                    if (target.exists()) require(target.readBytes().contentEquals(file.readBytes()))
                    else { file.copyTo(target); restoredAssets += target }
                    file.delete()
                }
                require(MMKV.backupAllToDirectory(rollback.absolutePath) > 0)
                changingStorage = true
                val count = MMKV.restoreAllFromDirectory(backupDir.absolutePath)
                require(count > 0)
                committed = true
                SettingsChangeManager.makeSetupGroupTab()
                SettingsChangeManager.makeRestartService()
                count > 0
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Backup archive decoding failed", IllegalStateException(error.javaClass.simpleName))
                false
            } finally {
                if (!committed) {
                    if (changingStorage) MMKV.restoreAllFromDirectory(rollback.absolutePath)
                    restoredAssets.forEach { it.delete() }
                }
                password.fill('\u0000')
                decoded.delete()
                zipFile.delete()
                backupDir.deleteRecursively()
                rollback.deleteRecursively()
            }
        }
}
