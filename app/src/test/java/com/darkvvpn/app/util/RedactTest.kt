package com.darkvvpn.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {

    @Test
    fun `secret keeps only a short fingerprint`() {
        val result = Redact.secret("abcdef12-3456-7890-abcd-ef1234567890")
        assertTrue(result.startsWith("ab"))
        assertTrue(result.endsWith("90"))
        assertFalse("the original secret must not survive", result.contains("3456"))
        assertTrue(result.contains("***"))
    }

    @Test
    fun `secret handles empty and short values`() {
        assertEquals("<none>", Redact.secret(null))
        assertEquals("<none>", Redact.secret(""))
        assertEquals("***", Redact.secret("abc"))
    }

    @Test
    fun `url strips the query string that carries tokens`() {
        assertEquals(
            "https://panel.example/sub/abc?<redacted>",
            Redact.url("https://panel.example/sub/abc?token=deadbeef"),
        )
    }

    @Test
    fun `url leaves a query-less url untouched`() {
        assertEquals("https://panel.example/sub/abc", Redact.url("https://panel.example/sub/abc"))
    }
}
