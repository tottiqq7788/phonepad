package cn.phonepad.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingUrlParserTest {
    @Test
    fun parsesPairingUrl() {
        val payload = PairingUrlParser.parse(
            "phonepad://pair?host=192.168.1.12&tcp=45455&udp=45454&id=dev-1&name=My%20PC&secret=abc123",
        )
        assertNotNull(payload)
        assertEquals("192.168.1.12", payload?.host)
        assertEquals("dev-1", payload?.deviceId)
        assertEquals("My PC", payload?.deviceName)
        assertEquals("abc123", payload?.secret)
        assertEquals(45455, payload?.tcpPort)
        assertEquals(45454, payload?.udpPort)
    }

    @Test
    fun rejectsLegacyHostOnlyUrl() {
        assertNull(PairingUrlParser.parse("phonepad://192.168.1.12:45455"))
    }

    @Test
    fun rejectsMissingSecret() {
        assertNull(PairingUrlParser.parse("phonepad://pair?host=192.168.1.12&id=dev-1"))
    }
}
