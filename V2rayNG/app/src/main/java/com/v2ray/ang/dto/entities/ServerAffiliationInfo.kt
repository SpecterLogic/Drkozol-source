package com.v2ray.ang.dto.entities

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    // Older releases stored an untyped result. Keep it for ordering, without calling it Real Delay.
    var realDelayMillis: Long? = null,
    var tcpDelayMillis: Long? = null,
    var favorite: Boolean = false,
    var probeHistory: List<com.v2ray.ang.handler.ProbeSample> = emptyList(),
)
