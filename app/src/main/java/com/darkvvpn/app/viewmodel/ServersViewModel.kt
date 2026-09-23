package com.darkvvpn.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.darkvvpn.app.DarkVvpnApplication
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.data.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the Servers screen: search, sort and selection. */
class ServersViewModel(private val repository: ServerRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val selectedServerId: StateFlow<String?> = repository.selectedServerId

    val servers: StateFlow<List<VpnServer>> =
        combine(repository.servers, _query) { list, q ->
            val filtered = if (q.isBlank()) {
                list
            } else {
                list.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.country.contains(q, ignoreCase = true) ||
                        it.city.contains(q, ignoreCase = true) ||
                        it.protocol.label.contains(q, ignoreCase = true)
                }
            }
            filtered.sortedWith(
                compareBy({ it.pingMs ?: Int.MAX_VALUE }, { it.name }),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun select(server: VpnServer) = repository.select(server.id)

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                repository.refresh()
            } finally {
                _refreshing.value = false
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DarkVvpnApplication
                ServersViewModel(app.container.serverRepository)
            }
        }
    }
}
