package com.darkvvpn.app.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LinkTextTest {

    // ---- query parsing -------------------------------------------------

    @Test
    fun `parses and percent-decodes query parameters`() {
        val params = LinkText.queryParams("type=ws&security=reality&spx=%2Fpath&sni=a.example")
        assertEquals("ws", params["type"])
        assertEquals("reality", params["security"])
        assertEquals("/path", params["spx"])
        assertEquals("a.example", params["sni"])
    }

    @Test
    fun `keeps the first of a repeated key`() {
        val params = LinkText.queryParams("a=1&a=2")
        assertEquals("1", params["a"])
    }

    @Test
    fun `handles a valueless key and an empty query`() {
        assertEquals("", LinkText.queryParams("flag")["flag"])
        assertTrue(LinkText.queryParams(null).isEmpty())
        assertTrue(LinkText.queryParams("").isEmpty())
    }

    @Test
    fun `a malformed percent escape does not throw`() {
        // A bare % is invalid; the raw value is kept rather than crashing.
        val params = LinkText.queryParams("path=%zz")
        assertEquals("%zz", params["path"])
    }

    // ---- fragment ------------------------------------------------------

    @Test
    fun `splits the remark off the fragment`() {
        val (head, remark) = LinkText.splitFragment("vless://a@b:443?x=1#My%20Node")
        assertEquals("vless://a@b:443?x=1", head)
        assertEquals("My Node", remark)
    }

    @Test
    fun `takes the last hash so a remark may contain one`() {
        val (head, remark) = LinkText.splitFragment("vless://a@b:443#part1#part2")
        assertEquals("vless://a@b:443#part1", head)
        assertEquals("part2", remark)
    }

    @Test
    fun `a link with no fragment yields a null remark`() {
        val (head, remark) = LinkText.splitFragment("vless://a@b:443")
        assertEquals("vless://a@b:443", head)
        assertNull(remark)
    }

    // ---- base64 --------------------------------------------------------

    @Test
    fun `decodes standard base64`() {
        val encoded = Base64.getEncoder().encodeToString("hello world".toByteArray())
        assertEquals("hello world", LinkText.decodeBase64(encoded))
    }

    @Test
    fun `decodes url-safe base64`() {
        // A payload whose standard encoding contains + and / exercises this.
        val raw = "????>>>>"
        val urlSafe = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray())
        assertEquals(raw, LinkText.decodeBase64(urlSafe))
    }

    @Test
    fun `tolerates the newlines panels sprinkle through base64`() {
        val encoded = Base64.getEncoder().encodeToString("line one\nline two".toByteArray())
        val withNewlines = encoded.chunked(8).joinToString("\n")
        assertEquals("line one\nline two", LinkText.decodeBase64(withNewlines))
    }

    @Test
    fun `invalid base64 yields null rather than garbage`() {
        assertNull(LinkText.decodeBase64("!!!!not base64!!!!"))
        assertNull(LinkText.decodeBase64(""))
    }

    @Test
    fun `looksLikeBase64 separates a blob from a link list`() {
        val blob = Base64.getEncoder().encodeToString("vless://x@y:443#Z".toByteArray())
        assertTrue(LinkText.looksLikeBase64(blob))
        // Anything containing a scheme is a link list, not a blob.
        assertFalse(LinkText.looksLikeBase64("vless://a@b:443#c"))
        assertFalse(LinkText.looksLikeBase64("short"))
        assertFalse(LinkText.looksLikeBase64(""))
    }

    // ---- paths ---------------------------------------------------------

    @Test
    fun `normalises a path to a leading slash`() {
        assertEquals("/ws", LinkText.normalisePath("ws"))
        assertEquals("/ws", LinkText.normalisePath("/ws"))
        assertEquals("/", LinkText.normalisePath(""))
        assertEquals("/", LinkText.normalisePath(null))
    }

    @Test
    fun `strips the query that some panels append to the path`() {
        assertEquals("/ws", LinkText.normalisePath("/ws?ed=2048"))
    }

    // ---- truthiness ----------------------------------------------------

    @Test
    fun `recognises every spelling of true`() {
        listOf("1", "true", "TRUE", "yes", "on").forEach {
            assertTrue("expected $it to be truthy", LinkText.isTruthy(it))
        }
    }

    @Test
    fun `treats everything else as false`() {
        listOf("0", "false", "no", "off", "", null).forEach {
            assertFalse("expected $it to be falsy", LinkText.isTruthy(it))
        }
    }
}
