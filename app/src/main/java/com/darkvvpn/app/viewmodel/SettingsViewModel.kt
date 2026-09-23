package com.darkvvpn.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.darkvvpn.app.DarkVvpnApplication
import com.darkvvpn.app.data.model.AppSettings
import com.darkvvpn.app.data.repository.SettingsRepository
import com.darkvvpn.app.vpn.VpnConnectionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the Settings screen. Writes straight through to DataStore. */
class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    // ---- connection ----------------------------------------------------
    fun setAutoConnect(enabled: Boolean) = launch { repository.setAutoConnect(enabled) }
    fun setKillSwitch(enabled: Boolean) = launch { repository.setKillSwitch(enabled) }
    fun setSortByPing(enabled: Boolean) = launch { repository.setSortByPing(enabled) }
    fun setBlockAds(enabled: Boolean) = launch { repository.setBlockAds(enabled) }

    // ---- appearance ----------------------------------------------------
    fun setForceDarkTheme(enabled: Boolean) = launch { repository.setForceDarkTheme(enabled) }
    fun setDynamicColor(enabled: Boolean) = launch { repository.setDynamicColor(enabled) }

    // ---- subscriptions -------------------------------------------------
    fun setSubscriptionRefreshHours(hours: Int) = launch {
        repository.setSubscriptionRefreshHours(hours)
    }

    fun setSubscriptionWifiOnly(enabled: Boolean) = launch {
        repository.setSubscriptionWifiOnly(enabled)
    }

    fun setMergeDuplicateNodes(enabled: Boolean) = launch {
        repository.setMergeDuplicateNodes(enabled)
    }

    // ---- updates -------------------------------------------------------
    fun setCheckForUpdatesOnLaunch(enabled: Boolean) = launch {
        repository.setCheckForUpdatesOnLaunch(enabled)
    }

    fun setAllowPrereleaseUpdates(enabled: Boolean) = launch {
        repository.setAllowPrereleaseUpdates(enabled)
    }

    fun clearSkippedUpdate() = launch { repository.setSkippedUpdateTag(null) }

    // ---- diagnostics ---------------------------------------------------

    /** Status lines the tunnel core emitted, newest last. */
    val coreStatus: StateFlow<List<String>> = VpnConnectionManager.coreStatus

    fun clearCoreStatus() = VpnConnectionManager.clearCoreStatus()

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DarkVvpnApplication
                SettingsViewModel(app.container.settingsRepository)
            }
        }
    }
}
