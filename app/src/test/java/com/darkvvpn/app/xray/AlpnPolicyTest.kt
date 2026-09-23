package com.darkvvpn.app.xray

import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ALPN rule, pinned against a real panel's node.
 *
 * ── The bug this exists for ──────────────────────────────────────────────────
 * A live subscription served links shaped like:
 *
 *     vless://…@www.speedtest.net:8443?type=ws&security=tls
 *           &alpn=h2%2Chttp%2F1.1%2Ch3&sni=panel.example&path=%2F%40CH
 *
 * Passing `alpn=h2,http/1.1,h3` straight into the config makes the server
 * negotiate **h2**. A WebSocket node needs the HTTP/1.1 `Upgrade` handshake, so
 * the upgrade request is answered with HTTP/2 binary frames instead of
 * `101 Switching Protocols`, and the tunnel never comes up.
 *
 * Measured against that panel, over TLS with SNI `panel.example`:
 *
 * | ALPN offered | negotiated | `GET /@CH` upgrade |
 * |---|---|---|
 * | `h2,http/1.1,h3` | `h2` | binary frames — no upgrade |
 * | `http/1.1` | `http/1.1` | `101 Switching Protocols` |
 * | *(none)* | *(none)* | `101 Switching Protocols` |
 *
 * Every case below traces back to that table.
 */
class AlpnPolicyTest {

    private fun effective(
        alpn: List<String>,
        transport: VpnTransport = VpnTransport.WS,
        security: VpnSecurity = VpnSecurity.TLS,
    ) = AlpnPolicy.effective(alpn, transport, security)

    // ---- the exact case from the real subscription ----------------------

    @Test
    fun `the panel's alpn loses h2 for a websocket node`() {
        val fromTheLink = listOf("h2", "http/1.1", "h3")

        val emitted = effective(fromTheLink, transport = VpnTransport.WS)

        assertFalse("h2 must not be offered for WS: it breaks the upgrade", "h2" in emitted)
        assertTrue("http/1.1 must survive: it is what the upgrade needs", "http/1.1" in emitted)
        assertTrue("http/1.1 must be the effective choice", emitted == listOf("http/1.1"))
    }

    @Test
    fun `h3 is dropped everywhere it is offered`() {
        // h3 is a QUIC protocol identifier; there is no HTTP/3 in a TCP handshake.
        VpnTransport.entries.forEach { transport ->
            val emitted = effective(listOf("h2", "http/1.1", "h3"), transport)
            assertFalse(
                "h3 must never reach the config (transport $transport): $emitted",
                emitted.any { it.equals("h3", ignoreCase = true) },
            )
        }
    }

    @Test
    fun `the reported node yields exactly http slash 1 dot 1`() {
        // The whole point, stated as one assertion: this is the ALPN that measured
        // as `101 Switching Protocols`.
        assertEquals(
            listOf("http/1.1"),
            AlpnPolicy.effective(listOf("h2", "http/1.1", "h3"), VpnTransport.WS, VpnSecurity.TLS),
        )
    }

    // ---- HTTPUpgrade has the same constraint ----------------------------

    @Test
    fun `httpupgrade also drops h2`() {
        // It runs the same HTTP/1.1 Upgrade handshake as WS.
        val emitted = effective(listOf("h2", "http/1.1"), VpnTransport.HTTP_UPGRADE)
        assertEquals(listOf("http/1.1"), emitted)
    }

    // ---- transports that genuinely speak h2 -----------------------------

    @Test
    fun `the http slash 2 transport keeps h2`() {
        // Here h2 is not a hazard, it is the point.
        val emitted = effective(listOf("h2", "http/1.1"), VpnTransport.HTTP)
        assertTrue("h2 must survive for an h2 transport", "h2" in emitted)
    }

    @Test
    fun `grpc requires h2 and gets it added when missing`() {
        // gRPC is HTTP/2-only, so a panel that omitted h2 needs it supplied.
        val emitted = effective(listOf("http/1.1"), VpnTransport.GRPC)
        assertTrue("h2 must be present for gRPC: $emitted", "h2" in emitted)
    }

    @Test
    fun `grpc does not duplicate h2`() {
        val emitted = effective(listOf("h2"), VpnTransport.GRPC)
        assertEquals(listOf("h2"), emitted)
    }

    // ---- left alone -----------------------------------------------------

    @Test
    fun `plain tcp and raw are left as the panel intended`() {
        assertEquals(listOf("http/1.1"), effective(listOf("http/1.1"), VpnTransport.TCP))
        assertEquals(listOf("h2"), effective(listOf("h2"), VpnTransport.TCP))
        assertEquals(emptyList<String>(), effective(emptyList(), VpnTransport.TCP))
    }

    @Test
    fun `splithttp and xhttp are not second-guessed`() {
        // They negotiate their own framing and support both h1 and h2, so the
        // panel's list is respected.
        assertEquals(listOf("h2", "http/1.1"), effective(listOf("h2", "http/1.1"), VpnTransport.SPLIT_HTTP))
        assertEquals(listOf("h2", "http/1.1"), effective(listOf("h2", "http/1.1"), VpnTransport.XHTTP))
    }

    // ---- no TLS ---------------------------------------------------------

    @Test
    fun `without tls there is no alpn at all`() {
        // ALPN is a TLS extension; Xray rejects the key under security=none.
        assertEquals(emptyList<String>(), effective(listOf("h2"), VpnTransport.WS, VpnSecurity.NONE))
        assertEquals(emptyList<String>(), effective(emptyList(), VpnTransport.TCP, VpnSecurity.NONE))
    }

    @Test
    fun `reality keeps the rule too`() {
        // REALITY is TLS-shaped, so the same hazard applies to a WS node over it.
        val emitted = effective(listOf("h2", "http/1.1"), VpnTransport.WS, VpnSecurity.REALITY)
        assertEquals(listOf("http/1.1"), emitted)
    }

    // ---- hygiene --------------------------------------------------------

    @Test
    fun `duplicates and empty entries are removed`() {
        val emitted = effective(listOf("http/1.1", "", "  ", "http/1.1"), VpnTransport.TCP)
        assertEquals(listOf("http/1.1"), emitted)
    }

    @Test
    fun `case does not matter`() {
        val emitted = effective(listOf("H2", "HTTP/1.1", "H3"), VpnTransport.WS)
        assertEquals(listOf("HTTP/1.1"), emitted)
    }

    @Test
    fun `an empty result is allowed rather than invented`() {
        // Offering no ALPN was measured to work, so nothing is fabricated.
        assertEquals(emptyList<String>(), effective(listOf("h2", "h3"), VpnTransport.WS))
    }

    // ---- wasAdjusted, which drives the explanation shown to the user ----

    @Test
    fun `wasAdjusted reports the change`() {
        assertTrue(
            AlpnPolicy.wasAdjusted(listOf("h2", "http/1.1", "h3"), VpnTransport.WS, VpnSecurity.TLS),
        )
        assertFalse(
            AlpnPolicy.wasAdjusted(listOf("http/1.1"), VpnTransport.WS, VpnSecurity.TLS),
        )
    }

    @Test
    fun `wasAdjusted is true when alpn is dropped for the lack of tls`() {
        assertTrue(AlpnPolicy.wasAdjusted(listOf("h2"), VpnTransport.WS, VpnSecurity.NONE))
        assertFalse(AlpnPolicy.wasAdjusted(emptyList(), VpnTransport.WS, VpnSecurity.NONE))
    }
}
