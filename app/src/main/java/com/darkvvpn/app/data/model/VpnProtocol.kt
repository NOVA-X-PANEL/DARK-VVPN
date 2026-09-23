package com.darkvvpn.app.data.model

import kotlinx.serialization.Serializable

/**
 * The outbound protocol a [VpnServer] speaks.
 *
 * [uriScheme] is the share-link scheme the protocol is distributed under.
 * [xrayProtocol] is the name Xray expects in an outbound's `protocol` field —
 * they are **not** always the same string: Shadowsocks is shared as `ss://` but
 * Xray calls the outbound `shadowsocks`, and emitting the scheme there makes the
 * core reject the config at startup.
 *
 * [isXrayNative] records whether Xray can dial the protocol at all. The
 * exception is documented on its entry; everything else is rendered as an Xray
 * outbound by [com.darkvvpn.app.xray.XrayConfigBuilder].
 */
@Serializable
enum class VpnProtocol(
    val label: String,
    val defaultPort: Int,
    val uriScheme: String,
    val xrayProtocol: String,
    val isXrayNative: Boolean = true,
) {
    VLESS("VLESS", 443, "vless", "vless"),
    VMESS("VMess", 443, "vmess", "vmess"),
    TROJAN("Trojan", 443, "trojan", "trojan"),

    /** Shared as `ss://`, but Xray's outbound is spelled out in full. */
    SHADOWSOCKS("Shadowsocks", 8388, "ss", "shadowsocks"),

    /**
     * Xray has a native `wireguard` outbound, so a node distributed as a
     * WireGuard profile still travels through the same config builder.
     */
    WIREGUARD("WireGuard", 51820, "wireguard", "wireguard"),

    /**
     * Hysteria2 is QUIC-based and has **no** Xray outbound. It is parsed and
     * stored so nothing is lost on import, but it needs a sing-box class core to
     * dial — [isXrayNative] is false and the config builder marks it as
     * unsupported rather than emitting a config the core would reject.
     */
    HYSTERIA2("Hysteria2", 443, "hysteria2", "", isXrayNative = false),

    /**
     * SOCKS/HTTP proxies are commonly embedded in subscription lists even when
     * the main node is something else; Xray dials both.
     */
    SOCKS("SOCKS", 1080, "socks", "socks"),
    HTTP("HTTP", 8080, "http", "http"),
    ;

    companion object {
        fun fromName(name: String?): VpnProtocol =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: VLESS

        /** Resolves the protocol from a share-link scheme such as `vless` or `ss`. */
        fun fromScheme(scheme: String?): VpnProtocol? {
            val s = scheme?.lowercase()?.removeSuffix("://") ?: return null
            return when (s) {
                "ss", "shadowsocks" -> SHADOWSOCKS
                "vmess" -> VMESS
                "vless" -> VLESS
                "trojan" -> TROJAN
                "wireguard", "wg" -> WIREGUARD
                "hysteria2", "hy2" -> HYSTERIA2
                "socks", "socks5" -> SOCKS
                "http", "https" -> HTTP
                else -> entries.firstOrNull { it.uriScheme == s }
            }
        }
    }
}

/**
 * The transport security layer wrapped around the outbound.
 *
 * These names match Xray's `streamSettings.security` values verbatim, so they
 * are written straight into the generated config.
 */
@Serializable
enum class VpnSecurity(val wireName: String, val label: String) {
    NONE("none", "None"),
    TLS("tls", "TLS"),
    REALITY("reality", "REALITY"),
    XTLS("xtls", "XTLS"),
    ;

    companion object {
        fun fromName(name: String?): VpnSecurity {
            val n = name?.lowercase()?.trim() ?: return NONE
            return when (n) {
                "", "none", "no", "off" -> NONE
                "tls" -> TLS
                "reality" -> REALITY
                "xtls" -> XTLS
                else -> entries.firstOrNull { it.wireName == n } ?: NONE
            }
        }
    }
}

/**
 * The stream transport. [wireName] is what Xray expects in
 * `streamSettings.network`; note that `httpupgrade` and `splithttp` are spelled
 * exactly as Xray spells them.
 */
@Serializable
enum class VpnTransport(val wireName: String, val label: String) {
    TCP("tcp", "TCP"),
    RAW("raw", "RAW"),
    WS("ws", "WebSocket"),
    GRPC("grpc", "gRPC"),
    HTTP("http", "HTTP/2"),
    HTTP_UPGRADE("httpupgrade", "HTTPUpgrade"),
    SPLIT_HTTP("splithttp", "SplitHTTP"),
    QUIC("quic", "QUIC"),
    KCP("kcp", "mKCP"),
    XHTTP("xhttp", "XHTTP"),
    ;

    /** True when the transport carries a `path`/`host` pair. */
    val usesPath: Boolean
        get() = this in setOf(WS, HTTP, HTTP_UPGRADE, SPLIT_HTTP, XHTTP)

    /** True when the transport is addressed by `serviceName` instead of `path`. */
    val usesServiceName: Boolean
        get() = this == GRPC

    companion object {
        fun fromName(name: String?): VpnTransport {
            val n = name?.lowercase()?.trim() ?: return TCP
            return entries.firstOrNull { it.wireName == n }
                ?: when (n) {
                    "h2", "h2c" -> HTTP
                    "http/2" -> HTTP
                    "websocket" -> WS
                    "httpupgrade" -> HTTP_UPGRADE
                    "mkcp" -> KCP
                    "" -> TCP
                    else -> TCP
                }
        }
    }
}

/**
 * The XTLS flow control value. Only meaningful for VLESS over TLS/REALITY;
 * `xtls-rprx-vision` is the modern one and the only one worth offering.
 */
@Serializable
enum class VlessFlow(val wireName: String, val label: String) {
    NONE("", "None"),
    VISION("xtls-rprx-vision", "Vision"),
    ;

    companion object {
        fun fromName(name: String?): VlessFlow =
            entries.firstOrNull { it.wireName == name?.trim() } ?: NONE
    }
}
