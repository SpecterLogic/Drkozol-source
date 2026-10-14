package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.ClientOption
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.*

/** VPN-only screen automation has its own foreground lifetime while the tunnel is stopped. */
class ConnectionAutomationService : Service() {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private var screenJob: Job? = null
    private var resumeJob: Job? = null
    private var runningReply: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingGuid: String? = null
    private val pauseOrigin = java.util.UUID.randomUUID().toString()

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val previous = screenJob
                    screenJob = scope.launch { previous?.cancelAndJoin(); onLock() }
                }
                // Do not cancel the lock operation: it may still be waiting for its daemon reply.
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> startResumeMonitor()
                AppConfig.BROADCAST_ACTION_ACTIVITY -> when (intent.getIntExtra("key", 0)) {
                    AppConfig.MSG_STATE_RUNNING -> runningReply?.complete(true)
                    AppConfig.MSG_STATE_START_SUCCESS -> { runningReply?.complete(true); pendingGuid = null }
                    AppConfig.MSG_STATE_START_FAILURE -> { runningReply?.complete(false); pendingGuid = null }
                    AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> runningReply?.complete(false)
                }
                AppConfig.BROADCAST_ACTION_SERVICE -> if (intent.getIntExtra("key", 0) == AppConfig.MSG_STATE_STOP) {
                    if (!isAutomationPause(intent.getStringExtra("content"), pauseOrigin)) {
                        pendingGuid = null; screenJob?.cancel(); resumeJob?.cancel(); stopSelf()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction(AppConfig.BROADCAST_ACTION_ACTIVITY); addAction(AppConfig.BROADCAST_ACTION_SERVICE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = PendingIntent.getService(this, 16, Intent(this, ConnectionAutomationService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationHelper.startForeground(this, NotificationChannelType.CONNECTION_AUTOMATION,
            getString(R.string.app_name), getString(R.string.dr_disconnect_lock),
            NotificationCompat.Action.Builder(R.drawable.dr_dismiss, getString(R.string.dr_dismiss), stop).build())
        if (intent?.action == "stop") { pendingGuid = null; screenJob?.cancel(); resumeJob?.cancel(); stopSelf(startId) }
        return START_NOT_STICKY
    }

    private suspend fun onLock() {
        val preferences = SettingsManager.clientPreferences()
        if (!preferences.enabled(ClientOption.DISCONNECT_LOCK)) return
        if (pendingGuid != null) return // Repeated lock events must retain the paused profile.
        val guid = MmkvManager.getSelectServer() ?: return
        val reply = CompletableDeferred<Boolean>()
        runningReply = reply
        try {
            // Fresh daemon response is the authority. A cached UI Boolean is never used.
            MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
            val running = withTimeoutOrNull(1500) { reply.await() } == true
            currentCoroutineContext().ensureActive()
            if (!running || MmkvManager.getSelectServer() != guid) return
            pendingGuid = if (preferences.enabled(ClientOption.RECONNECT_UNLOCK)) guid else null
            LauncherManager.stopService(this, pauseOrigin)
            if (pendingGuid == null) withContext(Dispatchers.Main) { stopSelf() }
            else withContext(Dispatchers.Main) { startResumeMonitor() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            LogUtil.e(AppConfig.TAG, "VPN screen-lock automation failed profile=$guid", error)
        } finally { if (runningReply === reply) runningReply = null }
    }

    private fun startResumeMonitor() {
        if (pendingGuid == null || resumeJob?.isActive == true) return
        resumeJob = scope.launch {
            // Checking interactive + keyguard also covers wake/unlock without USER_PRESENT
            // (for example a phone with no secure lock). Work exists only while a profile is paused.
            while (pendingGuid != null) {
                if (isUnlockedScreen(getSystemService(PowerManager::class.java).isInteractive,
                        getSystemService(KeyguardManager::class.java).isKeyguardLocked)) {
                    onUnlock()
                    return@launch
                }
                delay(1_000)
            }
        }
    }

    private suspend fun onUnlock() {
        val guid = pendingGuid
        val enabled = SettingsManager.clientPreferences().enabled(ClientOption.RECONNECT_UNLOCK)
        val ready = VpnService.prepare(this) == null
        if (!shouldResumeOnUnlock(guid, MmkvManager.getSelectServer(), enabled, ready)) {
            pendingGuid = null
            return
        }
        try {
            currentCoroutineContext().ensureActive()
            val xray = MmkvManager.decodeServerConfig(guid!!)?.amneziaConfig == null
            val released = withTimeoutOrNull(10_000) {
                // This watcher runs in the daemon process. Native stop is asynchronous, and
                // VPN disappearance alone does not prove that the old core released its ports.
                while (SettingsManager.hasActiveVpn(this@ConnectionAutomationService) ||
                    (xray && CoreServiceManager.isRunning())) delay(50)
                true
            } == true
            currentCoroutineContext().ensureActive()
            if (released && pendingGuid == guid && SettingsManager.isVpnMode() &&
                shouldResumeOnUnlock(guid, MmkvManager.getSelectServer(),
                    SettingsManager.clientPreferences().enabled(ClientOption.RECONNECT_UNLOCK), VpnService.prepare(this) == null) &&
                isUnlockedScreen(getSystemService(PowerManager::class.java).isInteractive,
                    getSystemService(KeyguardManager::class.java).isKeyguardLocked)) {
                pendingGuid = null
                LauncherManager.startService(this, guid)
            } else if (!released) {
                LogUtil.e(AppConfig.TAG, "VPN unlock resume release timeout profile=$guid", IllegalStateException("Previous tunnel/core still active"))
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            LogUtil.e(AppConfig.TAG, "VPN unlock resume failed profile=$guid", error)
        }
    }

    override fun onDestroy() {
        pendingGuid = null
        owner.cancel()
        runningReply?.cancel()
        unregisterReceiver(receiver)
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }
}
