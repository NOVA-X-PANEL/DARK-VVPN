package com.darkvvpn.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.darkvvpn.app.DarkVvpnApplication
import com.darkvvpn.app.data.model.Subscription
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.data.repository.ServerRepository
import com.darkvvpn.app.data.repository.SettingsRepository
import com.darkvvpn.app.data.subscription.RefreshOutcome
import com.darkvvpn.app.data.subscription.SubscriptionRepository
import com.darkvvpn.app.util.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A one-shot message for the screen to surface in a snackbar. */
data class SubscriptionMessage(val text: String, val isError: Boolean)

/** What the import dialog is currently holding. */
data class ImportState(
    val urlInput: String = "",
    val nameInput: String = "",
    val autoUpdate: Boolean = true,
    val isWorking: Boolean = false,
    val preview: List<VpnServer> = emptyList(),
    val previewFormat: String? = null,
    val error: String? = null,
) {
    val canImport: Boolean get() = urlInput.isNotBlank() && !isWorking
}

/**
 * Backs the Subscriptions screen: the stored list, refresh, import, and the
 * automatic refresh schedule.
 */
class SubscriptionsViewModel(
    private val repository: SubscriptionRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val subscriptions: StateFlow<List<Subscription>> = repository.subscriptions

    /** Subscription ids currently being refreshed, so each row can spin alone. */
    private val _refreshingIds = MutableStateFlow<Set<String>>(emptySet())
    val refreshingIds: StateFlow<Set<String>> = _refreshingIds.asStateFlow()

    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()

    private val _message = MutableStateFlow<SubscriptionMessage?>(null)
    val message: StateFlow<SubscriptionMessage?> = _message.asStateFlow()

    /** Total nodes contributed by subscriptions, for the summary line. */
    val nodeCount: StateFlow<Int> = repository.nodesBySubscription
        .map { it.values.sumOf { nodes -> nodes.size } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            // Guarded because this coroutine runs on the ViewModel scope, where an
            // uncaught exception reaches the default handler and takes the process
            // down. A storage read is not supposed to fail, but "not supposed to"
            // is not a guarantee, and an app that cannot open because a preferences
            // file is unreadable is worse than one that opens with an empty list.
            try {
                // Loading restores both the subscription list and its cached node
                // list from disk. The nodes must then be pushed into the
                // catalogue: this is the step whose absence made an imported
                // subscription show an empty Servers screen until the next
                // scheduled refresh.
                repository.load()
                publishNodes()

                // If a subscription has no cached nodes — a first run, or a cache
                // written by an older schema — fetch now rather than waiting for
                // the interval. Otherwise a restart inside the refresh window
                // leaves the user with nothing to connect to.
                if (repository.hasSubscriptionsWithoutNodes()) {
                    val settings = settingsRepository.settings.first()
                    if (!settings.subscriptionRefreshOverWifiOnly || networkIsUnmetered()) {
                        repository.refreshAllAuto(settings.subscriptionUserAgent)
                        publishNodes()
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _message.value = SubscriptionMessage(
                    "Stored subscriptions could not be read: " +
                        (t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName),
                    isError = true,
                )
            }
        }
    }

    // ---- refresh ------------------------------------------------------

    fun refresh(subscriptionId: String) {
        if (subscriptionId in _refreshingIds.value) return
        viewModelScope.launch {
            _refreshingIds.update { it + subscriptionId }
            try {
                val userAgent = settingsRepository.settings.first().subscriptionUserAgent
                when (val outcome = repository.refresh(subscriptionId, userAgent)) {
                    is RefreshOutcome.Success -> {
                        publishNodes()
                        _message.value = SubscriptionMessage(
                            "${outcome.serverCount} nodes updated.",
                            isError = false,
                        )
                    }
                    is RefreshOutcome.Failure ->
                        _message.value = SubscriptionMessage(outcome.reason, isError = true)
                }
            } finally {
                _refreshingIds.update { it - subscriptionId }
            }
        }
    }

    /** The toolbar button: fetch everything enabled, not just the auto ones. */
    fun refreshAll() = refreshEverything()

    /**
     * Runs a scheduled refresh only when one is due, so launching the app does
     * not hit the provider on every open.
     */
    fun refreshIfDue() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (!repository.isRefreshDue(settings.subscriptionRefreshHours)) return@launch
            if (settings.subscriptionRefreshOverWifiOnly && !networkIsUnmetered()) {
                return@launch
            }
            val userAgent = settings.subscriptionUserAgent
            repository.refreshAllAuto(userAgent)
            publishNodes()
        }
    }

    /**
     * Fetches every enabled subscription, whatever the schedule says.
     *
     * This is what the user reaches for when the list is empty or a node stopped
     * working, so it reports what happened instead of finishing silently.
     */
    fun refreshEverything() {
        viewModelScope.launch {
            val targets = subscriptions.value.filter { it.enabled }
            if (targets.isEmpty()) {
                _message.value = SubscriptionMessage("There is nothing to refresh yet.", isError = false)
                return@launch
            }
            _refreshingIds.update { it + targets.map { s -> s.id } }
            try {
                val userAgent = settingsRepository.settings.first().subscriptionUserAgent
                val results = repository.refreshAll(userAgent)
                publishNodes()
                val ok = results.count { it.second is RefreshOutcome.Success }
                val failed = results.size - ok
                val nodes = repository.allNodes().size
                _message.value = if (failed == 0) {
                    SubscriptionMessage("Updated $ok subscription(s) — $nodes node(s).", isError = false)
                } else {
                    val firstError = results.firstNotNullOfOrNull { it.second as? RefreshOutcome.Failure }
                    SubscriptionMessage(
                        "Updated $ok of ${results.size}. ${firstError?.reason.orEmpty()}".trim(),
                        isError = ok == 0,
                    )
                }
            } finally {
                _refreshingIds.update { it - targets.map { s -> s.id }.toSet() }
            }
        }
    }

    // ---- import ------------------------------------------------------

    fun onImportUrlChange(value: String) = _import.update { it.copy(urlInput = value, error = null) }

    fun onImportNameChange(value: String) = _import.update { it.copy(nameInput = value) }

    fun onImportAutoUpdateChange(value: Boolean) = _import.update { it.copy(autoUpdate = value) }

    fun clearImport() {
        _import.value = ImportState()
    }

    /** Fetches without saving, so the user can see what they are about to add. */
    fun preview(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _import.update { it.copy(isWorking = true, error = null, preview = emptyList()) }
            val (servers, format, error) = repository.previewUrl(url)
            _import.update {
                it.copy(
                    isWorking = false,
                    preview = servers,
                    previewFormat = format,
                    error = error,
                )
            }
        }
    }

    /**
     * Imports the current input. A share link or a pasted list is added as nodes
     * directly; anything else is treated as a subscription URL.
     */
    fun importCurrentInput() {
        val state = _import.value
        val input = state.urlInput.trim()
        if (input.isEmpty()) return

        viewModelScope.launch {
            _import.update { it.copy(isWorking = true, error = null) }

            if (looksLikeShareLinkList(input)) {
                val servers = repository.importLinks(input)
                if (servers.isEmpty()) {
                    _import.update {
                        it.copy(isWorking = false, error = "No usable node was found in that text.")
                    }
                    return@launch
                }
                serverRepository.addImported(servers)
                _import.value = ImportState()
                _message.value = SubscriptionMessage(
                    "Imported ${servers.size} node(s).",
                    isError = false,
                )
                return@launch
            }

            val subscription = repository.add(name = state.nameInput, url = input, autoUpdate = state.autoUpdate)
            val userAgent = settingsRepository.settings.first().subscriptionUserAgent
            when (val outcome = repository.refresh(subscription.id, userAgent)) {
                is RefreshOutcome.Success -> {
                    publishNodes()
                    _import.value = ImportState()
                    _message.value = SubscriptionMessage(
                        "Added \"${subscription.name}\" with ${outcome.serverCount} nodes.",
                        isError = false,
                    )
                }
                is RefreshOutcome.Failure -> {
                    // The subscription is kept: the URL may simply be temporarily
                    // unreachable, and the row shows the error and a retry.
                    _import.value = ImportState()
                    _message.value = SubscriptionMessage(
                        "Added, but the first fetch failed: ${outcome.reason}",
                        isError = true,
                    )
                }
            }
        }
    }

    // ---- manage ------------------------------------------------------

    fun remove(subscriptionId: String) {
        viewModelScope.launch {
            repository.remove(subscriptionId)
            publishNodes()
            _message.value = SubscriptionMessage("Subscription removed.", isError = false)
        }
    }

    fun toggleEnabled(subscription: Subscription) {
        viewModelScope.launch {
            repository.setEnabled(subscription.id, !subscription.enabled)
            publishNodes()
        }
    }

    fun toggleAutoUpdate(subscription: Subscription) {
        viewModelScope.launch { repository.setAutoUpdate(subscription.id, !subscription.autoUpdate) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---- plumbing ----------------------------------------------------

    /** Pushes the merged node list into the catalogue the Servers screen reads. */
    private fun publishNodes() {
        serverRepository.replaceSubscriptionNodes(repository.allNodes())
    }

    private fun looksLikeShareLinkList(input: String): Boolean =
        input.contains("://") && SubscriptionSchemes.any { input.contains("$it://") }

    /** Best-effort check; the caller treats "unknown" as metered and skips. */
    private fun networkIsUnmetered(): Boolean = NetworkState.isUnmetered

    companion object {
        private val SubscriptionSchemes = listOf(
            "vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "socks",
        )

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as DarkVvpnApplication
                SubscriptionsViewModel(
                    app.container.subscriptionRepository,
                    app.container.serverRepository,
                    app.container.settingsRepository,
                )
            }
        }
    }
}
