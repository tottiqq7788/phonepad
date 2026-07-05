package cn.phonepad.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun movePacketHasExpectedLength() {
        val packet = Protocol.InputPacket.move(7, 123_456L, -3, 5, fingers = 1)
        assertEquals(Protocol.PACKET_LEN, packet.encode().size)
    }

    @Test
    fun clickPacketEncodesButtonAndAction() {
        val packet = Protocol.InputPacket.click(9, 999L, Protocol.MouseButton.Right)
        val bytes = packet.encode()
        assertEquals('T'.code.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals(Protocol.PacketKind.Click.code, bytes[3])
        assertEquals(Protocol.MouseButton.Right.code, bytes[20])
        assertEquals(Protocol.ButtonAction.Click.code, bytes[21])
    }

    @Test
    fun discoveryRequestMatchesDesktopProtocol() {
        assertArrayEquals("PHONEPAD_DISCOVER_V1".toByteArray(), Protocol.DISCOVERY_REQUEST)
    }

    @Test
    fun movePacketMatchesRustGoldenVector() {
        val packet = Protocol.InputPacket.move(42, 123_456L, -7, 9, fingers = 1)
        assertArrayEquals(
            byteArrayOf(
                0x54, 0x50, 0x01, 0x01, 0x2A, 0x00, 0x00, 0x00, 0x40, 0xE2.toByte(), 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0xF9.toByte(), 0xFF.toByte(), 0x09, 0x00, 0x00, 0x00, 0x01, 0x00,
            ),
            packet.encode(),
        )
    }

    @Test
    fun clickPacketMatchesRustGoldenVector() {
        val packet = Protocol.InputPacket.click(43, 123_999L, Protocol.MouseButton.Right)
        assertArrayEquals(
            byteArrayOf(
                0x54, 0x50, 0x01, 0x03, 0x2B, 0x00, 0x00, 0x00, 0x5F, 0xE4.toByte(), 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x03, 0x00, 0x00,
            ),
            packet.encode(),
        )
    }
}
