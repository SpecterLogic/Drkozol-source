package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.handler.ConfirmedLinkCommands
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkConsentDeviceTest {
    @Test fun consentTicketsCannotBeForgedOrReplayed() {
        val ticket=ConfirmedLinkCommands.issue("disconnect")
        assertNull(ConfirmedLinkCommands.consume("forged"))
        assertEquals("disconnect",ConfirmedLinkCommands.consume(ticket))
        assertNull(ConfirmedLinkCommands.consume(ticket))
    }

    @Test fun cryptPromptConcealsUrlAndSurvivesActivityRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val vector="incy://crypt1/AAECAwQFBgcICQoLNyIQL3rDwRZqnyoD8pGKSLXP6o8NdSXQVSSALNbbUyIr__tWGFUexdIfKvvmDnuDGbmBvuppfNef6aKNZUwOm4c-Sg"
        val intent=Intent(instrumentation.targetContext,UrlSchemeActivity::class.java).apply {
            action=Intent.ACTION_VIEW
            // Same wire format, own registered scheme.
            data=Uri.parse(vector.replace("incy://","drkozol://"))
        }
        val scenario=ActivityScenario.launch<UrlSchemeActivity>(intent)
        fun labels():String {
            fun visit(node:android.view.accessibility.AccessibilityNodeInfo?):String {
                if(node==null)return ""
                return node.text.toString()+" "+(0 until node.childCount).joinToString(" ") { visit(node.getChild(it)) }
            }
            return visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun checkPrompt() {
            val deadline=System.currentTimeMillis()+10_000
            while(!labels().contains("••••")) {
                check(System.currentTimeMillis()<deadline) { "Masked prompt not shown" }
                Thread.sleep(50)
            }
            assertFalse(labels().contains("https://"))
            assertFalse(labels().contains("test-vector"))
        }
        try { checkPrompt();scenario.recreate();checkPrompt() } finally { scenario.close() }
    }
}
