package com.darkvvpn.app.data.repository

import android.util.Log
import com.darkvvpn.app.data.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The one place the app reads nodes from.
 *
 * ── Two sources, one list ────────────────────────────────────────────────────
 *  1. **Imported** — nodes pasted in as share links, owned by no subscription.
 *  2. **Subscriptions** — nodes fetched from a URL, replaced wholesale on refresh.
 *
 * They are kept apart internally and merged for consumers on every change. That
 * separation is what makes a refresh safe: replacing the subscription nodes
 * touches nothing the user imported by hand, so a pasted link never silently
 * disappears because a subscription refreshed.
 *
 * ── There is no bundled node list ────────────────────────────────────────────
 * Earlier versions shipped demo nodes on `*.invalid` hostnames. They could not
 * resolve, so they only ever produced failures, and they made a working import
 * look broken because the list still showed unusable entries. The app now starts
 * empty and says so.
 */
class ServerRepository {

    private val imported = MutableStateFlow<List<VpnServer>>(emptyList())
    private val fromSubscriptions = MutableStateFlow<List<VpnServer>>(emptyList())

    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _selectedServerId = MutableStateFlow<String?>(null)
    val selectedServerId: StateFlow<String?> = _selectedServerId.asStateFlow()

    private val _measuring = MutableStateFlow(false)

    /** True while a latency sweep is in flight, so the UI can show a spinner. */
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

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
        selectFirstIfNone()
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

    // ------------------------------------------------------------------
    // Latency
    // ------------------------------------------------------------------

    /**
     * Measures the real round-trip time to each node and stores it.
     *
     * A TCP connect is used rather than an ICMP ping: the panel's port is what
     * actually has to be reachable, ICMP is very often filtered, and an unprivileged
     * app cannot send ICMP anyway. The measured value is therefore "time to reach
     * the service", which is the number a user cares about.
     *
     * Probes run with bounded concurrency so a 200-node subscription does not open
     * 200 sockets at once.
     */
    suspend fun measureAll() {
        val snapshot = _servers.value
        if (snapshot.isEmpty()) return

        _measuring.value = true
        try {
            // Clear first: leaving a stale figure next to a node being retested
            // reads as a fresh measurement.
            _servers.update { list -> list.map { it.copy(pingMs = null) } }

            val gate = Semaphore(MAX_CONCURRENT_PROBES)
            coroutineScope {
                snapshot.map { server ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val latencyMs = probe(server.host, server.port)
                            _servers.update { list ->
                                list.map {
                                    if (it.id == server.id) it.copy(pingMs = latencyMs) else it
                                }
                            }
                        }
                    }
                }.forEach { it.await() }
            }
        } finally {
            _measuring.value = false
        }
    }

    private suspend fun probe(host: String, port: Int): Int? = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) return@withContext null
        try {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                elapsed.toInt().coerceAtLeast(0)
            }
        } catch (t: Throwable) {
            // Unreachable is a legitimate answer, and a more useful one than a
            // made-up number: the row shows "—" instead of a latency that lies.
            null
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

    fun clearSelection() {
        _selectedServerId.value = null
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    /**
     * Merges the two sources. Deduplication is on [VpnServer.nodeKey]
     * (protocol + host + port), and the subscription wins: it is the source that
     * carries an account, so it should supersede an identical manual entry.
     */
    private fun recompute() {
        val merged = LinkedHashMap<String, VpnServer>()
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

    companion object {
        private const val TAG = "ServerRepository"

        /** Enough parallelism to finish a long list quickly, few enough to be polite. */
        private const val MAX_CONCURRENT_PROBES = 12

        private const val PROBE_TIMEOUT_MS = 3_000
    }
}
