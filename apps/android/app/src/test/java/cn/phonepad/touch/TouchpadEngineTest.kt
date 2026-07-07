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
        engine = TouchpadEngine(
            sender = sender,
            haptics = HapticsManager(context),
            enableScheduledFlush = false,
        )
        engine.setTarget(ReceiverTarget(host = "192.168.1.12", udpPort = 45454))
    }

    @Test
    fun oneFingerMoveSendsMovePacket() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        engine.onPointerMove(1, 110f, 105f)
        engine.flushPendingMotionForTest()
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
        engine.flushPendingMotionForTest()
        sender.packets.clear()

        engine.onPointerDown(2, 110f, 100f, 50L)
        engine.onPointerMove(2, 110f, 130f)
        engine.flushPendingMotionForTest()

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
        engine.flushPendingMotionForTest()
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
        engine.flushPendingMotionForTest()
        engine.onPointerDown(2, 105f, 100f, 40L)
        engine.onPointerUp(0, 105f, 100f, 120L)
        assertTrue(sender.packets.none { it.kind == Protocol.PacketKind.Click && it.button == Protocol.MouseButton.Right })
    }

    @Test
    fun slowMoveAccumulatesFractionalDisplacement() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        repeat(4) { step ->
            engine.onPointerMove(1, 100f + 0.5f * (step + 1), 100f)
        }
        engine.flushPendingMotionForTest()
        assertEquals(1, sender.packets.size)
        val packet = sender.packets.first()
        assertEquals(Protocol.PacketKind.Move, packet.kind)
        assertEquals(2, packet.x.toInt())
        assertEquals(0, packet.y.toInt())
    }

    @Test
    fun fastMovePreservesTotalDisplacementAcrossFrames() {
        engine.onPointerDown(1, 0f, 0f, 0L)
        engine.onPointerMove(1, 30f, -12f)
        engine.flushPendingMotionForTest()
        engine.onPointerMove(1, 45f, 8f)
        engine.flushPendingMotionForTest()
        val totalX = sender.packets.sumOf { it.x.toLong() }.toInt()
        val totalY = sender.packets.sumOf { it.y.toLong() }.toInt()
        assertEquals(45, totalX)
        assertEquals(8, totalY)
    }

    @Test
    fun slowScrollDoesNotDropSmallIncrementsAfterStart() {
        engine.onPointerDown(2, 100f, 100f, 0L)
        engine.onPointerMove(2, 100f, 120f)
        engine.flushPendingMotionForTest()
        sender.packets.clear()
        repeat(5) { step ->
            engine.onPointerMove(2, 100f, 120f + 0.5f * (step + 1))
        }
        engine.flushPendingMotionForTest()
        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.PacketKind.Scroll, sender.packets.first().kind)
        assertEquals(2, sender.packets.first().y.toInt())
    }

    @Test
    fun pointerUpFlushesRemainingMotion() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        engine.onPointerMove(1, 103f, 100f)
        engine.onPointerUp(0, 103f, 100f, 80L)
        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.PacketKind.Move, sender.packets.first().kind)
        assertEquals(3, sender.packets.first().x.toInt())
    }

    @Test
    fun secondFingerDownFlushesPendingSingleFingerMove() {
        engine.onPointerDown(1, 100f, 100f, 0L)
        engine.onPointerMove(1, 108f, 100f)
        sender.packets.clear()

        engine.onPointerDown(2, 105f, 100f, 40L)

        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.PacketKind.Move, sender.packets.first().kind)
        assertEquals(8, sender.packets.first().x.toInt())
    }

    @Test
    fun threeFingerSwipeUpSendsGesturePacket() {
        engine.onPointerDown(3, 100f, 200f, 0L)
        engine.onPointerMove(3, 100f, 100f)
        engine.onPointerUp(0, 100f, 100f, 200L)

        assertEquals(1, sender.packets.size)
        val packet = sender.packets.first()
        assertEquals(Protocol.PacketKind.Gesture, packet.kind)
        assertEquals(Protocol.GestureKind.SwipeUp, packet.gestureKind)
        assertEquals(Protocol.GesturePhase.End, packet.gesturePhase)
        assertEquals(3.toByte(), packet.fingers)
    }

    @Test
    fun fourFingerSwipeLeftSendsGesturePacket() {
        engine.onPointerDown(4, 200f, 100f, 0L)
        engine.onPointerMove(4, 100f, 100f)
        engine.onPointerUp(0, 100f, 100f, 200L)

        assertEquals(1, sender.packets.size)
        val packet = sender.packets.first()
        assertEquals(Protocol.PacketKind.Gesture, packet.kind)
        assertEquals(Protocol.GestureKind.SwipeLeft, packet.gestureKind)
        assertEquals(4.toByte(), packet.fingers)
    }

    @Test
    fun twoFingerHorizontalScrollStillSendsScrollPacket() {
        engine.onPointerDown(2, 100f, 100f, 0L)
        engine.onPointerMove(2, 160f, 100f)
        engine.flushPendingMotionForTest()

        assertEquals(1, sender.packets.size)
        assertEquals(Protocol.PacketKind.Scroll, sender.packets.first().kind)
        assertTrue(sender.packets.none { it.kind == Protocol.PacketKind.Gesture })
    }

    @Test
    fun staggeredFingerLiftStillSendsFourFingerGesture() {
        engine.onPointerDown(4, 200f, 100f, 0L)
        engine.onPointerMove(4, 100f, 100f)
        engine.onPointerUp(3, 100f, 100f, 100L)
        engine.onPointerUp(2, 100f, 100f, 120L)
        engine.onPointerUp(1, 100f, 100f, 140L)
        engine.onPointerUp(0, 100f, 100f, 160L)

        assertEquals(1, sender.packets.size)
        val packet = sender.packets.first()
        assertEquals(Protocol.PacketKind.Gesture, packet.kind)
        assertEquals(Protocol.GestureKind.SwipeLeft, packet.gestureKind)
        assertEquals(4.toByte(), packet.fingers)
    }

    @Test
    fun fourFingerSwipeDownUsesSessionFingerCount() {
        engine.onPointerDown(4, 100f, 100f, 0L)
        engine.onPointerMove(4, 100f, 200f)
        engine.onPointerUp(3, 100f, 200f, 100L)
        engine.onPointerUp(0, 100f, 200f, 160L)

        assertEquals(1, sender.packets.size)
        val packet = sender.packets.first()
        assertEquals(Protocol.GestureKind.SwipeDown, packet.gestureKind)
        assertEquals(4.toByte(), packet.fingers)
    }

    @Test
    fun shortThreeFingerSwipeDoesNotSendGesture() {
        engine.onPointerDown(3, 100f, 100f, 0L)
        engine.onPointerUp(0, 120f, 110f, 120L)
        assertTrue(sender.packets.isEmpty())
    }

    private class RecordingInputSender : InputSender() {
        val packets = mutableListOf<Protocol.InputPacket>()

        override fun send(host: String, port: Int, packet: Protocol.InputPacket) {
            packets.add(packet)
        }
    }
}
