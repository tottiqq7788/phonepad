package cn.phonepad.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    @Test
    fun acceptsRfc1918Ranges() {
        assertTrue(LanAddress.isLikelyLan("192.168.1.12"))
        assertTrue(LanAddress.isLikelyLan("10.0.0.5"))
        assertTrue(LanAddress.isLikelyLan("172.16.0.1"))
        assertTrue(LanAddress.isLikelyLan("172.31.255.1"))
    }

    @Test
    fun rejectsPublicAndMisclassifiedAddresses() {
        assertFalse(LanAddress.isLikelyLan("172.200.1.1"))
        assertFalse(LanAddress.isLikelyLan("8.8.8.8"))
        assertFalse(LanAddress.isLikelyLan("172.15.0.1"))
    }
}
