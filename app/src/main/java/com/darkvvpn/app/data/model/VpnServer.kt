package com.darkvvpn.app.data.model

import java.util.UUID

/**
 * A single upstream node the user can connect through.
 *
 * This is the single representation every input path funnels into — the built-in
 * catalogue, an imported share link, and a fetched subscription list all produce
 * a [VpnServer]. [com.darkvvpn.app.xray.XrayConfigBuilder] then turns it into an
 * Xray outbound, so a new input format never needs a new config path.
 *
 * SECURITY: [uuid], [password], [publicKey] and [privateKey] are connection
 * credentials. They must never be written to logcat (use
 * [com.darkvvpn.app.util.Redact]) and must not reach a cloud backup — see
 * `res/xml/backup_rules.xml`.
 */
data class VpnServer(
    val id: String = UUID.randomUUID().toString(),

    // ---- identity -------------------------------------------------------
    val name: String,
    val country: String = "",
    val countryCode: String = "",
    val city: String = "",

    // ---- endpoint -------------------------------------------------------
    val host: String,
    val port: Int = VpnProtocol.VLESS.defaultPort,
    val protocol: VpnProtocol = VpnProtocol.VLESS,

    // ---- transport security --------------------------------------------
    val security: VpnSecurity = VpnSecurity.NONE,
    val transport: VpnTransport = VpnTransport.TCP,

    /** SNI / `serverName`. Also the REALITY handshake target name. */
    val sni: String? = null,
    /** `fingerprint` for uTLS — `chrome`, `firefox`, `safari`, `random`… */
    val fingerprint: String? = null,
    /** REALITY public key. */
    val publicKey: String? = null,
    /** REALITY short id (hex, may be empty). */
    val shortId: String? = null,
    /** REALITY spiderX path. */
    val spiderX: String? = null,
    /** Whether to skip certificate verification. */
    val allowInsecure: Boolean = false,
    /** ALPN override, e.g. `h2`, `http/1.1`. */
    val alpn: List<String> = emptyList(),

    // ---- transport options ---------------------------------------------
    /** WS/HTTP path. */
    val path: String? = null,
    /** WS/HTTP `Host` header (falls back to [sni] then [host] when absent). */
    val hostHeader: String? = null,
    /** gRPC service name. */
    val serviceName: String? = null,
    /** mKCP seed, when the transport is KCP. */
    val kcpSeed: String? = null,

    // ---- credentials ----------------------------------------------------
    /** VLESS/VMess user id. */
    val uuid: String? = null,
    /** Trojan/Shadowsocks/proxy password. */
    val password: String? = null,
    /** Shadowsocks cipher, e.g. `aes-256-gcm`, `chacha20-poly1305`. */
    val method: String? = null,
    /** VMess alterId (0 for the modern AEAD variant). */
    val alterId: Int = 0,
    /** VMess encryption, default `auto`. */
    val vmessSecurity: String = "auto",
    /** VLESS XTLS flow. */
    val flow: VlessFlow = VlessFlow.NONE,

    // ---- WireGuard-only -------------------------------------------------
    val wgPrivateKey: String? = null,
    val wgPeerPublicKey: String? = null,
    val wgPreSharedKey: String? = null,
    val wgLocalAddress: List<String> = emptyList(),
    val wgMtu: Int? = null,

    // ---- UI state (not part of the wire config) -------------------------
    /** Latency in milliseconds, or `null` when not measured yet. */
    val pingMs: Int? = null,
    /** 0..100 estimated load, shown as a quality hint. */
    val loadPercent: Int = 0,
    val isPremium: Boolean = false,
    /** The subscription this node came from, or `null` for a built-in/manual node. */
    val subscriptionId: String? = null,
) {
    val displayLocation: String
        get() = when {
            city.isNotBlank() && country.isNotBlank() -> "$city, $country"
            country.isNotBlank() -> country
            else -> host
        }

    /** `vless://host:443?security=reality&…` — enough to re-export the node. */
    val endpoint: String
        get() = "$host:$port"

    val pingQuality: PingQuality
        get() = when (val p = pingMs) {
            null -> PingQuality.UNKNOWN
            in 0..80 -> PingQuality.EXCELLENT
            in 81..160 -> PingQuality.GOOD
            in 161..300 -> PingQuality.FAIR
            else -> PingQuality.POOR
        }

    /**
     * The bits of the config that a change of *value* would invalidate — used to
     * decide whether an imported node is genuinely new or a refreshed copy of one
     * already in the list. Deliberately excludes [pingMs], [name] and [id],
     * because a subscription is expected to re-serve the same node with a tidied
     * remark and a fresh latency.
     */
    val configFingerprint: String
        get() = listOf(
            protocol.name, host, port.toString(), security.wireName, transport.wireName,
            uuid.orEmpty(), password.orEmpty(), method.orEmpty(), publicKey.orEmpty(),
            shortId.orEmpty(), sni.orEmpty(), path.orEmpty(), serviceName.orEmpty(),
            flow.wireName, alterId.toString(),
        ).joinToString("|").lowercase()

    /** A stable identity across subscription refreshes (node, not credentials). */
    val nodeKey: String
        get() = "$protocol|$host|$port".lowercase()
}

enum class PingQuality { UNKNOWN, EXCELLENT, GOOD, FAIR, POOR }
