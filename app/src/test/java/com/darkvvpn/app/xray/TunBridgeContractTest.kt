package com.darkvvpn.app.xray

import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnServer
import com.darkvvpn.app.data.model.VpnTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract between the app's tun interface and the core's config.
 *
 * These two halves live in different files and are wired together at runtime by
 * a file descriptor, so nothing at compile time forces them to agree. When they
 * disagree the tunnel *establishes successfully* and then forwards nothing —
 * the hardest failure in the project to notice, let alone diagnose. These tests
 * pin the shared values so a future edit to one half fails here.
 */
class TunBridgeContractTest {

    private fun success(server: VpnServer) =
        XrayConfigBuilder.build(server) as XrayConfigResult.Success

    private fun tunSettings(server: VpnServer): JsonObject {
        val doc = success(server).document
        val tun = doc["inbounds"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["protocol"]?.jsonPrimitive?.content == "tun" }
        return tun["settings"]!!.jsonObject
    }

    private fun sample(protocol: VpnProtocol = VpnProtocol.VLESS) = VpnServer(
        name = "contract",
        host = "example.com",
        port = 443,
        protocol = protocol,
        security = VpnSecurity.TLS,
        transport = VpnTransport.TCP,
        sni = "example.com",
        uuid = "11111111-2222-3333-4444-555555555555",
        password = "pw",
        method = "aes-256-gcm",
        wgPrivateKey = "PRIV",
        wgPeerPublicKey = "PUB",
    )

    @Test
    fun `the tun inbound exists for every xray-native protocol`() {
        // The inbound is the thing the fd attaches to; without it, no protocol
        // can carry traffic however correct its outbound is.
        VpnProtocol.entries.filter { it.isXrayNative }.forEach { protocol ->
            val settings = tunSettings(sample(protocol))
            assertNotNull("no tun name for $protocol", settings["name"])
            assertNotNull("no MTU for $protocol", settings["mtu"])
        }
    }

    @Test
    fun `every protocol gets a tun inbound in the same document as its outbound`() {
        VpnProtocol.entries.filter { it.isXrayNative }.forEach { protocol ->
            val doc = success(sample(protocol)).document
            val protocols = doc["inbounds"]!!.jsonArray
                .map { it.jsonObject["protocol"]!!.jsonPrimitive.content } +
                doc["outbounds"]!!.jsonArray.map { it.jsonObject["protocol"]!!.jsonPrimitive.content }
            assertTrue("$protocol is missing its tun inbound", protocols.contains("tun"))
            assertTrue("$protocol is missing its outbound", protocols.contains(protocol.xrayProtocol))
        }
    }

    @Test
    fun `the tun settings carry no address because the app owns it`() {
        // The fd's address comes from VpnService.Builder.addAddress; a second
        // address in the config would be a competing authority.
        val settings = tunSettings(sample())
        assertEquals(null, settings["address"])
    }

    @Test
    fun `sniffing is on so domain routing rules can match`() {
        val doc = success(sample()).document
        val tun = doc["inbounds"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["protocol"]?.jsonPrimitive?.content == "tun" }
        val sniffing = tun["sniffing"]!!.jsonObject
        assertTrue(sniffing["enabled"]!!.jsonPrimitive.content.toBoolean())
        val overrides = sniffing["destOverride"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(overrides.contains("tls"))
        // routeOnly=false is what lets a sniffed domain drive the routing rules.
        assertEquals("false", sniffing["routeOnly"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the mtu is configurable and defaults to the shared constant`() {
        assertEquals(
            XrayConfigBuilder.TUN_MTU,
            tunSettings(sample())["mtu"]!!.jsonPrimitive.content.toInt(),
        )
        val custom = XrayConfigBuilder.build(sample(), mtu = 1380) as XrayConfigResult.Success
        val tun = custom.document["inbounds"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["protocol"]?.jsonPrimitive?.content == "tun" }
        assertEquals(
            1380,
            tun["settings"]!!.jsonObject["mtu"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `the config carries no dns section`() {
        // DNS is the device's resolver pointed at the tun's DNS servers; Xray
        // would hijack those queries if it had its own dns block here.
        val doc = success(sample()).document
        assertEquals(null, doc["dns"])
    }

    @Test
    fun `an unsupported protocol is refused before any config is built`() {
        val hy2 = sample(VpnProtocol.HYSTERIA2)
        val result = XrayConfigBuilder.build(hy2)
        assertTrue(result is XrayConfigResult.UnsupportedProtocol)
    }
}
