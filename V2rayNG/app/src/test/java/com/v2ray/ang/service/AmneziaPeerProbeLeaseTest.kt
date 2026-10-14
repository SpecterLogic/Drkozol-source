package com.v2ray.ang.service

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class AmneziaPeerProbeLeaseTest {
    @Test fun probesSharingPeerIdentityNeverOverlap() {
        runBlocking {
            val active = AtomicInteger()
            val peak = AtomicInteger()
            List(6) {
                async(Dispatchers.Default) {
                    AmneziaProbe.withPeerProbeLease {
                        peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        try { delay(15) } finally { active.decrementAndGet() }
                    }
                }
            }.awaitAll()
            assertEquals(1, peak.get())
            assertEquals(0, active.get())
        }
    }

    @Test fun cancelledWaiterCannotStartASecondPeerAndLeaseIsReusable() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var cancelledPeerStarted = false
            val holder = launch {
                AmneziaProbe.withPeerProbeLease { entered.complete(Unit); release.await() }
            }
            entered.await()
            val waiter = launch { AmneziaProbe.withPeerProbeLease { cancelledPeerStarted = true } }
            yield(); waiter.cancelAndJoin(); release.complete(Unit); holder.join()
            assertFalse(cancelledPeerStarted)
            assertEquals("ready", AmneziaProbe.withPeerProbeLease { "ready" })
        }
    }
}
