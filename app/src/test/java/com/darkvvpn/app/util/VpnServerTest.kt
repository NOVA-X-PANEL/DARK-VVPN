package com.darkvvpn.app.util

import com.darkvvpn.app.data.model.PingQuality
import com.darkvvpn.app.data.model.VpnProtocol
import com.darkvvpn.app.data.model.VpnServer
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnServerTest {

    private fun server(ping: Int?) = VpnServer(
        name = "Test",
        country = "Germany",
        countryCode = "DE",
        city = "Frankfurt",
        host = "de1.example",
        pingMs = ping,
    )

    @Test
    fun `ping quality buckets follow the documented thresholds`() {
        assertEquals(PingQuality.UNKNOWN, server(null).pingQuality)
        assertEquals(PingQuality.EXCELLENT, server(0).pingQuality)
        assertEquals(PingQuality.EXCELLENT, server(80).pingQuality)
        assertEquals(PingQuality.GOOD, server(81).pingQuality)
        assertEquals(PingQuality.GOOD, server(160).pingQuality)
        assertEquals(PingQuality.FAIR, server(161).pingQuality)
        assertEquals(PingQuality.FAIR, server(300).pingQuality)
        assertEquals(PingQuality.POOR, server(301).pingQuality)
    }

    @Test
    fun `display location falls back to the country when the city is blank`() {
        val withCity = server(10)
        assertEquals("Frankfurt, Germany", withCity.displayLocation)

        val withoutCity = withCity.copy(city = "")
        assertEquals("Germany", withoutCity.displayLocation)
    }

    @Test
    fun `protocol falls back to VLESS on an unknown name`() {
        assertEquals(VpnProtocol.VLESS, VpnProtocol.fromName("not-a-protocol"))
        assertEquals(VpnProtocol.VLESS, VpnProtocol.fromName(null))
        assertEquals(VpnProtocol.WIREGUARD, VpnProtocol.fromName("wireguard"))
    }
}
