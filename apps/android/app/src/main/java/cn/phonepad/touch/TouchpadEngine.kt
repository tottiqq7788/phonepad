package cn.phonepad.touch

import cn.phonepad.haptics.HapticsManager
import cn.phonepad.logging.PhonePadLogger
import cn.phonepad.net.InputSender
import cn.phonepad.protocol.Protocol
import kotlin.math.hypot
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ReceiverTarget(
    val host: String,
    val udpPort: Int = Protocol.UDP_INPUT_PORT,
    val deviceId: String = "",
    val deviceName: String = "",
)

class TouchpadEngine(
    private val sender: InputSender,
    private val haptics: HapticsManager,
    private val frameIntervalMs: Long = FRAME_INTERVAL_MS,
    private val enableScheduledFlush: Boolean = true,
    private val gestureRecognizer: GestureRecognizer = GestureRecognizer(),
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

    private val flushExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "phonepad-motion-flush").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }
    private var flushTask: ScheduledFuture<*>? = null

    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private var moveFracX = 0f
    private var moveFracY = 0f
    private var scrollFracX = 0f
    private var scrollFracY = 0f
    private var gestureTracking = false

    fun setTarget(target: ReceiverTarget?) {
        synchronized(this) {
            this.target = target
            if (target == null) {
                resetMotionAccumulators()
                stopFlushLoop()
                gestureTracking = false
                gestureRecognizer.cancel()
            }
        }
    }

    fun onPointerDown(count: Int, x: Float, y: Float, eventTime: Long) {
        synchronized(this) {
            val host = target
            if (host != null) {
                when (mode) {
                    Mode.OneFinger -> flushMoveLocked(host)
                    Mode.TwoFinger -> flushScrollLocked(host)
                    else -> Unit
                }
            }

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
            mode = modeForCount(count)
            if (mode == Mode.OneFinger || mode == Mode.TwoFinger) {
                startFlushLoop()
            } else {
                stopFlushLoop()
            }
            if (!gestureTracking && count >= 3) {
                gestureTracking = true
                gestureRecognizer.begin(count, x, y)
            }
        }
    }

    fun onPointerMove(count: Int, x: Float, y: Float) {
        synchronized(this) {
            if (target == null) return
            pointerCount = count

            if (gestureTracking) {
                val gesture = gestureRecognizer.update(x, y)
                if (gesture != null) {
                    moved = true
                    sessionMoved = true
                }
                return
            }

            when (mode) {
                Mode.OneFinger -> {
                    if (count != 1) return
                    val totalDx = x - downX
                    val totalDy = y - downY
                    val dx = totalDx - lastSentX
                    val dy = totalDy - lastSentY
                    if (hypot(dx, dy) < MIN_DELTA) return
                    moved = true
                    sessionMoved = true
                    lastSentX = totalDx
                    lastSentY = totalDy
                    pendingMoveX += dx
                    pendingMoveY += dy
                }

                Mode.TwoFinger -> {
                    if (count != 2) return
                    val totalDx = x - downX
                    val totalDy = y - downY
                    val dx = totalDx - lastSentX
                    val dy = totalDy - lastSentY
                    if (!moved && hypot(totalDx, totalDy) < SCROLL_START_DISTANCE) return
                    if (hypot(dx, dy) < MIN_DELTA) return
                    moved = true
                    sessionMoved = true
                    lastSentX = totalDx
                    lastSentY = totalDy
                    pendingScrollX += dx
                    pendingScrollY += dy
                }

                else -> Unit
            }
        }
    }

    fun onPointerCancel() {
        synchronized(this) {
            flushMotionLocked()
            gestureTracking = false
            gestureRecognizer.cancel()
            pointerCount = 0
            mode = Mode.Idle
            sessionMaxPointerCount = 0
            sessionMoved = false
            twoFingerTapEligible = false
            moved = false
            resetMotionAccumulators()
            stopFlushLoop()
        }
    }

    fun onPointerUp(count: Int, x: Float, y: Float, eventTime: Long) {
        val host: ReceiverTarget?
        val tapAction: TapAction?
        val gestureEvent: GestureEvent?
        synchronized(this) {
            host = target
            if (count in 1..2 && gestureTracking && gestureRecognizer.cancelIfBelowThreshold(x, y)) {
                gestureTracking = false
            }
            gestureEvent = if (count == 0 && gestureTracking) {
                gestureTracking = false
                gestureRecognizer.end(x, y)
            } else {
                null
            }
            tapAction = if (count == 0 && host != null && gestureEvent == null) {
                val duration = eventTime - sessionDownTime
                val distance = hypot(x - sessionDownX, y - sessionDownY)
                when {
                    !sessionMoved &&
                        sessionMaxPointerCount == 1 &&
                        duration <= TAP_MAX_DURATION &&
                        distance <= TAP_MAX_DISTANCE -> TapAction.LeftClick

                    !sessionMoved &&
                        sessionMaxPointerCount == 2 &&
                        twoFingerTapEligible &&
                        duration <= TAP_MAX_DURATION -> TapAction.RightClick

                    else -> null
                }
            } else {
                null
            }

            flushMotionLocked()
            pointerCount = count
            if (count == 0) {
                mode = Mode.Idle
                sessionMaxPointerCount = 0
                sessionMoved = false
                twoFingerTapEligible = false
                moved = false
                resetMotionAccumulators()
                stopFlushLoop()
                gestureRecognizer.cancel()
            } else {
                mode = modeForCount(count)
                downX = x
                downY = y
                downTime = eventTime
                lastSentX = 0f
                lastSentY = 0f
                moved = false
                if (!gestureTracking && (mode == Mode.OneFinger || mode == Mode.TwoFinger)) {
                    startFlushLoop()
                } else if (!gestureTracking) {
                    stopFlushLoop()
                }
            }
        }

        if (host != null && gestureEvent != null) {
            sendGesture(host, gestureEvent, gestureEvent.fingers)
        }

        if (host != null && tapAction != null) {
            val now = wallMicros()
            when (tapAction) {
                TapAction.LeftClick -> {
                    sender.send(
                        host.host,
                        host.udpPort,
                        Protocol.InputPacket.click(0, now, Protocol.MouseButton.Left, authToken = 0L),
                    )
                    haptics.leftClick()
                }

                TapAction.RightClick -> {
                    sender.send(
                        host.host,
                        host.udpPort,
                        Protocol.InputPacket.click(0, now, Protocol.MouseButton.Right, authToken = 0L),
                    )
                    haptics.rightClick()
                }
            }
        }
    }

    fun release() {
        synchronized(this) {
            flushMotionLocked()
            resetMotionAccumulators()
            stopFlushLoop()
            gestureRecognizer.cancel()
            flushExecutor.shutdownNow()
        }
    }

    internal fun flushPendingMotionForTest() {
        synchronized(this) {
            flushMotionLocked()
        }
    }

    private fun sendGesture(host: ReceiverTarget, event: GestureEvent, fingerCount: Int) {
        if (event.phase != Protocol.GesturePhase.End) return
        PhonePadLogger.i(
            "touch",
            "gesture_send",
            "kind=${event.kind.name} fingers=$fingerCount amount=${event.amount} host=${host.host}",
        )
        sender.send(
            host.host,
            host.udpPort,
            Protocol.InputPacket.gesture(
                sequence = 0,
                timestampMicros = wallMicros(),
                gestureKind = event.kind,
                gesturePhase = event.phase,
                fingers = fingerCount,
                authToken = 0L,
                amount = event.amount,
            ),
        )
        haptics.leftClick()
    }

    private fun modeForCount(count: Int): Mode = when (count) {
        1 -> Mode.OneFinger
        2 -> Mode.TwoFinger
        3 -> Mode.ThreeFinger
        4 -> Mode.FourFinger
        else -> Mode.MultiFinger
    }

    private fun startFlushLoop() {
        if (!enableScheduledFlush || flushTask != null) return
        flushTask = flushExecutor.scheduleAtFixedRate(
            { flushPendingMotionForTest() },
            0,
            frameIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopFlushLoop() {
        flushTask?.cancel(false)
        flushTask = null
    }

    private fun resetMotionAccumulators() {
        pendingMoveX = 0f
        pendingMoveY = 0f
        pendingScrollX = 0f
        pendingScrollY = 0f
        moveFracX = 0f
        moveFracY = 0f
        scrollFracX = 0f
        scrollFracY = 0f
    }

    private fun flushMotionLocked() {
        val host = target ?: return
        when (mode) {
            Mode.OneFinger -> flushMoveLocked(host)
            Mode.TwoFinger -> flushScrollLocked(host)
            else -> Unit
        }
    }

    private fun flushMoveLocked(host: ReceiverTarget) {
        if (pendingMoveX == 0f && pendingMoveY == 0f && moveFracX == 0f && moveFracY == 0f) {
            return
        }
        val totalX = pendingMoveX + moveFracX
        val totalY = pendingMoveY + moveFracY
        val dx = totalX.toInt()
        val dy = totalY.toInt()
        moveFracX = totalX - dx
        moveFracY = totalY - dy
        pendingMoveX = 0f
        pendingMoveY = 0f
        if (dx == 0 && dy == 0) return
        sender.send(
            host.host,
            host.udpPort,
            Protocol.InputPacket.move(
                0,
                wallMicros(),
                dx,
                dy,
                authToken = 0L,
                fingers = 1,
            ),
        )
    }

    private fun flushScrollLocked(host: ReceiverTarget) {
        if (pendingScrollX == 0f && pendingScrollY == 0f && scrollFracX == 0f && scrollFracY == 0f) {
            return
        }
        val totalX = pendingScrollX + scrollFracX
        val totalY = pendingScrollY + scrollFracY
        val dx = totalX.toInt()
        val dy = totalY.toInt()
        scrollFracX = totalX - dx
        scrollFracY = totalY - dy
        pendingScrollX = 0f
        pendingScrollY = 0f
        if (dx == 0 && dy == 0) return
        sender.send(
            host.host,
            host.udpPort,
            Protocol.InputPacket.scroll(0, wallMicros(), dx, dy, authToken = 0L),
        )
    }

    private enum class Mode {
        Idle,
        OneFinger,
        TwoFinger,
        ThreeFinger,
        FourFinger,
        MultiFinger,
    }

    private enum class TapAction {
        LeftClick,
        RightClick,
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 16L
        private const val TAP_MAX_DURATION = 250L
        private const val TAP_MAX_DISTANCE = 14f
        private const val SCROLL_START_DISTANCE = 14f
        private const val MIN_DELTA = 0.05f

        private fun wallMicros(): Long {
            val instant = Instant.now()
            return instant.epochSecond * 1_000_000L + instant.nano / 1_000L
        }
    }
}
