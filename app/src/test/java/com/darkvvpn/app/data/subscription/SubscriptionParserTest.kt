package com.darkvvpn.app.data.subscription

import com.darkvvpn.app.data.model.VlessFlow
import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Parser tests. These run on the JVM with no Android framework, which is why
 * [LinkText.decodeBase64] falls back to the JDK decoder when
 * `android.util.Base64` is unavailable.
 */
class SubscriptionParserTest {

    private val parser = SubscriptionParser()

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray())

    // ---- VLESS ---------------------------------------------------------

    @Test
    fun `parses a vless reality link with every security field`() {
        val link = "vless://8f7a1b2c-3d4e-5f60-7182-93a4b5c6d7e8@nl1.example.com:443" +
            "?type=tcp&security=reality&pbk=PUBLIC_KEY_VALUE&fp=chrome" +
            "&sni=www.microsoft.com&sid=abcd1234&spx=%2F&flow=xtls-rprx-vision" +
            "&alpn=h2%2Chttp%2F1.1#%F0%9F%87%B3%F0%9F%87%B1%20Amsterdam%20REALITY"

        val node = parser.parseLink(link)
        assertNotNull(node)
        requireNotNull(node)

        assertEquals(VpnProtocol.VLESS, node.protocol)
        assertEquals("nl1.example.com", node.host)
        assertEquals(443, node.port)
        assertEquals(VpnSecurity.REALITY, node.security)
        assertEquals(VpnTransport.TCP, node.transport)
        assertEquals("PUBLIC_KEY_VALUE", node.publicKey)
        assertEquals("chrome", node.fingerprint)
        assertEquals("www.microsoft.com", node.sni)
        assertEquals("abcd1234", node.shortId)
        assertEquals("/", node.spiderX)
        assertEquals(VlessFlow.VISION, node.flow)
        assertEquals(listOf("h2", "http/1.1"), node.alpn)
        assertEquals("8f7a1b2c-3d4e-5f60-7182-93a4b5c6d7e8", node.uuid)
        // The flag emoji in the remark must resolve to the ISO code.
        assertEquals("NL", node.countryCode)
        assertEquals("Netherlands", node.country)
    }

    @Test
    fun `infers reality from a public key when the link omits the security flag`() {
        // Panels published before `security=` became common are shaped like this.
        val link = "vless://11111111-2222-3333-4444-555555555555@host.example:443" +
            "?type=tcp&pbk=KEY&sni=example.org#Node"

        val node = parser.parseLink(link)
        requireNotNull(node)
        assertEquals(VpnSecurity.REALITY, node.security)
    }

    @Test
    fun `parses a vless websocket link and normalises the path`() {
        val link = "vless://11111111-2222-3333-4444-555555555555@ws.example:8443" +
            "?type=ws&security=tls&path=ws%3Fed%3D2048&host=cdn.example&sni=cdn.example#WS"

        val node = parser.parseLink(link)
        requireNotNull(node)
        assertEquals(VpnTransport.WS, node.transport)
        assertEquals(VpnSecurity.TLS, node.security)
        // The `?ed=2048` suffix belongs to the query, not the path.
        assertEquals("/ws", node.path)
        assertEquals("cdn.example", node.hostHeader)
    }

    @Test
    fun `parses a vless grpc link into serviceName`() {
        val link = "vless://11111111-2222-3333-4444-555555555555@g.example:443" +
            "?type=grpc&security=tls&serviceName=mygrpc&sni=g.example#gRPC"

        val node = parser.parseLink(link)
        requireNotNull(node)
        assertEquals(VpnTransport.GRPC, node.transport)
        assertEquals("mygrpc", node.serviceName)
    }

    // ---- VMess ---------------------------------------------------------

    @Test
    fun `parses a vmess base64 json link`() {
        val payload = """
            {"v":"2","ps":"🇩🇪 Frankfurt","add":"de.example.com","port":"443",
             "id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":"0","scy":"auto",
             "net":"ws","type":"none","host":"de.example.com","path":"/vm",
             "tls":"tls","sni":"de.example.com","fp":"chrome"}
        """.trimIndent().replace("\n", "").replace(" ", "")

        val node = parser.parseLink("vmess://${b64(payload)}")
        requireNotNull(node)

        assertEquals(VpnProtocol.VMESS, node.protocol)
        assertEquals("de.example.com", node.host)
        assertEquals(443, node.port)
        assertEquals(VpnSecurity.TLS, node.security)
        assertEquals(VpnTransport.WS, node.transport)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", node.uuid)
        assertEquals("/vm", node.path)
        assertEquals("chrome", node.fingerprint)
        assertEquals("DE", node.countryCode)
    }

    @Test
    fun `vmess reports no security when the tls field is empty`() {
        val payload = """{"v":"2","ps":"Plain","add":"plain.example","port":"80",
            "id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","net":"tcp","tls":""}"""
            .replace("\n", "").replace(" ", "")

        val node = parser.parseLink("vmess://${b64(payload)}")
        requireNotNull(node)
        assertEquals(VpnSecurity.NONE, node.security)
    }

    @Test
    fun `a malformed vmess body is rejected rather than guessed at`() {
        assertNull(parser.parseLink("vmess://not-valid-base64-!!!!"))
    }

    // ---- Trojan --------------------------------------------------------

    @Test
    fun `trojan defaults to tls because the protocol requires it`() {
        val link = "trojan://my-password@tr.example.com:443?sni=tr.example.com#Istanbul"

        val node = parser.parseLink(link)
        requireNotNull(node)
        assertEquals(VpnProtocol.TROJAN, node.protocol)
        assertEquals("my-password", node.password)
        assertEquals(VpnSecurity.TLS, node.security)
    }

    // ---- Shadowsocks ---------------------------------------------------

    @Test
    fun `parses sip002 shadowsocks with base64 credentials`() {
        val creds = b64("aes-256-gcm:secret-password")
        val node = parser.parseLink("ss://$creds@ss.example.com:8388#Tokyo")

        requireNotNull(node)
        assertEquals(VpnProtocol.SHADOWSOCKS, node.protocol)
        assertEquals("aes-256-gcm", node.method)
        assertEquals("secret-password", node.password)
        assertEquals(8388, node.port)
    }

    @Test
    fun `parses sip002 shadowsocks with plain credentials`() {
        val node = parser.parseLink("ss://chacha20-poly1305:pw@ss2.example.com:9999#Plain")
        requireNotNull(node)
        assertEquals("chacha20-poly1305", node.method)
        assertEquals("pw", node.password)
    }

    @Test
    fun `parses the legacy shadowsocks form where the whole body is base64`() {
        val body = b64("aes-128-gcm:legacy-pass@legacy.example.com:8388")
        val node = parser.parseLink("ss://$body#Legacy")

        requireNotNull(node)
        assertEquals("legacy.example.com", node.host)
        assertEquals("aes-128-gcm", node.method)
        assertEquals("legacy-pass", node.password)
    }

    // ---- Hysteria2 -----------------------------------------------------

    @Test
    fun `parses hysteria2 and records that it is not an xray outbound`() {
        val node = parser.parseLink("hysteria2://hy2pass@ca.example.com:443?sni=ca.example.com&insecure=1#Toronto")

        requireNotNull(node)
        assertEquals(VpnProtocol.HYSTERIA2, node.protocol)
        assertEquals("hy2pass", node.password)
        assertTrue(node.allowInsecure)
        assertTrue("Hysteria2 needs a sing-box class core", !node.protocol.isXrayNative)
    }

    @Test
    fun `the hy2 alias maps to hysteria2`() {
        val node = parser.parseLink("hy2://pw@host.example:443#Alias")
        requireNotNull(node)
        assertEquals(VpnProtocol.HYSTERIA2, node.protocol)
    }

    // ---- rejection -----------------------------------------------------

    @Test
    fun `unknown schemes are rejected`() {
        assertNull(parser.parseLink("ftp://host.example/file"))
        assertNull(parser.parseLink("not a link at all"))
        assertNull(parser.parseLink(""))
    }

    @Test
    fun `a link with no host is rejected`() {
        assertNull(parser.parseLink("vless://uuid@:443?type=tcp"))
    }

    // ---- lists and base64 ----------------------------------------------

    @Test
    fun `parses a plain newline separated list and skips comments`() {
        val text = """
            # a comment
            vless://11111111-2222-3333-4444-555555555555@a.example:443?security=tls#A

            trojan://pw@b.example:443#B
            not-a-link
        """.trimIndent()

        val nodes = parser.parseLinkList(text)
        assertEquals(2, nodes.size)
        assertEquals("a.example", nodes[0].host)
        assertEquals("b.example", nodes[1].host)
    }

    @Test
    fun `decodes a base64 subscription blob`() {
        val inner = "vless://uuid-1@one.example:443#One\ntrojan://pw@two.example:443#Two"
        val result = parser.parse(b64(inner))

        assertEquals(com.darkvvpn.app.data.model.SubscriptionFormat.BASE64_LIST, result.format)
        assertEquals(2, result.servers.size)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `accepts url-safe unpadded base64`() {
        val inner = "vless://uuid-x@pad.example:443#Padded"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(inner.toByteArray())
            .replace('-', '-') // keep the url-safe alphabet

        val result = parser.parse(encoded)
        assertEquals(1, result.servers.size)
        assertEquals("pad.example", result.servers[0].host)
    }

    @Test
    fun `an empty body reports an error instead of an empty node list`() {
        val result = parser.parse("   ")
        assertTrue(result.servers.isEmpty())
        assertNotNull(result.error)
    }

    @Test
    fun `a body with no links reports an error`() {
        val result = parser.parse("hello world, this is not a subscription")
        assertTrue(result.servers.isEmpty())
        assertNotNull(result.error)
    }

    // ---- traffic header ------------------------------------------------

    @Test
    fun `parses the subscription-userinfo header`() {
        val traffic = SubscriptionTraffic.parse("upload=100; download=200; total=1000; expire=1700000000")
        requireNotNull(traffic)
        assertEquals(300L, traffic.usedBytes)
        assertEquals(1000L, traffic.totalBytes)
        assertEquals(1_700_000_000_000L, traffic.expiresAtEpochMillis)
    }

    @Test
    fun `ignores unknown keys in the traffic header`() {
        val traffic = SubscriptionTraffic.parse("upload=1; download=2; total=10; unknown=zzz")
        requireNotNull(traffic)
        assertEquals(10L, traffic.totalBytes)
    }

    @Test
    fun `an absent traffic header yields null`() {
        assertNull(SubscriptionTraffic.parse(null))
        assertNull(SubscriptionTraffic.parse(""))
    }

    // ---- Clash ---------------------------------------------------------

    @Test
    fun `parses a clash proxies block`() {
        val yaml = """
            proxies:
              - name: "🇳🇱 Amsterdam"
                type: vless
                server: nl.example.com
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                network: ws
                tls: true
                servername: nl.example.com
                ws-path: /ws
                skip-cert-verify: false
              - name: "🇩🇪 Frankfurt"
                type: trojan
                server: de.example.com
                port: 443
                password: trojanpw
        """.trimIndent()

        val result = parser.parse(yaml)
        assertEquals(com.darkvvpn.app.data.model.SubscriptionFormat.CLASH_YAML, result.format)
        assertEquals(2, result.servers.size)

        val first = result.servers[0]
        assertEquals(VpnProtocol.VLESS, first.protocol)
        assertEquals(VpnSecurity.TLS, first.security)
        assertEquals(VpnTransport.WS, first.transport)
        assertEquals("NL", first.countryCode)

        val second = result.servers[1]
        assertEquals(VpnProtocol.TROJAN, second.protocol)
        assertEquals("trojanpw", second.password)
    }
}
