package com.v2ray.ang.dto.entities

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubscriptionUsageTest {
    @Test fun `reads real panel limits and remaining volume`() {
        val usage = SubscriptionUsage.parse("upload=100; download=200; total=1000; expire=200000", 100000, 100000)!!
        assertEquals(300L, usage.used)
        assertEquals(700L, usage.remaining)
        assertEquals(2L, usage.daysRemaining(100000))
        assertFalse(usage.locked(100000))
    }
    @Test fun `locks exactly at expiry and not one second before`() {
        val usage = SubscriptionUsage(expire = 200000)
        assertFalse(usage.locked(199999))
        assertTrue(usage.locked(200000))
        assertEquals(0L, usage.daysRemaining(200001))
    }
    @Test fun `locks when volume is exhausted and clamps remaining`() {
        val usage = SubscriptionUsage(upload = 100, download = 900, total = 1000)
        assertTrue(usage.locked(100))
        assertEquals(0L, usage.remaining)
        assertEquals(0L, usage.copy(download = 1100).remaining)
    }
    @Test fun `zero limits are unlimited and missing limits stay unknown`() {
        val usage = SubscriptionUsage.parse("upload=100; download=900; total=0; expire=0", 100)!!
        assertNull(usage.remaining)
        assertNull(usage.daysRemaining(100000))
        assertFalse(usage.locked(100000))
        assertNull(SubscriptionUsage.parse(null, 100))
        assertNull(SubscriptionUsage.parse("bad=value; expire=-1; total=oops", 100))
        assertNull(SubscriptionUsage(total = 1000).remaining)
    }
    @Test fun `overflow cannot unlock a depleted account`() {
        val usage = SubscriptionUsage(upload = Long.MAX_VALUE, download = 1, total = 100)
        assertEquals(Long.MAX_VALUE, usage.used)
        assertTrue(usage.exhausted())
    }
    @Test fun `server date prevents a slow device clock granting extra time`() {
        val usage = SubscriptionUsage(expire = 2000, checkedAt = 1000, serverTime = 1900)
        assertTrue(usage.expired(1100))
        assertFalse(usage.expired(1099))
    }
    @Test fun `renewed limits unlock expired account`() {
        val old = SubscriptionUsage(expire = 1000)
        assertTrue(old.locked(1000))
        val refreshed = SubscriptionUsage.parse("upload=0;download=0;total=100000;expire=100000", 1000)!!
        assertFalse(refreshed.locked(1000))
    }
    @Test fun `legacy subscription deserialization keeps new metadata absent`() {
        val legacy = com.google.gson.Gson().fromJson("""{"remarks":"Account","url":"https://panel.example/sub/test","enabled":true}""", SubscriptionItem::class.java)
        assertNull(legacy.usage)
        assertNull(legacy.renewalUrl)
        assertEquals("Account", legacy.remarks)
    }
}
