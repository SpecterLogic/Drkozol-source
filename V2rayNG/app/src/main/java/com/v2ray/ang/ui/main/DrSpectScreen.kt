package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.DrKozolPalette
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.compose.insetSelection
import com.v2ray.ang.ui.compose.paletteColorScheme
import java.text.NumberFormat
import java.text.DateFormat
import java.util.Date

@Composable
fun DrSpectScreen(mainViewModel: MainViewModel, dispatchAction: (MainAction) -> Unit, dispatchNavigation: (MainDestination) -> Unit) {
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val view = androidx.compose.ui.platform.LocalView.current
    val lastFeedback = remember { longArrayOf(0L) }
    val feedback: () -> Unit = {
        val now = android.os.SystemClock.uptimeMillis()
        if (state.preferences.enabled(com.v2ray.ang.handler.ClientOption.VIBRATION) && now - lastFeedback[0] >= 80L) {
            lastFeedback[0] = now
            // App actions use touch feedback, not the independently configured keyboard channel.
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    val onAction: (MainAction) -> Unit = { action ->
        if (action !is MainAction.Search) feedback()
        dispatchAction(action)
    }
    val onNavigate: (MainDestination) -> Unit = { destination -> feedback(); dispatchNavigation(destination) }
    val savedMessage = stringResource(R.string.dr_saved)
    val saveSnackbar = remember { SnackbarHostState() }
    // Start from the current result after recreation; do not replay an old save or retain a dead ViewModel revision.
    var observedSaveRevision by remember { mutableLongStateOf(state.settingsSave.revision) }
    LaunchedEffect(state.settingsSave.revision) {
        if (state.settingsSave.revision > observedSaveRevision) {
            observedSaveRevision = state.settingsSave.revision
            saveSnackbar.currentSnackbarData?.dismiss()
            if (state.settingsSave.successful) saveSnackbar.showSnackbar(savedMessage)
        }
    }
    val loading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    val palette by ThemeManager.palette.collectAsStateWithLifecycle()
    val themeMode by ThemeManager.themeMode.collectAsStateWithLifecycle()
    val dynamicColors by ThemeManager.dynamicColorEnabled.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    androidx.activity.compose.BackHandler(enabled = tab != 0) { tab = 0 }
    var importing by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var themeDialog by rememberSaveable { mutableStateOf(false) }
    var languageDialog by rememberSaveable { mutableStateOf(false) }
    var settingsPanel by rememberSaveable { mutableStateOf<String?>(null) }
    var urlSchemes by rememberSaveable { mutableStateOf(false) }
    var resetDialog by rememberSaveable { mutableStateOf(false) }
    val homeScroll = rememberScrollState()
    val serverScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val pageStates = rememberSaveableStateHolder()
    // Reapply restored input through its owner when Android recreates the process/ViewModel.
    LaunchedEffect(search, tab) { onAction(MainAction.Search(if (tab == 1) search else "")) }
    val paletteColors = palette.colors(dark)
    val colors = if (dynamicColors) MaterialTheme.colorScheme else paletteColorScheme(palette, dark)
    val ink = colors.onSurface
    val muted = colors.onSurfaceVariant
    val glass = colors.surface
    val accent = colors.primary
    val border = accent.copy(alpha = .25f)
    val powerLabel = stringResource(if (state.isRunning) R.string.dr_disconnect else R.string.dr_connect)
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableStateOf(84.dp) }
    val generousLayout = configuration.fontScale > 1.15f || configuration.screenWidthDp < 360
    val compact = configuration.screenHeightDp < 740
    val powerSize = (configuration.screenWidthDp * if (compact) .47f else .5f).coerceIn(168f, 214f).dp
    CompositionLocalProvider(LocalButtonFeedback provides feedback) {
    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            if (dynamicColors) listOf(colors.background, colors.surfaceContainerLowest)
            else listOf(Color(paletteColors.background), Color(paletteColors.backgroundEnd))))
            .themeDecoration(palette, dark, dynamicColors)) {
        if (!themeDialog) Scaffold(containerColor = Color.Transparent, contentColor = ink) { padding ->
            Column(Modifier.fillMaxSize()
                .padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(when (tab) { 1 -> serverScroll; 2 -> settingsScroll; else -> homeScroll }).padding(horizontal = 18.dp, vertical = 12.dp).padding(bottom = if (state.subscriptionReady) bottomBarHeight else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (tab == 0 && compact) 8.dp else 16.dp)) {
                FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (!state.subscriptionReady || tab == 0) R.string.app_name else if (tab == 1) R.string.dr_servers else R.string.dr_settings),
                        Modifier.weight(1f).widthIn(min = 140.dp), color = if (tab == 0) accent else ink,
                        fontSize = if (tab == 1) 24.sp else 27.sp, fontWeight = FontWeight.Bold)
                    if (tab == 0) {
                        Spacer(Modifier.width(8.dp))
                        ActionIcon(R.drawable.dr_globe, R.string.dr_language) { languageDialog = true }
                    } else if (tab == 1) {
                        ActionIcon(R.drawable.dr_refresh, R.string.dr_refresh, !loading) { onAction(MainAction.UpdateSubscriptions) }
                        PingActions(state, onAction)
                        ActionIcon(R.drawable.ic_add_24dp, R.string.dr_add, prominent = true) { importing = true }
                    }
                }
                state.updateSummary?.let { result ->
                    GlassCard(glass, border) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.dr_update_done), fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount), fontSize = 12.sp)
                            }
                            IconButton(onClick = { onAction(MainAction.DismissUpdateSummary) }) {
                                Icon(painterResource(R.drawable.dr_dismiss), stringResource(R.string.dr_dismiss))
                            }
                        }
                    }
                }
                if (!state.subscriptionReady) {
                    GlassCard(glass, border) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Icon(painterResource(R.drawable.dr_notification), null, Modifier.size(56.dp), tint = accent)
                            Text(stringResource(R.string.dr_welcome), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dr_login_help), color = muted)
                            OutlinedTextField(input, { input = it }, label = { Text(stringResource(R.string.dr_subscription_url)) },
                                modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !loading, isError = state.subscriptionError)
                            if (state.subscriptionError) Text(stringResource(R.string.dr_login_error), color = colors.error)
                            TextButton(onClick = { onAction(MainAction.ImportQRcode) }, enabled = !loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(painterResource(R.drawable.dr_qr), null, Modifier.size(21.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.dr_scan))
                            }
                            Button(onClick = { onAction(MainAction.SubmitSubscription(input.trim())) }, enabled = input.isNotBlank() && !loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) {
                                if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = colors.onPrimary) else Text(stringResource(R.string.dr_login))
                            }
                            TextButton(onClick = { onAction(MainAction.ContinueWithoutSubscription) }, enabled = !loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.dr_continue_without_sub))
                            }
                            LanguageButtons(onAction)
                        }
                    }
                } else pageStates.SaveableStateProvider(tab) { when (tab) {
                    0 -> {
                        Column(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 28.dp)) {
                            ConnectionPower(state, powerSize) { onAction(MainAction.ToggleService) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HomeConnectionButton(selected = state.homeConnectionChoice == HomeConnectionChoice.BEST,
                                    onClick = { onAction(MainAction.ConnectBestServer) }, enabled = !state.isTesting && !state.isConnecting && !state.backgroundTesting && !loading) {
                                    Icon(painterResource(R.drawable.dr_speed), null, Modifier.size(19.dp))
                                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dr_connect_best), fontSize = 12.sp)
                                }
                                HomeConnectionButton(selected = state.homeConnectionChoice == HomeConnectionChoice.BALANCER,
                                    onClick = { onAction(MainAction.ConnectBalancer) }, enabled = !state.isTesting && !state.isConnecting && !state.backgroundTesting && !loading) {
                                    Icon(painterResource(R.drawable.dr_layers), null, Modifier.size(19.dp))
                                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dr_balancer), fontSize = 12.sp)
                                }
                            }
                            Text(if (state.isConnecting) stringResource(R.string.dr_connecting) else if (state.backgroundTesting) stringResource(R.string.dr_background_test) else if (state.isTesting) mainViewModel.formatStatus(state.status) else stringResource(if (state.accountLocked) R.string.dr_renew else if (state.isRunning) R.string.dr_connected else if (state.selectedGuid.isNullOrEmpty()) R.string.dr_choose_server else R.string.dr_tap_connect),
                                color = if (state.isRunning || state.isTesting) accent else muted, fontSize = 19.sp)
                            if (state.isRunning) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Metric(stringResource(R.string.dr_download), bytes(state.traffic.downloadPerSecond) + stringResource(R.string.dr_per_second), ink, muted)
                                Metric(stringResource(R.string.dr_upload), bytes(state.traffic.uploadPerSecond) + stringResource(R.string.dr_per_second), ink, muted)
                                Metric(stringResource(R.string.dr_session), bytes(state.traffic.sessionBytes), ink, muted)
                            }
                            if (state.isRunning) {
                                HomeConnectionButton(selected = state.isTesting,
                                    onClick = { onAction(MainAction.TestCurrentServer) }, enabled = !state.isTesting && !state.isConnecting) {
                                    Icon(painterResource(R.drawable.dr_speed), null, Modifier.size(19.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.dr_ping_connected))
                                }
                                state.connectedTestResult?.takeIf { state.connectedTestGuid == state.selectedGuid }?.let { result ->
                                    Text(mainViewModel.formatStatus(MainStatus.ConnectionTest(result)),
                                        color = if (result.delayMillis < 0) colors.error else accent, fontSize = 13.sp,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                            }
                            state.selectedServerSummary?.takeIf { it.guid == state.selectedGuid }?.let { server ->
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(accent.copy(alpha = .09f)).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (server.flag != null) Text(server.flag, fontSize = 23.sp)
                                    else Icon(painterResource(R.drawable.dr_globe), null, Modifier.size(25.dp), tint = muted)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(configurationName(server.name), Modifier.fillMaxWidth(), fontSize = 15.sp, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Left,
                                            style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                                        ProtocolLabel(server.protocol)
                                    }
                                }
                                }
                            }
                        }
                        if (state.preferences.enabled(com.v2ray.ang.handler.ClientOption.MEMORY_MONITOR)) {
                            Text(stringResource(R.string.dr_memory_usage, bytes(state.memoryBytes)), color = muted, fontSize = 12.sp)
                        }
                        val homeGroup = state.selectedServerSummary?.subscriptionId?.takeIf { it.isNotBlank() } ?: state.selectedGroupId
                        state.groups.firstOrNull { it.id == homeGroup && state.accounts[it.id]?.showOnHome != false }?.let { provider ->
                            Text(stringResource(R.string.dr_current_provider), color = muted, fontSize = 12.sp)
                            key(provider.id) { ProviderCard(provider.id, provider.remarks, state, mainViewModel, glass, border, onAction, onNavigate, if (tab == 1) state.category else ServerCategory.ALL, tab == 1) }
                        }
                        ImportActions(loading, { importing = true }, onAction)
                    }
                    1 -> {
                        Text(stringResource(R.string.dr_provider_count, state.groups.size), color = muted, fontSize = 12.sp)
                        OutlinedTextField(search, { search = it }, singleLine = true,
                            label = { Text(stringResource(R.string.dr_search)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_search_24dp), null) },
                            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = border, focusedBorderColor = accent,
                                focusedContainerColor = glass, unfocusedContainerColor = glass))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ServerCategory.entries.forEach { category ->
                                FilterChip(modifier = Modifier.insetSelection(state.category == category, RoundedCornerShape(18.dp), glass, accent), colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = Color.Transparent), selected = state.category == category, onClick = { onAction(MainAction.SelectCategory(category)) }, label = { Text(categoryLabel(category), fontSize = 12.sp) })
                            }
                        }
                        TextButton(onClick = { onAction(MainAction.SelectFastestServer) }, enabled = !state.isTesting && !state.isRunning,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(painterResource(R.drawable.dr_speed), null, Modifier.size(21.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.dr_best))
                        }
                        if (state.category != ServerCategory.ALL && state.categoryCounts.isNotEmpty() && (state.categoryCounts[state.category] ?: 0) == 0) {
                            Text(stringResource(R.string.dr_empty), color = muted)
                        }
                        state.groups.forEach { provider -> key(provider.id) {
                            ProviderCard(provider.id, provider.remarks, state, mainViewModel, glass, border, onAction, onNavigate, if (tab == 1) state.category else ServerCategory.ALL, tab == 1)
                        } }
                        ImportActions(loading, { importing = true }, onAction)
                    }
                    2 -> {
                        Section(R.string.dr_split)
                        GlassCard(glass, border) { SettingsRow(R.drawable.dr_apps, R.string.dr_split) { onNavigate(MainDestination.PerAppProxy) } }
                        Section(R.string.dr_appearance)
                        GlassCard(glass, border) {
                            Column {
                                SettingsRow(R.drawable.dr_palette, R.string.dr_theme,
                                    value = if (dynamicColors) stringResource(R.string.dr_system) else stringResource(R.string.dr_theme_selected, paletteLabel(palette), stringResource(if (dark) R.string.dr_night else R.string.dr_day))) { themeDialog = true }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = border)
                                SettingsRow(R.drawable.dr_globe, R.string.dr_language,
                                    value = configuration.locales[0].getDisplayLanguage(configuration.locales[0])) { languageDialog = true }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = border)
                                SettingsRow(R.drawable.dr_palette, R.string.dr_app_icon) { settingsPanel = "appearance" }
                            }
                        }
                        Section(R.string.dr_connection)
                        GlassCard(glass, border) {
                            SettingsRow(R.drawable.ic_flash_on_24dp, R.string.dr_connection, value = stringResource(R.string.dr_configure)) { settingsPanel = "connection" }
                        }
                        Section(R.string.dr_tunnel)
                        GlassCard(glass, border) { SettingsRow(R.drawable.dr_layers, R.string.dr_routing, value = stringResource(R.string.dr_configure)) { onNavigate(MainDestination.Routing) } }
                        Section(R.string.dr_providers)
                        GlassCard(glass, border) { Column {
                            SettingsRow(R.drawable.dr_refresh, R.string.dr_provider_settings) { settingsPanel = "providers" }
                            SettingsRow(R.drawable.dr_servers, R.string.title_sub_setting) { onNavigate(MainDestination.Subscriptions) }
                            SettingsRow(R.drawable.dr_speed, R.string.dr_ping_settings) { settingsPanel = "ping" }
                        } }
                        Section(R.string.dr_performance)
                        GlassCard(glass, border) { Column {
                            SettingsRow(R.drawable.ic_settings_24dp, R.string.dr_performance) { settingsPanel = "performance" }
                            SettingsRow(R.drawable.ic_flash_on_24dp, R.string.dr_advanced) { onNavigate(MainDestination.Settings) }
                        } }
                        GlassCard(glass, border) { Column {
                            SettingsRow(R.drawable.dr_globe, R.string.dr_url_schemes) { urlSchemes = true }
                            SettingsRow(R.drawable.dr_notification, R.string.dr_feedback) { onAction(MainAction.AppFeedback) }
                            SettingsRow(R.drawable.dr_refresh, R.string.dr_reset_settings) { resetDialog = true }
                        } }
                        Section(R.string.dr_application)
                        GlassCard(glass, border) {
                            Column {
                                listOf(MainDestination.CheckUpdate, MainDestination.About, MainDestination.BackupRestore).forEachIndexed { index, destination ->
                                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = border)
                                    SettingsRow(destination.iconRes, destination.labelRes) { onNavigate(destination) }
                                }
                            }
                        }
                        Section(R.string.dr_debug)
                        GlassCard(glass, border) { SettingsRow(MainDestination.Logcat.iconRes, MainDestination.Logcat.labelRes) { onNavigate(MainDestination.Logcat) } }
                    }
                } }
            }
        }
            if (!themeDialog && state.subscriptionReady && WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) == 0) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = if (generousLayout) 16.dp else 42.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } + 16.dp }.shadow(4.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(Brush.verticalGradient(listOf(glass.copy(alpha = .96f), glass.copy(alpha = .86f))))
                    .border(1.dp, accent.copy(alpha = .14f), RoundedCornerShape(26.dp)).padding(4.dp)) {
                    listOf(R.string.dr_home to R.drawable.dr_home, R.string.dr_servers to R.drawable.dr_servers,
                        R.string.dr_settings to R.drawable.ic_settings_24dp).forEachIndexed { index, item ->
                        Surface(onClick = { feedback(); tab = index; search = ""; onAction(MainAction.Search("")) },
                            color = Color.Transparent,
                            shape = RoundedCornerShape(23.dp),
                            modifier = Modifier.weight(1f).insetSelection(tab == index, RoundedCornerShape(23.dp), glass, accent).semantics { }, selected = tab == index) {
                            Column(Modifier.heightIn(min = 48.dp).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(painterResource(item.second), null, Modifier.size(21.dp), tint = if (tab == index) accent else muted)
                                Text(stringResource(item.first), fontSize = 11.sp, color = if (tab == index) accent else muted)
                            }
                        }
                    }
                }
            }

        SnackbarHost(saveSnackbar, Modifier.align(Alignment.BottomCenter)
            .navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = if (state.subscriptionReady) bottomBarHeight else 16.dp)) { notice ->
            Snackbar(containerColor = colors.surfaceContainerHigh, contentColor = ink,
                shape = RoundedCornerShape(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.dr_notification), null, Modifier.size(24.dp), tint = accent)
                    Text(notice.visuals.message)
                }
            }
        }

        if (importing) ClientSheet(stringResource(R.string.dr_import), { importing = false }) {
            Text(stringResource(R.string.dr_import_help))
            OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dr_link)) }, minLines = 3, maxLines = 7)
            Button(enabled = input.isNotBlank() && !loading, onClick = {
                onAction(MainAction.ImportBatchConfig(input.trim())); input = ""; importing = false
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.dr_import))
            }
            TextButton(onClick = { importing = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.dr_cancel))
            }
        }
        settingsPanel?.let { panel -> ClientPreferencesSheet(panel, state, onAction) { settingsPanel = null } }
        if (urlSchemes) ClientSheet(stringResource(R.string.dr_url_schemes), { urlSchemes = false }) {
            Text(stringResource(R.string.dr_url_schemes_help))
            TextButton(onClick = { onAction(MainAction.ShowUrlSchemes) }) { Text(stringResource(R.string.dr_copy_url)) }
        }
        if (resetDialog) AlertDialog(onDismissRequest = { resetDialog = false },
            title = { Text(stringResource(R.string.dr_reset_settings)) }, text = { Text(stringResource(R.string.dr_reset_confirm)) },
            confirmButton = { TextButton(onClick = { resetDialog = false; onAction(MainAction.ResetPreferences) }) { Text(stringResource(R.string.dr_reset_settings)) } },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text(stringResource(R.string.dr_cancel)) } })
        com.v2ray.ang.ui.compose.QRCodeDialog(state.shareQRCodeBitmap) { onAction(MainAction.DismissQRCodeDialog) }
        if (themeDialog) ThemeChoicePage(palette, themeMode, dynamicColors, onAction) { themeDialog = false }
        if (languageDialog) LanguageChoiceDialog(state.preferences.languageCode, onAction) { languageDialog = false }
    }
}
}
}

@Composable
private fun HomeConnectionButton(selected: Boolean, onClick: () -> Unit, enabled: Boolean,
    content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    OutlinedButton(onClick = onClick, enabled = enabled, shape = shape,
        modifier = Modifier.heightIn(min = 48.dp)
            .semantics { this.selected = selected }
            .insetSelection(selected, shape, colors.surface, colors.primary),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = .25f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, contentColor = colors.primary,
            disabledContentColor = if (selected) colors.primary else colors.onSurfaceVariant),
        content = content)
}

@Composable
private fun ProviderCard(id: String, name: String, state: MainUiState, viewModel: MainViewModel,
    glass: Color, border: Color, onAction: (MainAction) -> Unit, onNavigate: (MainDestination) -> Unit,
    filterCategory: ServerCategory, showTcp: Boolean) {
    val group by viewModel.serverGroupState(id).collectAsStateWithLifecycle()
    val roomyRows = LocalConfiguration.current.fontScale > 1.2f || LocalConfiguration.current.screenWidthDp < 360
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(filterCategory) { if (filterCategory != ServerCategory.ALL) expanded = true }
    var menu by rememberSaveable(id) { mutableStateOf(false) }
    var providerSettings by rememberSaveable(id) { mutableStateOf(false) }
    var providerShare by rememberSaveable(id) { mutableStateOf(false) }
    var deleteDialog by rememberSaveable(id) { mutableStateOf(false) }
    val expansionLabel = stringResource(if (expanded) R.string.dr_expanded else R.string.dr_collapsed)
    val account = state.accounts[id]
    val accent = MaterialTheme.colorScheme.primary
    val usage = account?.usage
    val rows = remember(group.rows, filterCategory) { group.rows.filter { filterCategory == ServerCategory.ALL || serverCategory(it.profile) == filterCategory } }
    if (filterCategory != ServerCategory.ALL && rows.isEmpty()) return
    if (providerSettings && account != null) ProviderSettingsSheet(id, account, state, onAction) { providerSettings = false }
    if (providerShare) ClientSheet(stringResource(R.string.dr_share) + " · " + name, { providerShare = false }) {
        listOf(R.string.dr_share to MainAction.ShareProvider(id), R.string.dr_copy_url to MainAction.CopyProvider(id),
            R.string.dr_show_qr to MainAction.ShowProviderQr(id)).forEach { (label, action) ->
            TextButton(onClick = { providerShare = false; onAction(action) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(label))
            }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text(stringResource(R.string.dr_delete)) },
        text = { Text(stringResource(R.string.dr_delete_provider_confirm)) },
        confirmButton = { TextButton(onClick = { deleteDialog = false; onAction(MainAction.DeleteProvider(id)) }) { Text(stringResource(R.string.dr_delete)) } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text(stringResource(R.string.dr_cancel)) } })
    GlassCard(glass.copy(alpha = .65f), border) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(onClick = { expanded = !expanded; onAction(MainAction.SelectGroup(id)) }, color = Color.Transparent,
                    modifier = Modifier.weight(1f).widthIn(min = 120.dp).semantics { stateDescription = expansionLabel }) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_expand_more_24dp), null, Modifier.size(20.dp).rotate(if (expanded) 0f else -90f))
                        Spacer(Modifier.width(8.dp))
                        Text(name, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                account?.let { com.v2ray.ang.handler.effectiveUpdateMinutes(it.customUpdateInterval, it.updateInterval, state.preferences.updateMinutes) }?.takeIf { it > 0 }?.let { Text(stringResource(R.string.dr_hours, it / 60), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp) }
                ActionIcon(R.drawable.dr_refresh, R.string.dr_refresh) { onAction(MainAction.SelectGroup(id)); onAction(MainAction.UpdateSubscriptions) }
                Box {
                    ActionIcon(R.drawable.dr_speed, R.string.dr_ping, state.canStartBulkProbe) {
                        onAction(MainAction.SelectGroup(id))
                        onAction(if (showTcp && account?.probeMethod == "tcp") MainAction.TestAllServers else MainAction.TestRealAllServers)
                    }

                }
                Box {
                    ActionIcon(R.drawable.ic_more_vert_24dp, R.string.dr_more) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dr_settings)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_settings_24dp), null) }, onClick = { menu = false; providerSettings = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.dr_share)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_share_24dp), null) }, enabled = !account?.url.isNullOrBlank(), onClick = { menu = false; providerShare = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.dr_delete)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_delete_24dp), null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleteDialog = true })
                    }
                }
            }
            if (filterCategory == ServerCategory.ALL) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.dr_days) + ": " + (state.providerDays[id]?.let { NumberFormat.getIntegerInstance().format(it) }
                    ?: stringResource(if (usage?.expire == 0L) R.string.dr_unlimited else R.string.dr_unknown)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((usage?.used?.let { bytes(it) } ?: "—") + " / " + (usage?.total?.takeIf { it > 0 }?.let { bytes(it) }
                    ?: stringResource(if (usage?.total == 0L) R.string.dr_unlimited else R.string.dr_unknown)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { if (usage?.total != null && usage.total > 0 && usage.used != null)
                (usage.used!!.toDouble() / usage.total).toFloat().coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp), color = accent, trackColor = accent.copy(alpha = .09f))
            Text(stringResource(R.string.dr_remaining) + ": " + (usage?.remaining?.let { bytes(it) }
                ?: stringResource(if (usage?.total == 0L) R.string.dr_unlimited else R.string.dr_unknown)),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            account?.announcement?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
            if (state.providerLocks[id] == true) Text(stringResource(R.string.dr_renew), color = accent)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.dr_server_count, group.rows.size), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                account?.lastUpdated?.takeIf { it > 0 }?.let { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
            }
            }
            if (expanded) {
                TestActions(state, showTcp, onAction)
                if (rows.isEmpty()) Text(stringResource(R.string.dr_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEach { server -> key(server.guid) {
                    val accountInfo = com.v2ray.ang.handler.ProfileEligibility.isAccountInfo(server.profile)
                    val selected = state.selectedGuid == server.guid
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    FlowRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (selected) accent.copy(alpha = .12f) else Color.Transparent)
                        .insetSelection(selected, RoundedCornerShape(18.dp), glass, accent)
                        .semantics(mergeDescendants = true) {}
                        .selectable(selected = selected, enabled = !accountInfo, role = Role.RadioButton,
                            onClick = { onAction(MainAction.SelectGroup(id)); onAction(MainAction.SelectServer(server.guid)) })
                        .padding(12.dp), maxItemsInEachRow = if (roomyRows) 3 else 4,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            val flag = configurationFlag(server.remarks)
                            if (flag != null) Text(flag, fontSize = 20.sp) else Icon(painterResource(R.drawable.dr_globe), null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(configurationName(server.remarks), Modifier.fillMaxWidth(), maxLines = if (roomyRows) 2 else 1, fontSize = 15.sp,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Left,
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                            if (accountInfo) Text(stringResource(R.string.dr_account_info), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else ProtocolLabel(server.typeDescription)
                        }
                        if (!accountInfo) Column((if (roomyRows) Modifier.fillMaxWidth() else Modifier.width(96.dp)).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                            val delay = state.realResults[server.guid]
                            Text("● " + (if (account?.displayDelayAs == "status" && delay != null && delay != 0L)
                                stringResource(if (delay > 0) R.string.dr_connected else R.string.dr_unreachable) else delayText(delay)), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr), color = when {
                                delay == null || delay == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
                                delay < 0 -> MaterialTheme.colorScheme.error
                                delay < 250 -> Color(0xFF50C88D)
                                else -> accent
                            })
                            if (showTcp) Text(if (state.tcpResults[server.guid] == -2L) stringResource(R.string.dr_tcp_udp)
                                else stringResource(R.string.dr_tcp_result, delayText(state.tcpResults[server.guid])), fontSize = 9.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    }
                } }
            }
        }
    }
}

@Composable private fun TestActions(state: MainUiState, showTcp: Boolean, onAction: (MainAction) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAction(MainAction.TestRealAllServers) }, enabled = state.canStartBulkProbe, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dr_real_delay), fontSize = 11.sp) }
            if (showTcp) OutlinedButton(onClick = { onAction(MainAction.TestAllServers) }, enabled = state.canStartBulkProbe, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dr_tcp_ping), fontSize = 11.sp) }
        }
        TextButton(onClick = { onAction(if (state.isTesting) MainAction.CancelTesting else MainAction.SelectFastestServer) }, enabled = state.isTesting || !state.isRunning) {
            Text(stringResource(if (state.isTesting) R.string.dr_cancel_test else R.string.dr_best), fontSize = 12.sp)
        }
    }
}
@Composable private fun ImportActions(loading: Boolean, add: () -> Unit, onAction: (MainAction) -> Unit) {
    val configuration = LocalConfiguration.current
    val stackActions = configuration.fontScale > 1.2f || configuration.screenWidthDp < 360
    FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (stackActions) 1 else 3,
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = add, enabled = !loading, shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .15f), contentColor = MaterialTheme.colorScheme.primary),
            modifier = (if (stackActions) Modifier.fillMaxWidth() else Modifier.weight(1f)).heightIn(min = 52.dp)) {
            Icon(painterResource(R.drawable.ic_add_24dp), null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dr_add))
        }
        FilledTonalButton(onClick = { onAction(MainAction.ImportClipboard) }, enabled = !loading, shape = RoundedCornerShape(20.dp), modifier = (if (stackActions) Modifier.fillMaxWidth() else Modifier.weight(1f)).heightIn(min = 52.dp)) {
            Icon(painterResource(R.drawable.dr_clipboard), null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dr_paste))
        }
        if (stackActions) {
            OutlinedButton(onClick = { onAction(MainAction.ImportQRcode) }, enabled = !loading,
                shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(painterResource(R.drawable.dr_qr), null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.dr_scan))
            }
        } else ActionIcon(R.drawable.dr_qr, R.string.dr_scan, !loading) { onAction(MainAction.ImportQRcode) }
    }
}
internal val LocalButtonFeedback = compositionLocalOf<() -> Unit> { {} }

@Composable private fun ActionIcon(icon: Int, label: Int, enabled: Boolean = true, prominent: Boolean = false, rotation: Float = 0f, click: () -> Unit) {
    val feedback = LocalButtonFeedback.current
    IconButton(onClick = { feedback(); click() }, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(21.dp).rotate(rotation),
            tint = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Section(label: Int) { Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
@Composable private fun SettingsHeading(icon: Int, title: Int) {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp)); Text(stringResource(title), fontSize = 16.sp)
    }
}
@Composable private fun SettingsRow(icon: Int, title: Int, value: String? = null, action: () -> Unit) {
    val feedback = LocalButtonFeedback.current
    Surface(onClick = { feedback(); action() }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(title), fontSize = 16.sp)
                value?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(painterResource(R.drawable.ic_expand_more_24dp), null,
                Modifier.size(20.dp).rotate(if (LocalLayoutDirection.current == LayoutDirection.Rtl) 90f else -90f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun paletteLabel(palette: DrKozolPalette): String = stringResource(when (palette) {
    DrKozolPalette.FOREST -> R.string.dr_palette_forest
    DrKozolPalette.MAROON -> R.string.dr_palette_maroon
    DrKozolPalette.PURPLE -> R.string.dr_palette_purple
    DrKozolPalette.OCEAN -> R.string.dr_palette_ocean
    DrKozolPalette.SUNSET -> R.string.dr_palette_sunset
    DrKozolPalette.OLED -> R.string.dr_palette_oled
    DrKozolPalette.JAPAN -> R.string.dr_palette_japan
    DrKozolPalette.ANIME -> R.string.dr_palette_anime
})

@Composable private fun ProtocolLabel(description: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        remember(description) { description.split(" / ") }.forEachIndexed { index, part ->
            if (index > 0) Text("·", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(part.uppercase(java.util.Locale.ROOT), fontSize = 10.sp, maxLines = 1,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr), color = when {
                index == 0 -> MaterialTheme.colorScheme.primary
                part.equals("tls", true) || part.equals("reality", true) -> if (LocalDarkTheme.current) Color(0xFF50C88D) else Color(0xFF006B43)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
        }
    }
    }
}

@Composable private fun PingActions(state: MainUiState, onAction: (MainAction) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        ActionIcon(R.drawable.dr_speed, R.string.dr_ping) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            if (state.isRunning) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dr_ping_connected)) }, enabled = !state.isTesting,
                    onClick = { expanded = false; onAction(MainAction.TestCurrentServer) })
            } else {
                DropdownMenuItem(text = { Text(stringResource(R.string.dr_real_delay)) }, enabled = !state.isTesting,
                    onClick = { expanded = false; onAction(MainAction.TestRealAllServers) })
                DropdownMenuItem(text = { Text(stringResource(R.string.dr_tcp_ping)) }, enabled = !state.isTesting,
                    onClick = { expanded = false; onAction(MainAction.TestAllServers) })
                DropdownMenuItem(text = { Text(stringResource(R.string.dr_best)) }, enabled = !state.isTesting,
                    onClick = { expanded = false; onAction(MainAction.SelectFastestServer) })
            }
            if (state.isTesting) DropdownMenuItem(text = { Text(stringResource(R.string.dr_cancel_test)) },
                onClick = { expanded = false; onAction(MainAction.CancelTesting) })
        }
    }
}

@Composable private fun ThemeChoicePage(palette: DrKozolPalette, mode: String, dynamic: Boolean, onAction: (MainAction) -> Unit, dismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = dismiss)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = dismiss) {
                    Icon(painterResource(R.drawable.dr_dismiss), stringResource(R.string.dr_dismiss))
                }
                Text(stringResource(R.string.dr_theme), style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().selectable(selected = dynamic, role = Role.RadioButton,
                onClick = { onAction(MainAction.UseSystemColors) }).heightIn(min = 64.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(dynamic, onClick = null)
                Text(stringResource(R.string.dr_system), Modifier.weight(1f))
            }
            DrKozolPalette.entries.forEach { choice ->
                (if (choice == DrKozolPalette.OLED) listOf("2" to R.string.dr_night) else listOf("1" to R.string.dr_day, "2" to R.string.dr_night)).forEach { (choiceMode, label) ->
                    val selected = !dynamic && palette == choice && mode == choiceMode
                    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = {
                        onAction(MainAction.SetPalette(choice.settingId)); onAction(MainAction.SetTheme(choiceMode))
                    }).heightIn(min = 68.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.dr_theme_selected, paletteLabel(choice), stringResource(label)))
                        }
                        val preview = choice.colors(choiceMode == "2")
                        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf(preview.background, preview.accent, preview.muted).forEach { swatch ->
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(swatch)))
                            }
                        }
                    }
                }
            }

            }
        }
    }
}

@Composable private fun LanguageChoiceDialog(language: String, onAction: (MainAction) -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.dr_language)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
            listOf("auto" to R.string.dr_system, "fa" to R.string.dr_farsi, "en" to R.string.dr_english).forEach { (code, label) ->
                Row(Modifier.fillMaxWidth().selectable(selected = language == code, role = Role.RadioButton,
                    onClick = { dismiss(); onAction(MainAction.SetLanguage(code)) }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(language == code, onClick = null)
                    Text(stringResource(label), Modifier.weight(1f))
                }
            }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_ok)) } })
}
@Composable private fun LanguageButtons(onAction: (MainAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = { onAction(MainAction.SetLanguage("fa")) }) { Text(stringResource(R.string.dr_farsi)) }
        TextButton(onClick = { onAction(MainAction.SetLanguage("en")) }) { Text(stringResource(R.string.dr_english)) }
    }
}

@Composable private fun categoryLabel(category: ServerCategory): String = when (category) {
    ServerCategory.ALL -> stringResource(R.string.dr_all)
    ServerCategory.OTHER -> stringResource(R.string.dr_other)
    ServerCategory.AMNEZIA -> stringResource(R.string.dr_protocol_amnezia)
    ServerCategory.SHADOWSOCKS -> stringResource(R.string.dr_protocol_shadowsocks)
    ServerCategory.WIREGUARD -> stringResource(R.string.dr_protocol_wireguard)
    ServerCategory.VLESS -> stringResource(R.string.dr_protocol_vless)
    ServerCategory.VMESS -> stringResource(R.string.dr_protocol_vmess)
    ServerCategory.TROJAN -> stringResource(R.string.dr_protocol_trojan)
}
@Composable private fun delayText(value: Long?): String = when {
    value == null || value == 0L -> stringResource(R.string.dr_untested)
    value < 0 -> stringResource(R.string.dr_unreachable)
    else -> stringResource(R.string.dr_ms, value)
}
@Composable private fun GlassCard(color: Color, border: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(color).border(1.dp, border, shape)) { content() }
}
@Composable private fun Metric(label: String, value: String, ink: Color, muted: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = muted, fontSize = 11.sp)
    }
}
private fun bytes(value: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var amount = value.coerceAtLeast(0).toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
    return NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(amount) + " " + units[unit]
}
