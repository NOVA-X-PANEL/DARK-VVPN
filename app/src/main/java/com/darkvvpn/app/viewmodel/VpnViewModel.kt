package com.darkvvpn.app.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.darkvvpn.app.DarkVvpnApplication
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.data.model.VpnState
import com.darkvvpn.app.data.model.VpnStats
import com.darkvvpn.app.data.repository.ServerRepository
import com.darkvvpn.app.data.repository.SettingsRepository
import com.darkvvpn.app.vpn.DarkVvpnService
import com.darkvvpn.app.vpn.VpnConnectionManager
import com.darkvvpn.app.xray.XrayConfigBuilder
import com.darkvvpn.app.xray.XrayConfigResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Home screen: which node is selected, what the tunnel is doing, and
 * the consent handshake with the platform.
 *
 * The connect flow needs an `Intent` to be launched by an Activity, so the
 * ViewModel *publishes* [pendingPermissionIntent] and the composable launches
 * it; the ViewModel never touches an Activity itself.
 */
class VpnViewModel(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<VpnState> = VpnConnectionManager.state
    val stats: StateFlow<VpnStats> = VpnConnectionManager.stats

    val selectedServer: StateFlow<VpnServer?> =
        combine(serverRepository.servers, serverRepository.selectedServerId) { servers, id ->
            servers.firstOrNull { it.id == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _pendingPermissionIntent = MutableStateFlow<Intent?>(null)

    /** Non-null while the system is waiting for the user to accept the VPN prompt. */
    val pendingPermissionIntent: StateFlow<Intent?> = _pendingPermissionIntent.asStateFlow()

    private var pendingServer: VpnServer? = null

    init {
        // Restore the last selection (or pick the first node) once, at startup.
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                if (settings.lastServerId != null && serverRepository.selectedServerId.value == null) {
                    serverRepository.select(settings.lastServerId)
                }
            }
        }
        serverRepository.selectFirstIfNone()
    }

    /** Entry point for the big connect button. */
    fun connect(context: Context) {
        if (state.value.isBusy || state.value.isConnected) return
        val server = selectedServer.value ?: run {
            VpnConnectionManager.onError("Pick a server before connecting.")
            return
        }
        pendingServer = server
        prepareAndStart(context, server)
    }

    fun disconnect(context: Context) {
        if (!state.value.isConnected && !state.value.isBusy) return
        DarkVvpnService.stop(context)
    }

    fun selectServer(server: VpnServer) {
        serverRepository.select(server.id)
        viewModelScope.launch { settingsRepository.setLastServerId(server.id) }
    }

    /** Called by the Activity when the system dialog returns `RESULT_OK`. */
    fun onPermissionGranted(context: Context) {
        _pendingPermissionIntent.value = null
        val server = pendingServer ?: run {
            VpnConnectionManager.onError("No server selected.")
            return
        }
        launchTunnel(context, server)
    }

    /** Called by the Activity when the user declines, or the launch fails. */
    fun onPermissionDenied() {
        _pendingPermissionIntent.value = null
        pendingServer = null
        VpnConnectionManager.onDisconnected("VPN permission was not granted.")
    }

    fun consumeError() {
        val current = state.value
        if (current is VpnState.Error || (current is VpnState.Disconnected && current.lastError != null)) {
            VpnConnectionManager.onDisconnected(null)
        }
    }

    private fun prepareAndStart(context: Context, server: VpnServer) {
        val consent = VpnService.prepare(context)
        if (consent != null) {
            VpnConnectionManager.onAwaitingPermission()
            _pendingPermissionIntent.value = consent
        } else {
            launchTunnel(context, server)
        }
    }

    /**
     * Renders the node into an Xray document and hands it to the service.
     *
     * The config is built here, while the tunnel is still disconnected, so a node
     * that cannot produce a valid config fails with a named reason *before* the
     * VPN interface is opened — rather than establishing a tunnel that silently
     * forwards nothing.
     */
    private fun launchTunnel(context: Context, server: VpnServer) {
        viewModelScope.launch {
            val blockAds = settingsRepository.settings.first().blockAdsAndTrackers

            when (val result = XrayConfigBuilder.build(server, blockAds = blockAds)) {
                is XrayConfigResult.Success -> {
                    pendingServer = null
                    DarkVvpnService.start(context, server.id, server.name, result.rendered)
                }

                is XrayConfigResult.UnsupportedProtocol -> {
                    pendingServer = null
                    VpnConnectionManager.onError(result.reason)
                }

                is XrayConfigResult.InvalidNode -> {
                    pendingServer = null
                    VpnConnectionManager.onError(result.reason)
                }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DarkVvpnApplication
                VpnViewModel(app.container.serverRepository, app.container.settingsRepository)
            }
        }
    }
}
