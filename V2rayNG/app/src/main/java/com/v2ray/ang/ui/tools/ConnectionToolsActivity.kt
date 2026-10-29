package com.v2ray.ang.ui.tools

import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class ConnectionToolsActivity : BaseComponentActivity() {
    private val viewModel: ConnectionToolsViewModel by viewModels()

    @Composable override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        ConnectionToolsScreen(state, viewModel::onAction) { finish() }
    }
}

@Composable internal fun ConnectionToolsScreen(state: ConnectionToolsState, action: (ToolsAction) -> Unit, back: () -> Unit) {
    // Secret input is deliberately not saved into activity saved-state bundles.
    var warpText by remember { mutableStateOf("") }
    var warpExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.warpImported) { if (state.warpImported) { warpText = ""; warpExpanded = false } }
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.dr_connection_tools), onBackClick = back,
        isLoading = state.loading || state.busyGuid != null) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.dr_auto_network), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dr_auto_network_help), style = MaterialTheme.typography.bodySmall)
                listOf("any" to R.string.dr_network_any, "wifi" to R.string.dr_network_wifi,
                    "mobile" to R.string.dr_network_mobile, "unmetered" to R.string.dr_network_unmetered).forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.policy == id, role = Role.RadioButton,
                        enabled = !state.loading && state.busyGuid == null, onClick = { action(ToolsAction.Policy(id)) })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.policy == id, onClick = null)
                        Text(stringResource(label), Modifier.weight(1f))
                    }
                }
            }
            item {
                TextButton(onClick = { warpExpanded = !warpExpanded }) { Text(stringResource(R.string.dr_warp_import)) }
                if (warpExpanded) {
                    Text(stringResource(R.string.dr_warp_import_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(warpText, { if (it.length <= 65_536) warpText = it },
                        modifier = Modifier.fillMaxWidth(), enabled = !state.loading, minLines = 3,
                        visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.dr_warp_import)) })
                    Button(onClick = { action(ToolsAction.ImportWarp(warpText)) },
                        enabled = !state.loading && state.busyGuid == null && warpText.isNotBlank()) { Text(stringResource(R.string.dr_add)) }
                }
                if (state.warpImported) Text(stringResource(R.string.dr_tools_saved), color = MaterialTheme.colorScheme.primary)
                if (state.error) Text(stringResource(R.string.dr_tools_error), color = MaterialTheme.colorScheme.error)
            }
            item {
                if (state.routing.isNotEmpty()) {
                    Text(stringResource(R.string.dr_routing_profiles), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.dr_routing_reconnect), style = MaterialTheme.typography.bodySmall)
                    state.routing.forEach { group -> key(group.id) {
                        Text(group.title, style = MaterialTheme.typography.titleSmall)
                        (listOf(null) + group.names).forEach { name ->
                            val label = name ?: stringResource(R.string.dr_routing_off)
                            Row(Modifier.fillMaxWidth().selectable(selected = name == group.active, role = Role.RadioButton,
                                enabled = !state.loading && state.busyGuid == null,
                                onClick = { action(ToolsAction.ActivateRouting(group.id, name)) }).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = name == group.active, onClick = null)
                                Text(label, Modifier.weight(1f))
                            }
                        }
                    } }
                }
            }
            item {
                Text(stringResource(R.string.dr_quality_history), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dr_quality_help), style = MaterialTheme.typography.bodySmall)
                if (!state.loading && state.servers.isEmpty()) Text(stringResource(R.string.dr_empty))
            }
            items(state.servers, key = { it.guid }) { server ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val favoriteName = stringResource(R.string.dr_favorite_server, server.title)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(server.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Checkbox(server.favorite, onCheckedChange = { action(ToolsAction.Favorite(server.guid, it)) },
                                enabled = !state.loading && state.busyGuid == null,
                                modifier = Modifier.semantics { contentDescription = favoriteName })
                        }
                        val quality = server.quality
                        Text(if (quality.attempts == 0) stringResource(R.string.dr_quality_no_samples) else
                            stringResource(R.string.dr_quality_summary, quality.attempts, quality.failurePercent,
                                quality.averageMillis?.toString() ?: "—", quality.jitterMillis?.toString() ?: "—"),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { action(ToolsAction.Diagnose(server.guid)) },
                            enabled = !state.loading && state.busyGuid == null) { Text(stringResource(R.string.dr_basic_diagnostic)) }
                        state.diagnostics[server.guid].orEmpty().forEach { step ->
                            Text(stringResource(when (step) {
                                DiagnosticStep.DNS_OK -> R.string.dr_diagnostic_dns_ok
                                DiagnosticStep.DNS_FAILED -> R.string.dr_diagnostic_dns_failed
                                DiagnosticStep.TCP_OK -> R.string.dr_diagnostic_tcp_ok
                                DiagnosticStep.TCP_FAILED -> R.string.dr_diagnostic_tcp_failed
                                DiagnosticStep.TLS_OK -> R.string.dr_diagnostic_tls_ok
                                DiagnosticStep.TLS_FAILED -> R.string.dr_diagnostic_tls_failed
                                DiagnosticStep.NOT_APPLICABLE -> R.string.dr_diagnostic_udp
                            }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
