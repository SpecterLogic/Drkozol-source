package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

class MainActivity : HelperBaseComponentActivity() {
    private var handoverJob: Job? = null

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
        if (savedInstanceState == null) lifecycleScope.launch {
            if (!mainViewModel.awaitInitialPage()) return@launch
            val preferences = withContext(Dispatchers.IO) { com.v2ray.ang.handler.SettingsManager.clientPreferences() }
            if (preferences.enabled(com.v2ray.ang.handler.ClientOption.AUTO_CONNECT) && !mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()
                && com.v2ray.ang.handler.AutomaticNetworkPolicy.allows(this@MainActivity)) requestServiceStart()
            if (preferences.enabled(com.v2ray.ang.handler.ClientOption.UPDATE_LAUNCH)) mainViewModel.onAction(MainAction.UpdateSubscriptions)
            else if (preferences.enabled(com.v2ray.ang.handler.ClientOption.PING_LAUNCH)) mainViewModel.testAllRealPing(ignoreCategory = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Shared-link imports finish in another activity before returning to this singleTask.
        mainViewModel.onAction(MainAction.RefreshGroups)
        mainViewModel.refreshSelectedGuid()
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        DrSpectScreen(
            mainViewModel = mainViewModel,
            dispatchAction = { action ->
                when (action) {
                    is MainAction.SetLanguage -> com.v2ray.ang.handler.AppLocaleManager.setApplicationLanguage(action.code)
                    MainAction.OpenSystemVpn -> openPage(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
                    MainAction.ShowUrlSchemes -> Utils.setClipboard(this, "drkozol://install-sub?url=HTTPS_SUBSCRIPTION")
                    MainAction.AppFeedback -> openPage(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AppConfig.APP_ISSUES_URL)))
                    is MainAction.ShareProvider, is MainAction.CopyProvider -> lifecycleScope.launch {
                        val id = if (action is MainAction.ShareProvider) action.id else (action as MainAction.CopyProvider).id
                        val url = withContext(Dispatchers.IO) { MmkvManager.decodeSubscription(id)?.url }.orEmpty()
                        if (url.isNotBlank()) {
                            if (action is MainAction.CopyProvider) Utils.setClipboard(this@MainActivity, url)
                            else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), getString(R.string.dr_share)))
                        }
                    }
                    MainAction.ToggleService -> handleFabAction()
                    is MainAction.DeleteProvider -> {
                        if (handoverJob?.isActive != true) handoverJob = lifecycleScope.launch {
                            val state = mainViewModel.uiState.value
                            val activeGroup = withContext(Dispatchers.IO) { state.selectedGuid?.let { MmkvManager.decodeServerConfig(it)?.subscriptionId } }
                            if ((state.isRunning || state.isConnecting) && activeGroup == action.id) {
                                LauncherManager.stopService(this@MainActivity)
                                val stopped = withTimeoutOrNull(10_000) {
                                    mainViewModel.uiState.first { !it.isRunning && !it.isConnecting }
                                    withContext(Dispatchers.IO) { while (SettingsManager.hasActiveVpn(this@MainActivity)) delay(50) }
                                    true
                                } == true
                                if (!stopped) { toastError(R.string.dr_unreachable); return@launch }
                            }
                            mainViewModel.onAction(action)
                        }
                    }
                    MainAction.ConnectBestServer, MainAction.ConnectBalancer -> {
                        if (handoverJob?.isActive != true) handoverJob = lifecycleScope.launch {
                        val previous = withContext(Dispatchers.IO) { mainViewModel.uiState.value.selectedGuid?.let { MmkvManager.decodeServerConfig(it) } }
                        val chosen = if (action == MainAction.ConnectBestServer) mainViewModel.chooseBestForConnection()
                            else mainViewModel.chooseBalancerForConnection()
                        if (chosen) {
                            restartSelectedOrStart(previous)
                        } else if (action == MainAction.ConnectBalancer) toastError(R.string.dr_balancer_unavailable)
                        }
                    }
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    else -> mainViewModel.onAction(action)
                }
            },
            dispatchNavigation = { route -> navigateTo(route) },
        )
    }

    private fun openPage(intent: Intent) {
        try { startActivity(intent) }
        catch (error: android.content.ActivityNotFoundException) {
            LogUtil.e(AppConfig.TAG, "Requested settings/browser activity unavailable", error)
            toastError(R.string.dr_no_handler)
        }
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java).putExtra("bypass_split", true)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.ConnectionTools -> Intent(this, com.v2ray.ang.ui.tools.ConnectionToolsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
            MainDestination.Promotion -> Intent(this, AboutActivity::class.java)
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isTesting) {
            handoverJob?.cancel()
            mainViewModel.onAction(MainAction.CancelTesting)
            return
        }
        if (handoverJob?.isActive == true) return
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
        } else {
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        mainViewModel.onAction(MainAction.ConnectionStarting)
        LauncherManager.startService(this)
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(qrImportAction(scanResult, mainViewModel.uiState.value.subscriptionReady))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private suspend fun restartSelectedOrStart(previous: ProfileItem?) {
        val next = withContext(Dispatchers.IO) { mainViewModel.uiState.value.selectedGuid?.let { MmkvManager.decodeServerConfig(it) } }
        if (!mainViewModel.uiState.value.isRunning) { requestServiceStart(); return }
        if (!requiresTransportHandover(previous, next)) { LauncherManager.restartService(this); return }
        mainViewModel.onAction(MainAction.ConnectionStarting)
        LauncherManager.stopService(this)
        // The daemon's stop acknowledgement precedes Android's tunnel removal. Wait for both
        // before another VpnService acquires the interface; a fixed sleep cannot establish this.
        val released = withTimeoutOrNull(10_000) {
            mainViewModel.uiState.first { !it.isRunning }
            withContext(Dispatchers.IO) { while (SettingsManager.hasActiveVpn(this@MainActivity)) delay(50) }
            true
        } == true
        if (released) requestServiceStart() else toastError(R.string.dr_unreachable)
    }

    private fun setSelectServer(guid: String) {
        if (handoverJob?.isActive == true) return
        handoverJob = lifecycleScope.launch {
            val selected = mainViewModel.uiState.value.selectedGuid
            val previous = withContext(Dispatchers.IO) { selected?.let { MmkvManager.decodeServerConfig(it) } }
            if (guid != selected && mainViewModel.updateSelectedGuid(guid) && mainViewModel.uiState.value.isRunning) {
                restartSelectedOrStart(previous)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
