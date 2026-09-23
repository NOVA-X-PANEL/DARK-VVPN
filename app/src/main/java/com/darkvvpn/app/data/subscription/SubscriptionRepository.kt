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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

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

/**
 * The cached node list, stored as one blob.
 *
 * It carries a schema version because [VpnServer] persists enum names: if a
 * future change renames one, old data must be discarded rather than decoded into
 * a node pointing at the wrong security layer or transport.
 */
@Serializable
data class StoredNodes(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val bySubscription: Map<String, List<VpnServer>> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** What a refresh attempt produced, so the UI can report it without guessing. */
sealed interface RefreshOutcome {
    data class Success(val serverCount: Int, val format: SubscriptionFormat) : RefreshOutcome
    data class Failure(val reason: String, val httpCode: Int? = null) : RefreshOutcome
}

/**
 * Fetches and caches subscription node lists.
 *
 * ── Node persistence ─────────────────────────────────────────────────────────
 * Fetched nodes are written to disk and restored on the next launch. Without
 * that, a subscription whose last refresh was minutes ago would show an empty
 * node list after a restart, because nothing repopulates it until the refresh
 * interval elapses — which reads to the user as "my subscription imported but
 * the configs never appeared".
 *
 * ── Node merging ─────────────────────────────────────────────────────────────
 * A refresh replaces the nodes that belong to *this* subscription and leaves
 * manual entries and other subscriptions alone. Nodes are matched on
 * [VpnServer.nodeKey] (protocol + host + port), so a provider that moves a node
 * to a different host produces a replaced entry rather than a duplicate.
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
    private val nodesCodec: NodesCodec = NodesCodec(),
) {

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    /** Nodes contributed by subscriptions, keyed by their owning subscription id. */
    private val _nodesBySubscription = MutableStateFlow<Map<String, List<VpnServer>>>(emptyMap())
    val nodesBySubscription: StateFlow<Map<String, List<VpnServer>>> = _nodesBySubscription.asStateFlow()

    /**
     * Loads persisted subscriptions **and their cached nodes**. Call once at
     * startup, before anything reads [allNodes].
     *
     * A cached node list is restored only for subscriptions that still exist, so
     * removing a subscription and restarting does not resurrect its nodes.
     */
    suspend fun load() {
        val stored = store.read()
        _subscriptions.value = stored.map { it.toDomain() }

        val liveIds = _subscriptions.value.map { it.id }.toSet()
        val cached = nodesCodec.decode(store.readNodesRaw())
            .filterKeys { it in liveIds }
        _nodesBySubscription.value = cached

        Log.i(TAG, "loaded ${_subscriptions.value.size} subscription(s), ${cached.values.sumOf { it.size }} cached nodes")
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
        persistNodes()
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
            recordFailure(subscriptionId, fetch.error, fetch.httpCode)
            return RefreshOutcome.Failure(fetch.error, fetch.httpCode)
        }

        val parsed = parser.parse(fetch.body.orEmpty(), fetch.userInfoHeader)

        if (parsed.servers.isEmpty()) {
            val reason = parsed.error ?: "The subscription contained no usable nodes."
            recordFailure(subscriptionId, reason, fetch.httpCode)
            return RefreshOutcome.Failure(reason, fetch.httpCode)
        }

        // Tag every node with its origin so a later refresh replaces exactly
        // these and nothing else.
        val tagged = parsed.servers.map { it.copy(subscriptionId = subscriptionId) }
        _nodesBySubscription.update { it + (subscriptionId to tagged) }
        persistNodes()

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

    /** Refreshes every enabled subscription, ignoring the auto-update flag. */
    suspend fun refreshAll(userAgent: String): List<Pair<String, RefreshOutcome>> {
        val targets = _subscriptions.value.filter { it.enabled }
        return targets.map { it.id to refresh(it.id, userAgent) }
    }

    /**
     * True when a refresh is worth doing on launch: at least one enabled,
     * auto-updating subscription has never been fetched, is in error, has no
     * cached nodes, or is past the interval.
     */
    fun isRefreshDue(refreshHours: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val interval = refreshHours.coerceAtLeast(1) * 60L * 60L * 1000L
        return _subscriptions.value.any { subscription ->
            if (!subscription.enabled || !subscription.autoUpdate) return@any false
            // No cached nodes means the user has nothing to connect to, whatever
            // the clock says. This is the case that made a fresh import look broken.
            if (_nodesBySubscription.value[subscription.id].isNullOrEmpty()) return@any true
            if (subscription.hasError) return@any true
            val last = subscription.lastUpdatedEpochMillis ?: return@any true
            (nowMillis - last) >= interval
        }
    }

    /** True when at least one enabled subscription has no nodes cached. */
    fun hasSubscriptionsWithoutNodes(): Boolean =
        _subscriptions.value.any { it.enabled && _nodesBySubscription.value[it.id].isNullOrEmpty() }

    /** All nodes from every enabled subscription, in subscription order. */
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
            val detail = fetch.httpCode?.let { " (HTTP $it)" }.orEmpty()
            return Triple(emptyList(), null, fetch.error + detail)
        }
        val parsed = parser.parse(fetch.body.orEmpty(), fetch.userInfoHeader)
        return Triple(parsed.servers, parsed.format.label, parsed.error)
    }

    // ------------------------------------------------------------------
    private suspend fun persistNodes() {
        store.writeNodesRaw(nodesCodec.encode(_nodesBySubscription.value))
    }

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

/** Serialises the cached node map, and refuses data from an incompatible schema. */
class NodesCodec(
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun encode(bySubscription: Map<String, List<VpnServer>>): String =
        json.encodeToString(
            StoredNodes.serializer(),
            StoredNodes(bySubscription = bySubscription),
        )

    /**
     * Returns an empty map for absent, unparseable, or schema-mismatched data.
     *
     * Discarding rather than guessing is deliberate: a node restored with the
     * wrong security layer would fail to connect in a way that looks like a
     * server problem, which is far harder to diagnose than an empty list the user
     * knows to refresh.
     */
    fun decode(raw: String?): Map<String, List<VpnServer>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val stored = json.decodeFromString(StoredNodes.serializer(), raw)
            if (stored.schemaVersion != StoredNodes.CURRENT_SCHEMA_VERSION) {
                Log.w("NodesCodec", "cached nodes are schema ${stored.schemaVersion}, discarding")
                emptyMap()
            } else {
                stored.bySubscription
            }
        } catch (t: Throwable) {
            Log.w("NodesCodec", "cached nodes could not be read, discarding")
            emptyMap()
        }
    }
}

/** Where the subscription list and its cached nodes are persisted. */
interface SubscriptionStore {
    suspend fun read(): List<StoredSubscription>
    suspend fun write(subscriptions: List<StoredSubscription>)
    suspend fun readNodesRaw(): String?
    suspend fun writeNodesRaw(payload: String)
}

/**
 * Pure classification helpers used by [HttpFetcher].
 *
 * Kept separate from the fetcher so they are unit-testable: a unit test cannot
 * open a socket to a controlled server, but it can call these directly. Testing
 * the real functions beats re-implementing their logic in the test, which would
 * pass while the product did something different.
 */
internal object ResponseClassifier {

    /**
     * True when the payload is a document rather than a node list.
     *
     * A login page or a 404 body parses into zero nodes, so without this the user
     * is told their subscription "contains no usable nodes" when the truth is
     * that the URL expired or is behind auth.
     */
    fun looksLikeMarkup(text: String): Boolean =
        // A subscription body never starts with '<': a share link begins with a
        // scheme letter and a base64 blob begins with a letter or a digit. So the
        // presence of a leading angle bracket is conclusive, and the simpler rule
        // catches fragments such as "<div>…" that a narrower "does it mention
        // html" test would miss and pass to the parser.
        text.trimStart().startsWith("<")

    /** Turns an HTTP status into something the user can act on. */
    fun describeHttpFailure(code: Int): String = when (code) {
        401, 403 ->
            "The subscription was refused (HTTP $code). The link may have expired, or your provider blocks this app."
        404 ->
            "The subscription was not found (HTTP 404). Check the URL for a typo or a truncated token."
        429 ->
            "The provider is rate-limiting you (HTTP 429). Try again in a few minutes."
        in 500..599 ->
            "The provider's server is failing (HTTP $code). This is on their side, not yours."
        else ->
            "The server returned an error (HTTP $code)."
    }

    /** Only http and https are ever dialled, at every redirect hop. */
    fun isAllowedScheme(scheme: String?): Boolean =
        scheme?.lowercase() in setOf("http", "https")

    /** The gzip magic bytes, for servers that compress without saying so. */
    fun isGzip(raw: ByteArray): Boolean =
        raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
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
 * ── Three bugs this class exists to avoid ────────────────────────────────────
 *
 * 1. **Compressed responses.** Many panels sit behind Cloudflare or nginx with
 *    gzip on, and `HttpURLConnection` does *not* decompress for you. Reading the
 *    raw stream and calling it a string yields binary noise, the parser finds no
 *    links, and the user is told their subscription has no nodes. This class asks
 *    for gzip and inflates it, and also sniffs the gzip magic bytes in case a
 *    server compresses without saying so.
 *
 * 2. **Cross-protocol redirects.** `HttpURLConnection.instanceFollowRedirects`
 *    does not follow an `http → https` hop. Plenty of panels redirect exactly
 *    that way, so the body arrives as a 301 page. Redirects are therefore walked
 *    by hand, with the scheme re-validated at every hop.
 *
 * 3. **Error pages presented as node lists.** A login page or a 404 body is valid
 *    HTML and parses into zero nodes, producing "no usable nodes" for what is
 *    really an authentication or URL problem. The body is now classified first,
 *    so the message names the actual situation.
 *
 * ── Security notes ───────────────────────────────────────────────────────────
 *  - Only `http`/`https` are dialled, at every hop, so a redirect cannot reach
 *    `file:` or `content:`.
 *  - The decompressed size is capped, not just the wire size: a small gzip bomb
 *    would otherwise expand without bound.
 *  - The body is never logged or echoed into an error message; a subscription URL
 *    usually carries an access token in it, and so does the payload.
 */
class HttpFetcher(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
) {
    fun get(url: String, userAgent: String): HttpFetch {
        var current = url.trim()

        repeat(MAX_REDIRECTS + 1) { hop ->
            val parsed = try {
                URL(current)
            } catch (_: Exception) {
                return HttpFetch(null, null, null, "That is not a valid URL.")
            }

            if (!isAllowedScheme(parsed.protocol)) {
                return HttpFetch(
                    null, null, null,
                    if (hop == 0) {
                        "Only http:// and https:// subscription URLs are allowed."
                    } else {
                        "The subscription redirected to a disallowed address."
                    },
                )
            }

            val response = requestOnce(parsed, userAgent)
            val code = response.httpCode
            if (code != null && code in 300..399 && response.location != null) {
                current = runCatching {
                    URL(parsed, response.location).toString()
                }.getOrElse {
                    return HttpFetch(null, code, null, "The subscription redirected to an invalid address.")
                }
                return@repeat
            }
            if (response.error != null || code == null) {
                return HttpFetch(null, code, response.userInfoHeader, response.error)
            }
            if (code !in 200..299) {
                return HttpFetch(null, code, response.userInfoHeader, describeHttpFailure(code))
            }

            val raw = response.bytes
                ?: return HttpFetch(null, code, response.userInfoHeader, "The server returned an empty response.")

            val text = decodeBody(raw, response.contentEncoding)
            if (text.isBlank()) {
                return HttpFetch(null, code, response.userInfoHeader, "The server returned an empty response.")
            }
            if (looksLikeMarkup(text)) {
                // A web page where a node list was expected. Naming it is the
                // difference between "your provider is down" and "no usable nodes".
                return HttpFetch(
                    null, code, response.userInfoHeader,
                    "The server returned a web page instead of a node list — " +
                        "the URL is probably wrong, expired, or behind a login.",
                )
            }

            return HttpFetch(text, code, response.userInfoHeader)
        }

        return HttpFetch(null, null, null, "The subscription redirected too many times.")
    }

    // ------------------------------------------------------------------

    private fun requestOnce(parsed: URL, userAgent: String): RawResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (parsed.openConnection() as? HttpURLConnection)
                ?: return RawResponse(error = "That URL cannot be fetched.")

            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "GET"
            // Redirects are walked by hand so the scheme is re-validated at each
            // hop and an http→https upgrade actually lands.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "gzip")

            val code = connection.responseCode
            val userInfo = connection.getHeaderField("subscription-userinfo")
                ?: connection.getHeaderField("Subscription-Userinfo")

            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                return RawResponse(httpCode = code, userInfoHeader = userInfo, location = location)
            }
            if (code !in 200..299) {
                return RawResponse(httpCode = code, userInfoHeader = userInfo)
            }

            val stream = connection.inputStream
                ?: return RawResponse(httpCode = code, userInfoHeader = userInfo, error = "The server returned no body.")
            val bytes = stream.use { readCapped(it) }
            RawResponse(
                httpCode = code,
                userInfoHeader = userInfo,
                bytes = bytes,
                contentEncoding = connection.contentEncoding,
            )
        } catch (e: IOException) {
            RawResponse(error = "Could not reach the subscription (${e.javaClass.simpleName}).")
        } catch (e: Exception) {
            RawResponse(error = "The subscription could not be loaded.")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * Inflates gzip when the server says it gzipped, and when it did so without
     * saying. Falls back to the raw bytes if inflation fails, so a mislabelled
     * response is still attempted as text rather than discarded.
     */
    private fun decodeBody(raw: ByteArray, contentEncoding: String?): String {
        val encoding = contentEncoding?.lowercase()?.trim().orEmpty()
        val gzipMagic = ResponseClassifier.isGzip(raw)

        val inflated: ByteArray? = when {
            encoding.contains("gzip") || gzipMagic ->
                inflate(raw) { GZIPInputStream(it) }
            encoding.contains("deflate") ->
                inflate(raw) { InflaterInputStream(it) }
            else -> null
        }

        if (inflated != null) return inflated.toString(Charsets.UTF_8)
        if (encoding.isNotEmpty() && encoding != "identity") {
            Log.w(TAG, "unsupported content-encoding \"$encoding\"; reading raw bytes")
        }
        return raw.toString(Charsets.UTF_8)
    }

    private fun inflate(raw: ByteArray, wrap: (java.io.InputStream) -> java.io.InputStream): ByteArray? = try {
        wrap(raw.inputStream()).use { readCapped(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "could not decompress the response; reading it as-is")
        null
    }

    /**
     * Reads at most [MAX_BODY_BYTES] **after** decompression.
     *
     * The cap has to apply here rather than on the socket, because a few
     * kilobytes of gzip can expand to gigabytes.
     */
    private fun readCapped(stream: java.io.InputStream): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            val room = MAX_BODY_BYTES - total
            if (room <= 0) break
            out.write(buffer, 0, minOf(read, room))
            total += read
        }
        return out.toByteArray()
    }

    /** True when the payload is a document rather than a node list. */
    private fun looksLikeMarkup(text: String): Boolean = ResponseClassifier.looksLikeMarkup(text)

    private fun describeHttpFailure(code: Int): String = ResponseClassifier.describeHttpFailure(code)

    private fun isAllowedScheme(scheme: String?): Boolean = ResponseClassifier.isAllowedScheme(scheme)

    private data class RawResponse(
        val httpCode: Int? = null,
        val userInfoHeader: String? = null,
        val bytes: ByteArray? = null,
        val contentEncoding: String? = null,
        val location: String? = null,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "HttpFetcher"

        /** ~4 MB, applied after decompression. Far more than any real node list. */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024

        /** Panels commonly chain one or two; more than this is a loop. */
        const val MAX_REDIRECTS = 5
    }
}
