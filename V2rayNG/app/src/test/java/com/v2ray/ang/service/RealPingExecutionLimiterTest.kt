package com.v2ray.ang.service

import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealPingExecutionLimiterTest {

    @Test
    fun nativeProbeOwnsSuccessFailureAndSlowPathResults() = runBlocking {
        for (delay in listOf(1400L, 8000L, -1L)) {
            var called = false
            assertEquals(delay, RealPingExecutionLimiter.measure(EConfigType.VLESS, { "prepared" }) {
                called = true
                assertEquals("prepared", it)
                delay
            })
            assertTrue(called)
        }
    }

    @Test
    fun invalidConfigurationNeverStartsNativeProbe() = runBlocking {
        for (configuration in listOf(null, "", " ")) {
            var called = false
            assertEquals(-1L, RealPingExecutionLimiter.measure(EConfigType.VLESS, { configuration }) {
                called = true
                10L
            })
            assertFalse(called)
        }
    }

    @Test
    fun customConfigMeasurementsAreSerializedAcrossWorkers() {
        runBlocking {
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)

            List(8) {
                async(Dispatchers.Default) {
                    RealPingExecutionLimiter.run(EConfigType.CUSTOM) {
                        val current = active.incrementAndGet()
                        maxActive.accumulateAndGet(current, ::maxOf)
                        Thread.sleep(20)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()

            assertEquals(1, maxActive.get())
        }
    }

    @Test
    fun generatedConfigMeasurementsRemainConcurrent() {
        runBlocking {
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)

            val jobs = List(2) {
                async(Dispatchers.Default) {
                    RealPingExecutionLimiter.run(EConfigType.VMESS) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            }

            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
            jobs.awaitAll()
        }
    }
}
