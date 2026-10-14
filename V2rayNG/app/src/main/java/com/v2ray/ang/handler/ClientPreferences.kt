package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import java.net.URI

/** Stable MMKV keys shared by UI, scheduled refreshes and connection owners. */
enum class ClientOption(val key: String, val default: Boolean = false) {
    AUTO_CONNECT("dr_auto_connect"), AUTO_BOOT(AppConfig.PREF_IS_BOOTED),
    AUTO_UPDATE("dr_auto_update", true), NOTIFY_UPDATE("dr_notify_update"),
    UPDATE_LAUNCH("dr_update_launch"), PING_LAUNCH("dr_ping_launch"),
    PING_UPDATE(AppConfig.PREF_AUTO_TEST_AFTER_UPDATE_SUBSCRIPTION, true),
    HWID("dr_send_hwid"), VIBRATION("dr_vibration", true),
    DISCONNECT_LOCK("dr_disconnect_lock"), RECONNECT_UNLOCK("dr_reconnect_unlock"),
    MEMORY_MONITOR("dr_memory_monitor"), HOTSPOT(AppConfig.PREF_PROXY_SHARING),
    ALLOW_LAN("dr_allow_lan", true), ROUTE_LAN("dr_route_lan"),
}

data class ClientPreferences(
    val options: Map<ClientOption, Boolean> = ClientOption.entries.associateWith { it.default },
    val updateMinutes: Long = DEFAULT_AUTO_UPDATE_MINUTES,
    val pingUrl: String = AppConfig.DELAY_TEST_URL,
    val userAgent: String = "DrKozol/Android",
    val socksUser: String = "",
    val socksPassword: String = "",
    val icon: String = "default",
    val languageCode: String = "auto",
) {
    fun enabled(option: ClientOption): Boolean = options[option] ?: option.default
}

internal const val DEFAULT_AUTO_UPDATE_MINUTES = 720L

/** The preceding global default was two hours. Explicit non-default intervals stay intact. */
internal fun refreshIntervalUpgrade(previous: String?): Long? =
    if (previous == null || previous.toLongOrNull() == 120L) DEFAULT_AUTO_UPDATE_MINUTES else null

/** Automatic native batches belong to quiet charging time; user-requested tests are separate. */
internal fun canRunAutomaticProbe(interactive: Boolean, charging: Boolean, vpnActive: Boolean): Boolean =
    !interactive && charging && !vpnActive

internal fun validProbeUrl(value: String): Boolean = try {
    val uri = URI(value.trim())
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
} catch (_: Exception) { false }

internal fun effectiveUpdateMinutes(custom: Boolean, provider: Long, global: Long): Long =
    (if (custom) provider else global).coerceIn(15, 43200)

internal fun validDnsOverride(value: String): Boolean {
    if (value.isBlank() || validProbeUrl(value)) return true
    return value.split(',').all { field ->
        val address = field.trim()
        if (':' in address) try {
            URI("https://[$address]").host != null
        } catch (_: Exception) { false }
        else address.split('.').let { octets -> octets.size == 4 && octets.all { it.toIntOrNull()?.let { number -> number in 0..255 } == true && it.all(Char::isDigit) } }
    }
}
