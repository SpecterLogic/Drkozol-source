package com.v2ray.ang.dto.entities

/** Panel totals are authoritative; session traffic must never be subtracted from them twice. */
data class SubscriptionUsage(
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
    val checkedAt: Long = 0,
    val serverTime: Long = 0,
) {
    val used: Long?
        get() = if (upload == null || download == null) null else
            if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download
    val remaining: Long?
        get() = total?.takeIf { it > 0 }?.let { limit -> used?.let { (limit - it).coerceAtLeast(0) } }

    fun effectiveNow(deviceSeconds: Long): Long =
        if (serverTime > 0 && checkedAt > 0) maxOf(deviceSeconds, serverTime + (deviceSeconds - checkedAt).coerceAtLeast(0))
        else deviceSeconds

    fun expired(deviceSeconds: Long): Boolean = expire?.let { it > 0 && effectiveNow(deviceSeconds) >= it } == true
    fun exhausted(): Boolean = total?.let { it > 0 && used != null && used!! >= it } == true
    fun locked(deviceSeconds: Long): Boolean = expired(deviceSeconds) || exhausted()
    fun daysRemaining(deviceSeconds: Long): Long? = expire?.takeIf { it > 0 }?.let {
        val seconds = (it - effectiveNow(deviceSeconds)).coerceAtLeast(0)
        seconds / 86400 + if (seconds % 86400 > 0) 1 else 0
    }

    companion object {
        fun parse(header: String?, checkedAt: Long, serverTime: Long = 0): SubscriptionUsage? {
            if (header.isNullOrBlank()) return null
            val fields = header.split(';').mapNotNull { field ->
                val parts = field.trim().split('=', limit = 2)
                if (parts.size != 2) null else parts[1].trim().toLongOrNull()?.takeIf { it >= 0 }
                    ?.let { parts[0].trim().lowercase() to it }
            }.toMap()
            if (fields.keys.none { it in setOf("upload", "download", "total", "expire") }) return null
            return SubscriptionUsage(fields["upload"], fields["download"], fields["total"], fields["expire"], checkedAt, serverTime)
        }
    }
}
