package cn.phonepad.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingUrlParserTest {
    @Test
    fun parseHost_fromConnectionUrl() {
        assertEquals("192.168.1.12", PairingUrlParser.parseHost("phonepad://192.168.1.12:45455"))
    }

    @Test
    fun parseHost_fromPlainIp() {
        assertEquals("192.168.1.12", PairingUrlParser.parseHost("192.168.1.12"))
    }

    @Test
    fun parseHost_rejectsPlaceholder() {
        assertNull(PairingUrlParser.parseHost("phonepad://desktop-ip:45455"))
    }
}
