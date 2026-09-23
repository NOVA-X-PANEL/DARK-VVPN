package com.darkvvpn.app.data.model

/**
 * The tunnelling protocol a [VpnServer] speaks.
 *
 * DARK VVPN is a *client shell*: the actual tunnel is produced by pluggable
 * cores (Xray, sing-box, WireGuard …). This enum only describes what the UI
 * needs to render, not how the packet flow is implemented.
 */
enum class VpnProtocol(val label: String, val defaultPort: Int) {
    VLESS("VLESS", 443),
    VMESS("VMess", 443),
    TROJAN("Trojan", 443),
    SHADOWSOCKS("Shadowsocks", 8388),
    WIREGUARD("WireGuard", 51820),
    HYSTERIA2("Hysteria2", 443),
    ;

    companion object {
        fun fromName(name: String?): VpnProtocol =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: VLESS
    }
}
