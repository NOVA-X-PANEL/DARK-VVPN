package com.darkvvpn.app.data.subscription

import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A whole subscription, shaped exactly like a live panel's.
 *
 * ── Provenance ───────────────────────────────────────────────────────────────
 * The structure here is copied from a real subscription that would not import or
 * connect: gzip-compressed, base64-encoded, six links, and every field preserved
 * — the ALPN list, the WebSocket path, the CDN hosts, the emoji remarks, the
 * Hysteria2 entry, and the provider's injected announcement node on an
 * undialable host.
 *
 * The UUID, the SNI host and the panel host are replaced with placeholders. The
 * fixture is a shape, not a credential.
 *
 * ── What it pins ─────────────────────────────────────────────────────────────
 *  - `alpn=h2,http/1.1,h3` arrives intact from the link
 *  - the announcement node on `1.2.3.4.5` is dropped
 *  - the Hysteria2 node is kept (it is real) but marked as needing another core
 *  - the five VLESS nodes are usable, over WebSocket with TLS
 */
class RealSubscriptionShapeTest {

    /**
     * The payload as the panel serves it, after gzip: base64 of the link list.
     * Spaces are stripped before decoding, matching LinkText.decodeBase64.
     */
    private val base64Body: String = java.util.Base64.getEncoder().encodeToString(
        """
        vless://00000000-1111-2222-3333-444444444444@www.speedtest.net:8443?alpn=h2%2Chttp%2F1.1%2Ch3&encryption=none&fp=chrome&host=panel.example.invalid&path=%2F%40CHANNEL&security=tls&sni=panel.example.invalid&type=ws#%F0%9F%87%A9%F0%9F%87%AA%7CNode-1%7CTest
        vless://00000000-1111-2222-3333-444444444444@188.114.97.6:8443?alpn=h2%2Chttp%2F1.1%2Ch3&encryption=none&fp=chrome&host=panel.example.invalid&path=%2F%40CHANNEL&security=tls&sni=panel.example.invalid&type=ws#%F0%9F%87%A9%F0%9F%87%AA%7CNode-2%7CCDN
        vless://00000000-1111-2222-3333-444444444444@cdn1.example.invalid:8443?alpn=h2%2Chttp%2F1.1%2Ch3&encryption=none&fp=chrome&host=panel.example.invalid&path=%2F%40CHANNEL&security=tls&sni=panel.example.invalid&type=ws#%F0%9F%87%A9%F0%9F%87%AA%7CNode-3%7CCDN-1
        hysteria2://9e67dec29d0c46ec91abf0bec7175fd1@hy.example.invalid:17511?security=tls&sni=hy.example.invalid&alpn=h3#%F0%9F%8E%AE%7CNode-HY
        vless://00000000-1111-2222-3333-444444444444@1.2.3.4.5:1234?type=tcp&security=none#Update+your+subscription+daily
        """.trimIndent().toByteArray(),
    )

    private val parser = SubscriptionParser()

    private fun parseAll() = parser.parse(base64Body)

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    @Test
    fun `the whole subscription imports`() {
        val result = parseAll()

        assertTrue("the parse must count as a success: ${result.error}", result.isSuccess)
        assertEquals(SubscriptionFormat.BASE64_LIST, result.format)
        // Six lines in, five nodes out: the announcement node is dropped.
        assertEquals(5, result.servers.size)
    }

    @Test
    fun `the vless nodes carry the alpn the panel advertised`() {
        // The parser must NOT strip it — the config builder decides what to emit,
        // so the parsed value is the panel's own and stays inspectable.
        val vless = parseAll().servers.filter { it.protocol == VpnProtocol.VLESS }
        assertEquals(4, vless.size)
        vless.forEach { node ->
            assertEquals(
                "the link's ALPN must survive parsing: ${node.name}",
                listOf("h2", "http/1.1", "h3"),
                node.alpn,
            )
        }
    }

    @Test
    fun `a websocket node keeps its transport, path, sni and host header`() {
        val node = parseAll().servers.first { it.transport == VpnTransport.WS }

        assertEquals(VpnSecurity.TLS, node.security)
        assertEquals(8443, node.port)
        assertEquals("/@CHANNEL", node.path)
        assertEquals("panel.example.invalid", node.sni)
        assertEquals("panel.example.invalid", node.hostHeader)
        assertEquals("chrome", node.fingerprint)
        assertEquals("00000000-1111-2222-3333-444444444444", node.uuid)
    }

    @Test
    fun `an ip literal endpoint is kept`() {
        // 188.114.97.6 is a real address, so it is a usable node even though it
        // looks numeric.
        val ipNode = parseAll().servers.firstOrNull { it.host == "188.114.97.6" }
        assertNotNull("a valid IPv4 endpoint must not be filtered out", ipNode)
    }

    @Test
    fun `the announcement node on an undialable host is dropped`() {
        // `1.2.3.4.5` is five octets: not an address and not a hostname. Imported,
        // it becomes a row that always shows "—" and fails if picked.
        val hosts = parseAll().servers.map { it.host }
        assertFalse(
            "the provider's announcement node must not become a server: $hosts",
            hosts.any { it.startsWith("1.2.3.4") },
        )
    }

    @Test
    fun `the hysteria2 node is kept because it is real`() {
        // It cannot be dialled by Xray, but it is not junk — dropping it would
        // silently discard a node the user is paying for.
        val hy = parseAll().servers.firstOrNull { it.protocol == VpnProtocol.HYSTERIA2 }
        assertNotNull(hy)
        requireNotNull(hy)
        assertEquals("hy.example.invalid", hy.host)
        assertEquals(17511, hy.port)
        // And the list can say so before the user picks it.
        assertFalse("Hysteria2 is not an Xray outbound", hy.protocol.isXrayNative)
    }

    // ------------------------------------------------------------------
    // Config generation, which is where the connection used to fail
    // ------------------------------------------------------------------

    @Test
    fun `the generated config for a websocket node does not offer h2`() {
        val node = parseAll().servers.first { it.transport == VpnTransport.WS }

        val rendered = com.darkvvpn.app.xray.XrayConfigBuilder.render(node)
        requireNotNull(rendered)

        // The single assertion that fixes the tunnel: the config must not tell the
        // server it can speak HTTP/2, or the WebSocket upgrade is answered with
        // HTTP/2 frames and never completes.
        val alpnBlock = Regex("\"alpn\"\\s*:\\s*\\[[^\\]]*\\]").find(rendered)?.value
        assertNotNull("an alpn block is expected", alpnBlock)
        requireNotNull(alpnBlock)
        assertFalse("h2 must not appear: $alpnBlock", alpnBlock.contains("\"h2\""))
        assertFalse("h3 must not appear: $alpnBlock", alpnBlock.contains("\"h3\""))
        assertTrue("http/1.1 must be the value: $alpnBlock", alpnBlock.contains("http/1.1"))
    }

    @Test
    fun `every usable node in this subscription produces a runnable config`() {
        val usable = parseAll().servers.filter { it.protocol.isXrayNative }
        assertEquals(4, usable.size)

        usable.forEach { node ->
            val result = com.darkvvpn.app.xray.XrayConfigBuilder.build(node)
            assertTrue(
                "${node.name} must build: ${result.errorMessage}",
                result.isSuccess,
            )
        }
    }

    @Test
    fun `the hysteria2 node is refused by name rather than mis-compiled`() {
        val hy = parseAll().servers.first { it.protocol == VpnProtocol.HYSTERIA2 }
        val result = com.darkvvpn.app.xray.XrayConfigBuilder.build(hy)

        assertTrue(result is com.darkvvpn.app.xray.XrayConfigResult.UnsupportedProtocol)
        assertTrue(
            "the reason must name the core that is missing: ${result.errorMessage}",
            result.errorMessage!!.contains("sing-box"),
        )
    }

    // ------------------------------------------------------------------
    // Traffic headers, as this panel sends them
    // ------------------------------------------------------------------

    @Test
    fun `a zeroed traffic header is harmless`() {
        // This panel sends `upload=0; download=0; total=0; expire=0`. A quota of
        // zero must not read as "expired" or produce a division by zero.
        val traffic = SubscriptionTraffic.parse("upload=0; download=0; total=0; expire=0")
        requireNotNull(traffic)
        assertEquals(0L, traffic.usedBytes)
        assertEquals(0L, traffic.totalBytes)
        assertNull("expire=0 is not an expiry date", traffic.expiresAtEpochMillis)
    }

    @Test
    fun `the injector's undialable host is rejected on every protocol`() {
        // The same check runs for vmess/ss shapes, not only vless.
        assertNull(parser.parseLink("vmess://" + java.util.Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"Junk","add":"1.2.3.4.5","port":"1234","id":"00000000-1111-2222-3333-444444444444","net":"tcp"}"""
                .toByteArray(),
        )))
        assertNull(parser.parseLink("trojan://pw@1.2.3.4.5:443#Junk"))
        assertNull(parser.parseLink("ss://YWVzLTI1Ni1nY206cHc@1.2.3.4.5:8388#Junk"))
    }
}
