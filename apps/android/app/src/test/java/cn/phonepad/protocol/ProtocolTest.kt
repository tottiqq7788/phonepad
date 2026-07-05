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
}
