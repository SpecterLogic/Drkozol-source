package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainImportMenuTest {

    @Test
    fun onboardingQrUsesSubscriptionValidationAndReadyScreenKeepsConfigImports() {
        val subscription = "https://example.org/sub/test"
        assertEquals(MainAction.SubmitSubscription(subscription), qrImportAction(subscription, false))
        for (invalid in listOf("", "not a link", "http://example.org/sub", "vless://example.org")) {
            assertEquals(MainAction.SubmitSubscription(invalid), qrImportAction(invalid, false))
            assertEquals(false, validSubscriptionUrl(invalid))
        }
        assertEquals(MainAction.ImportBatchConfig(subscription), qrImportAction(subscription, true))
        assertEquals(MainAction.ImportBatchConfig("vless://example.org"), qrImportAction("vless://example.org", true))
    }

    @Test
    fun regularShareMenuContainsOnlyShareActions() {
        val expected = listOf(
            ServerMenuAction.ShareQRCode,
            ServerMenuAction.ShareClipboard,
            ServerMenuAction.ShareFullContent,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = false, includeManagementActions = false))
    }

    @Test
    fun regularMoreMenuContainsEveryActionInDisplayOrder() {
        assertEquals(
            ServerMenuAction.entries,
            serverMenuActions(isComplexProfile = false, includeManagementActions = true),
        )
    }

    @Test
    fun complexShareMenuContainsOnlyFullContent() {
        assertEquals(
            listOf(ServerMenuAction.ShareFullContent),
            serverMenuActions(isComplexProfile = true, includeManagementActions = false),
        )
    }

    @Test
    fun complexMoreMenuRetainsManagementActions() {
        val expected = listOf(
            ServerMenuAction.ShareFullContent,
            ServerMenuAction.Edit,
            ServerMenuAction.Delete,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = true, includeManagementActions = true))
    }
}
