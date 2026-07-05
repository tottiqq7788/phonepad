package cn.phonepad.touch

import cn.phonepad.haptics.HapticsManager
import cn.phonepad.net.InputSender
import cn.phonepad.protocol.Protocol
import kotlin.math.hypot

data class ReceiverTarget(
    val host: String,
    val udpPort: Int = Protocol.UDP_INPUT_PORT,
)

class TouchpadEngine(
    private val sender: InputSender,
    private val haptics: HapticsManager,
) {
    private var target: ReceiverTarget? = null
    private var pointerCount = 0
    private var maxPointerCount = 0
    private var mode = Mode.Idle
    private var moved = false

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastSentX = 0f
    private var lastSentY = 0f

    fun setTarget(target: ReceiverTarget?) {
        this.target = target
    }

    fun onPointerDown(count: Int, x: Float, y: Float, eventTime: Long) {
        pointerCount = count
        maxPointerCount = maxOf(maxPointerCount, count)
        downTime = eventTime
        downX = x
        downY = y
        lastX = x
        lastY = y
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
        val now = System.currentTimeMillis() * 1000L

        when (mode) {
            Mode.OneFinger -> {
                if (count != 1) return
                val totalDx = x - downX
                val totalDy = y - downY
                val dx = totalDx - lastSentX
                val dy = totalDy - lastSentY
                if (dx.toInt() == 0 && dy.toInt() == 0) return
                moved = true
                lastSentX = totalDx
                lastSentY = totalDy
                sender.send(
                    host.host,
                    host.udpPort,
                    Protocol.InputPacket.move(0, now, dx.toInt(), dy.toInt(), fingers = 1),
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
                lastSentX = totalDx
                lastSentY = totalDy
                sender.send(
                    host.host,
                    host.udpPort,
                    Protocol.InputPacket.scroll(0, now, dx.toInt(), dy.toInt()),
                )
            }

            else -> Unit
        }

        lastX = x
        lastY = y
    }

    fun onPointerUp(count: Int, x: Float, y: Float, eventTime: Long) {
        val duration = eventTime - downTime
        val distance = hypot(x - downX, y - downY)
        val host = target

        if (count == 0 && host != null) {
            when (mode) {
                Mode.OneFinger -> {
                    if (!moved && maxPointerCount == 1 && duration <= TAP_MAX_DURATION && distance <= TAP_MAX_DISTANCE) {
                        val now = System.currentTimeMillis() * 1000L
                        sender.send(
                            host.host,
                            host.udpPort,
                            Protocol.InputPacket.click(0, now, Protocol.MouseButton.Left),
                        )
                        haptics.leftClick()
                    }
                }

                Mode.TwoFinger -> {
                    if (!moved && maxPointerCount == 2 && duration <= TAP_MAX_DURATION) {
                        val now = System.currentTimeMillis() * 1000L
                        sender.send(
                            host.host,
                            host.udpPort,
                            Protocol.InputPacket.click(0, now, Protocol.MouseButton.Right),
                        )
                        haptics.rightClick()
                    }
                }

                else -> Unit
            }
        }

        pointerCount = count
        if (count == 0) {
            mode = Mode.Idle
            maxPointerCount = 0
            moved = false
        } else {
            // Keep a multi-finger gesture classified until all fingers are lifted.
            mode = when (maxPointerCount) {
                1 -> Mode.OneFinger
                2 -> Mode.TwoFinger
                else -> Mode.MultiFinger
            }
            if (maxPointerCount == 1) {
                downX = x
                downY = y
                downTime = eventTime
                lastSentX = 0f
                lastSentY = 0f
            }
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
    }
}
