package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProbeQualityTest {
    @Test fun failuresAndJitterAreSeparateMeasurements() {
        val quality = probeQuality(listOf(100L, 140L, -1L, 300L, 320L).map { ProbeSample(it, 1) })
        assertEquals(5, quality.attempts)
        assertEquals(20, quality.failurePercent)
        assertEquals(215L, quality.averageMillis)
        assertEquals(30L, quality.jitterMillis)
        assertNull(probeQuality(listOf(ProbeSample(-1, 1))).averageMillis)
        assertNull(probeQuality(emptyList()).jitterMillis)
    }

    @Test fun oldAffiliationRecordsKeepTheirMeasurements() {
        val old = com.google.gson.Gson().fromJson("""{"testDelayMillis":42,"realDelayMillis":42}""",
            com.v2ray.ang.dto.entities.ServerAffiliationInfo::class.java)
        assertEquals(42L, old.realDelayMillis)
        assertFalse(old.favorite)
        assertEquals(0, probeQuality(old.probeHistory.orEmpty()).attempts)
        assertEquals(20, probeQuality((1..30).map { ProbeSample(it.toLong(), 1) }).attempts)
    }
}
