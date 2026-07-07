package cn.phonepad.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class PhonePadLoggerTest {
    @Test
    fun shortId_keepsShortValues() {
        assertEquals("abcdefgh", PhonePadLogger.shortId("abcdefgh"))
    }

    @Test
    fun shortId_truncatesLongValues() {
        assertEquals("12345678…", PhonePadLogger.shortId("1234567890"))
    }
}
