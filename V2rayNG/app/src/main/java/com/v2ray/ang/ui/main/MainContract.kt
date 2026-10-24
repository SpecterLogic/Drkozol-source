package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

enum class HomeConnectionChoice { MANUAL, BEST, BALANCER }

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isConnecting: Boolean = false,
    val homeConnectionChoice: HomeConnectionChoice = HomeConnectionChoice.MANUAL,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val traffic: com.v2ray.ang.dto.TrafficSnapshot = com.v2ray.ang.dto.TrafficSnapshot(),
    val account: com.v2ray.ang.dto.entities.SubscriptionItem? = null,
    val accounts: Map<String, com.v2ray.ang.dto.entities.SubscriptionItem> = emptyMap(),
    val providerDays: Map<String, Long?> = emptyMap(),
    val providerLocks: Map<String, Boolean> = emptyMap(),
    val accountLocked: Boolean = false,
    val accountDays: Long? = null,
    val subscriptionReady: Boolean = false,
    val subscriptionError: Boolean = false,
    val category: ServerCategory = ServerCategory.ALL,
    val categoryCounts: Map<ServerCategory, Int> = emptyMap(),
    val tcpResults: Map<String, Long> = emptyMap(),
    val realResults: Map<String, Long> = emptyMap(),
    val connectedTestGuid: String? = null,
    val connectedTestResult: ConnectionTestResult? = null,
    val selectedServerSummary: SelectedServerSummary? = null,
    val sortProbeKind: ProbeKind = ProbeKind.REAL,
    val updateSummary: com.v2ray.ang.dto.SubscriptionUpdateResult? = null,
    val preferences: com.v2ray.ang.handler.ClientPreferences = com.v2ray.ang.handler.ClientPreferences(),
    val preferencesError: Boolean = false,
    val settingsSave: SettingsSaveResult = SettingsSaveResult(),
    val settingsSaveInFlight: String? = null,
    val memoryBytes: Long = 0,
    val providerSavedId: String? = null,
    val backgroundTesting: Boolean = false,
) {
    val canStartBulkProbe: Boolean get() = !isRunning && !isTesting && !isConnecting && !backgroundTesting
}

/** A completed write is scoped to its form and never replays when that form reopens. */
data class SettingsSaveResult(val revision: Long = 0, val target: String = "", val successful: Boolean = false)

internal fun MainUiState.withSettingsSave(target: String, successful: Boolean): MainUiState = copy(
    preferencesError = !successful,
    settingsSaveInFlight = null,
    settingsSave = SettingsSaveResult(settingsSave.revision + 1, target, successful),
)

internal fun SettingsSaveResult.completes(target: String, submittedRevision: Long?): Boolean =
    submittedRevision != null && this.target == target && revision > submittedRevision

internal fun MainUiState.expireUpdateSummary(expected: com.v2ray.ang.dto.SubscriptionUpdateResult): MainUiState =
    if (updateSummary === expected) copy(updateSummary = null) else this

data class SelectedServerSummary(val guid: String, val name: String, val protocol: String, val flag: String?, val subscriptionId: String = "")

internal fun MainUiState.withSelectedServerSnapshot(guid: String?, profile: com.v2ray.ang.dto.entities.ProfileItem?): MainUiState {
    if (guid != selectedGuid) return this
    val choice = when {
        guid.isNullOrEmpty() || profile == null -> HomeConnectionChoice.MANUAL
        profile.configType == com.v2ray.ang.enums.EConfigType.POLICYGROUP -> HomeConnectionChoice.BALANCER
        homeConnectionChoice == HomeConnectionChoice.BEST -> HomeConnectionChoice.BEST
        else -> HomeConnectionChoice.MANUAL
    }
    return copy(homeConnectionChoice = choice, selectedServerSummary = if (guid.isNullOrEmpty() || profile == null) null
        else SelectedServerSummary(guid, profile.remarks, serverProtocolDescription(profile), configurationFlag(profile.remarks), profile.subscriptionId))
}

/** A result belongs to the connected profile, never to a newly selected server. */
internal fun MainUiState.withConnectedTestResult(guid: String?, result: ConnectionTestResult): MainUiState {
    if (!isRunning || guid.isNullOrEmpty() || guid != selectedGuid) return this
    return copy(connectedTestGuid = guid, connectedTestResult = result,
        realResults = realResults + (guid to result.delayMillis), sortProbeKind = ProbeKind.REAL)
}

internal fun MainUiState.withAccountSnapshot(
    guid: String?, groupId: String,
    subscription: com.v2ray.ang.dto.entities.SubscriptionItem?, now: Long,
): MainUiState {
    if (selectedGuid != guid || selectedGroupId != groupId) return this
    return copy(account = subscription, accountLocked = subscription?.enabled == false || subscription?.usage?.locked(now) == true,
        accountDays = subscription?.usage?.daysRemaining(now))
}

/** Tick every provider in the owner, including cards whose account is not currently selected. */
internal fun MainUiState.withProviderSnapshots(
    subscriptions: Map<String, com.v2ray.ang.dto.entities.SubscriptionItem>, now: Long,
): MainUiState {
    val ids = groups.mapTo(hashSetOf()) { it.id }
    val current = subscriptions.filterKeys { it in ids }
    return copy(accounts = current,
        providerDays = current.mapValues { it.value.usage?.daysRemaining(now) },
        providerLocks = current.mapValues { it.value.enabled == false || it.value.usage?.locked(now) == true })
}

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object ContinueWithoutSubscription : MainAction
    data object Initialize : MainAction
    data class SetTheme(val mode: String) : MainAction
    data class SetPalette(val settingId: String) : MainAction
    data class SetLanguage(val code: String) : MainAction
    data class SubmitSubscription(val url: String) : MainAction
    data class SelectCategory(val category: ServerCategory) : MainAction
    data object SelectFastestServer : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object ConnectionStarting : MainAction
    data object ConnectBestServer : MainAction
    data object ConnectBalancer : MainAction
    data object UseSystemColors : MainAction
    data object DismissUpdateSummary : MainAction
    data class SavePreferences(val preferences: com.v2ray.ang.handler.ClientPreferences) : MainAction
    data class SaveProvider(val id: String, val provider: com.v2ray.ang.dto.entities.SubscriptionItem) : MainAction
    data class DeleteProvider(val id: String) : MainAction
    data class ShareProvider(val id: String) : MainAction
    data class CopyProvider(val id: String) : MainAction
    data class ShowProviderQr(val id: String) : MainAction
    data object OpenSystemVpn : MainAction
    data object ShowUrlSchemes : MainAction
    data object AppFeedback : MainAction
    data object ResetPreferences : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction
}

/** Entering without a subscription requires a successful persisted choice, never an optimistic UI flag. */
internal fun MainUiState.withClientPreferencesSave(value: com.v2ray.ang.handler.ClientPreferences, saved: Boolean): MainUiState =
    withSettingsSave("preferences", saved).copy(preferences = if (saved) value else preferences,
        subscriptionReady = subscriptionReady || (saved && value.onboardingCompleted))
