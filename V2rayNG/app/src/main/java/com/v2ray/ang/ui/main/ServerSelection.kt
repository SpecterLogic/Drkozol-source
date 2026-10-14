package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import java.net.URI

enum class ServerCategory { ALL, VLESS, VMESS, TROJAN, SHADOWSOCKS, WIREGUARD, AMNEZIA, OTHER }
enum class ProbeKind { TCP, REAL }

internal fun serverCategory(profile: ProfileItem): ServerCategory {
    if (profile.amneziaConfig != null) return ServerCategory.AMNEZIA
    return when (profile.configType.name.uppercase()) {
        "VLESS" -> ServerCategory.VLESS
        "VMESS" -> ServerCategory.VMESS
        "TROJAN" -> ServerCategory.TROJAN
        "SHADOWSOCKS" -> ServerCategory.SHADOWSOCKS
        "WIREGUARD" -> ServerCategory.WIREGUARD
        else -> ServerCategory.OTHER
    }
}

internal fun validSubscriptionUrl(value: String): Boolean = try {
    val uri = URI(value.trim())
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
} catch (_: Exception) { false }

/** Real HTTP delay is preferred; TCP is a fallback, never a bandwidth promise. */
internal fun fastestServerGuid(guids: Set<String>, real: Map<String, Long>, tcp: Map<String, Long>): String? {
    fun best(results: Map<String, Long>) = results.entries
        .filter { it.key in guids && it.value > 0 }
        .minWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })?.key
    return best(real) ?: best(tcp)
}

internal fun MainUiState.withProbeResult(guid: String, delay: Long, kind: ProbeKind): MainUiState =
    if (kind == ProbeKind.TCP) copy(tcpResults = tcpResults + (guid to delay))
    else copy(realResults = realResults + (guid to delay))

internal fun MainUiState.resetProbeResults(guids: Set<String>, kind: ProbeKind): MainUiState =
    if (kind == ProbeKind.TCP) copy(tcpResults = tcpResults.filterKeys { it !in guids })
    else copy(realResults = realResults.filterKeys { it !in guids })

/** Healthy measurements first, then pending entries, then failed probes; ties remain stable. */
internal fun probeOrder(delay: Long?): Pair<Int, Long> = when {
    delay == null || delay == 0L -> 1 to Long.MAX_VALUE
    delay < 0 -> 2 to Long.MAX_VALUE
    else -> 0 to delay
}

internal fun sortedServerRows(rows: List<ServerRowUiModel>, results: Map<String, Long>): List<ServerRowUiModel> =
    rows.sortedWith(compareBy<ServerRowUiModel> { probeOrder(results[it.guid]).first }
        .thenBy { probeOrder(results[it.guid]).second })
