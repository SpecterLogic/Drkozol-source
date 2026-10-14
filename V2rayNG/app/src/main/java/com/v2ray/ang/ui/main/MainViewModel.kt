package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isGroupType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

private fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, Long>,
): List<ServersCache> = servers.map { server ->
    val delayMillis = updates[server.guid]
    if (delayMillis == null || delayMillis == server.testDelayMillis) {
        server
    } else {
        server.copy(testDelayMillis = delayMillis)
    }
}

private fun applyTestDelayResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, Long>,
): List<ServerRowUiModel> = rows.map { row ->
    val delayMillis = updates[row.guid]
    if (delayMillis == null || delayMillis == row.testDelayMillis) {
        row
    } else {
        row.copy(testDelayMillis = delayMillis)
    }
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val selectionMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var testResultFlushJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, Long>()

    private val testRequests = MainTestRequests()
    private var bulkTestJob: Job? = null
    private var lastCompletedBulk: String? = null
    private var backgroundRevision: String? = null
    private var connectionDeadline: Job? = null
    private var updateSummaryDeadline: Job? = null

    private val initialPageReady = CompletableDeferred<Unit>()

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        setupGroupTab()
        viewModelScope.launch(ioDispatcher) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val snapshot = uiState.value
                try {
                    val profile = snapshot.selectedGuid?.let { dataSource.decodeServerConfig(it) }
                    val group = profile?.subscriptionId
                        ?.takeIf { it.isNotEmpty() } ?: snapshot.selectedGroupId
                    val account = dataSource.getSubscriptionItem(group)
                    val accounts = snapshot.groups.mapNotNull { item ->
                        dataSource.getSubscriptionItem(item.id)?.let { item.id to it }
                    }.toMap()
                    val revision = dataSource.backgroundProbeRevision()
                    if (backgroundRevision != null && backgroundRevision != revision) {
                        // A due refresh can populate a first subscription that previously failed.
                        // Re-read readiness and group metadata as well as server rows.
                        setupGroupTab(forceRefresh = true).join()
                    }
                    backgroundRevision = revision
                    val backgroundTesting = dataSource.backgroundProbeActive()
                    val preferences = dataSource.clientPreferences()
                    val memoryBytes = if (preferences.enabled(com.v2ray.ang.handler.ClientOption.MEMORY_MONITOR)) dataSource.memoryBytes() else 0L
                    val now = System.currentTimeMillis() / 1000
                    _uiState.update { it.withAccountSnapshot(snapshot.selectedGuid, snapshot.selectedGroupId, account, now)
                        .withProviderSnapshots(accounts, now).withSelectedServerSnapshot(snapshot.selectedGuid, profile)
                        .copy(preferences = preferences, memoryBytes = memoryBytes, backgroundTesting = backgroundTesting) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LogUtil.e(AppConfig.TAG, "Account read failed group=${snapshot.selectedGroupId}", error)
                }
                delay(1000)
            }
        }
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            is MainServiceEvent.Traffic -> _uiState.update { it.copy(traffic = event.snapshot) }
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                if (!event.message.isNullOrBlank()) {
                    toastError(event.message)
                } else {
                    toastError(R.string.toast_services_failure)
                }
                updateRunningState(false)
            }

            MainServiceEvent.StateStopSuccess -> {
                updateRunningState(false)
                viewModelScope.launch(ioDispatcher) { delay(500); dataSource.resumePendingProbes() }
            }
            is MainServiceEvent.MeasureDelayResult -> {
                val state = uiState.value
                if (!state.isRunning || !testRequests.completeCurrent(event.requestId, state.selectedGuid ?: "")) return
                _uiState.update { it.withConnectedTestResult(state.selectedGuid, event.result)
                    .copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(event.result)) }
                viewModelScope.launch(ioDispatcher) {
                    state.selectedGuid?.let { dataSource.saveProbeResult(it, event.result.delayMillis) }
                    cacheMutex.withLock { groupDataCache.clear() }
                    reloadAllGroups(state.groups.map { it.id })
                }
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestResult(event.result, request)
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                if (event.requestId == testRequests.bulk?.id) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                val scheduledFlush = testResultFlushJob
                testResultFlushJob = viewModelScope.launch {
                    scheduledFlush?.cancelAndJoin()
                    if (testRequests.bulk?.id != request.id) return@launch
                    flushPendingTestResults(request)
                    onTestsFinished(request.id)
                }
            }

            is MainServiceEvent.MeasureDelayCancelled -> {
                if (testRequests.completeCurrent(event.requestId)) resetTestStatus()
            }

            is MainServiceEvent.MeasureConfigCancelled -> {
                if (testRequests.completeBulk(event.requestId) != null) {
                    cancelPendingTestResults()
                    resetTestStatus()
                }
            }
        }
    }

    private fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        _uiState.update { it.withProbeResult(result.guid, result.delayMillis, request.kind) }
        pendingTestResults[result.guid] = result.delayMillis
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = viewModelScope.launch {
            while (pendingTestResults.isNotEmpty()) {
                delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(request)
            }
        }
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (pendingTestResults.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return

        val updates = cacheMutex.withLock {
            val drained = pendingTestResults.toMap()
            pendingTestResults.clear()
            groupDataCache[request.groupId]?.let { cached ->
                groupDataCache[request.groupId] = applyTestDelayResults(cached, drained)
            }
            drained
        }
        if (updates.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return
        val current = mutableServerGroupState(request.groupId).value
        val sorted = withContext(defaultDispatcher) {
            val state = uiState.value
            sortedServerRows(applyTestDelayResultsToRows(current.rows, updates),
                if (request.kind == ProbeKind.TCP) state.tcpResults else state.realResults)
        }
        if (testRequests.bulk?.id != request.id) return
        val servers = applyTestDelayResults(current.servers, updates).associateBy { it.guid }
        mutableServerGroupState(request.groupId).value = ServerGroupUiState(sorted.mapNotNull { servers[it.guid] }, sorted)
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = mutableServerGroupState(groupId)
            groupState
                .map { it.servers }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = groupState.value.servers,
                )
        }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    private fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    private fun currentServers(): List<ServersCache> =
        mutableServerGroupState(uiState.value.selectedGroupId).value.servers

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            is MainAction.SubmitSubscription -> submitSubscription(action.url)
            is MainAction.SelectCategory -> _uiState.update { it.copy(category = action.category) }
            MainAction.SelectFastestServer -> {
                val state = uiState.value
                val eligible = currentServers().filter {
                    !com.v2ray.ang.handler.ProfileEligibility.isAccountInfo(it.profile) &&
                        (state.category == ServerCategory.ALL || serverCategory(it.profile) == state.category)
                }.mapTo(hashSetOf()) { it.guid }
                fastestServerGuid(eligible, state.realResults, state.tcpResults)?.let { guid ->
                    viewModelScope.launch { updateSelectedGuid(guid) }
                } ?: toastError(R.string.dr_test_first)
            }
            MainAction.ConnectionStarting -> {
                connectionDeadline?.cancel()
                _uiState.update { it.copy(isConnecting = true) }
                connectionDeadline = viewModelScope.launch {
                    delay(30_000)
                    _uiState.update { it.copy(isConnecting = false) }
                }
            }
            MainAction.UseSystemColors -> viewModelScope.launch(ioDispatcher) {
                com.v2ray.ang.ui.compose.ThemeManager.setThemeMode("0")
                com.v2ray.ang.ui.compose.ThemeManager.setDynamicColorEnabled(true)
            }
            is MainAction.SavePreferences -> savePreferences(action.preferences)
            MainAction.ResetPreferences -> savePreferences(com.v2ray.ang.handler.ClientPreferences())
            is MainAction.SaveProvider -> {
                if (uiState.value.settingsSaveInFlight != null) return
                _uiState.update { it.copy(settingsSaveInFlight = action.id, preferencesError = false) }
                viewModelScope.launch(ioDispatcher) {
                try {
                    val saved = dataSource.saveProvider(action.id, action.provider)
                    _uiState.update { it.withSettingsSave(action.id, saved).copy(providerSavedId = if (saved) action.id else null) }
                    if (saved) {
                        toastSuccess(R.string.dr_saved)
                        setupGroupTab(forceRefresh = true).join()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    LogUtil.e(AppConfig.TAG, "Provider settings save failed group=${action.id}", error)
                    _uiState.update { it.withSettingsSave(action.id, false) }
                }
            }
            }
            is MainAction.DeleteProvider -> viewModelScope.launch(ioDispatcher) {
                try { dataSource.deleteProvider(action.id); setupGroupTab(forceRefresh = true).join() }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    LogUtil.e(AppConfig.TAG, "Provider removal failed group=${action.id}", error)
                    _uiState.update { it.copy(preferencesError = true) }
                }
            }
            is MainAction.ShowProviderQr -> viewModelScope.launch(ioDispatcher) {
                val bitmap = dataSource.shareProviderQr(action.id)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }
            is MainAction.ShareProvider, is MainAction.CopyProvider,
            MainAction.OpenSystemVpn, MainAction.ShowUrlSchemes, MainAction.AppFeedback -> Unit
            MainAction.DismissUpdateSummary -> {
                updateSummaryDeadline?.cancel()
                _uiState.update { it.copy(updateSummary = null) }
            }
            MainAction.ConnectBestServer, MainAction.ConnectBalancer -> Unit // Activity owns permission/start dispatch.
            is MainAction.SetTheme -> viewModelScope.launch(ioDispatcher) { com.v2ray.ang.ui.compose.ThemeManager.setThemeMode(action.mode) }
            is MainAction.SetPalette -> viewModelScope.launch(ioDispatcher) { com.v2ray.ang.ui.compose.ThemeManager.setPalette(action.settingId) }
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> importConfigViaSub()
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> viewModelScope.launch { updateSelectedGuid(action.guid) }
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            is MainAction.SetLanguage,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    // ---------- Initialization ----------
    suspend fun awaitInitialPage(): Boolean = kotlinx.coroutines.withTimeoutOrNull(10_000) {
        initialPageReady.await()
        true
    } == true

    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
                dataSource.resumePendingProbes()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }

    private fun savePreferences(value: com.v2ray.ang.handler.ClientPreferences) {
        if (uiState.value.settingsSaveInFlight != null) return
        _uiState.update { it.copy(settingsSaveInFlight = "preferences", preferencesError = false) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val saved = dataSource.saveClientPreferences(value)
                _uiState.update { it.withSettingsSave("preferences", saved).copy(preferences = if (saved) value else it.preferences) }
                if (saved) toastSuccess(R.string.dr_saved)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                LogUtil.e(AppConfig.TAG, "Client preferences save failed", error)
                _uiState.update { it.withSettingsSave("preferences", false) }
            }
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L
            )
        }

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock { groupDataCache[groupId] = servers }
            servers
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>): List<ServersCache> {
        val keyword = keywordFilter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    private suspend fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        val filteredServers = applyKeywordFilter(servers)
        val order = withContext(ioDispatcher) { dataSource.getSubscriptionItem(groupId)?.serverListOrder }
        if (!uiState.value.isTesting) {
            val saved = withContext(ioDispatcher) {
                filteredServers.mapNotNull { server -> dataSource.decodeAffiliationInfo(server.guid)?.let { server.guid to it } }
            }
            _uiState.update { state ->
                if (state.isTesting) state else state.copy(
                    realResults = state.realResults + saved.mapNotNull { (id, result) -> result.realDelayMillis?.let { id to it } },
                    tcpResults = state.tcpResults + saved.mapNotNull { (id, result) -> result.tcpDelayMillis?.let { id to it } },
                )
            }
        }
        val cached = cacheMutex.withLock { groupDataCache.values.toList() }
        val counts = withContext(defaultDispatcher) {
            cached.flatMap { applyKeywordFilter(it) }.filter { !com.v2ray.ang.handler.ProfileEligibility.isAccountInfo(it.profile) }
                .groupingBy { serverCategory(it.profile) }.eachCount()
        }
        _uiState.update { it.copy(categoryCounts = counts + (ServerCategory.ALL to counts.values.sum())) }
        val built = withContext(ioDispatcher) { buildServerRows(groupId, filteredServers) }
        val rows = withContext(defaultDispatcher) {
            val state = uiState.value
            if (order == "name") built.sortedBy { it.remarks.lowercase(java.util.Locale.ROOT) }
            else sortedServerRows(built, if (state.sortProbeKind == ProbeKind.TCP) state.tcpResults else state.realResults)
        }
        val byGuid = filteredServers.associateBy { it.guid }
        mutableServerGroupState(groupId).value = ServerGroupUiState(rows.mapNotNull { byGuid[it.guid] }, rows)
    }

    private fun buildServerRows(groupId: String, servers: List<ServersCache>): List<ServerRowUiModel> {
        val subscriptionRemarks = if (groupId.isEmpty()) {
            servers.asSequence()
                .map { it.profile.subscriptionId }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { subscriptionId ->
                    dataSource.getSubscriptionItem(subscriptionId)?.remarks.orEmpty()
                }
        } else {
            emptyMap()
        }
        return servers.map { server ->
            buildServerRowUiModel(
                server = server,
                subscriptionRemarks = subscriptionRemarks[server.profile.subscriptionId].orEmpty()
            )
        }
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val subscriptions = dataSource.getSubscriptions()
                val ready = subscriptions.any { subscription ->
                    subscription.guid.isNotEmpty() &&
                        (dataSource.getServerGuidList(subscription.guid).isNotEmpty() ||
                            subscription.subscription.usage?.locked(System.currentTimeMillis() / 1000) == true)
                }
                val groups = subscriptions.map {
                    GroupMapItem(id = it.guid, remarks = it.subscription.remarks)
                }
                val selectedGroup = resolveSelectedGroup(groups)
                selectionMutex.withLock {
                    val savedGuid = dataSource.getSelectServer()
                    val savedProfile = savedGuid?.let(dataSource::decodeServerConfig)
                    if (savedProfile != null && !com.v2ray.ang.handler.ProfileEligibility.isAccountInfo(savedProfile)) savedGuid else {
                        val candidates = linkedMapOf<String, ProfileItem>()
                        (listOf(selectedGroup) + groups.map { it.id }).distinct().forEach { groupId ->
                            dataSource.getServerGuidList(groupId).forEach { guid ->
                                dataSource.decodeServerConfig(guid)?.let { candidates[guid] = it }
                            }
                        }
                        com.v2ray.ang.handler.ProfileEligibility.selectedGuid(savedGuid, savedProfile, candidates)
                            .also { if (it != savedGuid) dataSource.setSelectServer(it.orEmpty()) }
                    }
                }
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupUiFlows.keys.removeAll { it !in validIds }
                groupServerFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer()?.takeIf { guid -> guid.isNotBlank() },
                        subscriptionReady = ready,
                    )
                }
                groups.forEach { mutableServerGroupState(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    private fun submitSubscription(value: String) {
        if (!validSubscriptionUrl(value)) {
            _uiState.update { it.copy(subscriptionError = true) }
            return
        }
        _uiState.update { it.copy(subscriptionError = false) }
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val url = value.trim()
                    var subscription = dataSource.getSubscriptions().firstOrNull { it.subscription.url == url }
                    val existing = subscription != null
                    if (subscription == null) {
                        dataSource.importBatchConfig(url, "", true)
                        subscription = dataSource.getSubscriptions().firstOrNull { it.subscription.url == url }
                    }
                    if (subscription == null) {
                        _uiState.update { it.copy(subscriptionError = true) }
                    } else {
                        if (existing) dataSource.updateConfigViaSub(subscription)
                        val account = dataSource.getSubscriptionItem(subscription.guid)
                        val ready = dataSource.getServerGuidList(subscription.guid).isNotEmpty() ||
                            account?.usage?.locked(System.currentTimeMillis() / 1000) == true
                        dataSource.setSelectedSubscriptionId(subscription.guid)
                        _uiState.update { it.copy(selectedGroupId = subscription.guid, subscriptionError = !ready) }
                        setupGroupTab(forceRefresh = true).join()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription login failed", IllegalStateException(error.javaClass.simpleName))
                    _uiState.update { it.copy(subscriptionError = true) }
                }
            }
        }
    }

    private fun importBatchConfig(configText: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val previousSubscriptions = dataSource.getSubscriptions().map { it.guid }.toSet()
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> {
                            val added = dataSource.getSubscriptions().filter { it.guid !in previousSubscriptions }
                            val updated = added.map { dataSource.updateConfigViaSub(it) }
                            added.firstOrNull()?.let { subscription ->
                                dataSource.setSelectedSubscriptionId(subscription.guid)
                                _uiState.update { it.copy(selectedGroupId = subscription.guid) }
                            }
                            if (updated.any { it.failureCount > 0 }) toastError(R.string.toast_failure)
                            setupGroupTab(forceRefresh = true)
                            refreshSelectedGuid()
                        }
                        else -> toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun importConfigViaSub() {
        val subId = uiState.value.selectedGroupId
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    updateSummaryDeadline?.cancel()
                    _uiState.update { it.copy(updateSummary = result) }
                    updateSummaryDeadline = viewModelScope.launch {
                        kotlinx.coroutines.delay(5_000)
                        _uiState.update { it.expireUpdateSummary(result) }
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true).join()
                        refreshSelectedGuid()
                        if (dataSource.clientPreferences().enabled(com.v2ray.ang.handler.ClientOption.PING_UPDATE)) {
                            if (uiState.value.isRunning || uiState.value.isConnecting) dataSource.queuePostUpdateProbe(subId)
                            else withContext(Dispatchers.Main) { testAllRealPing(ignoreCategory = true) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            val guids = currentServers().map { it.guid }
                            guids.forEach { dataSource.removeServer(it) }
                            guids.size
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    duplicates.forEach { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, duplicates.size))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServerGroupState(id)
        if (uiState.value.selectedGroupId != id) {
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                dataSource.setSelectedSubscriptionId(id)
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            val snapshot = cacheMutex.withLock { groupDataCache.toMap() }
            ensureActive()
            snapshot.forEach { (groupId, servers) ->
                ensureActive()
                updateGroupUi(groupId, servers)
            }
        }
    }

    suspend fun updateSelectedGuid(guid: String): Boolean = withContext(ioDispatcher) {
        selectionMutex.withLock {
            try {
                val profile = dataSource.decodeServerConfig(guid) ?: return@withLock false
                if (com.v2ray.ang.handler.ProfileEligibility.isAccountInfo(profile)) return@withLock false
                ensureActive()
                dataSource.setSelectServer(guid)
                _uiState.update { it.copy(selectedGuid = guid) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Profile selection failed profile=$guid", error)
                toastError(R.string.toast_failure)
                false
            }
        }
    }

    /** An explicit Home action may measure first; imports never call this selection path. */
    suspend fun chooseBestForConnection(): Boolean {
        val snapshot = uiState.value
        val groupId = withContext(ioDispatcher) {
            snapshot.selectedGuid?.let { dataSource.decodeServerConfig(it)?.subscriptionId } ?: snapshot.selectedGroupId
        }
        val eligible = withContext(ioDispatcher) { loadGroup(groupId, true) }
            .filter { !com.v2ray.ang.handler.ProfileEligibility.isAccountInfo(it.profile) && !it.profile.configType.isGroupType() }
            .mapTo(linkedSetOf()) { it.guid }
        var best = fastestServerGuid(eligible, snapshot.realResults, snapshot.tcpResults)
        if (best == null && !snapshot.isRunning) {
            if (groupId != uiState.value.selectedGroupId) {
                subscriptionIdChanged(groupId)
                kotlinx.coroutines.withTimeoutOrNull(5_000) { uiState.first { it.selectedGroupId == groupId } } ?: return false
            }
            testAllRealPing(ignoreCategory = true)
            val requestId = testRequests.bulk?.id ?: return false
            val finished = kotlinx.coroutines.withTimeoutOrNull(180_000) {
                uiState.first { !it.isTesting }
            } ?: return false
            if (lastCompletedBulk != requestId || finished.selectedGuid != snapshot.selectedGuid) return false
            best = fastestServerGuid(eligible, finished.realResults, finished.tcpResults)
        }
        if (best == null) { toastError(R.string.dr_no_healthy_server); return false }
        return updateSelectedGuid(best)
    }

    suspend fun chooseBalancerForConnection(): Boolean = withContext(ioDispatcher) {
        val state = uiState.value
        val groupId = state.selectedGuid?.let { dataSource.decodeServerConfig(it)?.subscriptionId }
            ?: state.selectedGroupId
        val guid = dataSource.createBalancer(groupId) ?: return@withContext false
        setupGroupTab(forceRefresh = true).join()
        updateSelectedGuid(guid)
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    fun moveServer(groupId: String, fromPosition: Int, toPosition: Int) {
        val groupState = mutableServerGroupState(groupId).value
        val servers = groupState.servers.toMutableList()
        if (!servers.moveItem(fromPosition, toPosition)) return
        val rows = groupState.rows.toMutableList()
        rows.moveItem(fromPosition, toPosition)
        val guids = servers.map { it.guid }
        mutableServerGroupState(groupId).value = ServerGroupUiState(servers, rows)
        // A drag emits several moves; serialize writes so an older order cannot overwrite a newer one.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        serverOrderPersistenceJobs[groupId] = viewModelScope.launch(ioDispatcher) {
            previousPersistenceJob?.join()
            dataSource.encodeServerList(guids, groupId)
            cacheMutex.withLock { groupDataCache[groupId] = servers }
        }
    }

    // ---------- Testing ----------
    fun cancelAllPing() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        cancelPendingTestResults()
        resetTestStatus()
        dataSource.cancelAllPing()
    }

    private fun resetTestStatus() {
        _uiState.update {
            it.copy(
                isTesting = testRequests.isTesting,
                status = if (testRequests.isTesting) MainStatus.Testing
                else if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    fun testAllRealPing(onlyTcp: Boolean = false, ignoreCategory: Boolean = false) {
        // Standalone probes must not compete with an active AWG peer or run inside its VPN.
        // The connected-server action measures the existing connection instead.
        if (!uiState.value.canStartBulkProbe) return
        cancelAllPing()
        val groupId = uiState.value.selectedGroupId
        val category = if (ignoreCategory) ServerCategory.ALL else uiState.value.category
        val servers = currentServers().filter { !com.v2ray.ang.handler.ProfileEligibility.isAccountInfo(it.profile) &&
            (category == ServerCategory.ALL || serverCategory(it.profile) == category) }
        if (servers.isEmpty()) {
            return
        }
        val serverGuids = servers.map { it.guid }
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.testDelayMillis == 0L) server
                    else server.copy(testDelayMillis = 0L)
                },
                rows = current.rows.map { row ->
                    if (row.testDelayMillis == 0L) row
                    else row.copy(testDelayMillis = 0L)
                }
            )
        }
        val request = testRequests.beginBulk(groupId, if (onlyTcp) ProbeKind.TCP else ProbeKind.REAL)
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = serverGuids,
            onlyTcp = onlyTcp
        )
        _uiState.update {
            it.resetProbeResults(serverGuids.toSet(), if (onlyTcp) ProbeKind.TCP else ProbeKind.REAL).copy(
                isTesting = true,
                sortProbeKind = if (onlyTcp) ProbeKind.TCP else ProbeKind.REAL,
                status = MainStatus.Testing
            )
        }
        bulkTestJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        groupDataCache[groupId] = cached.map { server ->
                            if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                            else server.copy(testDelayMillis = 0L)
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
    }

    fun testCurrentServerRealPing() {
        val state = uiState.value
        if (!state.isRunning || state.isTesting || state.isConnecting || state.selectedGuid.isNullOrEmpty()) return
        val requestId = testRequests.beginCurrent(state.selectedGuid)
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing, connectedTestGuid = null, connectedTestResult = null) }
        dataSource.testCurrentServerRealPing(requestId)
    }

    private fun onTestsFinished(requestId: String) {
        val completed = testRequests.completeBulk(requestId) ?: return
        lastCompletedBulk = requestId
        resetTestStatus()
        viewModelScope.launch(ioDispatcher) {
            dataSource.sortByTestResultsForSub(completed.groupId)
            cacheMutex.withLock { groupDataCache.clear() }
            reloadAllGroups(_uiState.value.groups.map { it.id })
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        connectionDeadline?.cancel()
        if (!running || clearTestingText) testRequests.invalidateCurrent()
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isConnecting = false,
                isTesting = testRequests.isTesting,
                connectedTestGuid = if (!running || clearTestingText) null else state.connectedTestGuid,
                connectedTestResult = if (!running || clearTestingText) null else state.connectedTestResult,
                status = if (!clearTestingText && state.isRunning == running) state.status
                else if (running) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    override fun onCleared() {
        connectionDeadline?.cancel()
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L
    }
}
