package cn.phonepad.touch

import cn.phonepad.haptics.HapticsManager
import cn.phonepad.net.InputSender
import cn.phonepad.protocol.Protocol
import kotlin.math.hypot
import java.time.Instant

data class ReceiverTarget(
    val host: String,
    val udpPort: Int = Protocol.UDP_INPUT_PORT,
    val deviceId: String = "",
    val deviceName: String = "",
)

class TouchpadEngine(
    private val sender: InputSender,
    private val haptics: HapticsManager,
) {
    private var target: ReceiverTarget? = null
    private var pointerCount = 0
    private var mode = Mode.Idle
    private var moved = false

    private var sessionMaxPointerCount = 0
    private var sessionMoved = false
    private var sessionDownTime = 0L
    private var sessionDownX = 0f
    private var sessionDownY = 0f
    private var twoFingerTapEligible = false

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastSentX = 0f
    private var lastSentY = 0f

    fun setTarget(target: ReceiverTarget?) {
        this.target = target
    }

    fun onPointerDown(count: Int, x: Float, y: Float, eventTime: Long) {
        pointerCount = count
        if (mode == Mode.Idle) {
            sessionDownTime = eventTime
            sessionDownX = x
            sessionDownY = y
            sessionMoved = false
            sessionMaxPointerCount = count
            twoFingerTapEligible = count == 2
        } else {
            sessionMaxPointerCount = maxOf(sessionMaxPointerCount, count)
            if (count == 2) {
                twoFingerTapEligible = !sessionMoved
            }
        }

        downTime = eventTime
        downX = x
        downY = y
        lastSentX = 0f
        lastSentY = 0f
        moved = false
        mode = when (count) {
            1 -> Mode.OneFinger
            2 -> Mode.TwoFinger
            else -> Mode.MultiFinger
        }
    }

    fun onPointerMove(count: Int, x: Float, y: Float) {
        pointerCount = count
        val host = target ?: return
        val now = wallMicros()

        when (mode) {
            Mode.OneFinger -> {
                if (count != 1) return
                val totalDx = x - downX
                val totalDy = y - downY
                val dx = totalDx - lastSentX
                val dy = totalDy - lastSentY
                if (dx.toInt() == 0 && dy.toInt() == 0) return
                moved = true
                sessionMoved = true
                lastSentX = totalDx
                lastSentY = totalDy
                sender.send(
                    host.host,
                    host.udpPort,
                    Protocol.InputPacket.move(0, now, dx.toInt(), dy.toInt(), authToken = 0L, fingers = 1),
                )
            }

            Mode.TwoFinger -> {
                if (count != 2) return
                val totalDx = x - downX
                val totalDy = y - downY
                val dx = totalDx - lastSentX
                val dy = totalDy - lastSentY
                if (!moved && hypot(totalDx, totalDy) < SCROLL_START_DISTANCE) return
                if (hypot(dx, dy) < 1.5) return
                moved = true
                sessionMoved = true
                lastSentX = totalDx
                lastSentY = totalDy
                sender.send(
                    host.host,
                    host.udpPort,
                    Protocol.InputPacket.scroll(0, now, dx.toInt(), dy.toInt(), authToken = 0L),
                )
            }

            else -> Unit
        }
    }

    fun onPointerUp(count: Int, x: Float, y: Float, eventTime: Long) {
        val host = target

        if (count == 0 && host != null) {
            val duration = eventTime - sessionDownTime
            val distance = hypot(x - sessionDownX, y - sessionDownY)
            when {
                !sessionMoved &&
                    sessionMaxPointerCount == 1 &&
                    duration <= TAP_MAX_DURATION &&
                    distance <= TAP_MAX_DISTANCE -> {
                    val now = wallMicros()
                    sender.send(
                        host.host,
                        host.udpPort,
                        Protocol.InputPacket.click(0, now, Protocol.MouseButton.Left, authToken = 0L),
                    )
                    haptics.leftClick()
                }

                !sessionMoved &&
                    sessionMaxPointerCount == 2 &&
                    twoFingerTapEligible &&
                    duration <= TAP_MAX_DURATION -> {
                    val now = wallMicros()
                    sender.send(
                        host.host,
                        host.udpPort,
                        Protocol.InputPacket.click(0, now, Protocol.MouseButton.Right, authToken = 0L),
                    )
                    haptics.rightClick()
                }
            }
        }

        pointerCount = count
        if (count == 0) {
            mode = Mode.Idle
            sessionMaxPointerCount = 0
            sessionMoved = false
            twoFingerTapEligible = false
            moved = false
        } else {
            mode = when (count) {
                1 -> Mode.OneFinger
                2 -> Mode.TwoFinger
                else -> Mode.MultiFinger
            }
            downX = x
            downY = y
            downTime = eventTime
            lastSentX = 0f
            lastSentY = 0f
            moved = false
        }
    }

    private enum class Mode {
        Idle,
        OneFinger,
        TwoFinger,
        MultiFinger,
    }

    companion object {
        private const val TAP_MAX_DURATION = 250L
        private const val TAP_MAX_DISTANCE = 14f
        private const val SCROLL_START_DISTANCE = 14f

        private fun wallMicros(): Long {
            val instant = Instant.now()
            return instant.epochSecond * 1_000_000L + instant.nano / 1_000L
        }
    }
}
