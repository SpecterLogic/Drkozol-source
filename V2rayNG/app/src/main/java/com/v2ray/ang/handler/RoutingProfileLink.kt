package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.RulesetItem
import java.util.Base64

/** Portable routing rules use the existing export schema, never executable core JSON. */
object RoutingProfileLink {
    private const val PREFIX = "drkozol://routing/add/"

    fun isLink(value: String): Boolean = value.trim().startsWith(PREFIX) || HappRoutingParser.isLink(value)

    fun parse(value: String): List<RulesetItem>? = try {
        val input = value.trim()
        require(input.length <= 1_000_000)
        val json = if (isLink(input)) String(Base64.getUrlDecoder().decode(input.removePrefix(PREFIX)), Charsets.UTF_8) else input
        val rules = Gson().fromJson(json, Array<RulesetItem>::class.java).toList()
        require(rules.isNotEmpty() && rules.size <= 5000)
        require(rules.all { rule ->
            rule.outboundTag.isNotBlank() && rule.outboundTag.length <= 128 &&
                (listOfNotNull(rule.domain, rule.ip, rule.process, rule.protocol).any { it.isNotEmpty() } ||
                    !rule.port.isNullOrBlank() || !rule.network.isNullOrBlank()) &&
                listOfNotNull(rule.domain, rule.ip, rule.process, rule.protocol).flatten().all { it.isNotBlank() && it.length <= 1024 }
        })
        rules.map { it.copy(id = java.util.UUID.randomUUID().toString(), locked = false) }
    } catch (_: Exception) { null }

    fun export(rules: List<RulesetItem>): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Gson().toJson(rules).toByteArray(Charsets.UTF_8))
}
