package com.darkvvpn.app.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoNamingTest {

    @Test
    fun `reads a regional indicator pair as the iso country code`() {
        // The flag emoji is the most reliable location signal a remark carries,
        // because it is not a word in any language.
        assertEquals("NL", GeoNaming.countryCodeFromFlag("🇳🇱 Amsterdam"))
        assertEquals("DE", GeoNaming.countryCodeFromFlag("🇩🇪 Frankfurt #1"))
        assertEquals("US", GeoNaming.countryCodeFromFlag("🇺🇸 New York"))
        assertEquals("JP", GeoNaming.countryCodeFromFlag("🇯🇵 Tokyo"))
    }

    @Test
    fun `a remark with no flag yields no code`() {
        assertNull(GeoNaming.countryCodeFromFlag("Amsterdam"))
        assertNull(GeoNaming.countryCodeFromFlag(""))
    }

    @Test
    fun `a lone regional indicator is not a country`() {
        // Half of a flag pair must not be read as a code.
        assertNull(GeoNaming.countryCodeFromFlag("🇳 alone"))
    }

    @Test
    fun `resolves country and city from a flagged remark`() {
        val geo = GeoNaming.resolve("🇳🇱 Amsterdam")
        assertEquals("NL", geo.countryCode)
        assertEquals("Netherlands", geo.country)
        assertEquals("Amsterdam", geo.city)
    }

    @Test
    fun `resolves the country from a name when there is no flag`() {
        val geo = GeoNaming.resolve("Germany - Frankfurt")
        assertEquals("DE", geo.countryCode)
        assertEquals("Germany", geo.country)
    }

    @Test
    fun `matches the longest country name first`() {
        // "United" alone must not win over "United States".
        assertEquals("US", GeoNaming.resolve("United States West").countryCode)
        assertEquals("AE", GeoNaming.resolve("United Arab Emirates").countryCode)
    }

    @Test
    fun `recognises the aliases panels actually write`() {
        assertEquals("NL", GeoNaming.resolve("Holland").countryCode)
        assertEquals("US", GeoNaming.resolve("USA").countryCode)
        assertEquals("GB", GeoNaming.resolve("UK London").countryCode)
        assertEquals("TR", GeoNaming.resolve("Turkey Istanbul").countryCode)
        assertEquals("AE", GeoNaming.resolve("Dubai").countryCode)
    }

    @Test
    fun `an unrecognised remark leaves everything blank`() {
        val geo = GeoNaming.resolve("Premium Node 01")
        assertEquals("", geo.countryCode)
        assertEquals("", geo.country)
        assertEquals("", geo.city)
    }

    @Test
    fun `city extraction rejects a fragment that is just digits`() {
        // "01" is a server index, not a city.
        val geo = GeoNaming.resolve("🇩🇪 Germany 01")
        assertTrue(geo.city.isEmpty() || geo.city.all { !it.isDigit() })
    }

    @Test
    fun `stripDecoration removes the flag and brackets but keeps words`() {
        val stripped = GeoNaming.stripDecoration("🇳🇱 [Amsterdam] (Premium)")
        assertTrue(stripped.contains("Amsterdam"))
        assertTrue(stripped.contains("Premium"))
        assertTrue("the flag must be gone", stripped.none { it.code > 0x1F1E5 })
    }
}
