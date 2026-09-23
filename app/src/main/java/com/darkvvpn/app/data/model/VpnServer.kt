package com.darkvvpn.app.data.model

import java.util.UUID

/**
 * A single upstream node the user can connect through.
 *
 * NOTE ON SECRETS: [uuid] / [password] are *connection credentials*, not a
 * DARK VVPN account. In the skeleton they are kept in memory only; a production
 * build should keep them in `EncryptedSharedPreferences` or the Keystore and
 * never log them (see [com.darkvvpn.app.util.Redact]).
 */
data class VpnServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val country: String,
    val countryCode: String,
    val city: String,
    val host: String,
    val port: Int = VpnProtocol.VLESS.defaultPort,
    val protocol: VpnProtocol = VpnProtocol.VLESS,
    /** Latency in milliseconds, or `null` when not measured yet. */
    val pingMs: Int? = null,
    /** 0..100 quality score derived from [pingMs]; used for the sort order. */
    val loadPercent: Int = 0,
    val isPremium: Boolean = false,

    // --- credentials (never rendered) ---
    val uuid: String? = null,
    val password: String? = null,
    val sni: String? = null,
    val network: String = "tcp",
    val security: String = "reality",
) {
    val displayLocation: String
        get() = if (city.isBlank()) country else "$city, $country"

    /** Coarse, non-localised bucket used to pick a colour for the ping badge. */
    val pingQuality: PingQuality
        get() = when (val p = pingMs) {
            null -> PingQuality.UNKNOWN
            in 0..80 -> PingQuality.EXCELLENT
            in 81..160 -> PingQuality.GOOD
            in 161..300 -> PingQuality.FAIR
            else -> PingQuality.POOR
        }
}

enum class PingQuality { UNKNOWN, EXCELLENT, GOOD, FAIR, POOR }
