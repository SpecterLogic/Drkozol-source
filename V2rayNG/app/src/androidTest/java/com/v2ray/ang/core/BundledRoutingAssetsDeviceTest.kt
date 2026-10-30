package com.v2ray.ang.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.Utils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** Run on an isolated emulator after installation and again after `pm clear`. */
@RunWith(AndroidJUnit4::class)
class BundledRoutingAssetsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)

    @Test fun liveSubscriptionProfilesAfterFreshInstall() {
        val url = InstrumentationRegistry.getArguments().getString("testSubscription")
        org.junit.Assume.assumeTrue("Private live fixture is optional", !url.isNullOrBlank())
        val group = java.util.UUID.randomUUID().toString()
        try {
            SettingsManager.initAssets(context, context.assets)
            CoreNativeManager.initCoreEnv(context)
            val connection = java.net.URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
            val response = connection.getInputStream().use { it.bufferedReader().readText() }
            com.v2ray.ang.handler.MmkvManager.encodeSubscription(group,
                com.v2ray.ang.dto.entities.SubscriptionItem(remarks = "Fresh-install live fixture", autoUpdate = false))
            assertTrue(com.v2ray.ang.handler.AngConfigManager.importBatchConfig(response, group, false).first > 0)
            val candidates = com.v2ray.ang.handler.MmkvManager.decodeServerList(group).filter { guid ->
                val profile = com.v2ray.ang.handler.MmkvManager.decodeServerConfig(guid)
                profile?.configType == com.v2ray.ang.enums.EConfigType.VLESS && profile.network == "xhttp"
            }.take(3)
            assertTrue("Live fixture contains VLESS XHTTP profiles", candidates.isNotEmpty())
            val delays = candidates.map { guid ->
                val config = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
                assertTrue("Fresh-install profile configuration builds", config.status)
                CoreNativeManager.measureOutboundDelay(config.content, "https://www.gstatic.com/generate_204")
            }
            println("Live fixture successful profiles: ${delays.count { it >= 0 }}/${delays.size}")
            assertTrue("At least one real subscription tunnel returns HTTP successfully", delays.any { it >= 0 })
        } finally { com.v2ray.ang.handler.MmkvManager.removeSubscription(group) }
    }

    @Test fun firstMainScreenInstallsAssetsWithoutOpeningAssetSettings() {
        val directory = File(Utils.userAssetPath(context))
        names.forEach { File(directory, it).let { file -> if (file.exists()) assertTrue(file.delete()) } }
        androidx.test.core.app.ActivityScenario.launch(com.v2ray.ang.ui.main.MainActivity::class.java).use {
            val deadline = System.currentTimeMillis() + 20_000
            while (names.any { File(directory, it).length() == 0L }) {
                assertTrue("Main startup installs all routing files", System.currentTimeMillis() < deadline)
                Thread.sleep(50)
            }
        }
    }

    @Test fun freshStorageAndRemovedFilesAreRestoredFromTheApk() {
        val directory = File(Utils.userAssetPath(context))
        repeat(2) {
            names.forEach { File(directory, it).let { file -> if (file.exists()) assertTrue(file.delete()) } }
            SettingsManager.initAssets(context, context.assets)
            names.forEach { name ->
                val bundled = context.assets.open(name).use { it.readBytes() }
                assertTrue("Bundled routing asset $name is nonempty", bundled.isNotEmpty())
                assertArrayEquals("Installed routing asset $name", bundled, File(directory, name).readBytes())
            }
        }
    }

    @Test fun realNativeProbeLoadsDefaultGoogleAndPrivateRoutingCategories() {
        SettingsManager.initAssets(context, context.assets)
        CoreNativeManager.initCoreEnv(context)
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val stopped = AtomicBoolean(false)
            val responder = Thread {
                while (!stopped.get()) {
                    val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                    socket.use {
                        it.soTimeout = 3000
                        val reader = it.getInputStream().bufferedReader()
                        while (reader.readLine()?.takeIf(String::isNotEmpty) != null) { }
                        it.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                val config = """{"log":{"loglevel":"none"},"inbounds":[],"outbounds":[{"tag":"direct","protocol":"freedom","settings":{}}],"routing":{"rules":[{"type":"field","domain":["geosite:google"],"outboundTag":"direct"},{"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}}"""
                val delay = CoreNativeManager.measureOutboundDelay(config, "http://127.0.0.1:${server.localPort}/generate_204")
                assertTrue("Xray must load bundled geodata and complete a real HTTP request", delay >= 0)
            } finally {
                stopped.set(true); server.close(); responder.join(4000)
                assertFalse("Local probe responder stopped", responder.isAlive)
            }
        }
    }
}
