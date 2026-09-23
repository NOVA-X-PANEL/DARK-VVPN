package com.darkvvpn.app.data.subscription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endpoint filtering, pinned against the real subscription that exposed it.
 *
 * That list contained a node the provider had injected to talk to the user:
 *
 *     vless://…@1.2.3.4.5:1234?type=tcp&security=none#Update+your+subscription+daily
 *
 * `1.2.3.4.5` is five octets — not an address and not a hostname. It never
 * resolves, so imported it becomes a row showing "—" that fails if picked, and it
 * sits in the list looking like a broken server rather than an advertisement.
 */
class HostValidatorTest {

    @Test
    fun `the provider's placeholder node is rejected`() {
        assertFalse(HostValidator.isDialable("1.2.3.4.5"))
        assertFalse(HostValidator.isDialable("1.1.1.1.1"))
        assertFalse(HostValidator.isDialable("1.2.3.4.5.6"))
    }

    @Test
    fun `the real endpoints from that subscription are all accepted`() {
        // Both the hostnames and the IPv4 literal, because the list mixed them.
        listOf(
            "www.speedtest.net",
            "188.114.97.6",
            "cnd1.ksmrx2.ir",
            "cdn2.ksmrx2.ir",
            "panel.mr-x-shop.ir",
            "hysteriya.ksmrx2.ir",
        ).forEach {
            assertTrue("$it must be dialable", HostValidator.isDialable(it))
        }
    }

    // ---- hostnames ------------------------------------------------------

    @Test
    fun `ordinary hostnames pass`() {
        listOf(
            "example.com",
            "sub.example.co.uk",
            "a-b.example.com",
            "xn--mgba3a4f16a.ir", // punycode IDN
            "node_1.example.com", // underscores appear in real DNS records
        ).forEach { assertTrue("$it must pass", HostValidator.isDialable(it)) }
    }

    @Test
    fun `a bare word is not a hostname`() {
        // `localhost` and friends would point the tunnel at the phone itself.
        assertFalse(HostValidator.isDialable("localhost"))
        assertFalse(HostValidator.isDialable("test"))
        assertFalse(HostValidator.isDialable("server"))
    }

    @Test
    fun `a name with no TLD is rejected`() {
        assertFalse(HostValidator.isDialable("myhost."))
        assertFalse(HostValidator.isDialable(".myhost"))
        assertFalse(HostValidator.isDialable("my..host.com"))
    }

    @Test
    fun `hostnames with forbidden characters are rejected`() {
        assertFalse(HostValidator.isDialable("host name.com"))
        assertFalse(HostValidator.isDialable("host/path.com"))
        assertFalse(HostValidator.isDialable("user@host.com"))
        assertFalse(HostValidator.isDialable("-leading.example.com"))
        assertFalse(HostValidator.isDialable("trailing-.example.com"))
    }

    @Test
    fun `absurd lengths are rejected`() {
        assertFalse(HostValidator.isDialable(""))
        assertFalse(HostValidator.isDialable("   "))
        assertFalse(HostValidator.isDialable("a"))
        assertFalse(HostValidator.isDialable("ab"))
        assertFalse(HostValidator.isDialable("x".repeat(254) + ".com"))
        assertFalse(HostValidator.isDialable(("a".repeat(64)) + ".com"))
        assertFalse(HostValidator.isDialable(null))
    }

    // ---- IPv4 -----------------------------------------------------------

    @Test
    fun `valid IPv4 literals pass`() {
        listOf("1.1.1.1", "8.8.8.8", "188.114.97.6", "255.255.255.255").forEach {
            assertTrue("$it must pass", HostValidator.isDialable(it))
        }
    }

    @Test
    fun `out-of-range octets are rejected`() {
        assertFalse(HostValidator.isDialable("256.1.1.1"))
        assertFalse(HostValidator.isDialable("1.2.3.999"))
        assertFalse(HostValidator.isDialable("1.2.3.-4"))
    }

    @Test
    fun `an address that points at the device itself is rejected`() {
        // A node on 127.0.0.1 would tunnel the phone into the phone.
        assertFalse(HostValidator.isDialable("127.0.0.1"))
        assertFalse(HostValidator.isDialable("127.1.2.3"))
        assertFalse(HostValidator.isDialable("0.0.0.0"))
    }

    @Test
    fun `a private LAN address is allowed`() {
        // A self-hosted panel at home is a legitimate node; only loopback and the
        // unspecified address are meaningless.
        listOf("192.168.1.5", "10.0.0.7", "172.16.4.4").forEach {
            assertTrue("$it must be allowed", HostValidator.isDialable(it))
        }
    }

    @Test
    fun `a three-octet string is neither an address nor a hostname`() {
        assertFalse(HostValidator.isDialable("1.2.3"))
        assertFalse(HostValidator.isDialable("1.2"))
    }

    // ---- IPv6 -----------------------------------------------------------

    @Test
    fun `IPv6 literals pass, bracketed or not`() {
        listOf("2001:db8::1", "[2001:db8::1]", "2001:0db8:0000:0000:0000:0000:0000:0001", "2606:4700::1111").forEach {
            assertTrue("$it must pass", HostValidator.isDialable(it))
        }
    }

    @Test
    fun `IPv6 loopback and link-local are rejected`() {
        assertFalse(HostValidator.isDialable("::1"))
        assertFalse(HostValidator.isDialable("[::1]"))
        assertFalse(HostValidator.isDialable("::"))
        assertFalse(HostValidator.isDialable("fe80::1"))
    }

    @Test
    fun `a malformed IPv6 is rejected`() {
        assertFalse(HostValidator.isDialable("2001:zzzz::1"))
        assertFalse(HostValidator.isDialable("12345::1"))
        assertFalse(HostValidator.isDialable("::1::2"))
    }
}
