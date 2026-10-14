package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SubscriptionImportSchedulingTest {
    @Test fun firstParsedUrlRegistersWorkEvenWhenItsDirectProfileCountIsZero() {
        var schedules = 0
        val imported = 0 to 1
        assertSame(imported, SubscriptionUpdater.afterSubscriptionImport(imported) { schedules++ })
        assertEquals(1, schedules)
    }

    @Test fun multipleUrlsUseOneIndexSyncAndProfileOnlyOrFailedImportsDoNotSchedule() {
        var schedules = 0
        val imported = 0 to 2
        assertSame(imported, SubscriptionUpdater.afterSubscriptionImport(imported) { schedules++ })
        assertEquals(1, schedules)
        listOf(0 to 0, 3 to 0, 0 to -1).forEach { result ->
            assertSame(result, SubscriptionUpdater.afterSubscriptionImport(result) { schedules++ })
        }
        assertEquals(1, schedules)
    }
}
