package com.v2ray.ang.handler

data class ProbeSample(val delayMillis: Long, val measuredAt: Long)
data class ProbeQuality(val attempts: Int, val successes: Int, val averageMillis: Long?, val jitterMillis: Long?) {
    val failurePercent: Int get() = if (attempts == 0) 0 else (attempts - successes) * 100 / attempts
}

/** Summarizes application-probe history, never labels failed HTTP probes as packet loss. */
fun probeQuality(samples: List<ProbeSample>): ProbeQuality {
    val recent = samples.takeLast(20).filter { it.delayMillis != 0L }
    val healthy = recent.filter { it.delayMillis > 0 }.map { it.delayMillis.coerceAtMost(120_000) }
    val variations = recent.zipWithNext().filter { it.first.delayMillis > 0 && it.second.delayMillis > 0 }
        .map { kotlin.math.abs(it.first.delayMillis.coerceAtMost(120_000) - it.second.delayMillis.coerceAtMost(120_000)) }
    return ProbeQuality(recent.size, healthy.size, healthy.takeIf { it.isNotEmpty() }?.average()?.toLong(),
        variations.takeIf { it.isNotEmpty() }?.average()?.toLong())
}
