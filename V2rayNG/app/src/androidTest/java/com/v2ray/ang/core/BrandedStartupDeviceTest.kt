package com.v2ray.ang.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.Utils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrandedStartupDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun selectedBalancerSavesOnlyChosenMembersAndSurvivesRepositoryReopening() {
        val group = UUID.randomUUID().toString()
        val passwords = List(3) { UUID.randomUUID().toString() }
        MmkvManager.encodeSubscription(group, SubscriptionItem(remarks = "Selected fixture", autoUpdate = false))
        try {
            SettingsManager.initAssets(context, context.assets)
            val ids = passwords.mapIndexed { index, password ->
                MmkvManager.encodeServerConfig("", ProfileItem.create(EConfigType.VLESS).apply {
                    subscriptionId = group; remarks = "Peer $index"; server = "example.com"; serverPort = "443"
                    this.password = password; method = "none"; security = "tls"
                })
            }
            val app = context.applicationContext as com.v2ray.ang.AngApplication
            var repository = com.v2ray.ang.ui.main.MainRepository(app)
            assertNull(repository.createSelectedBalancer(group, emptyList()))
            assertNull(repository.createSelectedBalancer(group, listOf(ids[0])))
            assertNull(repository.createSelectedBalancer(group, listOf(ids[0], "removed")))
            assertNull(repository.createSelectedBalancer(group, listOf(ids[0], ids[0])))
            val guid = repository.createSelectedBalancer(group, listOf(ids[0], ids[2]))!!
            repository.close()
            repository = com.v2ray.ang.ui.main.MainRepository(app)
            try {
                val profile = repository.decodeServerConfig(guid)!!
                assertEquals(listOf(ids[0], ids[2]), profile.policyGroupServerIds)
                val generated = CoreConfigManager.getV2rayConfig(context, guid)
                assertTrue(generated.errorMessage, generated.status)
                assertTrue(generated.content.contains(passwords[0]))
                assertTrue(generated.content.contains(passwords[2]))
                assertFalse(generated.content.contains(passwords[1]))
                assertEquals(guid, repository.createSelectedBalancer(group, listOf(ids[1], ids[2])))
                assertEquals(listOf(ids[1], ids[2]), repository.decodeServerConfig(guid)!!.policyGroupServerIds)
            } finally { repository.close() }
        } finally { MmkvManager.removeSubscription(group) }
    }

    @Test fun anotherAppsHttpPortDoesNotPreventNativeStartupOrRepeatStartup() {
        val previousMode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)
        val previousPort = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT)
        val previousDynamic = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
        val group = UUID.randomUUID().toString()
        val controller = CoreNativeManager.newCoreController(object : libv2ray.CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(l: Long, s: String?): Long = 0
        })
        try {
            SettingsManager.initAssets(context, context.assets)
            CoreNativeManager.initCoreEnv(context)
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, "PROXY")
            MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
            val port = Utils.findRandomFreePort()
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PORT, port.toString())
            MmkvManager.encodeSubscription(group, SubscriptionItem(remarks = "Startup fixture", autoUpdate = false))
            val guid = MmkvManager.encodeServerConfig("", ProfileItem.create(EConfigType.VLESS).apply {
                subscriptionId = group; server = "127.0.0.1"; serverPort = "9"
                password = "00000000-0000-4000-8000-000000000001"; method = "none"
            })
            ServerSocket(10809, 4, InetAddress.getByName("127.0.0.1")).use {
                val generated = CoreConfigManager.getV2rayConfig(context, guid)
                assertTrue(generated.errorMessage, generated.status)
                val listeners = JsonParser.parseString(generated.content).asJsonObject["inbounds"].asJsonArray
                assertFalse(listeners.any { it.asJsonObject.get("port")?.asInt == 10809 })
                assertEquals(port, SettingsManager.getHttpPort())
                repeat(3) {
                    controller.startLoop(generated.content, 0)
                    assertTrue(controller.isRunning)
                    Socket("127.0.0.1", port).use { socket -> assertTrue(socket.isConnected) }
                    controller.stopLoop()
                    assertFalse(controller.isRunning)
                }
            }
        } finally {
            if (controller.isRunning) controller.stopLoop()
            MmkvManager.removeSubscription(group)
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, previousMode)
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PORT, previousPort)
            MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, previousDynamic)
        }
    }
}
