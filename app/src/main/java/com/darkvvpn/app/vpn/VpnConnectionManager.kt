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
        _state.value = VpnState.Disconnected(error)
    }

    fun onError(message: String) {
        _stats.value = VpnStats.Empty
        _state.value = VpnState.Error(message)
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
