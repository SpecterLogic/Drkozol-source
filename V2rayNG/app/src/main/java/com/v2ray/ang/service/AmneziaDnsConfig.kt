package com.v2ray.ang.service

/** Replace only Interface DNS; preserve keys, endpoints, AWG attributes and peer section order. */
internal fun amneziaDnsConfig(raw: String, servers: List<String>): String {
    if (servers.isEmpty()) return raw
    val lines = raw.lines().toMutableList()
    val interfaceIndex = lines.indexOfFirst { it.trim().equals("[Interface]", true) }
    if (interfaceIndex < 0) return raw
    val peerIndex = lines.indexOfFirst { it.trim().equals("[Peer]", true) }.takeIf { it >= 0 } ?: lines.size
    for (index in (interfaceIndex + 1 until peerIndex).reversed()) {
        if (lines[index].substringBefore('=').trim().equals("DNS", true)) lines.removeAt(index)
    }
    lines.add(interfaceIndex + 1, "DNS = " + servers.joinToString(", "))
    return lines.joinToString("\n")
}
