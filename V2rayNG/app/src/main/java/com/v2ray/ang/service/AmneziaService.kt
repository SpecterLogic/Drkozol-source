package com.v2ray.ang.service

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.TrafficSnapshot
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionAccess
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns AWG native work in the same daemon process as Xray. Backend owns the TUN descriptor. */
class AmneziaService : Service() {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val nativeMutex = Mutex()
    private var setup: Job? = null
    private var monitor: Job? = null
    private var delayProbe: Job? = null
    private var backend: GoBackend? = null
    private var profileGuid: String? = null
    private var restartAfterStop = false
    private val stopping = AtomicBoolean(false)
    @Volatile private var up = false
    private val tunnel = object : Tunnel {
        override fun getName() = "DrKozol"
        override fun onStateChange(newState: Tunnel.State) {
            up = newState == Tunnel.State.UP
            if (!up && !stopping.get()) requestStop()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> MessageHelper.sendMsg2UI(this@AmneziaService, if (up) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING, "")
                AppConfig.MSG_STATE_STOP -> requestStop()
                AppConfig.MSG_STATE_RESTART -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    requestStop(restart = true)
                }
                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    val requestId = intent.getStringExtra("content").orEmpty()
                    if (stopping.get() || !up) return
                    delayProbe?.cancel()
                    delayProbe = scope.launch {
                        val started = android.os.SystemClock.elapsedRealtime()
                        val client = okhttp3.OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
                        try {
                            val result = try {
                                suspendCancellableCoroutine<ConnectionTestResult> { continuation ->
                                    val call = client.newCall(okhttp3.Request.Builder().url(SettingsManager.getDelayTestUrlForProfile(profileGuid)).build())
                                    continuation.invokeOnCancellation { call.cancel() }
                                    call.enqueue(object : okhttp3.Callback {
                                        override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                                            if (continuation.isActive) continuation.resumeWithException(java.io.IOException("AWG connected probe failed"))
                                        }
                                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                            response.use {
                                                if (continuation.isActive) {
                                                    if (it.isSuccessful) continuation.resume(ConnectionTestResult((android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(1)))
                                                    else continuation.resumeWithException(java.io.IOException("AWG connected probe rejected"))
                                                }
                                            }
                                        }
                                    })
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                LogUtil.e(AppConfig.TAG, "AWG connected probe failed profile=$profileGuid", IllegalStateException(error.javaClass.simpleName))
                                ConnectionTestResult(-1, getString(R.string.dr_unreachable))
                            }
                            if (!stopping.get()) MessageHelper.sendMsg2UI(this@AmneziaService, AppConfig.MSG_MEASURE_DELAY_RESULT, result, requestId)
                        } finally {
                            client.dispatcher.executorService.shutdown()
                            client.connectionPool.evictAll()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopIntent = Intent(this, AmneziaService::class.java).setAction("stop")
        val stopAction = NotificationCompat.Action.Builder(R.drawable.ic_stop_24dp, getString(R.string.dr_disconnect), PendingIntent.getService(this, 14, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        NotificationHelper.startForeground(this, NotificationChannelType.AMNEZIA, getString(R.string.app_name), getString(R.string.dr_ready), stopAction)
        if (intent?.action == "stop") { requestStop(); return START_NOT_STICKY }
        if (stopping.get() || setup?.isActive == true || up) return START_NOT_STICKY
        setup = scope.launch {
            try {
                val guid = MmkvManager.getSelectServer() ?: error("Missing profile")
                profileGuid = guid
                check(!SubscriptionAccess.locked(guid)) { getString(R.string.dr_locked) }
                val profile = MmkvManager.decodeServerConfig(guid) ?: error("Missing AWG profile")
                val subscriptionId = profile.subscriptionId
                val raw = profile.amneziaConfig ?: error("Missing AWG config")
                val selectedApps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty()
                val installedApps = if (selectedApps.isEmpty()) emptySet() else
                    com.v2ray.ang.util.AppManagerUtil.loadNetworkAppList(this@AmneziaService).map { it.packageName }.toSet()
                val splitRaw = amneziaSplitConfig(raw,
                    MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false),
                    MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false), selectedApps.intersect(installedApps))
                val customDns = MmkvManager.decodeSubscription(subscriptionId)?.tunnelDns
                    ?.split(',')?.map { it.trim() }?.filter { com.v2ray.ang.util.Utils.isPureIpAddress(it) }.orEmpty()
                val dnsRaw = if (customDns.isEmpty()) splitRaw else amneziaDnsConfig(splitRaw, customDns)
                val config = Config.parse(dnsRaw.byteInputStream())
                check(!Regex("(?im)^\\s*(PreUp|PostUp|PreDown|PostDown)\\s*=").containsMatchIn(raw))
                nativeMutex.withLock {
                    ensureActive()
                    val native = GoBackend(this@AmneziaService)
                    backend = native
                    native.setState(tunnel, Tunnel.State.UP, config)
                    ensureActive()
                    check(!stopping.get())
                }
                MessageHelper.sendMsg2UI(this@AmneziaService, AppConfig.MSG_STATE_START_SUCCESS, "")
                NotificationHelper.updateNotification(NotificationChannelType.AMNEZIA, this@AmneziaService, getString(R.string.app_name), getString(R.string.dr_connected))
                monitor = scope.launch {
                    var previousTx = 0L
                    var previousRx = 0L
                    var previousTime = android.os.SystemClock.elapsedRealtime()
                    while (isActive && !stopping.get()) {
                        delay(1000)
                        if (SubscriptionAccess.subscriptionLocked(subscriptionId)) { requestStop(); break }
                        val stats = nativeMutex.withLock { backend?.getStatistics(tunnel) } ?: break
                        val time = android.os.SystemClock.elapsedRealtime()
                        val elapsed = (time - previousTime).coerceAtLeast(1)
                        val tx = stats.totalTx()
                        val rx = stats.totalRx()
                        MessageHelper.sendMsg2UI(this@AmneziaService, 10901, TrafficSnapshot((tx - previousTx).coerceAtLeast(0) * 1000 / elapsed, (rx - previousRx).coerceAtLeast(0) * 1000 / elapsed, tx + rx))
                        previousTx = tx; previousRx = rx; previousTime = time
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "AWG setup failed profile=$profileGuid", IllegalStateException(error.javaClass.simpleName))
                MessageHelper.sendMsg2UI(this@AmneziaService, AppConfig.MSG_STATE_START_FAILURE, getString(R.string.dr_unreachable))
                requestStop()
            } finally {
                if (stopping.get() || !currentCoroutineContext().isActive) withContext(NonCancellable) { closeNative() }
            }
        }
        return START_NOT_STICKY
    }

    private fun requestStop(restart: Boolean = false) {
        if (!stopping.compareAndSet(false, true)) return
        restartAfterStop = restart
        delayProbe?.cancel()
        monitor?.cancel()
        scope.launch {
            setup?.cancelAndJoin()
            closeNative()
            MessageHelper.sendMsg2UI(this@AmneziaService, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            withContext(Dispatchers.Main) {
                NotificationHelper.stopForeground(this@AmneziaService)
                stopSelf()
            }
        }
    }

    private suspend fun closeNative() = nativeMutex.withLock {
        val native = backend ?: return@withLock
        try { native.setState(tunnel, Tunnel.State.DOWN, null) }
        catch (error: Exception) { LogUtil.e(AppConfig.TAG, "AWG teardown failed profile=$profileGuid", IllegalStateException(error.javaClass.simpleName)) }
        finally {
            backend = null
            up = false
            stopService(Intent(this, GoBackend.VpnService::class.java))
        }
    }

    override fun onDestroy() {
        stopping.set(true)
        delayProbe?.cancel()
        setup?.cancel()
        monitor?.cancel()
        // Android may destroy a service outside our stop path. Await TUN teardown, bounded to
        // five seconds to avoid a main-thread ANR; remove when backend exposes async teardown.
        runBlocking { withTimeoutOrNull(5000) { withContext(Dispatchers.IO) { setup?.join(); closeNative() } } }
        owner.cancel()
        unregisterReceiver(receiver)
        NotificationHelper.stopForeground(this)
        super.onDestroy()
        if (restartAfterStop) LauncherManager.startService(applicationContext)
    }
}
