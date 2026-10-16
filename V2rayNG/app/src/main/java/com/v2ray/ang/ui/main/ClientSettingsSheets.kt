package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.insetSelection
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.ClientOption
import com.v2ray.ang.handler.ClientPreferences

/** Stable Dialog-based bottom sheet, with one scroll/IME/inset owner for every form control. */
@Composable
internal fun ClientSheet(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val height = LocalConfiguration.current.screenHeightDp.dp
    val feedback = LocalButtonFeedback.current
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            // The dialog window fills the display: dismiss from the backdrop, not from the form.
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = stringResource(R.string.dr_dismiss),
                onClick = { feedback(); dismiss() }))
            Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().heightIn(max = height * .9f)) {
                Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(38.dp, 4.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .4f), RoundedCornerShape(3.dp)))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { feedback(); dismiss() }) {
                            Icon(painterResource(R.drawable.dr_dismiss), stringResource(R.string.dr_dismiss))
                        }
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun PreferenceToggle(title: String, value: Boolean, change: (Boolean) -> Unit) {
    val feedback = LocalButtonFeedback.current
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .toggleable(value, role = Role.Switch, onValueChange = { feedback(); change(it) })
        .insetSelection(value, RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(value, onCheckedChange = null)
    }
}

@Composable
internal fun ClientPreferencesSheet(panel: String, state: MainUiState, onAction: (MainAction) -> Unit, dismiss: () -> Unit) {
    val feedback = LocalButtonFeedback.current
    var choices by rememberSaveable(panel) { mutableStateOf(state.preferences.options.mapKeys { it.key.name }) }
    var minutes by rememberSaveable(panel) { mutableLongStateOf(state.preferences.updateMinutes) }
    var pingUrl by rememberSaveable(panel) { mutableStateOf(state.preferences.pingUrl) }
    var userAgent by rememberSaveable(panel) { mutableStateOf(state.preferences.userAgent) }
    var socksUser by rememberSaveable(panel) { mutableStateOf(state.preferences.socksUser) }
    var socksPassword by rememberSaveable(panel) { mutableStateOf(state.preferences.socksPassword) }
    var icon by rememberSaveable(panel) { mutableStateOf(state.preferences.icon) }
    var submittedRevision by rememberSaveable(panel) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.settingsSave, state.settingsSaveInFlight, submittedRevision) {
        if (state.settingsSave.completes("preferences", submittedRevision)) {
            submittedRevision = null
            if (state.settingsSave.successful) dismiss()
        } else if (state.settingsSaveInFlight == null) {
            submittedRevision = null // A restored form cannot keep waiting for a destroyed ViewModel.
        }
    }
    val options = when (panel) {
        "providers" -> listOf(ClientOption.AUTO_UPDATE, ClientOption.NOTIFY_UPDATE, ClientOption.UPDATE_LAUNCH,
            ClientOption.PING_LAUNCH, ClientOption.PING_UPDATE, ClientOption.HWID)
        "connection" -> listOf(ClientOption.AUTO_CONNECT, ClientOption.AUTO_BOOT, ClientOption.ALLOW_LAN,
            ClientOption.HOTSPOT, ClientOption.ROUTE_LAN)
        "performance" -> listOf(ClientOption.DISCONNECT_LOCK, ClientOption.RECONNECT_UNLOCK, ClientOption.MEMORY_MONITOR)
        "appearance" -> listOf(ClientOption.VIBRATION)
        else -> emptyList()
    }
    ClientSheet(stringResource(when (panel) {
        "providers" -> R.string.dr_provider_settings
        "connection" -> R.string.dr_connection
        "performance" -> R.string.dr_performance
        "appearance" -> R.string.dr_app_icon
        else -> R.string.dr_ping_settings
    }), dismiss) {
        options.forEach { option ->
            key(option.name) {
                PreferenceToggle(stringResource(clientOptionLabel(option)), choices[option.name] ?: option.default) {
                    choices = choices + (option.name to it)
                }
            }
        }
        if (panel == "connection") {
            OutlinedButton(onClick = { onAction(MainAction.OpenSystemVpn) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dr_kill_switch))
            }
            Text(stringResource(R.string.dr_kill_switch_help), style = MaterialTheme.typography.bodySmall)
            if (choices[ClientOption.HOTSPOT.name] == true) {
                Text(stringResource(R.string.dr_hotspot_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(socksUser, { socksUser = it }, label = { Text(stringResource(R.string.dr_proxy_user)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(socksPassword, { socksPassword = it }, label = { Text(stringResource(R.string.dr_proxy_password)) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.dr_next_connection), style = MaterialTheme.typography.bodySmall)
        }
        if (panel == "providers") {
            Text(stringResource(R.string.dr_update_interval), style = MaterialTheme.typography.titleSmall)
            IntervalChoices(minutes) { minutes = it }
            Text(stringResource(R.string.dr_update_schedule_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(userAgent, { userAgent = it }, label = { Text(stringResource(R.string.dr_user_agent)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (panel == "ping") {
            Text(stringResource(R.string.dr_ping_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(pingUrl, { pingUrl = it }, label = { Text(stringResource(R.string.dr_ping_url)) }, modifier = Modifier.fillMaxWidth())
        }
        if (panel == "appearance") {
            listOf("default" to R.string.dr_icon_default, "ocean" to R.string.dr_palette_ocean, "sakura" to R.string.dr_palette_japan).forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().selectable(icon == id, role = Role.RadioButton, onClick = { feedback(); icon = id })
                    .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(icon == id, onClick = null)
                    Text(stringResource(label))
                }
            }
        }
        if (state.preferencesError) Text(stringResource(R.string.dr_settings_invalid), color = MaterialTheme.colorScheme.error)
        Button(enabled = submittedRevision == null, onClick = {
            submittedRevision = state.settingsSave.revision
            onAction(MainAction.SavePreferences(ClientPreferences(
                choices.mapKeys { ClientOption.valueOf(it.key) }, minutes, pingUrl, userAgent, socksUser, socksPassword, icon)))
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dr_save)) }
    }
}

@Composable
internal fun ProviderSettingsSheet(id: String, item: SubscriptionItem, state: MainUiState, onAction: (MainAction) -> Unit, dismiss: () -> Unit) {
    var submittedRevision by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.settingsSave, state.settingsSaveInFlight, submittedRevision) {
        if (state.settingsSave.completes(id, submittedRevision)) {
            submittedRevision = null
            if (state.settingsSave.successful) dismiss()
        } else if (state.settingsSaveInFlight == null) {
            submittedRevision = null
        }
    }
    var name by rememberSaveable(id) { mutableStateOf(item.remarks.orEmpty()) }
    var visible by rememberSaveable(id) { mutableStateOf(item.showOnHome) }
    var enabled by rememberSaveable(id) { mutableStateOf(item.autoUpdate) }
    var customInterval by rememberSaveable(id) { mutableStateOf(item.customUpdateInterval) }
    var interval by rememberSaveable(id) { mutableLongStateOf(item.updateInterval) }
    var pingUrl by rememberSaveable(id) { mutableStateOf(item.delayTestUrl.orEmpty()) }
    var dns by rememberSaveable(id) { mutableStateOf(item.tunnelDns.orEmpty()) }
    var display by rememberSaveable(id) { mutableStateOf(item.displayDelayAs) }
    var order by rememberSaveable(id) { mutableStateOf(item.serverListOrder) }
    var style by rememberSaveable(id) { mutableStateOf(item.connectionStyle) }
    var method by rememberSaveable(id) { mutableStateOf(item.probeMethod) }
    ClientSheet(stringResource(R.string.dr_provider_settings) + " · " + item.remarks, dismiss) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.dr_name)) }, modifier = Modifier.fillMaxWidth())
        PreferenceToggle(stringResource(R.string.dr_show_home), visible) { visible = it }
        PreferenceToggle(stringResource(R.string.dr_auto_update), enabled) { enabled = it }
        PreferenceToggle(stringResource(R.string.dr_custom_interval), customInterval) { customInterval = it }
        if (customInterval) IntervalChoices(interval) { interval = it }
        ChoiceButtons(R.string.dr_display_method, display, listOf("time" to R.string.dr_time_ms, "status" to R.string.dr_status)) { display = it }
        ChoiceButtons(R.string.dr_ping_settings, method, listOf("real" to R.string.dr_real_delay, "tcp" to R.string.dr_tcp_ping)) { method = it }
        ChoiceButtons(R.string.dr_server_order, order, listOf("ping" to R.string.dr_by_ping, "name" to R.string.dr_by_name)) { order = it }
        ChoiceButtons(R.string.dr_connection_style, style, listOf("classic" to R.string.dr_classic, "minimal" to R.string.dr_minimal)) { style = it }
        OutlinedTextField(pingUrl, { pingUrl = it }, label = { Text(stringResource(R.string.dr_ping_url)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dns, { dns = it }, label = { Text(stringResource(R.string.dr_tunnel_dns)) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.dr_provider_dns_help), style = MaterialTheme.typography.bodySmall)
        if (state.preferencesError) Text(stringResource(R.string.dr_settings_invalid), color = MaterialTheme.colorScheme.error)
        Button(enabled = submittedRevision == null, onClick = {
            submittedRevision = state.settingsSave.revision
            onAction(MainAction.SaveProvider(id, item.copy(remarks = name.trim(), showOnHome = visible,
                autoUpdate = enabled, customUpdateInterval = customInterval, updateInterval = interval,
                delayTestUrl = pingUrl.trim().ifEmpty { null }, tunnelDns = dns.trim().ifEmpty { null },
                displayDelayAs = display, serverListOrder = order, connectionStyle = style, probeMethod = method)))
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dr_save)) }
    }
}

@Composable
private fun IntervalChoices(minutes: Long, select: (Long) -> Unit) {
    val feedback = LocalButtonFeedback.current
    listOf(listOf(30L, 60L, 120L), listOf(360L, 720L, 1440L)).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { value ->
                FilterChip(minutes == value, onClick = { feedback(); select(value) }, modifier = Modifier.weight(1f)
                    .insetSelection(minutes == value, RoundedCornerShape(12.dp), MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary),
                    colors = FilterChipDefaults.filterChipColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, selectedContainerColor = androidx.compose.ui.graphics.Color.Transparent),
                    label = { Text(if (value >= 60) stringResource(R.string.dr_hours, value / 60) else stringResource(R.string.dr_interval_minutes, value)) })
            }
        }
    }
}

@Composable
private fun ChoiceButtons(title: Int, value: String, options: List<Pair<String, Int>>, select: (String) -> Unit) {
    val feedback = LocalButtonFeedback.current
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) -> FilterChip(value == id, onClick = { feedback(); select(id) },
            modifier = Modifier.insetSelection(value == id, RoundedCornerShape(12.dp), MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary),
            colors = FilterChipDefaults.filterChipColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, selectedContainerColor = androidx.compose.ui.graphics.Color.Transparent),
            label = { Text(stringResource(label)) }) }
    }
}

private fun clientOptionLabel(option: ClientOption): Int = when (option) {
    ClientOption.AUTO_CONNECT -> R.string.dr_auto_connect
    ClientOption.AUTO_BOOT -> R.string.dr_auto_boot
    ClientOption.AUTO_UPDATE -> R.string.dr_auto_update
    ClientOption.NOTIFY_UPDATE -> R.string.dr_notify_update
    ClientOption.UPDATE_LAUNCH -> R.string.dr_update_launch
    ClientOption.PING_LAUNCH -> R.string.dr_ping_launch
    ClientOption.PING_UPDATE -> R.string.dr_ping_update
    ClientOption.HWID -> R.string.dr_send_hwid
    ClientOption.VIBRATION -> R.string.dr_vibration
    ClientOption.DISCONNECT_LOCK -> R.string.dr_disconnect_lock
    ClientOption.RECONNECT_UNLOCK -> R.string.dr_reconnect_unlock
    ClientOption.MEMORY_MONITOR -> R.string.dr_memory_monitor
    ClientOption.HOTSPOT -> R.string.dr_hotspot
    ClientOption.ALLOW_LAN -> R.string.dr_allow_lan
    ClientOption.ROUTE_LAN -> R.string.dr_route_lan
}
