package com.darkvvpn.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.darkvvpn.app.DarkVvpnApplication
import com.darkvvpn.app.data.model.AppSettings
import com.darkvvpn.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the Settings screen. Writes straight through to DataStore. */
class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setAutoConnect(enabled: Boolean) = launch { repository.setAutoConnect(enabled) }
    fun setKillSwitch(enabled: Boolean) = launch { repository.setKillSwitch(enabled) }
    fun setForceDarkTheme(enabled: Boolean) = launch { repository.setForceDarkTheme(enabled) }
    fun setDynamicColor(enabled: Boolean) = launch { repository.setDynamicColor(enabled) }
    fun setSortByPing(enabled: Boolean) = launch { repository.setSortByPing(enabled) }
    fun setBlockAds(enabled: Boolean) = launch { repository.setBlockAds(enabled) }

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
