package com.darkvvpn.app.vpn

import com.darkvvpn.app.data.model.VpnState
import com.darkvvpn.app.data.model.VpnStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    // ---- counters ----------------------------------------------------------

    fun pushStats(stats: VpnStats) {
        _stats.value = stats
    }
}
