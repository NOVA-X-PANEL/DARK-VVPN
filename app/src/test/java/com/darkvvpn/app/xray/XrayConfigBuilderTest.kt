package com.darkvvpn.app.xray

import com.darkvvpn.app.data.model.VlessFlow
import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.data.model.VpnTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderTest {

    private fun success(server: VpnServer): XrayConfigResult.Success {
        val result = XrayConfigBuilder.build(server)
        assertTrue("expected success but got ${result.errorMessage}", result.isSuccess)
        return result as XrayConfigResult.Success
    }

    /** The `proxy` outbound from a built document. */
    private fun JsonObject.proxyOutbound(): JsonObject =
        this["outbounds"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == XrayConfigBuilder.OUTBOUND_TAG_PROXY }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun baseVless() = VpnServer(
        name = "test",
        host = "example.com",
        port = 443,
        protocol = VpnProtocol.VLESS,
        security = VpnSecurity.TLS,
        sni = "example.com",
        uuid = "11111111-2222-3333-4444-555555555555",
    )

    // ---- document shape ------------------------------------------------

    @Test
    fun `the document carries the pieces xray needs to run`() {
        val doc = success(baseVless()).document

        assertNotNull("log section", doc["log"])
        assertNotNull("inbounds section", doc["inbounds"])
        assertNotNull("outbounds section", doc["outbounds"])
        assertNotNull("routing section", doc["routing"])

        // direct and block must exist, or the routing rules reference nothing.
        val tags = doc["outbounds"]!!.jsonArray
            .map { it.jsonObject["tag"]?.jsonPrimitive?.content }
        assertTrue(tags.contains(XrayConfigBuilder.OUTBOUND_TAG_DIRECT))
        assertTrue(tags.contains(XrayConfigBuilder.OUTBOUND_TAG_BLOCK))
    }

    @Test
    fun `the rendered string is valid json and pretty printed`() {
        val rendered = success(baseVless()).rendered
        assertTrue(rendered.trimStart().startsWith("{"))
        assertTrue("expected indentation", rendered.contains("\n  "))
        // Re-parsing the rendered form must not throw.
        assertNotNull(XrayConfigBuilder.render(baseVless()))
    }

    // ---- VLESS ---------------------------------------------------------

    @Test
    fun `vless carries the uuid and an explicit no-encryption marker`() {
        val outbound = success(baseVless()).document.proxyOutbound()
        assertEquals("vless", outbound.str("protocol"))

        val user = outbound["settings"]!!.jsonObject["vnext"]!!.jsonArray[0]
            .jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals("11111111-2222-3333-4444-555555555555", user.str("id"))
        assertEquals("none", user.str("encryption"))
    }

    @Test
    fun `vless omits flow when the security layer cannot carry it`() {
        // flow is only meaningful over TLS/REALITY; emitting it over plain tcp
        // makes the core reject the config.
        val noTls = baseVless().copy(security = VpnSecurity.NONE, flow = VlessFlow.VISION)
        val user = success(noTls).document.proxyOutbound()["settings"]!!.jsonObject["vnext"]!!
            .jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertNull(user["flow"])
    }

    @Test
    fun `vless emits the vision flow over tls`() {
        val withFlow = baseVless().copy(flow = VlessFlow.VISION)
        val user = success(withFlow).document.proxyOutbound()["settings"]!!.jsonObject["vnext"]!!
            .jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals("xtls-rprx-vision", user.str("flow"))
    }

    // ---- REALITY -------------------------------------------------------

    @Test
    fun `reality settings land in the realm the core expects`() {
        val reality = baseVless().copy(
            security = VpnSecurity.REALITY,
            publicKey = "PBK_VALUE",
            shortId = "abcd",
            spiderX = "/spx",
            fingerprint = "firefox",
            sni = "www.microsoft.com",
        )
        val stream = success(reality).document.proxyOutbound()["streamSettings"]!!.jsonObject

        assertEquals("reality", stream.str("security"))
        val rs = stream["realitySettings"]!!.jsonObject
        assertEquals("www.microsoft.com", rs.str("serverName"))
        assertEquals("PBK_VALUE", rs.str("publicKey"))
        assertEquals("abcd", rs.str("shortId"))
        assertEquals("/spx", rs.str("spiderX"))
        assertEquals("firefox", rs.str("fingerprint"))
        assertFalse(rs["show"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `reality without a public key is rejected by name`() {
        val broken = baseVless().copy(security = VpnSecurity.REALITY, publicKey = null)
        val result = XrayConfigBuilder.build(broken)
        assertTrue(result is XrayConfigResult.InvalidNode)
        assertTrue(result.errorMessage!!.contains("publicKey"))
    }

    @Test
    fun `reality without an sni is rejected by name`() {
        val broken = baseVless().copy(
            security = VpnSecurity.REALITY,
            publicKey = "KEY",
            sni = null,
        )
        val result = XrayConfigBuilder.build(broken)
        assertTrue(result is XrayConfigResult.InvalidNode)
        assertTrue(result.errorMessage!!.contains("sni"))
    }

    // ---- TLS / XTLS ----------------------------------------------------

    @Test
    fun `tls settings use the tlsSettings key`() {
        val stream = success(baseVless()).document.proxyOutbound()["streamSettings"]!!.jsonObject
        assertEquals("tls", stream.str("security"))
        assertNotNull(stream["tlsSettings"])
        assertNull("tls must not use the xtls key", stream["xtlsSettings"])
        assertEquals("example.com", stream["tlsSettings"]!!.jsonObject.str("serverName"))
    }

    @Test
    fun `xtls settings use the xtlsSettings key`() {
        val xtls = baseVless().copy(security = VpnSecurity.XTLS)
        val stream = success(xtls).document.proxyOutbound()["streamSettings"]!!.jsonObject
        assertEquals("xtls", stream.str("security"))
        assertNotNull(stream["xtlsSettings"])
    }

    @Test
    fun `xtls on a non-vless protocol is rejected`() {
        val trojan = VpnServer(
            name = "t", host = "h.example", port = 443,
            protocol = VpnProtocol.TROJAN, security = VpnSecurity.XTLS, password = "pw",
        )
        val result = XrayConfigBuilder.build(trojan)
        assertTrue(result is XrayConfigResult.InvalidNode)
        assertTrue(result.errorMessage!!.contains("XTLS"))
    }

    @Test
    fun `alpn is emitted only when present`() {
        assertNull(
            success(baseVless()).document.proxyOutbound()["streamSettings"]!!
                .jsonObject["tlsSettings"]!!.jsonObject["alpn"],
        )
        val withAlpn = baseVless().copy(alpn = listOf("h2", "http/1.1"))
        val alpn = success(withAlpn).document.proxyOutbound()["streamSettings"]!!
            .jsonObject["tlsSettings"]!!.jsonObject["alpn"]!!.jsonArray
        assertEquals(listOf("h2", "http/1.1"), alpn.map { it.jsonPrimitive.content })
    }

    // ---- transports ----------------------------------------------------

    @Test
    fun `websocket transport emits a path and a host header`() {
        val ws = baseVless().copy(
            transport = VpnTransport.WS,
            path = "/ws",
            hostHeader = "cdn.example.com",
        )
        val stream = success(ws).document.proxyOutbound()["streamSettings"]!!.jsonObject
        assertEquals("ws", stream.str("network"))
        val wsSettings = stream["wsSettings"]!!.jsonObject
        assertEquals("/ws", wsSettings.str("path"))
        assertEquals("cdn.example.com", wsSettings["headers"]!!.jsonObject.str("Host"))
    }

    @Test
    fun `grpc transport emits a service name`() {
        val grpc = baseVless().copy(transport = VpnTransport.GRPC, serviceName = "svc")
        val stream = success(grpc).document.proxyOutbound()["streamSettings"]!!.jsonObject
        assertEquals("grpc", stream.str("network"))
        assertEquals("svc", stream["grpcSettings"]!!.jsonObject.str("serviceName"))
    }

    @Test
    fun `httpupgrade and splithttp keep xray's exact spellings`() {
        val upgrade = baseVless().copy(transport = VpnTransport.HTTP_UPGRADE, path = "/u")
        val upStream = success(upgrade).document.proxyOutbound()["streamSettings"]!!.jsonObject
        assertEquals("httpupgrade", upStream.str("network"))
        assertNotNull(upStream["httpupgradeSettings"])

        val split = baseVless().copy(transport = VpnTransport.SPLIT_HTTP, path = "/s")
        val splitStream = success(split).document.proxyOutbound()["streamSettings"]!!.jsonObject
        assertEquals("splithttp", splitStream.str("network"))
        assertNotNull(splitStream["splithttpSettings"])
    }

    @Test
    fun `plain tcp carries no transport sub-object`() {
        val stream = success(baseVless()).document.proxyOutbound()["streamSettings"]!!.jsonObject
        assertEquals("tcp", stream.str("network"))
        assertNull(stream["wsSettings"])
        assertNull(stream["grpcSettings"])
    }

    // ---- other protocols -----------------------------------------------

    @Test
    fun `vmess carries alterId and the cipher`() {
        val vmess = VpnServer(
            name = "v", host = "h.example", port = 443,
            protocol = VpnProtocol.VMESS, security = VpnSecurity.TLS,
            uuid = "uuid-here", alterId = 4, vmessSecurity = "chacha20-poly1305",
        )
        val outbound = success(vmess).document.proxyOutbound()
        assertEquals("vmess", outbound.str("protocol"))
        val user = outbound["settings"]!!.jsonObject["vnext"]!!.jsonArray[0]
            .jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals(4, user["alterId"]!!.jsonPrimitive.content.toInt())
        assertEquals("chacha20-poly1305", user.str("security"))
    }

    @Test
    fun `trojan carries the password in the servers array`() {
        val trojan = VpnServer(
            name = "t", host = "h.example", port = 443,
            protocol = VpnProtocol.TROJAN, security = VpnSecurity.TLS, password = "secret",
        )
        val outbound = success(trojan).document.proxyOutbound()
        assertEquals("trojan", outbound.str("protocol"))
        val server = outbound["settings"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject
        assertEquals("secret", server.str("password"))
    }

    @Test
    fun `shadowsocks carries the method and ignores the transport layer`() {
        val ss = VpnServer(
            name = "s", host = "h.example", port = 8388,
            protocol = VpnProtocol.SHADOWSOCKS, method = "aes-256-gcm", password = "pw",
        )
        val outbound = success(ss).document.proxyOutbound()
        assertEquals("shadowsocks", outbound.str("protocol"))
        val server = outbound["settings"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject
        assertEquals("aes-256-gcm", server.str("method"))
        assertEquals("pw", server.str("password"))
        assertNull("shadowsocks has no streamSettings", outbound["streamSettings"])
    }

    @Test
    fun `wireguard emits peers and no stream settings`() {
        val wg = VpnServer(
            name = "w", host = "wg.example", port = 51820,
            protocol = VpnProtocol.WIREGUARD,
            wgPrivateKey = "PRIVATE",
            wgPeerPublicKey = "PEER_PUBLIC",
            wgPreSharedKey = "PSK",
            wgLocalAddress = listOf("10.0.0.2/32"),
            wgMtu = 1420,
        )
        val outbound = success(wg).document.proxyOutbound()
        assertEquals("wireguard", outbound.str("protocol"))
        assertNull(outbound["streamSettings"])

        val settings = outbound["settings"]!!.jsonObject
        assertEquals("PRIVATE", settings.str("secretKey"))
        assertEquals(1420, settings["mtu"]!!.jsonPrimitive.content.toInt())
        val peer = settings["peers"]!!.jsonArray[0].jsonObject
        assertEquals("PEER_PUBLIC", peer.str("publicKey"))
        assertEquals("PSK", peer.str("preSharedKey"))
        assertEquals("wg.example:51820", peer.str("endpoint"))
    }

    @Test
    fun `hysteria2 is reported as unsupported rather than mis-compiled`() {
        // Emitting a config the core cannot run would fail at runtime with an
        // opaque error; naming the problem is the whole point of this branch.
        val hy2 = VpnServer(
            name = "h", host = "h.example", port = 443,
            protocol = VpnProtocol.HYSTERIA2, password = "pw",
        )
        val result = XrayConfigBuilder.build(hy2)
        assertTrue(result is XrayConfigResult.UnsupportedProtocol)
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("sing-box"))
    }

    // ---- validation ----------------------------------------------------

    @Test
    fun `a vless node without a uuid is rejected by field name`() {
        val broken = baseVless().copy(uuid = null)
        val result = XrayConfigBuilder.build(broken)
        assertTrue(result is XrayConfigResult.InvalidNode)
        assertTrue(result.errorMessage!!.contains("uuid"))
    }

    @Test
    fun `a shadowsocks node without a method is rejected by field name`() {
        val broken = VpnServer(
            name = "s", host = "h.example", port = 8388,
            protocol = VpnProtocol.SHADOWSOCKS, password = "pw", method = null,
        )
        val result = XrayConfigBuilder.build(broken)
        assertTrue(result is XrayConfigResult.InvalidNode)
        assertTrue(result.errorMessage!!.contains("method"))
    }

    @Test
    fun `an out of range port is rejected`() {
        val broken = baseVless().copy(port = 70_000)
        val result = XrayConfigBuilder.build(broken)
        assertTrue(result is XrayConfigResult.InvalidNode)
        assertTrue(result.errorMessage!!.contains("Port"))
    }

    @Test
    fun `a blank host is rejected`() {
        val broken = baseVless().copy(host = "")
        val result = XrayConfigBuilder.build(broken)
        assertTrue(result is XrayConfigResult.InvalidNode)
    }

    @Test
    fun `a socks node with no credentials is allowed`() {
        // Anonymous proxies are legal; rejecting them would break valid nodes.
        val socks = VpnServer(
            name = "p", host = "h.example", port = 1080,
            protocol = VpnProtocol.SOCKS,
        )
        assertTrue(XrayConfigBuilder.build(socks).isSuccess)
    }

    // ---- routing -------------------------------------------------------

    @Test
    fun `ad blocking adds a blackhole rule`() {
        val doc = success(baseVless()).document
        val rules = doc["routing"]!!.jsonObject["rules"]!!.jsonArray
            .map { it.jsonObject }
        val blockRule = rules.firstOrNull {
            it["outboundTag"]?.jsonPrimitive?.content == XrayConfigBuilder.OUTBOUND_TAG_BLOCK
        }
        assertNotNull(blockRule)
        val domains = blockRule!!["domain"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(domains.any { it.contains("ads") })
    }

    @Test
    fun `disabling ad blocking removes the blackhole rule`() {
        val result = XrayConfigBuilder.build(baseVless(), blockAds = false)
        assertTrue(result.isSuccess)
        val rules = (result as XrayConfigResult.Success).document["routing"]!!
            .jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertFalse(
            rules.any {
                it["outboundTag"]?.jsonPrimitive?.content == XrayConfigBuilder.OUTBOUND_TAG_BLOCK
            },
        )
    }

    @Test
    fun `the default rule sends all network traffic to the proxy`() {
        val doc = success(baseVless()).document
        val first = doc["routing"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject
        assertEquals(XrayConfigBuilder.OUTBOUND_TAG_PROXY, first.str("outboundTag"))
        assertEquals("tcp,udp", first["network"]!!.jsonArray[0].jsonPrimitive.content)
    }
}
