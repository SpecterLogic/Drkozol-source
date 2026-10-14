package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionUpdateService : Service() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val runningTasks = AtomicInteger(0)

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())

    private val updateSemaphore = Semaphore(1)
    private val activeGroups = Collections.synchronizedSet(mutableSetOf<String>())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService is being destroyed")
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        serviceJob.cancel()
        NotificationHelper.stopForeground(this)
        NotificationHelper.cancel(NotificationChannelType.SUBSCRIPTION_UPDATE, this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.SUBSCRIPTION_UPDATE,
            getString(R.string.title_pref_auto_update_subscription),
            getString(R.string.app_name)
        )
        val message = intent?.serializable<SubscriptionUpdateMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_SUB_UPDATE_START -> handleUpdateStart(message)
            AppConfig.MSG_SUB_UPDATE_CANCEL -> {
                NotificationHelper.stopForeground(this)
                stopSelf(startId)
            }

            else -> {
                NotificationHelper.stopForeground(this)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleUpdateStart(message: SubscriptionUpdateMessage) {
        val ids = message.subIds.distinct().filter { activeGroups.add(it) }
        if (ids.isEmpty()) return
        runningTasks.incrementAndGet()
        serviceScope.launch {
            try {
                updateSemaphore.withPermit {
                    CoreNativeManager.initCoreEnv(this@SubscriptionUpdateService)
                    ids.forEach { subId ->
                        currentCoroutineContext().ensureActive()
                        updateSingle(subId, message.forcedUpdate, message.testOnly)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                LogUtil.e(AppConfig.TAG, "SubscriptionUpdateService batch operation failed count=${ids.size}", error)
            } finally {
                activeGroups.removeAll(ids.toSet())
                if (runningTasks.decrementAndGet() == 0 && activeWorkers.isEmpty()) {
                    NotificationHelper.stopForeground(this@SubscriptionUpdateService)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun updateSingle(subId: String, forcedUpdate: Boolean, testOnly: Boolean) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        if (!subItem.enabled || subItem.url.isEmpty()) {
            return
        }

        val sub = SubscriptionCache(subId, subItem)

        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: Updating group=$subId")
        showNotification(
            context = this,
            titleResId = R.string.title_pref_auto_update_subscription,
            content = getString(R.string.subscription_update_updating, subItem.remarks)
        )

        if (!testOnly && (forcedUpdate || MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_SUBSCRIPTION, false))) {
            val result = AngConfigManager.updateConfigViaSub(sub)
            if (result.successCount == 0) return
            SubscriptionUpdater.syncOne(this, subId)
        }
        currentCoroutineContext().ensureActive()
        if (SettingsManager.clientPreferences().enabled(com.v2ray.ang.handler.ClientOption.PING_UPDATE)) {
            // Standalone peers must not compete with any active Android VPN. This is a
            // transport gate, not a UI-process inference about the daemon's core state.
            // A refresh downloads metadata; its native probes run only as a separate idle job.
            if (!testOnly || !automaticProbeAllowed()) {
                val pending = MmkvManager.decodeSettingsStringSet("dr_pending_probes").orEmpty() + subId
                MmkvManager.encodeSettings("dr_pending_probes", pending.toMutableSet())
                SubscriptionUpdater.enqueuePendingProbes(this)
                return
            }
            testSubscriptionServers(sub)

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: removing invalid servers for group=$subId")
                showNotification(
                    context = this,
                    titleResId = R.string.title_del_invalid_config,
                    content = subItem.remarks
                )
                AngConfigManager.removeInvalidServer(subId)
            }
            run {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: sorting servers for group=$subId")
                showNotification(
                    context = this,
                    titleResId = R.string.title_sort_by_test_results,
                    content = subItem.remarks
                )
                AngConfigManager.sortByTestResultsForSub(subId)
                MmkvManager.decodeSubscription(subId)?.let { item -> item.serverListOrder = "ping"; MmkvManager.encodeSubscription(subId, item) }
                MmkvManager.encodeSettings("dr_probe_revision", System.currentTimeMillis().toString())
                MmkvManager.encodeSettings("dr_pending_probes", (MmkvManager.decodeSettingsStringSet("dr_pending_probes").orEmpty() - subId).toMutableSet())
            }
        }

        if (SettingsManager.clientPreferences().enabled(com.v2ray.ang.handler.ClientOption.NOTIFY_UPDATE)) {
            NotificationHelper.notify(NotificationChannelType.SUBSCRIPTION_RESULT, this,
                getString(R.string.app_name), getString(R.string.dr_update_done) + " · " + subItem.remarks, ongoing = false)
        }
        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: Finished group=$subId")
    }

    private suspend fun testSubscriptionServers(sub: SubscriptionCache) {
        val subId = sub.guid
        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: starting test phase for ${sub.subscription.remarks}")
        showNotification(
            context = this,
            titleResId = R.string.title_real_ping_all_server,
            content = sub.subscription.remarks
        )

        val guids = MmkvManager.decodeServerList(subId)
        if (guids.isNotEmpty()) {
            val deferred = CompletableDeferred<Unit>()
            lateinit var worker: RealPingWorkerService
            worker = RealPingWorkerService(
                context = this,
                guids = guids,
                onEvent = { event ->
                    handleWorkerEvent(event, sub.subscription.remarks) {
                        activeWorkers.remove(worker)
                        deferred.complete(Unit)
                    }
                }
            )
            activeWorkers.add(worker)
            val leaseId = java.util.UUID.randomUUID().toString()
            SettingsManager.beginBackgroundProbe(leaseId)
            val quietMonitor = serviceScope.launch {
                while (isActive) {
                    if (!automaticProbeAllowed()) {
                        worker.cancel()
                        deferred.cancel()
                        return@launch
                    }
                    kotlinx.coroutines.delay(250)
                }
            }
            try {
                if (!automaticProbeAllowed()) throw CancellationException("Automatic probe yielded to active use")
                worker.start()
                deferred.await()
            } finally {
                quietMonitor.cancel()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    // Native measurements have their own request timeout; await owned cleanup
                    // before another connection can reuse the peer, bounded to 35 seconds.
                    kotlinx.coroutines.withTimeoutOrNull(35_000) { worker.cancelAndJoin() }
                    activeWorkers.remove(worker)
                    SettingsManager.finishBackgroundProbe(leaseId)
                }
            }
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: test phase finished for ${sub.subscription.remarks}")
        }
    }

    private fun automaticProbeAllowed(): Boolean = com.v2ray.ang.handler.canRunAutomaticProbe(
        getSystemService(android.os.PowerManager::class.java).isInteractive,
        getSystemService(android.os.BatteryManager::class.java).isCharging,
        SettingsManager.hasActiveVpn(this),
    )

    private fun handleWorkerEvent(event: RealPingEvent, remarks: String, onWorkerDone: () -> Unit) {
        when (event) {
            is RealPingEvent.Progress -> {
                SettingsManager.refreshBackgroundProbe()
                val notificationText = getString(
                    R.string.subscription_update_progress,
                    event.text,
                    remarks
                )
                showNotification(
                    context = this,
                    titleResId = R.string.title_real_ping_all_server,
                    content = notificationText
                )
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: ${event.text} in $remarks")
            }

            is RealPingEvent.Result -> {
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis, false)
            }

            is RealPingEvent.Finish -> {
                onWorkerDone()
            }
        }
    }

    private fun showNotification(context: Context, titleResId: Int, content: String) {
        NotificationHelper.notify(
            NotificationChannelType.SUBSCRIPTION_UPDATE,
            context,
            context.getString(titleResId),
            content
        )
    }
}
