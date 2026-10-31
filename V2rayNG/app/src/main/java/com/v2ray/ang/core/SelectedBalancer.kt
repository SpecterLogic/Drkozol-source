package com.v2ray.ang.core

/** Null is the preceding release's all-server format; empty is explicitly no members. */
internal fun selectedBalancerIds(selected: List<String>?, available: List<String>): List<String> =
    if (selected == null) available else selected.distinct().filter { it in available }

/** Keep related authentication/media requests off per-connection round robin. Existing
 * direct/block rules remain ahead of proxy rules; only selected member tags are eligible.
 * Health-based switching can still change IP when a peer fails, and cannot unban server IPs.
 */
internal fun protectSelectedBalancerSessions(config: com.v2ray.ang.dto.V2rayConfig) {
    val main = config.routing.balancers?.firstOrNull { it.tag == com.v2ray.ang.AppConfig.TAG_BALANCER } ?: return
    val tag = main.tag + "-session"
    if (config.routing.balancers.orEmpty().any { it.tag == tag }) return
    val session = main.copy(tag = tag,
        strategy = com.v2ray.ang.dto.V2rayConfig.RoutingBean.StrategyObject(type = "leastPing"))
    config.routing.balancers = config.routing.balancers.orEmpty() + session
    if(config.observatory==null) config.observatory=com.v2ray.ang.dto.V2rayConfig.ObservatoryObject(
        subjectSelector=main.selector, probeUrl=com.v2ray.ang.AppConfig.DELAY_TEST_URL, probeInterval="15s", enableConcurrency=true)
    val domains = listOf("openai.com", "chatgpt.com", "auth0.com", "oaistatic.com", "oaiusercontent.com",
        "anthropic.com", "claude.ai", "instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net")
    val index = config.routing.rules.indexOfFirst { it.balancerTag == main.tag && it.port != "53" }
        .takeIf { it >= 0 } ?: config.routing.rules.size
    config.routing.rules.add(index, com.v2ray.ang.dto.V2rayConfig.RoutingBean.RulesBean(
        domain = domains.map { "domain:" + it }, balancerTag = tag))
}
