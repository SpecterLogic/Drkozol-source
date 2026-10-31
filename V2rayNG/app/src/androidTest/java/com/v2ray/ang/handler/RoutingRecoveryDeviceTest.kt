package com.v2ray.ang.handler

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.Utils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoutingRecoveryDeviceTest {
    @Test fun remoteRenameReplacesBoundSourceAndRetainsActiveSelection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val group=UUID.randomUUID().toString()
        MmkvManager.encodeSubscription(group,SubscriptionItem(remarks="Remote fixture",autoUpdate=false))
        val link="drkozol://autorouting/add/https://assets.example/routing.json"
        try {
            for(name in listOf("Before","After")) {
                assertTrue(HappRoutingStore.install(context,group,link) { _, destination ->
                    destination.writeText("""{"Name":"$name","DirectSites":["domain:example.com"]}""")
                })
            }
            val current=HappRoutingStore.state(group)
            assertEquals("After",current.active)
            assertEquals(1,current.profiles.size)
            assertTrue(current.profiles.single().autoRefresh)
            assertEquals("https://assets.example/routing.json",current.profiles.single().sourceUrl)
            assertTrue(current.attempts.isEmpty())
        } finally { MmkvManager.removeSubscription(group) }
    }

    @Test fun invalidAssetRetainsActiveRulesAndRetryCommitsTogether() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val group = UUID.randomUUID().toString()
        MmkvManager.encodeSubscription(group, SubscriptionItem(remarks="Recovery fixture", autoUpdate=false))
        SettingsManager.initAssets(context,context.assets)
        fun link(json:String) = "drkozol://routing/onadd/" + Base64.getEncoder().encodeToString(json.toByteArray())
        val first=link("""{"Name":"Recovery","DirectSites":["domain:example.com"]}""")
        val update=link("""{"Name":"Recovery","DirectSites":["geosite:google"],"Geositeurl":"https://assets.example/geosite.dat"}""")
        val created=mutableListOf<File>()
        try {
            assertTrue(HappRoutingStore.install(context,group,first))
            val previous=HappRoutingStore.state(group)
            assertFalse(HappRoutingStore.install(context,group,update) { _, destination ->
                created += destination
                destination.writeText("corrupt fixture")
            })
            val failed=HappRoutingStore.state(group)
            assertEquals(previous.profiles,failed.profiles)
            assertEquals(previous.active,failed.active)
            assertEquals("failed",failed.attempts.values.single().state)
            assertFalse(created.single().exists())
            assertTrue(HappRoutingStore.install(context,group,update) { _, destination ->
                created += destination
                File(Utils.userAssetPath(context),"geosite.dat").copyTo(destination,true)
            })
            val recovered=HappRoutingStore.state(group)
            assertEquals("Recovery",recovered.active)
            assertEquals(1,recovered.profiles.size)
            assertTrue(recovered.attempts.isEmpty())
            assertTrue(File(Utils.userAssetPath(context),recovered.profiles.single().assetFiles.getValue("geosite")).isFile)
        } finally {
            created.forEach { it.delete() }
            MmkvManager.removeSubscription(group)
        }
    }
}
