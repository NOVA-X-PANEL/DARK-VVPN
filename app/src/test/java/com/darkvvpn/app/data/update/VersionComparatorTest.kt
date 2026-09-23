package com.darkvvpn.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    // ---- ordering ------------------------------------------------------

    @Test
    fun `numeric identifiers compare numerically not lexically`() {
        // The bug this guards: "1.10.0" < "1.9.0" under a plain string compare.
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.0"))
        assertTrue(VersionComparator.isNewer("1.100.0", "1.99.0"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.999.999"))
    }

    @Test
    fun `the leading v is optional on either side`() {
        assertEquals(0, VersionComparator.compare("v1.2.3", "1.2.3"))
        assertTrue(VersionComparator.isNewer("v1.3.0", "1.2.3"))
        assertTrue(VersionComparator.isNewer("1.3.0", "v1.2.3"))
    }

    @Test
    fun `a missing component counts as zero`() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
        assertTrue(VersionComparator.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun `an older version is not reported as newer`() {
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.1"))
        assertFalse(VersionComparator.isNewer("0.9.9", "1.0.0"))
    }

    // ---- pre-releases --------------------------------------------------

    @Test
    fun `a release outranks its own pre-release`() {
        // This is what stops a user on 1.0.0 being offered 1.0.0-rc1 as an update.
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-rc1"))
        assertFalse(VersionComparator.isNewer("1.0.0-rc1", "1.0.0"))
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun `pre-release identifiers compare numerically then lexically`() {
        assertTrue(VersionComparator.isNewer("1.0.0-rc2", "1.0.0-rc1"))
        assertTrue(VersionComparator.isNewer("1.0.0-rc10", "1.0.0-rc9"))
        assertTrue(VersionComparator.isNewer("1.0.0-beta", "1.0.0-alpha"))
        // A numeric identifier has lower precedence than an alphanumeric one.
        assertTrue(VersionComparator.isNewer("1.0.0-alpha", "1.0.0-1"))
    }

    @Test
    fun `a longer pre-release outranks its prefix`() {
        assertTrue(VersionComparator.isNewer("1.0.0-rc.1", "1.0.0-rc"))
    }

    @Test
    fun `the newer numeric core wins regardless of pre-release`() {
        assertTrue(VersionComparator.isNewer("1.0.1-rc1", "1.0.0"))
    }

    @Test
    fun `build metadata is ignored for precedence`() {
        assertEquals(0, VersionComparator.compare("1.0.0+build5", "1.0.0+build9"))
        assertEquals(0, VersionComparator.compare("1.0.0+build", "1.0.0"))
    }

    // ---- robustness ----------------------------------------------------

    @Test
    fun `non-numeric junk does not throw`() {
        // A tag like `nightly` must not crash the checker; it simply sorts low.
        assertFalse(VersionComparator.isNewer("nightly", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.0.0", "nightly"))
    }

    @Test
    fun `a trailing suffix on a numeric component is tolerated`() {
        assertTrue(VersionComparator.isNewer("1.2.3beta", "1.2.2"))
    }

    // ---- digest normalisation ------------------------------------------

    @Test
    fun `normalises a prefixed sha256 digest`() {
        val hex = "a".repeat(64)
        assertEquals(hex, VersionComparator.normaliseDigest("sha256:$hex"))
        assertEquals(hex, VersionComparator.normaliseDigest("SHA256:$hex".lowercase().let { "sha256:" + hex }))
    }

    @Test
    fun `accepts a bare hex digest`() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, VersionComparator.normaliseDigest(hex))
    }

    @Test
    fun `rejects a digest that is not 64 hex characters`() {
        assertNull(VersionComparator.normaliseDigest(null))
        assertNull(VersionComparator.normaliseDigest(""))
        assertNull(VersionComparator.normaliseDigest("sha256:tooshort"))
        // A sha512 is a valid digest but not the one we verify against.
        assertNull(VersionComparator.normaliseDigest("sha512:" + "a".repeat(128)))
        // Non-hex characters are refused rather than silently compared.
        assertNull(VersionComparator.normaliseDigest("sha256:" + "z".repeat(64)))
    }
}
