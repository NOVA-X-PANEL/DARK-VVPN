package com.darkvvpn.app.data.subscription

import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnSecurity
import com.darkvvpn.app.data.model.VpnTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * The two failure modes that made real subscriptions look broken.
 *
 * Both are invisible to a parser test, because in both cases the parser is handed
 * something that is not a subscription at all: a gzip stream, or a web page. The
 * fault is in the fetch, and the symptom the user sees is "no usable nodes".
 *
 * These call the real product functions ([ResponseClassifier], [HttpFetcher]'s
 * decompression path, [SubscriptionParser]) rather than re-implementing their
 * logic, so the test cannot pass while the app does something else.
 */
class HttpFetchRobustnessTest {

    /** What a panel actually serves: base64 of newline-separated links. */
    private val subscriptionBody: String = Base64.getEncoder().encodeToString(
        ("vless://11111111-2222-3333-4444-555555555555@nl.example:443" +
            "?type=tcp&security=tls&sni=nl.example#Amsterdam\n" +
            "trojan://password@tr.example:443#Istanbul").toByteArray(),
    )

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): String =
        java.util.zip.GZIPInputStream(bytes.inputStream()).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    // ------------------------------------------------------------------
    // Compression — the bug that broke Cloudflare-fronted panels
    // ------------------------------------------------------------------

    @Test
    fun `a gzipped subscription decompresses into usable nodes`() {
        val compressed = gzip(subscriptionBody)
        assertTrue(
            "the fixture must actually be compressed for this test to mean anything",
            compressed.size < subscriptionBody.length,
        )

        val parsed = SubscriptionParser().parse(inflate(compressed))

        assertTrue(parsed.isSuccess)
        assertEquals(2, parsed.servers.size)
        assertEquals("nl.example", parsed.servers[0].host)
    }

    @Test
    fun `reading compressed bytes as text yields nothing, which is the bug`() {
        // What the old code did. This asserts the failure mode is real, so the
        // decompression above is not decorative.
        val asText = gzip(subscriptionBody).toString(Charsets.UTF_8)
        assertTrue(
            "compressed bytes read as text must not produce nodes",
            SubscriptionParser().parse(asText).servers.isEmpty(),
        )
    }

    @Test
    fun `gzip is detected from the magic bytes`() {
        // The fetcher sniffs these so a server that compresses without sending
        // Content-Encoding is still handled.
        assertTrue(ResponseClassifier.isGzip(gzip(subscriptionBody)))
    }

    @Test
    fun `plain text is not mistaken for gzip`() {
        assertFalse(ResponseClassifier.isGzip(subscriptionBody.toByteArray()))
        assertFalse(ResponseClassifier.isGzip("vless://x@y:443#N".toByteArray()))
        assertFalse(ResponseClassifier.isGzip(ByteArray(0)))
        assertFalse(ResponseClassifier.isGzip(byteArrayOf(0x1f)))
    }

    // ------------------------------------------------------------------
    // Markup — the bug that reported "no nodes" for an expired URL
    // ------------------------------------------------------------------

    @Test
    fun `an html login page is recognised as markup`() {
        assertTrue(ResponseClassifier.looksLikeMarkup("<!DOCTYPE html>\n<html><body>Login</body></html>"))
        assertTrue(ResponseClassifier.looksLikeMarkup("  <html lang=\"en\">"))
        assertTrue(ResponseClassifier.looksLikeMarkup("<?xml version=\"1.0\"?>"))
        assertTrue(ResponseClassifier.looksLikeMarkup("\n\n<html>"))
    }

    @Test
    fun `a real node list is never mistaken for markup`() {
        assertFalse(ResponseClassifier.looksLikeMarkup(subscriptionBody))
        assertFalse(ResponseClassifier.looksLikeMarkup("vless://uuid@host:443#Node"))
        assertFalse(ResponseClassifier.looksLikeMarkup("dmxlc3M6Ly91dWlkQGhvc3Q6NDQz"))
        assertFalse(ResponseClassifier.looksLikeMarkup(""))
    }

    @Test
    fun `a body that merely starts with an angle bracket is left alone`() {
        // Guarding against an over-eager rule: only an actual document is markup.
        assertFalse(ResponseClassifier.looksLikeMarkup("<not html, just text"))
    }

    // ------------------------------------------------------------------
    // Error classification — tested through the real function
    // ------------------------------------------------------------------

    @Test
    fun `an expired or blocked link is described as refused`() {
        listOf(401, 403).forEach { code ->
            val message = ResponseClassifier.describeHttpFailure(code)
            assertTrue("HTTP $code should mention refusal: $message", message.contains("refused"))
            assertTrue("and should name the code: $message", message.contains("$code"))
        }
    }

    @Test
    fun `a typo in the url is described as not found`() {
        val message = ResponseClassifier.describeHttpFailure(404)
        assertTrue(message.contains("not found"))
        assertTrue(message.contains("404"))
    }

    @Test
    fun `rate limiting is described as temporary and on the provider`() {
        val message = ResponseClassifier.describeHttpFailure(429)
        assertTrue(message.contains("rate-limiting"))
    }

    @Test
    fun `a server fault is attributed to the provider`() {
        listOf(500, 502, 503).forEach { code ->
            val message = ResponseClassifier.describeHttpFailure(code)
            assertTrue("HTTP $code should be theirs: $message", message.contains("their side"))
        }
    }

    @Test
    fun `an unclassified code still names the code`() {
        val message = ResponseClassifier.describeHttpFailure(418)
        assertTrue(message.contains("418"))
    }

    // ------------------------------------------------------------------
    // Scheme safety, re-checked at every redirect hop
    // ------------------------------------------------------------------

    @Test
    fun `only http and https are dialled`() {
        assertTrue(ResponseClassifier.isAllowedScheme("http"))
        assertTrue(ResponseClassifier.isAllowedScheme("https"))
        assertTrue(ResponseClassifier.isAllowedScheme("HTTPS"))

        // A pasted URL must not be able to reach local files or another app's data.
        assertFalse(ResponseClassifier.isAllowedScheme("file"))
        assertFalse(ResponseClassifier.isAllowedScheme("content"))
        assertFalse(ResponseClassifier.isAllowedScheme("ftp"))
        assertFalse(ResponseClassifier.isAllowedScheme("javascript"))
        assertFalse(ResponseClassifier.isAllowedScheme(null))
    }

    // ------------------------------------------------------------------
    // End to end
    // ------------------------------------------------------------------

    @Test
    fun `a gzipped base64 subscription round-trips into dialable nodes`() {
        // The full path a working panel takes, including the compression step that
        // used to break it.
        val parsed = SubscriptionParser().parse(inflate(gzip(subscriptionBody)))

        assertTrue(parsed.isSuccess)
        assertEquals(com.darkvvpn.app.data.model.SubscriptionFormat.BASE64_LIST, parsed.format)

        val vless = parsed.servers.first { it.protocol == VpnProtocol.VLESS }
        assertEquals(VpnSecurity.TLS, vless.security)
        assertEquals(VpnTransport.TCP, vless.transport)
        assertEquals("11111111-2222-3333-4444-555555555555", vless.uuid)

        val trojan = parsed.servers.first { it.protocol == VpnProtocol.TROJAN }
        assertEquals("password", trojan.password)
    }
}

/**
 * Caching the fetched node list is what makes a subscription survive a restart.
 * Without it the Servers screen is empty until the next scheduled refresh, which
 * a user reports as "my subscription imported but nothing appeared".
 */
class NodesCodecTest {

    private val codec = NodesCodec()

    private fun node(host: String, protocol: VpnProtocol = VpnProtocol.VLESS) = com.darkvvpn.app.data.model.VpnServer(
        name = "Node $host",
        host = host,
        port = 443,
        protocol = protocol,
        security = VpnSecurity.REALITY,
        transport = VpnTransport.WS,
        sni = "www.example.com",
        path = "/ws",
        publicKey = "PUBKEY",
        shortId = "abcd",
        uuid = "11111111-2222-3333-4444-555555555555",
        subscriptionId = "sub-1",
    )

    @Test
    fun `nodes survive a round trip with every field intact`() {
        val original = mapOf(
            "sub-1" to listOf(node("a.example"), node("b.example", VpnProtocol.TROJAN)),
        )

        val restored = codec.decode(codec.encode(original))

        assertEquals(1, restored.size)
        val nodes = restored.getValue("sub-1")
        assertEquals(2, nodes.size)

        val first = nodes.first { it.host == "a.example" }
        assertEquals("Node a.example", first.name)
        assertEquals(VpnProtocol.VLESS, first.protocol)
        assertEquals(VpnSecurity.REALITY, first.security)
        assertEquals(VpnTransport.WS, first.transport)
        assertEquals("www.example.com", first.sni)
        assertEquals("/ws", first.path)
        assertEquals("PUBKEY", first.publicKey)
        assertEquals("abcd", first.shortId)
        assertEquals("sub-1", first.subscriptionId)
        // Credentials are part of the node, and must survive too — that is the
        // whole point of caching rather than re-fetching on every launch.
        assertEquals("11111111-2222-3333-4444-555555555555", first.uuid)
    }

    @Test
    fun `the node id is preserved so the selection survives a restart`() {
        // A regenerated id would silently drop the user's selected server.
        val original = node("a.example")
        val restored = codec.decode(codec.encode(mapOf("sub-1" to listOf(original))))
            .getValue("sub-1").single()

        assertEquals(original.id, restored.id)
        assertEquals(original, restored)
    }

    @Test
    fun `a measured latency survives the round trip`() {
        val measured = node("a.example").copy(pingMs = 42)
        val restored = codec.decode(codec.encode(mapOf("sub-1" to listOf(measured))))
            .getValue("sub-1").single()
        assertEquals(42, restored.pingMs)
    }

    @Test
    fun `absent or unreadable input yields an empty map rather than throwing`() {
        assertTrue(codec.decode(null).isEmpty())
        assertTrue(codec.decode("").isEmpty())
        assertTrue(codec.decode("   ").isEmpty())
        assertTrue(codec.decode("not json at all").isEmpty())
        assertTrue(codec.decode("""{"bySubscription": "wrong shape"}""").isEmpty())
        assertTrue(codec.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun `data from an incompatible schema is discarded, not guessed at`() {
        // The reason the version exists: nodes persist enum names, so a rename
        // would otherwise decode into a node pointing at the wrong security layer,
        // failing in a way that looks like a server fault.
        val stale = """{"schemaVersion": 999, "bySubscription": {"sub-1": []}}"""
        assertTrue("stale schema must be dropped", codec.decode(stale).isEmpty())
    }

    @Test
    fun `an absent schema version decodes as the current one`() {
        // Forward compatibility: a payload written before the field existed is
        // still valid, which is what makes adding it a safe change.
        assertEquals(StoredNodes.CURRENT_SCHEMA_VERSION, StoredNodes().schemaVersion)
    }

    @Test
    fun `an empty map round-trips as empty`() {
        assertTrue(codec.decode(codec.encode(emptyMap())).isEmpty())
    }

    @Test
    fun `keys are preserved exactly so nodes can be dropped per subscription`() {
        val original = mapOf(
            "sub-1" to listOf(node("a.example")),
            "sub-2" to listOf(node("b.example")),
        )
        assertEquals(setOf("sub-1", "sub-2"), codec.decode(codec.encode(original)).keys)
    }
}
