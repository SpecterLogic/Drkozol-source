package com.v2ray.ang.handler

internal data class RemoteRoutingSource(val url: String, val automatic: Boolean, val activate: Boolean)

internal fun remoteRoutingSource(link: String): RemoteRoutingSource? {
    val match = Regex("(?:happ|drkozol|incy)://(autorouting|routing)/(add|onadd)/(.+)").matchEntire(link.trim()) ?: return null
    val payload = match.groupValues[3]
    if (!payload.startsWith("https://")) {
        require(match.groupValues[1] != "autorouting")
        return null
    }
    require(validProbeUrl(payload) && payload.length <= 8192)
    return RemoteRoutingSource(payload, match.groupValues[1] == "autorouting", match.groupValues[2] == "onadd")
}

/** Owns only periodic routing refresh; subscription refresh and native sessions are untouched. */
class RoutingRefreshWorker(context: android.content.Context, params: androidx.work.WorkerParameters) :
    androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val success = MmkvManager.decodeSubsList().map { HappRoutingStore.refresh(applicationContext, it) }.all { it }
        if (success || runAttemptCount >= 3) Result.success() else Result.retry()
    }
}
