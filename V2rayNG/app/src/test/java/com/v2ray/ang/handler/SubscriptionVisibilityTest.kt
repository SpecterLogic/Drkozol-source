package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubscriptionVisibilityTest {
    private val builtIn = AppConfig.DEFAULT_SUBSCRIPTION_ID

    @Test fun emptyBuiltInBucketIsAbsentAndUserCreatedGroupsRemainVisible() {
        val empty = SubscriptionItem(remarks = "Default")
        assertFalse(SubscriptionVisibility.shouldDisplay(builtIn, empty, 0))
        assertTrue(SubscriptionVisibility.shouldDisplay("user-created", empty, 0))
        assertTrue(SubscriptionVisibility.shouldDisplay(builtIn, empty, 1))
        assertTrue(SubscriptionVisibility.shouldDisplay(builtIn, empty.copy(url = "https://example.org/sub"), 0))
    }

    @Test fun priorSerializedManualProfilesKeepTheirGroupAndCustomNames() {
        val legacy = Gson().fromJson("""{"remarks":"Default","url":"","enabled":true}""", SubscriptionItem::class.java)
        assertTrue(SubscriptionVisibility.shouldDisplay(builtIn, legacy, 2))
        assertEquals("Servers", SubscriptionVisibility.displayRemarks(builtIn, legacy, "Servers"))
        assertEquals("Default", SubscriptionVisibility.displayRemarks("user-created", legacy, "Servers"))
        assertEquals("My group", SubscriptionVisibility.displayRemarks(builtIn, legacy.copy(remarks = "My group"), "Servers"))
        assertEquals("Default", legacy.remarks)
    }

    @Test fun legacyNullFieldsAreHandledAsAnEmptyInternalBucket() {
        val legacy = Gson().fromJson("""{"remarks":null,"url":null}""", SubscriptionItem::class.java)
        assertFalse(SubscriptionVisibility.shouldDisplay(builtIn, legacy, 0))
        assertTrue(SubscriptionVisibility.shouldDisplay(builtIn, legacy, 1))
        assertEquals("Servers", SubscriptionVisibility.displayRemarks(builtIn, legacy, "Servers"))
        assertEquals("", SubscriptionVisibility.displayRemarks("user-created", legacy, "Servers"))
    }
}
