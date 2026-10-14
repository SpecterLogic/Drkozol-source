package com.v2ray.ang.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.SubscriptionVisibility
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

class MainRepository(
    private val app: AngApplication
) : MainDataSource {

    private val localizedContext: Context
        get() = AppLocaleManager.localizedContext(app)

    private val closed = AtomicBoolean(false)

    // Probe results are finite and must remain lossless until the ViewModel coalesces them.
    private val mainServiceEventChannel = Channel<MainServiceEvent>(Channel.UNLIMITED)

    override val mainServiceEvent: Flow<MainServiceEvent> = mainServiceEventChannel.receiveAsFlow()

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            val requestId = safeIntent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty()
            val event = when (safeIntent.getIntExtra("key", 0)) {
                10901 -> safeIntent.serializable<com.v2ray.ang.dto.TrafficSnapshot>("content")?.let { MainServiceEvent.Traffic(it) }
                AppConfig.MSG_STATE_RUNNING -> MainServiceEvent.StateRunning
                AppConfig.MSG_STATE_NOT_RUNNING -> MainServiceEvent.StateNotRunning
                AppConfig.MSG_STATE_START_SUCCESS -> MainServiceEvent.StateStartSuccess
                AppConfig.MSG_STATE_START_FAILURE -> MainServiceEvent.StateStartFailure(
                    safeIntent.getStringExtra("content")
                )

                AppConfig.MSG_STATE_STOP_SUCCESS -> MainServiceEvent.StateStopSuccess
                AppConfig.MSG_MEASURE_DELAY_RESULT -> safeIntent
                    .serializable<ConnectionTestResult>("content")
                    ?.let { MainServiceEvent.MeasureDelayResult(it, requestId) }
                AppConfig.MSG_MEASURE_DELAY_CANCEL -> MainServiceEvent.MeasureDelayCancelled(requestId)

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> safeIntent
                    .serializable<RealPingResult>("content")
                    ?.let { MainServiceEvent.MeasureConfigSuccess(it, requestId) }
                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> MainServiceEvent.MeasureConfigNotify(
                    safeIntent.getStringExtra("content").orEmpty(), requestId
                )

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> MainServiceEvent.MeasureConfigFinish(
                    requestId
                )
                AppConfig.MSG_MEASURE_CONFIG_CANCEL -> MainServiceEvent.MeasureConfigCancelled(requestId)

                else -> null
            }
            event?.let { mainServiceEventChannel.trySend(it) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            serviceReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags()
        )
        MessageHelper.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            MessageHelper.sendMsg2Service(app, AppConfig.MSG_UNREGISTER_CLIENT, "")
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to unregister service client", it)
        }
        runCatching {
            app.unregisterReceiver(serviceReceiver)
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to unregister main service receiver", it)
        }
        mainServiceEventChannel.close()
    }

    override fun getSelectedSubscriptionId(): String =
        MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()

    override fun setSelectedSubscriptionId(id: String) {
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, id)
    }

    override fun getSelectServer(): String? = MmkvManager.getSelectServer()

    override fun setSelectServer(guid: String) = MmkvManager.setSelectServer(guid)

    override fun getConfirmRemove(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE, false)

    override fun getDoubleColumnDisplay(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)

    override fun isGroupAllDisplayEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)

    override fun getString(resId: Int): String = localizedContext.getString(resId)

    override fun getString(resId: Int, vararg formatArgs: Any): String =
        localizedContext.getString(resId, *formatArgs)

    override fun getSubscriptions(): List<SubscriptionCache> {
        val result = mutableListOf<SubscriptionCache>()
        if (isGroupAllDisplayEnabled()) {
            result += SubscriptionCache(
                guid = "",
                subscription = SubscriptionItem().apply {
                    remarks = localizedContext.getString(R.string.filter_config_all)
                }
            )
        }
        result += MmkvManager.decodeSubscriptions().map { cache ->
            cache.copy(subscription = cache.subscription.copy(remarks = SubscriptionVisibility.displayRemarks(
                cache.guid, cache.subscription, localizedContext.getString(R.string.dr_servers))))
        }
        return result
    }

    override fun getSubscriptionItem(id: String): SubscriptionItem? =
        MmkvManager.decodeSubscription(id)

    override fun getServerGuidList(groupId: String): List<String> =
        if (groupId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(groupId)
        }

    override fun decodeServerConfig(guid: String): ProfileItem? =
        MmkvManager.decodeServerConfig(guid)

    override fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo? =
        MmkvManager.decodeServerAffiliationInfo(guid)

    override fun encodeServerList(guids: List<String>, groupId: String) =
        MmkvManager.encodeServerList(ArrayList(guids), groupId)

    override fun removeServer(guid: String) = MmkvManager.removeServer(guid)

    override fun removeAllServer(): Int = MmkvManager.removeAllServer()

    override fun removeInvalidServerByGuid(guid: String): Int =
        MmkvManager.removeInvalidServer(guid)

    override fun removeInvalidServersInGroup(groupId: String): Int =
        if (groupId.isEmpty()) {
            MmkvManager.removeInvalidServer("")
        } else {
            getServerGuidList(groupId).sumOf(::removeInvalidServerByGuid)
        }

    override fun clearAllTestDelayResults(guids: List<String>) =
        MmkvManager.clearAllTestDelayResults(guids)

    override fun sortByTestResultsForSub(subId: String) {
        AngConfigManager.sortByTestResultsForSub(subId)
        MmkvManager.decodeSubscription(subId)?.let { item -> item.serverListOrder = "ping"; MmkvManager.encodeSubscription(subId, item) }
    }

    override fun getSubsList(): List<String> = MmkvManager.decodeSubsList()

    override fun saveProbeResult(guid: String, delay: Long) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        MmkvManager.encodeServerTestDelayMillis(guid, delay, false)
        sortByTestResultsForSub(profile.subscriptionId)
    }

    override suspend fun importBatchConfig(
        server: String?,
        subscriptionId: String,
        updateUI: Boolean
    ): Pair<Int, Int> = SubscriptionUpdater.afterSubscriptionImport(
        AngConfigManager.importBatchConfig(server, subscriptionId, updateUI)
    ) {
        try { SubscriptionUpdater.sync(app) }
        catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            LogUtil.e(AppConfig.TAG, "Subscription scheduling after import failed group=$subscriptionId",
                IllegalStateException(error.javaClass.simpleName))
        }
    }

    override fun updateConfigViaSubAll(): SubscriptionUpdateResult =
        AngConfigManager.updateConfigViaSubAll()

    override fun updateConfigViaSub(subscriptionCache: SubscriptionCache): SubscriptionUpdateResult =
        AngConfigManager.updateConfigViaSub(subscriptionCache)

    override fun shareNonCustomConfigsToClipboard(guids: List<String>): Int =
        AngConfigManager.shareNonCustomConfigsToClipboard(app, guids)

    override fun share2QRCode(guid: String): android.graphics.Bitmap? =
        AngConfigManager.share2QRCode(guid)

    override fun share2Clipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(app, guid) == 0

    override fun sendMsg2Service(msgId: Int, content: String) =
        MessageHelper.sendMsg2Service(app, msgId, content)

    override fun sendMsg2TestService(msg: TestServiceMessage, requestId: String?) =
        MessageHelper.sendMsg2TestService(app, msg, requestId)

    override fun cancelAllPing() {
        sendMsg2TestService(
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
    }

    override fun testCurrentServerRealPing(requestId: String) {
        MessageHelper.sendMsg2ServiceForResult(app, AppConfig.MSG_MEASURE_DELAY, requestId) { handled ->
            if (!handled) mainServiceEventChannel.trySend(MainServiceEvent.MeasureDelayCancelled(requestId))
        }
    }

    override fun createBalancer(groupId: String): String? {
        val profiles = getServerGuidList(groupId).mapNotNull { decodeServerConfig(it) }
            .filter { com.v2ray.ang.handler.balancerEligible(it) }
        if (profiles.size < 2) return null
        val existing = MmkvManager.decodeSettingsString("dr_balancer_" + groupId).orEmpty()
        val profile = ProfileItem.create(com.v2ray.ang.enums.EConfigType.POLICYGROUP).apply {
            remarks = localizedContext.getString(R.string.dr_balancer)
            subscriptionId = groupId
            policyGroupSubscriptionId = groupId
            policyGroupType = com.v2ray.ang.enums.BalancerStrategyType.ROUND_ROBIN.policyGroupTypeValue
            policyGroupTestOutbounds = true
        }
        val guid = MmkvManager.encodeServerConfig(existing, profile)
        MmkvManager.encodeSettings("dr_balancer_" + groupId, guid)
        return guid
    }

    override fun backgroundProbeRevision(): String = MmkvManager.decodeSettingsString("dr_probe_revision").orEmpty()
    override fun backgroundProbeActive(): Boolean = SettingsManager.backgroundProbeActive(app)
    override fun queuePostUpdateProbe(groupId: String) {
        val ids = if (groupId.isBlank()) MmkvManager.decodeSubsList() else listOf(groupId)
        MmkvManager.encodeSettings("dr_pending_probes", (MmkvManager.decodeSettingsStringSet("dr_pending_probes").orEmpty() + ids).toMutableSet())
        SubscriptionUpdater.enqueuePendingProbes(app)
    }
    override fun resumePendingProbes() = SettingsManager.resumePendingProbes(app)

    override fun memoryBytes(): Long {
        val manager = app.getSystemService(android.app.ActivityManager::class.java)
        val pids = manager.runningAppProcesses.orEmpty().filter { it.uid == android.os.Process.myUid() }.map { it.pid }.toIntArray()
        return if (pids.isEmpty()) 0 else manager.getProcessMemoryInfo(pids).sumOf { it.totalPss.toLong() } * 1024
    }

    override fun clientPreferences() = SettingsManager.clientPreferences()

    override fun saveClientPreferences(value: com.v2ray.ang.handler.ClientPreferences): Boolean {
        val previous = SettingsManager.clientPreferences()
        if (!SettingsManager.saveClientPreferences(value)) return false
        if (value.icon != previous.icon && !SettingsManager.applyLauncherIcon(app, value.icon)) {
            SettingsManager.saveClientPreferences(previous)
            return false
        }
        if (!value.enabled(com.v2ray.ang.handler.ClientOption.DISCONNECT_LOCK)) {
            app.stopService(Intent(app, com.v2ray.ang.service.ConnectionAutomationService::class.java))
        }
        SubscriptionUpdater.sync(app, forceReschedule = true)
        return true
    }

    override fun saveProvider(id: String, item: SubscriptionItem): Boolean {
        if (item.remarks.isBlank() || item.updateInterval !in 15..43200 ||
            (!item.delayTestUrl.isNullOrBlank() && !com.v2ray.ang.handler.validProbeUrl(item.delayTestUrl.orEmpty())) ||
            !com.v2ray.ang.handler.validDnsOverride(item.tunnelDns.orEmpty()) ||
            item.displayDelayAs !in setOf("time", "status") || item.serverListOrder !in setOf("ping", "name") ||
            item.connectionStyle !in setOf("classic", "minimal") || item.probeMethod !in setOf("real", "tcp")) return false
        val previous = MmkvManager.decodeSubscription(id) ?: return false
        item.customRemarks = previous.customRemarks || item.remarks != previous.remarks
        MmkvManager.encodeSubscription(id, item)
        if (MmkvManager.decodeSubscription(id) != item) {
            MmkvManager.encodeSubscription(id, previous)
            return false
        }
        SubscriptionUpdater.syncOne(app, id)
        return true
    }

    override fun deleteProvider(id: String) {
        SettingsManager.removeSubscriptionWithDefault(id)
        SubscriptionUpdater.cancelOne(app, id)
    }

    override fun shareProviderQr(id: String): android.graphics.Bitmap? =
        MmkvManager.decodeSubscription(id)?.url?.takeIf { it.isNotBlank() }
            ?.let { com.v2ray.ang.util.QRCodeDecoder.createQRCode(it) }

    override fun syncSubscriptions() {
        SettingsManager.migrateAutomaticRefresh()
        SubscriptionUpdater.sync(app)
    }

    override fun initAssets() {
        SettingsManager.initAssets(app, app.assets)
    }
}
