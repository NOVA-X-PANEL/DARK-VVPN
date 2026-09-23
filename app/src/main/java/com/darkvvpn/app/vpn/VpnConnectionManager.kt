package com.darkvvpn.app.vpn

import com.darkvvpn.app.data.model.VpnState
import com.darkvvpn.app.data.model.VpnStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide, single source of truth for the tunnel state and its counters.
 *
 * Both [DarkVvpnService] (inside the VPN process) and the UI observe this
 * object. Keeping it here — rather than inside the ViewModel — means the
 * notification and the UI can never disagree about whether the tunnel is up.
 */
object VpnConnectionManager {

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(VpnStats.Empty)
    val stats: StateFlow<VpnStats> = _stats.asStateFlow()

    // ---- transitions -------------------------------------------------------

    fun onAwaitingPermission() {
        _state.value = VpnState.AwaitingPermission
    }

    fun onConnecting() {
        _state.value = VpnState.Connecting
    }

    fun onConnected(serverId: String, serverName: String, sinceEpochMillis: Long = System.currentTimeMillis()) {
        _stats.value = VpnStats.Empty
        _state.value = VpnState.Connected(serverId, serverName, sinceEpochMillis)
    }

    fun onDisconnecting() {
        if (_state.value is VpnState.Connected || _state.value is VpnState.Connecting) {
            _state.value = VpnState.Disconnecting
        }
    }

    fun onDisconnected(error: String? = null) {
        _stats.value = VpnStats.Empty
        if (error != null) rememberFailure(error)
        _state.value = VpnState.Disconnected(error)
    }

    fun onError(message: String) {
        _stats.value = VpnStats.Empty
        rememberFailure(message)
        _state.value = VpnState.Error(message)
    }

    // ---- the readable failure card --------------------------------------

    private val _failure = MutableStateFlow<String?>(null)

    /**
     * The last connection failure, held until the user dismisses it.
     *
     * This exists because a snackbar was the wrong home for a connection error: it
     * auto-dismissed after four seconds, and the text — which is the *only* thing
     * that says why a tunnel did not come up — was gone before it could be read.
     * A failure is now a persistent card the user can read at their own pace and
     * copy out (the text is selectable), and it is also appended to
     * [coreStatus] so it survives a dismissal and lands in the diagnostics list.
     */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private fun rememberFailure(message: String) {
        if (message.isBlank()) return
        _failure.value = message
        onCoreStatus(message)
    }

    fun dismissFailure() {
        _failure.value = null
    }

    // ---- core diagnostics ----------------------------------------------

    private val _coreStatus = MutableStateFlow<List<String>>(emptyList())

    /**
     * The last few diagnostic lines the core emitted.
     *
     * Xray reports dial failures, TLS handshake errors and routing warnings
     * through its status callback. They are kept here rather than only logged,
     * because "connected but nothing loads" is the single most common tunnel
     * complaint and this is the only place that says why.
     */
    val coreStatus: StateFlow<List<String>> = _coreStatus.asStateFlow()

    fun onCoreStatus(line: String) {
        _coreStatus.update { current ->
            (current + line).takeLast(MAX_STATUS_LINES)
        }
    }

    fun clearCoreStatus() {
        _coreStatus.value = emptyList()
    }

    // ---- counters ----------------------------------------------------------

    fun pushStats(stats: VpnStats) {
        _stats.value = stats
    }

    /** Keep the newest few; the UI shows a short list, not a log file. */
    private const val MAX_STATUS_LINES = 20
}
