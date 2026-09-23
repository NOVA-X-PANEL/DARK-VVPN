package com.darkvvpn.app.data.subscription

import android.util.Log
import com.darkvvpn.app.data.model.Subscription
import com.darkvvpn.app.data.model.SubscriptionFormat
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.util.Redact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A subscription as it is persisted. Kept separate from the domain model so the
 * on-disk shape can change without touching the UI.
 *
 * Public because [SubscriptionStore] is part of the repository's constructor —
 * an internal type in a public signature would not compile.
 */
@Serializable
data class StoredSubscription(
    val id: String,
    val name: String,
    val url: String,
    val autoUpdate: Boolean = true,
    val enabled: Boolean = true,
    val format: String = SubscriptionFormat.UNKNOWN.name,
    val lastUpdatedEpochMillis: Long? = null,
    val lastError: String? = null,
    val lastNodeCount: Int = 0,
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochMillis: Long? = null,
)

/** What a refresh attempt produced, so the UI can report it without guessing. */
sealed interface RefreshOutcome {
    data class Success(val serverCount: Int, val format: SubscriptionFormat) : RefreshOutcome
    data class Failure(val reason: String, val httpCode: Int? = null) : RefreshOutcome
}

/**
 * Fetches and caches subscription node lists.
 *
 * ── Node merging ─────────────────────────────────────────────────────────────
 * A refresh replaces the nodes that belong to *this* subscription and leaves
 * every manual entry and every other subscription's nodes alone. Nodes are
 * matched on [VpnServer.nodeKey] (protocol + host + port), so a provider that
 * renames a node or moves it to a different host produces a replaced entry
 * rather than a duplicate.
 *
 * ── Threading ────────────────────────────────────────────────────────────────
 * Every network call is blocking and must be invoked from a background
 * dispatcher; the repository does not switch dispatchers itself so callers can
 * keep a whole refresh batch on one dispatcher.
 */
class SubscriptionRepository(
    private val parser: SubscriptionParser = SubscriptionParser(),
    private val store: SubscriptionStore,
    private val http: HttpFetcher = HttpFetcher(),
) {

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    /** Nodes contributed by subscriptions, keyed by their owning subscription id. */
    private val _nodesBySubscription = MutableStateFlow<Map<String, List<VpnServer>>>(emptyMap())
    val nodesBySubscription: StateFlow<Map<String, List<VpnServer>>> = _nodesBySubscription.asStateFlow()

    /** Loads persisted subscriptions. Call once at startup. */
    suspend fun load() {
        val stored = store.read()
        _subscriptions.value = stored.map { it.toDomain() }
        // Nodes are not persisted: they are re-fetched, which keeps a stale node
        // list from surviving a quota reset or an expired account.
    }

    suspend fun add(name: String, url: String, autoUpdate: Boolean = true): Subscription {
        val subscription = Subscription(
            name = name.ifBlank { deriveName(url) },
            url = url.trim(),
            autoUpdate = autoUpdate,
        )
        mutate { it + subscription }
        return subscription
    }

    suspend fun remove(subscriptionId: String) {
        mutate { list -> list.filterNot { it.id == subscriptionId } }
        _nodesBySubscription.update { it - subscriptionId }
    }

    suspend fun update(subscription: Subscription) {
        mutate { list -> list.map { if (it.id == subscription.id) subscription else it } }
    }

    suspend fun setEnabled(subscriptionId: String, enabled: Boolean) {
        mutate { list -> list.map { if (it.id == subscriptionId) it.copy(enabled = enabled) else it } }
    }

    suspend fun setAutoUpdate(subscriptionId: String, autoUpdate: Boolean) {
        mutate { list -> list.map { if (it.id == subscriptionId) it.copy(autoUpdate = autoUpdate) else it } }
    }

    /**
     * Refreshes one subscription. Never throws: a network or parse failure is
     * recorded on the subscription and returned as [RefreshOutcome.Failure].
     */
    suspend fun refresh(subscriptionId: String, userAgent: String): RefreshOutcome {
        val subscription = _subscriptions.value.firstOrNull { it.id == subscriptionId }
            ?: return RefreshOutcome.Failure("This subscription no longer exists.")

        val fetch = http.get(subscription.url, userAgent)

        if (fetch.error != null) {
            recordFailure(subscriptionId, fetch.error)
            return RefreshOutcome.Failure(fetch.error, fetch.httpCode)
        }

        val parsed = parser.parse(fetch.body.orEmpty(), fetch.userInfoHeader)

        if (parsed.servers.isEmpty()) {
            val reason = parsed.error ?: "The subscription contained no usable nodes."
            recordFailure(subscriptionId, reason, fetch.httpCode)
            return RefreshOutcome.Failure(reason, fetch.httpCode)
        }

        // Tag every node with its origin so a later refresh can replace exactly
        // these and nothing else.
        val tagged = parsed.servers.map { it.copy(subscriptionId = subscriptionId) }
        _nodesBySubscription.update { it + (subscriptionId to tagged) }

        val traffic = parsed.traffic
        mutate { list ->
            list.map { existing ->
                if (existing.id != subscriptionId) {
                    existing
                } else {
                    existing.copy(
                        format = parsed.format,
                        lastUpdatedEpochMillis = System.currentTimeMillis(),
                        lastError = null,
                        lastNodeCount = tagged.size,
                        usedBytes = traffic?.usedBytes ?: existing.usedBytes,
                        totalBytes = traffic?.totalBytes ?: existing.totalBytes,
                        expiresAtEpochMillis = traffic?.expiresAtEpochMillis
                            ?: existing.expiresAtEpochMillis,
                    )
                }
            }
        }
        Log.i(TAG, "refreshed ${Redact.url(subscription.url)}: ${tagged.size} nodes")
        return RefreshOutcome.Success(tagged.size, parsed.format)
    }

    /** Refreshes every enabled subscription that opted into auto-update. */
    suspend fun refreshAllAuto(userAgent: String): List<Pair<String, RefreshOutcome>> {
        val targets = _subscriptions.value.filter { it.enabled && it.autoUpdate }
        return targets.map { it.id to refresh(it.id, userAgent) }
    }

    /**
     * True when a scheduled refresh is due: at least one enabled, auto-updating
     * subscription has never been fetched, is in error, or was last fetched more
     * than [refreshHours] ago.
     */
    fun isRefreshDue(refreshHours: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (refreshHours <= 0) return false
        val interval = refreshHours * 60L * 60L * 1000L
        return _subscriptions.value.any { subscription ->
            if (!subscription.enabled || !subscription.autoUpdate) return@any false
            val last = subscription.lastUpdatedEpochMillis ?: return@any true
            subscription.hasError || (nowMillis - last) >= interval
        }
    }

    /** All nodes from every subscription, newest subscription last. */
    fun allNodes(): List<VpnServer> =
        _subscriptions.value
            .filter { it.enabled }
            .flatMap { _nodesBySubscription.value[it.id].orEmpty() }

    /** Imports pasted links directly, with no subscription to track them under. */
    fun importLinks(payload: String): List<VpnServer> = parser.parse(payload).servers

    /**
     * Fetches a URL and returns what it holds **without** storing anything, so
     * the import dialog can show the user what they are about to add.
     */
    suspend fun previewUrl(
        url: String,
        userAgent: String = "DARK-VPN/1.0",
    ): Triple<List<VpnServer>, String?, String?> {
        val fetch = http.get(url, userAgent)
        if (fetch.error != null) {
            return Triple(emptyList(), null, fetch.error)
        }
        val parsed = parser.parse(fetch.body.orEmpty(), fetch.userInfoHeader)
        return Triple(parsed.servers, parsed.format.label, parsed.error)
    }

    // ------------------------------------------------------------------
    private suspend fun recordFailure(subscriptionId: String, reason: String, httpCode: Int? = null) {
        val message = if (httpCode != null) "$reason (HTTP $httpCode)" else reason
        mutate { list ->
            list.map {
                if (it.id == subscriptionId) it.copy(lastError = message) else it
            }
        }
        Log.w(TAG, "subscription refresh failed: $message")
    }

    private suspend fun mutate(block: (List<Subscription>) -> List<Subscription>) {
        val updated = block(_subscriptions.value)
        _subscriptions.value = updated
        store.write(updated.map { it.toStored() })
    }

    private fun Subscription.toStored() = StoredSubscription(
        id = id,
        name = name,
        url = url,
        autoUpdate = autoUpdate,
        enabled = enabled,
        format = format.name,
        lastUpdatedEpochMillis = lastUpdatedEpochMillis,
        lastError = lastError,
        lastNodeCount = lastNodeCount,
        usedBytes = usedBytes,
        totalBytes = totalBytes,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private fun StoredSubscription.toDomain() = Subscription(
        id = id,
        name = name,
        url = url,
        autoUpdate = autoUpdate,
        enabled = enabled,
        format = SubscriptionFormat.entries.firstOrNull { it.name == format }
            ?: SubscriptionFormat.UNKNOWN,
        lastUpdatedEpochMillis = lastUpdatedEpochMillis,
        lastError = lastError,
        lastNodeCount = lastNodeCount,
        usedBytes = usedBytes,
        totalBytes = totalBytes,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    /** `https://host/path/sub?token=abc` → `host` — a sane default name. */
    private fun deriveName(url: String): String = try {
        URL(url).host.ifBlank { "Subscription" }
    } catch (_: Exception) {
        "Subscription"
    }

    private companion object {
        const val TAG = "SubscriptionRepo"
    }
}

/** Where the subscription list is persisted. Implemented on DataStore. */
interface SubscriptionStore {
    suspend fun read(): List<StoredSubscription>
    suspend fun write(subscriptions: List<StoredSubscription>)
}

/** Result of one HTTP GET. */
data class HttpFetch(
    val body: String?,
    val httpCode: Int?,
    val userInfoHeader: String?,
    val error: String? = null,
)

/**
 * Fetches a subscription URL.
 *
 * SECURITY NOTES
 *  - Only `http`/`https` are dialled; anything else (including `file:` and
 *    `content:`) is refused, so a pasted URL cannot read local files.
 *  - Redirects are followed by [HttpURLConnection] but the *final* protocol is
 *    re-checked, because a redirect can change the scheme.
 *  - The response body is capped by [MAX_BODY_BYTES]; a hostile or broken server
 *    must not be able to exhaust memory by streaming forever.
 *  - The body is never logged; only its size and HTTP code are.
 */
class HttpFetcher(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
) {
    fun get(url: String, userAgent: String): HttpFetch {
        val parsed = try {
            URL(url)
        } catch (_: Exception) {
            return HttpFetch(null, null, null, "That is not a valid URL.")
        }

        if (!isAllowedScheme(parsed.protocol)) {
            return HttpFetch(null, null, null, "Only http:// and https:// subscription URLs are allowed.")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (parsed.openConnection() as? HttpURLConnection)
                ?: return HttpFetch(null, null, null, "That URL cannot be fetched.")

            connection.instanceFollowRedirects = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "*/*")

            val code = connection.responseCode
            // Re-check after redirects: the final URL decides, not the one asked for.
            if (connection.url != null && !isAllowedScheme(connection.url.protocol)) {
                return HttpFetch(null, code, null, "The subscription redirected to a disallowed scheme.")
            }

            val userInfo = connection.getHeaderField("subscription-userinfo")
                ?: connection.getHeaderField("Subscription-Userinfo")

            if (code !in 200..299) {
                return HttpFetch(null, code, userInfo, "The server returned an error.")
            }

            val stream = connection.inputStream ?: return HttpFetch(null, code, userInfo, "The server returned no body.")
            val body = stream.use { readCapped(it) }
            HttpFetch(body, code, userInfo)
        } catch (e: IOException) {
            HttpFetch(null, null, null, "Could not reach the subscription: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            HttpFetch(null, null, null, "The subscription could not be loaded.")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun readCapped(stream: java.io.InputStream): String {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_BODY_BYTES) {
                // Truncate rather than fail: a valid list at the front is still
                // usable, and the alternative is an unbounded read.
                out.write(buffer, 0, MAX_BODY_BYTES - (total - read))
                break
            }
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun isAllowedScheme(scheme: String?): Boolean =
        scheme?.lowercase() in setOf("http", "https")

    companion object {
        /** ~4 MB: comfortably more than any real node list, far less than RAM. */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}
