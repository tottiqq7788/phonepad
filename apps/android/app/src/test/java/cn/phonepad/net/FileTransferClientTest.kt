package cn.phonepad.net

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FileTransferClientTest {
    @Test
    fun tokenToLittleEndianBytes_preservesLargeU64() {
        val bytes = tokenToLittleEndianBytes("7123182144640608305")
        val parsed = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
        assertEquals(7123182144640608305L, parsed)
    }

    @Test
    fun tokenToLittleEndianBytes_supportsMaxU64() {
        val bytes = tokenToLittleEndianBytes("18446744073709551615")
        assertArrayEquals(
            byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1),
            bytes,
        )
    }

    @Test
    fun parseFileTransferToken_acceptsStringValue() {
        val json = JSONObject()
            .put("ok", true)
            .put("token", "7123182144640608305")
        assertEquals("7123182144640608305", parseFileTransferToken(json))
    }

    @Test
    fun parseFileTransferToken_acceptsLongNumericValue() {
        val json = JSONObject()
            .put("ok", true)
            .put("token", 7123182144640608305L)
        assertEquals("7123182144640608305", parseFileTransferToken(json))
    }

    @Test
    fun parseFileTransferToken_rejectsMissingToken() {
        val json = JSONObject().put("ok", true)
        assertNull(parseFileTransferToken(json))
    }

    @Test
    fun validateFileTransferToken_rejectsInvalidValues() {
        assertTrue(validateFileTransferToken("7123182144640608305"))
        assertFalse(validateFileTransferToken(""))
        assertFalse(validateFileTransferToken("-1"))
        assertFalse(validateFileTransferToken("18446744073709551616"))
    }
}
