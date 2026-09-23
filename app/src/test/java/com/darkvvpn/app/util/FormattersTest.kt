package com.darkvvpn.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the formatting helpers. No Android framework is touched,
 * so these run on the JVM without Robolectric.
 */
class FormattersTest {

    @Test
    fun `bytes leaves sub-kilobyte values exact`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1023 B", Formatters.bytes(1023))
    }

    @Test
    fun `bytes scales through the unit table`() {
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.0 MB", Formatters.bytes(1024L * 1024))
        assertEquals("1.5 GB", Formatters.bytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `bytes rejects negative input rather than printing garbage`() {
        assertEquals("—", Formatters.bytes(-1))
    }

    @Test
    fun `speed appends the per-second suffix`() {
        assertEquals("1.0 MB/s", Formatters.speed(1024L * 1024))
    }

    @Test
    fun `duration omits the hour field below an hour`() {
        assertEquals("00:00", Formatters.duration(0))
        assertEquals("00:42", Formatters.duration(42))
        assertEquals("12:05", Formatters.duration(12 * 60 + 5))
        assertEquals("59:59", Formatters.duration(59 * 60 + 59))
    }

    @Test
    fun `duration adds the hour field at and above an hour`() {
        assertEquals("1:00:00", Formatters.duration(3600))
        assertEquals("2:03:11", Formatters.duration(2 * 3600 + 3 * 60 + 11))
    }

    @Test
    fun `duration clamps negative input to zero`() {
        assertEquals("00:00", Formatters.duration(-5))
    }

    @Test
    fun `ping renders a dash when the probe has not run`() {
        assertEquals("—", Formatters.ping(null))
        assertEquals("37 ms", Formatters.ping(37))
    }
}
