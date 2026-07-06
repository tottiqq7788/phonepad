package cn.phonepad.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
