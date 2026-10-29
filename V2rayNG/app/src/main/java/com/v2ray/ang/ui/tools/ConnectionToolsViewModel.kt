package com.v2ray.ang.ui.tools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.*
import com.v2ray.ang.dto.entities.SubscriptionItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class ToolsServer(val guid: String, val title: String, val favorite: Boolean, val quality: ProbeQuality)
data class ToolsRoutingGroup(val id: String, val title: String, val names: List<String>, val active: String?)
enum class DiagnosticStep { DNS_OK, DNS_FAILED, TCP_OK, TCP_FAILED, TLS_OK, TLS_FAILED, NOT_APPLICABLE }
data class ConnectionToolsState(val servers: List<ToolsServer> = emptyList(), val loading: Boolean = true,
    val busyGuid: String? = null, val diagnostics: Map<String, List<DiagnosticStep>> = emptyMap(),
    val policy: String = "any", val error: Boolean = false, val warpImported: Boolean = false,
    val routing: List<ToolsRoutingGroup> = emptyList())
sealed interface ToolsAction {
    data class Favorite(val guid: String, val value: Boolean) : ToolsAction
    data class Diagnose(val guid: String) : ToolsAction
    data class Policy(val value: String) : ToolsAction
    data class ImportWarp(val text: String) : ToolsAction
    data class ActivateRouting(val group: String, val name: String?) : ToolsAction
}

/** Owns the tools screen's persisted edits and cancellable diagnostic work. */
class ConnectionToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ConnectionToolsState())
    val state = mutableState.asStateFlow()
    init { viewModelScope.launch { reload() } }

    fun onAction(action: ToolsAction) {
        if (mutableState.value.loading || mutableState.value.busyGuid != null) return
        when (action) {
            is ToolsAction.Diagnose -> diagnose(action.guid)
            else -> viewModelScope.launch {
                mutableState.update { it.copy(loading = true, error = false, warpImported = false) }
                try {
                    withContext(Dispatchers.IO) {
                        when (action) {
                            is ToolsAction.ActivateRouting -> {
                                check(HappRoutingStore.activate(action.group, action.name))
                            }
                            is ToolsAction.Favorite -> check(MmkvManager.setServerFavorite(action.guid, action.value))
                            is ToolsAction.Policy -> {
                                require(action.value in AutomaticNetworkPolicy.choices)
                                check(MmkvManager.encodeSettings(AutomaticNetworkPolicy.KEY, action.value))
                            }
                            is ToolsAction.ImportWarp -> {
                                val profile = WarpProfileImport.parse(action.text) ?: error("Invalid profile")
                                val group = java.util.UUID.randomUUID().toString()
                                try {
                                    MmkvManager.encodeSubscription(group, SubscriptionItem(remarks = "WARP", autoUpdate = false))
                                    profile.subscriptionId = group
                                    val guid = MmkvManager.encodeServerConfig("", profile)
                                    check(MmkvManager.decodeServerConfig(guid) != null)
                                } catch (failure: Exception) { MmkvManager.removeSubscription(group); throw failure }
                                mutableState.update { it.copy(warpImported = true) }
                            }
                            else -> Unit
                        }
                        SettingsChangeManager.makeSetupGroupTab()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    com.v2ray.ang.util.LogUtil.e(com.v2ray.ang.AppConfig.TAG, "Connection tools save failed", IllegalStateException(failure.javaClass.simpleName))
                    mutableState.update { it.copy(error = true) }
                } finally { reload() }
            }
        }
    }

    private suspend fun reload() {
        val (servers, policy) = withContext(Dispatchers.IO) {
            MmkvManager.decodeAllServerList().distinct().mapNotNull { guid ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                if (ProfileEligibility.isAccountInfo(profile)) return@mapNotNull null
                val affiliation = MmkvManager.decodeServerAffiliationInfo(guid)
                ToolsServer(guid, profile.remarks, affiliation?.favorite == true, probeQuality(affiliation?.probeHistory.orEmpty()))
            }.sortedWith(compareByDescending<ToolsServer> { it.favorite }.thenBy { it.title }) to
                (MmkvManager.decodeSettingsString(AutomaticNetworkPolicy.KEY) ?: "any")
        }
        val routing = withContext(Dispatchers.IO) { MmkvManager.decodeSubscriptions().mapNotNull { group ->
            val routing = HappRoutingStore.state(group.guid)
            if (routing.profiles.isEmpty()) null else ToolsRoutingGroup(group.guid, group.subscription.remarks, routing.profiles.map { it.name }, routing.active)
        } }
        mutableState.update { it.copy(servers = servers, policy = policy, loading = false, routing = routing) }
    }

    private fun diagnose(guid: String) {
        mutableState.update { it.copy(busyGuid = guid, diagnostics = it.diagnostics - guid, error = false) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val profile = MmkvManager.decodeServerConfig(guid) ?: error("Missing profile")
                    if (profile.amneziaConfig != null || profile.configType == com.v2ray.ang.enums.EConfigType.WIREGUARD ||
                        profile.configType == com.v2ray.ang.enums.EConfigType.HYSTERIA2 || profile.server.isNullOrBlank())
                        return@withContext listOf(DiagnosticStep.NOT_APPLICABLE)
                    val addresses = try { runInterruptible { InetAddress.getAllByName(profile.server) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { return@withContext listOf(DiagnosticStep.DNS_FAILED) }
                    ensureActive()
                    var reachable: InetAddress? = null
                    val connected = addresses.take(2).any { address ->
                        ensureActive()
                        try { Socket().use { it.connect(InetSocketAddress(address, profile.serverPort?.toIntOrNull() ?: 0), 4000) }; reachable = address; true }
                        catch (_: java.io.IOException) { false }
                    }
                    val results = mutableListOf(DiagnosticStep.DNS_OK, if (connected) DiagnosticStep.TCP_OK else DiagnosticStep.TCP_FAILED)
                    if (connected && profile.security.equals("tls", true) && profile.insecure != true) {
                        ensureActive()
                        val host = profile.sni?.takeIf { it.isNotBlank() } ?: profile.server.orEmpty()
                        val tlsWorks = try {
                            val raw = Socket()
                            try {
                                raw.connect(InetSocketAddress(reachable, profile.serverPort?.toIntOrNull() ?: 0), 4000)
                                val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                                (factory.createSocket(raw, host, profile.serverPort!!.toInt(), true) as javax.net.ssl.SSLSocket).use { tls ->
                                    tls.soTimeout = 5000
                                    tls.sslParameters = tls.sslParameters.apply {
                                        endpointIdentificationAlgorithm = "HTTPS"
                                        if (!host.contains(':') && !host.matches(Regex("[0-9.]+")))
                                            serverNames = listOf(javax.net.ssl.SNIHostName(host))
                                    }
                                    tls.startHandshake()
                                }
                                true
                            } finally { if (!raw.isClosed) raw.close() }
                        } catch (_: Exception) { false }
                        results += if (tlsWorks) DiagnosticStep.TLS_OK else DiagnosticStep.TLS_FAILED
                    }
                    results
                }
                mutableState.update { it.copy(diagnostics = it.diagnostics + (guid to result)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                com.v2ray.ang.util.LogUtil.e(com.v2ray.ang.AppConfig.TAG, "Connection diagnostic failed for $guid", IllegalStateException(failure.javaClass.simpleName))
                mutableState.update { it.copy(error = true) }
            } finally { mutableState.update { it.copy(busyGuid = null) } }
        }
    }
}
