package com.darkvvpn.app.data.repository

import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/**
 * Source of truth for the server list.
 *
 * In this skeleton the catalogue is seeded locally and the latency probe is
 * simulated. To go live, swap [refresh] and [measureLatency] for calls into the
 * tunnel core and the subscription parser — the rest of the app only depends on
 * the [servers] flow and on [selectedServer], so nothing else has to change.
 */
class ServerRepository {

    private val _servers = MutableStateFlow(seedCatalogue())
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _selectedServerId = MutableStateFlow<String?>(null)
    val selectedServerId: StateFlow<String?> = _selectedServerId.asStateFlow()

    val selectedServer: VpnServer?
        get() = _servers.value.firstOrNull { it.id == _selectedServerId.value }

    /** Simulated "fetch the catalogue" round-trip. */
    suspend fun refresh() {
        delay(700)
        _servers.update { current ->
            current.map { it.copy(pingMs = null) }
        }
        measureAll()
    }

    /** Simulated latency probe for every node. */
    suspend fun measureAll() {
        val snapshot = _servers.value
        for (server in snapshot) {
            val ping = 18 + Random.nextInt(0, 320)
            _servers.update { list ->
                list.map { if (it.id == server.id) it.copy(pingMs = ping, loadPercent = Random.nextInt(5, 95)) else it }
            }
            delay(90)
        }
    }

    fun select(serverId: String) {
        if (_servers.value.any { it.id == serverId }) {
            _selectedServerId.value = serverId
        }
    }

    fun selectFirstIfNone() {
        if (_selectedServerId.value == null) {
            _selectedServerId.value = _servers.value.firstOrNull()?.id
        }
    }

    private fun seedCatalogue(): List<VpnServer> = listOf(
        VpnServer(
            name = "Amsterdam #1",
            country = "Netherlands",
            countryCode = "NL",
            city = "Amsterdam",
            host = "nl1.darkvvpn.example",
            protocol = VpnProtocol.VLESS,
        ),
        VpnServer(
            name = "Frankfurt Edge",
            country = "Germany",
            countryCode = "DE",
            city = "Frankfurt",
            host = "de1.darkvvpn.example",
            protocol = VpnProtocol.VLESS,
        ),
        VpnServer(
            name = "Istanbul Core",
            country = "Türkiye",
            countryCode = "TR",
            city = "Istanbul",
            host = "tr1.darkvvpn.example",
            protocol = VpnProtocol.TROJAN,
        ),
        VpnServer(
            name = "Dubai Fast",
            country = "UAE",
            countryCode = "AE",
            city = "Dubai",
            host = "ae1.darkvvpn.example",
            protocol = VpnProtocol.HYSTERIA2,
            isPremium = true,
        ),
        VpnServer(
            name = "Singapore Node",
            country = "Singapore",
            countryCode = "SG",
            city = "Singapore",
            host = "sg1.darkvvpn.example",
            protocol = VpnProtocol.VMESS,
        ),
        VpnServer(
            name = "New York Metro",
            country = "United States",
            countryCode = "US",
            city = "New York",
            host = "us1.darkvvpn.example",
            protocol = VpnProtocol.WIREGUARD,
            isPremium = true,
        ),
        VpnServer(
            name = "Toronto North",
            country = "Canada",
            countryCode = "CA",
            city = "Toronto",
            host = "ca1.darkvvpn.example",
            protocol = VpnProtocol.SHADOWSOCKS,
        ),
        VpnServer(
            name = "Tokyo Pulse",
            country = "Japan",
            countryCode = "JP",
            city = "Tokyo",
            host = "jp1.darkvvpn.example",
            protocol = VpnProtocol.VLESS,
        ),
    )
}
