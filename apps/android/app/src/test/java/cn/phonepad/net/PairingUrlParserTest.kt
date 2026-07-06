package cn.phonepad.net

import cn.phonepad.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingUrlParserTest {
    @Test
    fun parsesLegacyPairingUrlWithHost() {
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
        assertEquals(false, payload?.needsDiscovery())
    }

    @Test
    fun parsesHostlessPairingUrl() {
        val payload = PairingUrlParser.parse(
            "phonepad://pair?tcp=45455&udp=45454&discovery=45456&id=dev-1&name=My%20PC&secret=abc123",
        )
        assertNotNull(payload)
        assertNull(payload?.host)
        assertEquals("dev-1", payload?.deviceId)
        assertEquals(45456, payload?.discoveryPort)
        assertEquals(true, payload?.needsDiscovery())
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

class DiscoveryClientTest {
    @Test
    fun validatesDiscoverResponseAuth() {
        val secret = "secret123"
        val nonce = 42
        val auth = Protocol.authToken(secret, nonce + 1)
        val payload =
            """{"type":"discoverResponse","deviceId":"dev-1","ip":"10.40.184.10","tcpPort":45455,"udpPort":45454,"discoveryPort":45456,"name":"My PC","auth":$auth}"""

        val result = DiscoveryClient.parseResponse(payload, "dev-1", secret, nonce)
        assertNotNull(result)
        assertEquals("10.40.184.10", result?.host)
        assertEquals("My PC", result?.deviceName)
    }

    @Test
    fun rejectsWrongDeviceId() {
        val secret = "secret123"
        val nonce = 7
        val auth = Protocol.authToken(secret, nonce + 1)
        val payload =
            """{"type":"discoverResponse","deviceId":"other","ip":"10.0.0.1","auth":$auth}"""
        assertNull(DiscoveryClient.parseResponse(payload, "dev-1", secret, nonce))
    }

    @Test
    fun rejectsInvalidAuth() {
        val payload =
            """{"type":"discoverResponse","deviceId":"dev-1","ip":"10.0.0.1","auth":123}"""
        assertNull(DiscoveryClient.parseResponse(payload, "dev-1", "secret123", 1))
    }

    @Test
    fun validatesSignedDiscoverResponseAuth() {
        val secret = "pair-secret"
        var nonce = 0
        var auth = 0L
        for (candidate in 1..10_000) {
            val value = Protocol.authToken(secret, candidate + 1)
            if (value < 0) {
                nonce = candidate
                auth = value
                break
            }
        }
        assertTrue(auth < 0)
        val payload =
            """{"type":"discoverResponse","deviceId":"dev-1","ip":"10.0.0.1","auth":$auth}"""
        assertNotNull(DiscoveryClient.parseResponse(payload, "dev-1", secret, nonce))
    }
}
