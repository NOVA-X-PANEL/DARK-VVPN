package com.darkvvpn.app.data.repository

import android.util.Log
import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.data.model.VpnTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/**
 * The one place the app reads nodes from.
 *
 * ── Three sources, one list ──────────────────────────────────────────────────
 *  1. **Catalogue** — the built-in demo nodes, so a fresh install is usable.
 *  2. **Imported**  — nodes pasted in as share links, owned by no subscription.
 *  3. **Subscriptions** — nodes fetched from a URL, replaced wholesale on refresh.
 *
 * They are kept apart internally and merged for consumers on every change. That
 * separation is what makes a refresh safe: replacing the subscription nodes
 * touches nothing the user imported by hand and nothing built in, so importing a
 * link never silently disappears because a subscription refreshed.
 */
class ServerRepository {

    private val catalogue = MutableStateFlow(seedCatalogue())
    private val imported = MutableStateFlow<List<VpnServer>>(emptyList())
    private val fromSubscriptions = MutableStateFlow<List<VpnServer>>(emptyList())

    private val _servers = MutableStateFlow(catalogue.value)
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _selectedServerId = MutableStateFlow<String?>(null)
    val selectedServerId: StateFlow<String?> = _selectedServerId.asStateFlow()

    val selectedServer: VpnServer?
        get() = _servers.value.firstOrNull { it.id == _selectedServerId.value }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Replaces the subscription-sourced nodes. Called after every refresh. */
    fun replaceSubscriptionNodes(nodes: List<VpnServer>) {
        fromSubscriptions.value = nodes
        recompute()
        pruneSelection()
    }

    /** Adds nodes the user pasted in. Nodes already present are not duplicated. */
    fun addImported(nodes: List<VpnServer>) {
        if (nodes.isEmpty()) return
        val existingKeys = _servers.value.map { it.nodeKey }.toSet()
        val fresh = nodes.filterNot { it.nodeKey in existingKeys }
        if (fresh.isEmpty()) {
            Log.i(TAG, "addImported: all ${nodes.size} node(s) were already present")
            return
        }
        imported.value = imported.value + fresh
        recompute()
    }

    fun removeImported(serverId: String) {
        imported.value = imported.value.filterNot { it.id == serverId }
        recompute()
        pruneSelection()
    }

    /** Drops every subscription-sourced node. Used when the last sub is removed. */
    fun clearSubscriptionNodes() {
        fromSubscriptions.value = emptyList()
        recompute()
        pruneSelection()
    }

    /** Re-runs the simulated latency probe over whatever is in the list. */
    suspend fun refresh() {
        delay(300)
        _servers.update { current -> current.map { it.copy(pingMs = null) } }
        measureAll()
    }

    /** Simulated latency probe. A real build replaces this with a TCP/HTTP probe. */
    suspend fun measureAll() {
        val snapshot = _servers.value
        for (server in snapshot) {
            val ping = 18 + Random.nextInt(0, 320)
            _servers.update { list ->
                list.map {
                    if (it.id == server.id) {
                        it.copy(pingMs = ping, loadPercent = Random.nextInt(5, 95))
                    } else {
                        it
                    }
                }
            }
            delay(60)
        }
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    /**
     * Merges the three sources: subscription nodes first (they are the ones with
     * real credentials), then imported, then the built-in catalogue as filler so
     * the list is never empty on a fresh install.
     *
     * Deduplication is on [VpnServer.nodeKey] (protocol + host + port). The later
     * source wins, so a subscription node supersedes a same-endpoint demo node.
     */
    private fun recompute() {
        val merged = LinkedHashMap<String, VpnServer>()
        // Insert lowest priority first so higher priority overwrites.
        catalogue.value.forEach { merged[it.nodeKey] = it }
        imported.value.forEach { merged[it.nodeKey] = it }
        fromSubscriptions.value.forEach { merged[it.nodeKey] = it }
        _servers.value = merged.values.toList()
    }

    /** Keeps the selection valid when the selected node disappears. */
    private fun pruneSelection() {
        val id = _selectedServerId.value ?: return
        if (_servers.value.none { it.id == id }) {
            _selectedServerId.value = _servers.value.firstOrNull()?.id
        }
    }

    private companion object {
        const val TAG = "ServerRepository"
    }

    // ------------------------------------------------------------------
    // Demo catalogue
    // ------------------------------------------------------------------

    /**
     * Built-in demo nodes so the app has something to show before the user
     * imports anything. They cover the protocol and transport matrix on purpose:
     * a VLESS+REALITY node, a VLESS+Vision node, a WS node, a gRPC node, and the
     * non-Xray Hysteria2 case — which means the config builder's branches are all
     * exercised the moment the app is opened.
     */
    private fun seedCatalogue(): List<VpnServer> = listOf(
        VpnServer(
            name = "Amsterdam REALITY",
            country = "Netherlands",
            countryCode = "NL",
            city = "Amsterdam",
            host = "nl1.example.invalid",
            port = 443,
            protocol = VpnProtocol.VLESS,
            security = VpnSecurity.REALITY,
            transport = VpnTransport.TCP,
            sni = "www.microsoft.com",
            fingerprint = "chrome",
            publicKey = "DEMO_PUBLIC_KEY_REPLACE_ME",
            shortId = "0123456789abcdef",
            spiderX = "/",
            uuid = "00000000-0000-4000-8000-000000000001",
            flow = com.darkvvpn.app.data.model.VlessFlow.VISION,
        ),
        VpnServer(
            name = "Frankfurt Vision",
            country = "Germany",
            countryCode = "DE",
            city = "Frankfurt",
            host = "de1.example.invalid",
            port = 443,
            protocol = VpnProtocol.VLESS,
            security = VpnSecurity.TLS,
            transport = VpnTransport.TCP,
            sni = "de1.example.invalid",
            fingerprint = "chrome",
            uuid = "00000000-0000-4000-8000-000000000002",
            flow = com.darkvvpn.app.data.model.VlessFlow.VISION,
        ),
        VpnServer(
            name = "Istanbul WebSocket",
            country = "Türkiye",
            countryCode = "TR",
            city = "Istanbul",
            host = "tr1.example.invalid",
            port = 8443,
            protocol = VpnProtocol.VLESS,
            security = VpnSecurity.TLS,
            transport = VpnTransport.WS,
            sni = "tr1.example.invalid",
            path = "/ws",
            hostHeader = "tr1.example.invalid",
            uuid = "00000000-0000-4000-8000-000000000003",
        ),
        VpnServer(
            name = "Dubai gRPC",
            country = "UAE",
            countryCode = "AE",
            city = "Dubai",
            host = "ae1.example.invalid",
            port = 443,
            protocol = VpnProtocol.TROJAN,
            security = VpnSecurity.TLS,
            transport = VpnTransport.GRPC,
            sni = "ae1.example.invalid",
            serviceName = "grpcsvc",
            password = "demo-trojan-password",
            isPremium = true,
        ),
        VpnServer(
            name = "Singapore VMess",
            country = "Singapore",
            countryCode = "SG",
            city = "Singapore",
            host = "sg1.example.invalid",
            port = 443,
            protocol = VpnProtocol.VMESS,
            security = VpnSecurity.TLS,
            transport = VpnTransport.WS,
            sni = "sg1.example.invalid",
            path = "/vm",
            uuid = "00000000-0000-4000-8000-000000000005",
            alterId = 0,
        ),
        VpnServer(
            name = "Tokyo Shadowsocks",
            country = "Japan",
            countryCode = "JP",
            city = "Tokyo",
            host = "jp1.example.invalid",
            port = 8388,
            protocol = VpnProtocol.SHADOWSOCKS,
            security = VpnSecurity.NONE,
            transport = VpnTransport.TCP,
            method = "aes-256-gcm",
            password = "demo-ss-password",
        ),
        VpnServer(
            name = "Toronto Hysteria2",
            country = "Canada",
            countryCode = "CA",
            city = "Toronto",
            host = "ca1.example.invalid",
            port = 443,
            protocol = VpnProtocol.HYSTERIA2,
            security = VpnSecurity.TLS,
            sni = "ca1.example.invalid",
            password = "demo-hy2-password",
            isPremium = true,
        ),
    )
}
