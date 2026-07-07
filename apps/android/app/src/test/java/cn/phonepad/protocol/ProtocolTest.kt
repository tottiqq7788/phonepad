package cn.phonepad.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun movePacketHasExpectedLength() {
        val token = Protocol.authToken("secret123", 7)
        val packet = Protocol.InputPacket.move(7, 123_456L, -3, 5, token, fingers = 1)
        assertEquals(Protocol.PACKET_LEN, packet.encode().size)
        assertEquals(Protocol.VERSION, packet.encode()[2])
    }

    @Test
    fun authTokenMatchesRustGoldenVector() {
        val token42 = Protocol.authToken("secret123", 42)
        val token43 = Protocol.authToken("secret123", 43)
        assertEquals(0x23B3FBC258699E45L, token42)
        assertNotEquals(token42, token43)
    }

    @Test
    fun clickPacketEncodesButtonAndAction() {
        val token = Protocol.authToken("secret123", 9)
        val packet = Protocol.InputPacket.click(9, 999L, Protocol.MouseButton.Right, token)
        val bytes = packet.encode()
        assertEquals('T'.code.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals(Protocol.PacketKind.Click.code, bytes[3])
        assertEquals(Protocol.MouseButton.Right.code, bytes[20])
        assertEquals(Protocol.ButtonAction.Click.code, bytes[21])
    }

    @Test
    fun discoveryRequestMatchesDesktopProtocol() {
        assertEquals("PHONEPAD_DISCOVER_V1", String(Protocol.DISCOVERY_REQUEST))
    }

    @Test
    fun gesturePacketEncodesKindAndPhase() {
        val token = Protocol.authToken("secret123", 44)
        val packet = Protocol.InputPacket.gesture(
            sequence = 44,
            timestampMicros = 123_456L,
            gestureKind = Protocol.GestureKind.SwipeDown,
            gesturePhase = Protocol.GesturePhase.End,
            fingers = 3,
            authToken = token,
            amount = 90,
        )
        val bytes = packet.encode()
        assertEquals(Protocol.PacketKind.Gesture.code, bytes[3])
        assertEquals(Protocol.GestureKind.SwipeDown.code, bytes[20])
        assertEquals(Protocol.GesturePhase.End.code, bytes[21])
        assertEquals(3.toByte(), bytes[22])
    }
}
