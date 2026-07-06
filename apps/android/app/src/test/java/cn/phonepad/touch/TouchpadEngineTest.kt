package cn.phonepad.touch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.phonepad.haptics.HapticsManager
import cn.phonepad.net.InputSender
import cn.phonepad.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TouchpadEngineTest {
    private lateinit var sender: RecordingInputSender
    private lateinit var engine: TouchpadEngine

    @Before
    fun setUp() {
        sender = RecordingInputSender()
        val context = ApplicationProvider.getApplicationContext<Context>()
        engine = TouchpadEngine(sender, HapticsManager(context))
        engine.setTarget(ReceiverTarget(host = "192.168.1.12", udpPort = 45454))
    }

    @Test
    fun oneFingerMoveSendsMovePacket() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        engine.onPointerMove(1, 110f, 105f)
        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.PacketKind.Move, sender.packets.first().kind)
    }

    @Test
    fun oneFingerTapSendsLeftClick() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        engine.onPointerUp(0, 100f, 100f, 100L)
        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.PacketKind.Click, sender.packets.first().kind)
        assertEquals(Protocol.MouseButton.Left, sender.packets.first().button)
    }

    @Test
    fun twoFingerScrollAfterSecondFingerDown() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        engine.onPointerMove(1, 120f, 100f)
        sender.packets.clear()

        engine.onPointerDown(2, 110f, 100f, 50L)
        engine.onPointerMove(2, 110f, 130f)

        assertTrue(sender.packets.isNotEmpty())
        assertEquals(Protocol.PacketKind.Scroll, sender.packets.first().kind)
    }

    @Test
    fun twoFingerTapSendsRightClick() {
        engine.onPointerDown(2, 100f, 100f, 0L)
        engine.onPointerUp(0, 100f, 100f, 100L)
        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.PacketKind.Click, sender.packets.first().kind)
        assertEquals(Protocol.MouseButton.Right, sender.packets.first().button)
    }

    @Test
    fun staggeredTwoFingerTapStillSendsRightClick() {
        engine.onPointerDown(2, 100f, 100f, 0L)
        engine.onPointerUp(1, 100f, 100f, 80L)
        engine.onPointerUp(0, 100f, 100f, 100L)
        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.MouseButton.Right, sender.packets.first().button)
    }

    @Test
    fun twoFingerScrollDoesNotClickAfterStaggeredRelease() {
        engine.onPointerDown(2, 100f, 100f, 0L)
        engine.onPointerMove(2, 100f, 130f)
        sender.packets.clear()
        engine.onPointerUp(1, 100f, 130f, 120L)
        engine.onPointerUp(0, 100f, 130f, 140L)
        assertTrue(sender.packets.isEmpty())
    }

    @Test
    fun staggeredTwoFingerDownTapSendsRightClick() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        engine.onPointerDown(2, 180f, 100f, 50L)
        engine.onPointerUp(0, 140f, 100f, 150L)
        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.MouseButton.Right, sender.packets.first().button)
    }

    @Test
    fun twoFingerTapWithSpreadFingersSendsRightClick() {
        engine.onPointerDown(2, 100f, 100f, 0L)
        engine.onPointerUp(0, 200f, 100f, 100L)
        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.MouseButton.Right, sender.packets.first().button)
    }

    @Test
    fun accidentalSecondFingerAfterMoveDoesNotTriggerRightClick() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        engine.onPointerMove(1, 110f, 100f)
        engine.onPointerDown(2, 105f, 100f, 40L)
        engine.onPointerUp(0, 105f, 100f, 120L)
        assertTrue(sender.packets.none { it.kind == Protocol.PacketKind.Click && it.button == Protocol.MouseButton.Right })
    }

    private class RecordingInputSender : InputSender() {
        val packets = mutableListOf<Protocol.InputPacket>()

        override fun send(host: String, port: Int, packet: Protocol.InputPacket) {
            packets.add(packet)
        }
    }
}
