package com.darkvvpn.app.data.model

import java.util.UUID

/**
 * A remote subscription URL the panel/provider serves a node list from.
 *
 * Subscriptions are refreshed on [SettingsKey.SubscriptionRefreshHours] and the
 * outcome of the last attempt is kept so the UI can show it without re-fetching.
 */
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,

    /** When true this subscription's nodes are refreshed on the schedule. */
    val autoUpdate: Boolean = true,

    /** `Content-Type`/body detected on the last successful fetch. */
    val format: SubscriptionFormat = SubscriptionFormat.UNKNOWN,

    val enabled: Boolean = true,

    // ---- last fetch outcome ------------------------------------------
    val lastUpdatedEpochMillis: Long? = null,
    val lastError: String? = null,
    /** Node count from the last successful fetch. */
    val lastNodeCount: Int = 0,

    // ---- traffic headers, when the provider sends them -----------------
    /** `subscription-userinfo: upload=…; download=…; total=…; expire=…` */
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochMillis: Long? = null,
) {
    val hasError: Boolean get() = lastError != null

    /** 0.0..1.0, or `null` when the provider does not report a quota. */
    val quotaUsedFraction: Float?
        get() {
            val total = totalBytes ?: return null
            val used = usedBytes ?: return null
            if (total <= 0L) return null
            return (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

/**
 * How a subscription body was shaped. Used to label the source in the UI and to
 * decide which parser to run first.
 */
enum class SubscriptionFormat(val label: String) {
    /** A base64 blob that decodes to one share link per line. */
    BASE64_LIST("Base64 list"),

    /** Plain text, one share link per line. */
    PLAIN_LINKS("Share links"),

    /** A Clash/mihomo YAML document with a `proxies:` list. */
    CLASH_YAML("Clash YAML"),

    /** A sing-box JSON document with an `outbounds` array. */
    SINGBOX_JSON("sing-box JSON"),

    UNKNOWN("Unknown"),
}

/** Keys for the preference store, kept next to the model they describe. */
object SettingsKey {
    const val SubscriptionRefreshHours = 12
    const val DefaultSubscriptionUserAgent = "DARK-VVPN/1.0"
}
