package com.v2ray.ang.ui.tools

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.net.ServerSocket
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class ConnectionToolsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "Tools state deadline" }; Thread.sleep(25) }
    }

    @Test fun toolsRadioActionsAndSecretFormSurviveRecreation() {
        val previous = MmkvManager.decodeSettingsString(AutomaticNetworkPolicy.KEY) ?: "any"
        val strings = AppLocaleManager.localizedContext(context)
        val scenario = androidx.test.core.app.ActivityScenario.launch(ConnectionToolsActivity::class.java)
        fun find(text: String): android.view.accessibility.AccessibilityNodeInfo? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
                return null
            }
            return visit(root)
        }
        fun control(text: String): android.view.accessibility.AccessibilityNodeInfo? {
            var node = find(text)
            while (node != null && !node.isClickable && !node.isCheckable) node = node.parent
            return node
        }
        try {
            val wifi = strings.getString(com.v2ray.ang.R.string.dr_network_wifi)
            waitFor { control(wifi)?.isEnabled == true }
            val bounds = android.graphics.Rect(); control(wifi)!!.getBoundsInScreen(bounds)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action,
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            waitFor { MmkvManager.decodeSettingsString(AutomaticNetworkPolicy.KEY) == "wifi" }
            val mobile = strings.getString(com.v2ray.ang.R.string.dr_network_mobile)
            waitFor { control(mobile)?.isEnabled == true }
            // Android 15 stays in touch mode after the tap; a real D-pad event enters
            // keyboard mode before requesting input focus on a Compose virtual node.
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_DPAD_DOWN").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            waitFor { control(mobile)?.let { it.isFocused || it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS) } == true }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_DPAD_CENTER").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            waitFor { MmkvManager.decodeSettingsString(AutomaticNetworkPolicy.KEY) == "mobile" }
            scenario.recreate()
            waitFor { control(mobile)?.let { it.isChecked || it.isSelected } == true }
            val warp = strings.getString(com.v2ray.ang.R.string.dr_warp_import)
            assertTrue(control(warp)!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            fun editable(root: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (root == null) return null
                if (root.isEditable) return root
                for (index in 0 until root.childCount) editable(root.getChild(index))?.let { return it }
                return null
            }
            waitFor { editable(instrumentation.uiAutomation.rootInActiveWindow) != null }
            val field = editable(instrumentation.uiAutomation.rootInActiveWindow)!!
            assertTrue(field.isPassword)
            assertTrue(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "not a real profile") }))
            scenario.recreate()
            waitFor { editable(instrumentation.uiAutomation.rootInActiveWindow) != null }
            assertTrue(editable(instrumentation.uiAutomation.rootInActiveWindow)!!.text.isNullOrBlank())
        } finally { scenario.close(); MmkvManager.encodeSettings(AutomaticNetworkPolicy.KEY, previous) }
    }

    @Test fun favoritesQualityDiagnosticsAndNetworkPolicyPersistThroughTheirOwner() {
        val group = UUID.randomUUID().toString()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val previousPolicy = MmkvManager.decodeSettingsString(AutomaticNetworkPolicy.KEY) ?: "any"
        val originalGroups = MmkvManager.decodeSubsList().toSet()
        var vm: ConnectionToolsViewModel? = null
        val server = ServerSocket(0)
        try {
            MmkvManager.encodeSubscription(group, SubscriptionItem(remarks = "Device tools fixture", autoUpdate = false))
            val profile = ProfileItem.create(EConfigType.VLESS).apply {
                subscriptionId = group; remarks = "Device tools fixture"; this.server = "127.0.0.1"
                serverPort = server.localPort.toString(); password = "00000000-0000-4000-8000-000000000001"
            }
            val guid = MmkvManager.encodeServerConfig("", profile)
            MmkvManager.encodeServerTestDelayMillis(guid, 100, false)
            MmkvManager.encodeServerTestDelayMillis(guid, -1, false)
            instrumentation.runOnMainSync { vm = ViewModelProvider(owner,
                ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[ConnectionToolsViewModel::class.java] }
            val model = vm!!
            waitFor { !model.state.value.loading }
            assertEquals(50, model.state.value.servers.first { it.guid == guid }.quality.failurePercent)
            instrumentation.runOnMainSync { model.onAction(ToolsAction.Favorite(guid, true)) }
            waitFor { !model.state.value.loading && model.state.value.servers.any { it.guid == guid && it.favorite } }
            assertTrue(MmkvManager.decodeServerAffiliationInfo(guid)!!.favorite)
            instrumentation.runOnMainSync { model.onAction(ToolsAction.Policy("mobile")) }
            waitFor { !model.state.value.loading && model.state.value.policy == "mobile" }
            instrumentation.runOnMainSync { model.onAction(ToolsAction.Policy("unsupported")) }
            waitFor { !model.state.value.loading && model.state.value.error }
            assertEquals("mobile", MmkvManager.decodeSettingsString(AutomaticNetworkPolicy.KEY))
            instrumentation.runOnMainSync { model.onAction(ToolsAction.Diagnose(guid)) }
            waitFor { model.state.value.busyGuid == null && model.state.value.diagnostics[guid] != null }
            assertEquals(listOf(DiagnosticStep.DNS_OK, DiagnosticStep.TCP_OK), model.state.value.diagnostics[guid])
            MmkvManager.encodeServerConfig(guid, profile.copy(security = "tls", sni = "localhost"))
            instrumentation.runOnMainSync { model.onAction(ToolsAction.Diagnose(guid)) }
            waitFor { model.state.value.busyGuid == null && model.state.value.diagnostics[guid] != null }
            assertEquals(listOf(DiagnosticStep.DNS_OK, DiagnosticStep.TCP_OK, DiagnosticStep.TLS_FAILED), model.state.value.diagnostics[guid])
            MmkvManager.encodeServerConfig(guid, profile)
            server.close()
            instrumentation.runOnMainSync { model.onAction(ToolsAction.Diagnose(guid)) }
            waitFor { model.state.value.busyGuid == null && model.state.value.diagnostics[guid] != null }
            assertEquals(listOf(DiagnosticStep.DNS_OK, DiagnosticStep.TCP_FAILED), model.state.value.diagnostics[guid])
            instrumentation.runOnMainSync { model.onAction(ToolsAction.Diagnose("missing-fixture")) }
            waitFor { model.state.value.busyGuid == null && model.state.value.error }
            instrumentation.runOnMainSync { model.onAction(ToolsAction.ImportWarp("invalid")) }
            waitFor { !model.state.value.loading && model.state.value.error }
            assertEquals(originalGroups + group, MmkvManager.decodeSubsList().toSet())
            val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
            val warp = "[Interface]\nPrivateKey = $key\nAddress = 172.16.0.2/32\n[Peer]\nPublicKey = $key\nAllowedIPs = 0.0.0.0/0\nEndpoint = 127.0.0.1:2408"
            val selection = MmkvManager.getSelectServer()
            instrumentation.runOnMainSync { model.onAction(ToolsAction.ImportWarp(warp)) }
            waitFor { !model.state.value.loading && model.state.value.warpImported }
            assertEquals(selection, MmkvManager.getSelectServer())
        } finally {
            server.close()
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
            MmkvManager.encodeSettings(AutomaticNetworkPolicy.KEY, previousPolicy)
            MmkvManager.decodeSubsList().filter { it !in originalGroups }.forEach(MmkvManager::removeSubscription)
        }
    }

    @Test fun encryptedBackupRejectsWrongPasswordBeforeChangingNativeStorage() {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val key = "dr_tools_backup_device_fixture"
        val previous = MmkvManager.decodeSettingsString(key).orEmpty()
        val temporary = mutableListOf<java.io.File>()
        lateinit var model: com.v2ray.ang.ui.backup.BackupViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(owner,
            ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[com.v2ray.ang.ui.backup.BackupViewModel::class.java] }
        try {
            MmkvManager.encodeSettings(key, "before-backup")
            model.setArchivePassword("device-fixture-password")
            val event = kotlinx.coroutines.runBlocking {
                val pending = async(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(30_000) {
                        model.viewModelEvent.first { it is com.v2ray.ang.ui.backup.BackupViewModel.BackupViewModelEvent.ShareFile }
                    }
                }
                instrumentation.runOnMainSync { model.shareBackup(context.cacheDir, "DeviceFixture") }
                pending.await() as com.v2ray.ang.ui.backup.BackupViewModel.BackupViewModelEvent.ShareFile
            }
            val protected = java.io.File(event.filePath).also(temporary::add)
            assertTrue(BackupArchiveCodec.encrypted(protected.inputStream().use { input -> ByteArray(20).also { input.read(it) } }))
            MmkvManager.encodeSettings(key, "after-backup")
            val wrong = java.io.File(context.cacheDir, "device-wrong-password.backup").also { protected.copyTo(it, overwrite = true); temporary += it }
            model.setArchivePassword("incorrect-password")
            instrumentation.runOnMainSync { model.restoreConfiguration(context.cacheDir, wrong) }
            waitFor { !model.isLoading.value }
            assertEquals("after-backup", MmkvManager.decodeSettingsString(key))
            val correct = java.io.File(context.cacheDir, "device-correct-password.backup").also { protected.copyTo(it, overwrite = true); temporary += it }
            model.setArchivePassword("device-fixture-password")
            kotlinx.coroutines.runBlocking {
                val restored = async(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(30_000) {
                        model.viewModelEvent.first { it is com.v2ray.ang.ui.backup.BackupViewModel.BackupViewModelEvent.RestoreSuccess }
                    }
                }
                instrumentation.runOnMainSync { model.restoreConfiguration(context.cacheDir, correct) }
                restored.await()
            }
            waitFor { !model.isLoading.value }
            assertEquals("before-backup", MmkvManager.decodeSettingsString(key))
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
            MmkvManager.encodeSettings(key, previous)
            temporary.forEach { it.delete() }
        }
    }

    @Test fun happProfilesRemainIsolatedAndAppearInGeneratedNativeConfig() {
        val group = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
        try {
            MmkvManager.encodeSubscription(group, SubscriptionItem(remarks = "Routing fixture", autoUpdate = false))
            MmkvManager.encodeSubscription(second, SubscriptionItem(remarks = "Isolated fixture", autoUpdate = false))
            fun profile(id: String) = MmkvManager.encodeServerConfig("", ProfileItem.create(EConfigType.VLESS).apply {
                subscriptionId = id; server = "127.0.0.1"; serverPort = "9"
                password = "00000000-0000-4000-8000-000000000001"
            })
            val guid = profile(group); val other = profile(second)
            val json = """{"Name":"Fixture","DomainStrategy":"AsIs","BlockSites":["domain:blocked.example"],"DirectSites":["domain:direct.example"],"GlobalProxy":"false","RemoteDNSType":"DoU","RemoteDNSIP":"9.9.9.9","DnsHosts":{"fixture.example":"192.0.2.1"}}"""
            val link = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(json.toByteArray())
            val staleProvider = MmkvManager.decodeSubscription(group)!!
            assertTrue(HappRoutingStore.install(context, group, link))
            MmkvManager.encodeSubscription(group, staleProvider.copy(remarks = "Updated provider metadata"))
            assertNotNull(HappRoutingStore.active(guid)); assertNull(HappRoutingStore.active(other))
            assertEquals(listOf("9.9.9.9"), SettingsManager.getRemoteDnsServers(guid))
            val config = com.v2ray.ang.core.CoreConfigManager.getV2rayConfig(context, guid)
            assertTrue("Native configuration generation", config.status)
            val native = com.google.gson.JsonParser.parseString(config.content).asJsonObject
            assertEquals("AsIs", native["routing"].asJsonObject["domainStrategy"].asString)
            assertTrue(config.content.contains("domain:blocked.example"))
            assertTrue(config.content.contains("fixture.example"))
            assertTrue(HappRoutingStore.install(context, group, "happ://routing/off"))
            assertNull(HappRoutingStore.active(guid))
            assertFalse(HappRoutingStore.install(context, group, "happ://routing/add/invalid"))
        } finally { MmkvManager.removeSubscription(group); MmkvManager.removeSubscription(second) }
    }
}
