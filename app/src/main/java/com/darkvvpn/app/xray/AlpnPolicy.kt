package com.darkvvpn.app.xray

import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnTransport

/**
 * Decides the ALPN list that actually goes into the config.
 *
 * ── Why the subscription's own ALPN cannot be passed through ─────────────────
 *
 * A share link commonly advertises `alpn=h2,http/1.1,h3`. The server then
 * negotiates **h2**, and three things go wrong at once:
 *
 *  1. A WebSocket node needs the HTTP/1.1 `Upgrade` handshake. If ALPN settled on
 *     h2, the server speaks HTTP/2 and answers the upgrade request with binary
 *     frames instead of `101 Switching Protocols`. The tunnel never establishes.
 *  2. WebSocket over HTTP/2 needs RFC 8441 Extended CONNECT, which Xray's `ws`
 *     transport does not implement — so h2 is never the right answer for this
 *     transport, whatever the link says.
 *  3. `h3` is a QUIC protocol. It has no meaning in a TCP/TLS handshake, and
 *     offering it only gives the server an extra way to pick something the client
 *     cannot speak.
 *
 * Measured against a real panel, for a `type=ws&security=tls` node:
 *
 * | ALPN offered | server picks | WebSocket upgrade |
 * |---|---|---|
 * | `h2,http/1.1,h3` | `h2` | binary HTTP/2 frames, no upgrade |
 * | `http/1.1` | `http/1.1` | `101 Switching Protocols` |
 * | *(none)* | *(none)* | `101 Switching Protocols` |
 *
 * So the rule is per transport, not "trust the link":
 *
 *  - **WS / HTTPUpgrade** run an HTTP/1.1 upgrade: drop `h2`.
 *  - **HTTP/2 and gRPC** genuinely speak h2: keep it, and add it if absent.
 *  - **`h3` is dropped everywhere**, because no transport this builder emits
 *    speaks HTTP/3 over the TLS handshake.
 *
 * Dropping every protocol from the list is a valid outcome and is left alone:
 * offering no ALPN was measured to work, so there is no reason to invent one.
 */
internal object AlpnPolicy {

    /** The ALPN to emit, possibly empty. Never contains `h3`. */
    fun effective(
        alpn: List<String>,
        transport: VpnTransport,
        security: VpnSecurity,
    ): List<String> {
        // ALPN is a TLS extension. Without TLS there is nowhere to put it, and
        // Xray rejects the key.
        if (security == VpnSecurity.NONE) return emptyList()

        val requested = alpn.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // A QUIC-only protocol identifier in a TCP handshake.
            .filterNot { it.equals("h3", ignoreCase = true) }
            .distinct()
            .toList()

        return when (transport) {
            // The upgrade is HTTP/1.1 by definition; h2 must not be on the table.
            VpnTransport.WS, VpnTransport.HTTP_UPGRADE ->
                requested.filterNot { it.equals("h2", ignoreCase = true) }

            // These really do speak HTTP/2, so h2 is required rather than harmful.
            VpnTransport.HTTP, VpnTransport.GRPC ->
                if (requested.any { it.equals("h2", ignoreCase = true) }) requested
                else listOf("h2") + requested

            // TCP/RAW carry the protocol directly. SplitHTTP/XHTTP negotiate their
            // own framing and are left as the panel intended.
            else -> requested
        }
    }

    /**
     * True when the panel's ALPN had to be changed.
     *
     * The UI uses this to explain why a node needed correcting, which turns "why
     * does my link not work in this app" into a message the user can act on.
     */
    fun wasAdjusted(
        alpn: List<String>,
        transport: VpnTransport,
        security: VpnSecurity,
    ): Boolean {
        val requested = alpn.filter { it.isNotBlank() }
        if (security == VpnSecurity.NONE) return requested.isNotEmpty()
        return effective(alpn, transport, security) != requested.distinct()
    }
}
