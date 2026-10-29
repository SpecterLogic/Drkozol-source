package com.v2ray.ang.handler

import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import java.util.Base64

data class HappRoutingProfile(val name: String, val rules: List<RulesetItem>, val globalProxy: Boolean,
    val domainStrategy: String, val remoteDns: String, val domesticDns: String, val fakeDns: Boolean,
    val hosts: Map<String, String>, val geoipUrl: String?, val geositeUrl: String?,
    val assetFiles: Map<String, String> = emptyMap())
data class HappRoutingCommand(val profile: HappRoutingProfile? = null, val activate: Boolean = false, val off: Boolean = false)
data class HappRoutingState(val profiles: List<HappRoutingProfile> = emptyList(), val active: String? = null)

object HappRoutingParser {
    fun isLink(value: String): Boolean = value.trim().let {
        it.startsWith("happ://routing/add/") || it.startsWith("happ://routing/onadd/") || it == "happ://routing/off"
    }

    fun parse(value: String): HappRoutingCommand? = runCatching {
        val link = value.trim()
        require(link.length <= 1_000_000 && isLink(link))
        if (link == "happ://routing/off") return@runCatching HappRoutingCommand(off = true)
        val encoded = link.substringAfter("happ://routing/").substringAfter('/')
        val json = String(Base64.getDecoder().decode(encoded.replace('-', '+').replace('_', '/')), Charsets.UTF_8)
        val root = JsonParser.parseString(json).asJsonObject
        fun text(key: String, default: String = "") = root[key]?.takeUnless { it.isJsonNull }?.asString ?: default
        fun flag(key: String, default: Boolean) = when (text(key, default.toString()).lowercase()) {
            "true" -> true; "false" -> false; else -> error("Invalid flag")
        }
        fun values(key: String): List<String> = root[key]?.asJsonArray?.map { it.asString }?.also { list ->
            require(list.size <= 5000 && list.all { it.isNotBlank() && it.length <= 1024 && '\n' !in it && '\r' !in it })
        }.orEmpty()
        fun dns(prefix: String, fallback: String): String = when (text(prefix + "DNSType", "DoH").lowercase()) {
            "doh" -> text(prefix + "DNSDomain", fallback).also { require(validProbeUrl(it)) }
            "dou" -> text(prefix + "DNSIP").also { require(it.isNotBlank() && validDnsOverride(it) && ',' !in it) }
            else -> error("Unsupported DNS transport")
        }
        val rules = mutableListOf<RulesetItem>()
        for ((prefix, tag) in listOf("Block" to AppConfig.TAG_BLOCKED, "Direct" to AppConfig.TAG_DIRECT, "Proxy" to AppConfig.TAG_PROXY)) {
            val sites = values(prefix + "Sites"); val ips = values(prefix + "Ip")
            if (sites.isNotEmpty()) rules += RulesetItem(domain = sites, outboundTag = tag)
            if (ips.isNotEmpty()) rules += RulesetItem(ip = ips, outboundTag = tag)
        }
        val name = text("Name", "Happ").also { require(it.isNotBlank() && it.length <= 128) }
        val strategy = text("DomainStrategy", "IPIfNonMatch").also { require(it in setOf("AsIs", "IPIfNonMatch", "IPOnDemand")) }
        val hosts = root["DnsHosts"]?.asJsonObject?.entrySet()?.associate { (key, value) ->
            require(key.isNotBlank() && key.length <= 253 && value.isJsonPrimitive && value.asString.length <= 1024)
            key to value.asString
        }.orEmpty().also { require(it.size <= 1000) }
        fun asset(key: String) = text(key).takeIf { it.isNotBlank() }?.also { require(validProbeUrl(it)) }
        HappRoutingCommand(HappRoutingProfile(name, rules, flag("GlobalProxy", true), strategy,
            dns("Remote", "https://cloudflare-dns.com/dns-query"), dns("Domestic", "https://dns.google/dns-query"),
            flag("FakeDNS", false), hosts, asset("Geoipurl"), asset("Geositeurl")),
            activate = link.startsWith("happ://routing/onadd/"))
    }.getOrNull()
}
