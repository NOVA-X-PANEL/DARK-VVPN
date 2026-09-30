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

/** Backs the Servers screen: search, sort, latency and selection. */
class ServersViewModel(private val repository: ServerRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** True while a latency sweep is running. */
    val measuring: StateFlow<Boolean> = repository.measuring

    val selectedServerId: StateFlow<String?> = repository.selectedServerId

    /** The full list, unsorted and unfiltered — used to decide whether it is empty. */
    val allServers: StateFlow<List<VpnServer>> = repository.servers

    val servers: StateFlow<List<VpnServer>> =
        combine(repository.servers, _query) { list, q ->
            val filtered = if (q.isBlank()) {
                list
            } else {
                list.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.country.contains(q, ignoreCase = true) ||
                        it.city.contains(q, ignoreCase = true) ||
                        it.protocol.label.contains(q, ignoreCase = true) ||
                        it.host.contains(q, ignoreCase = true)
                }
            }
            // Sort order:
            // 1. Valid ping (fastest first: 15ms, 45ms, 120ms...)
            // 2. Timed out nodes (pingMs < 0)
            // 3. Unmeasured nodes (pingMs == null, "—")
            filtered.sortedWith(
                compareBy<VpnServer> {
                    when {
                        it.pingMs == null -> 2_000_000_000
                        it.pingMs < 0 -> 1_000_000_000
                        else -> it.pingMs
                    }
                }.thenBy { it.name }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tracks previous server count so new additions get measured. */
    private var lastMeasuredCount = 0

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun select(server: VpnServer) = repository.select(server.id)

    /**
     * Runs a latency sweep when the list first becomes non-empty or servers change.
     */
    fun measureOnceIfNeeded() {
        val count = allServers.value.size
        if (count == 0 || measuring.value) return
        if (count != lastMeasuredCount) {
            lastMeasuredCount = count
            measureAll()
        }
    }

    /** The refresh button: re-probe every node. */
    fun measureAll() {
        if (measuring.value) return
        viewModelScope.launch { repository.measureAll() }
    }

    fun removeImported(server: VpnServer) {
        viewModelScope.launch { repository.removeImported(server.id) }
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
