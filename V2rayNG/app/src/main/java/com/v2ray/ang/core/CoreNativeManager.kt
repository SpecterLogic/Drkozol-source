package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2Ray Native Library Manager
 *
 * Thread-safe singleton wrapper for Libv2ray native methods.
 * Provides initialization protection and unified API for V2Ray core operations.
 */
object CoreNativeManager {
    private val initialized = AtomicBoolean(false)

    /**
     * Initialize V2Ray core environment.
     * This method is thread-safe and ensures initialization happens only once.
     * Subsequent calls will be ignored silently.
     *
     */
    fun initCoreEnv(context: Context?) {
        if (initialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(context?.applicationContext)
                val assetPath = Utils.userAssetPath(context)
                val deviceId = Utils.getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                LogUtil.i(AppConfig.TAG, "V2Ray core environment initialized successfully")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to initialize V2Ray core environment", e)
                initialized.set(false)
                throw e
            }
        } else {
            LogUtil.d(AppConfig.TAG, "V2Ray core environment already initialized, skipping")
        }
    }

    /** Isolated no-listener configuration validation; never used as daemon lifecycle state. */
    fun validateRoutingProfile(context: Context, profile: com.v2ray.ang.handler.HappRoutingProfile) {
        com.v2ray.ang.handler.SettingsManager.initAssets(context,context.assets)
        initCoreEnv(context)
        val rules=com.v2ray.ang.handler.HappRoutingStore.rules("",profile).map {
            com.v2ray.ang.dto.V2rayConfig.RoutingBean.RulesBean(ip=it.ip,domain=it.domain,port=it.port,
                network=it.network,protocol=it.protocol,outboundTag="direct")
        }
        val config=com.google.gson.JsonObject().apply {
            add("log",com.google.gson.JsonObject().apply { addProperty("loglevel","none") })
            add("stats",com.google.gson.JsonObject())
            add("inbounds",com.google.gson.JsonArray())
            add("outbounds",com.google.gson.JsonArray().apply { add(com.google.gson.JsonObject().apply { addProperty("protocol","freedom");addProperty("tag","direct") }) })
            add("routing",com.google.gson.JsonObject().apply { add("rules",com.google.gson.JsonParser.parseString(com.v2ray.ang.util.JsonUtil.toJson(rules))) })
        }
        val validator=newCoreController(object: CoreCallbackHandler {
            override fun startup(): Long=0
            override fun shutdown(): Long=0
            override fun onEmitStatus(l: Long,s: String?): Long=0
        })
        try { validator.startLoop(config.toString(),0) } finally { validator.stopLoop() }
    }

    fun reconcileBrowserDialer(dialerAddr: String) {
        try {
            Libv2ray.reconcileBrowserDialer(dialerAddr)
            LogUtil.i(AppConfig.TAG, "Browser dialer reconciled successfully with address: $dialerAddr")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to reconcile browser dialer with address: $dialerAddr", e)
        }
    }


    /**
     * Get V2Ray core version.
     *
     * @return Version string of the V2Ray core
     */
    fun getLibVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to check V2Ray version", e)
            "Unknown"
        }
    }

    /**
     * Measure outbound connection delay.
     *
     * @param config The configuration JSON string
     * @param testUrl The URL to test against
     * @return Delay in milliseconds, or -1 if test failed
     */
    fun measureOutboundDelay(config: String, testUrl: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config, testUrl)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to measure outbound delay", e)
            -1L
        }
    }

    /**
     * Create a new core controller instance.
     *
     * @param handler The callback handler for core events
     * @return A new CoreController instance
     */
    fun newCoreController(handler: CoreCallbackHandler): CoreController {
        return try {
            Libv2ray.newCoreController(handler)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to create core controller", e)
            throw e
        }
    }
}
