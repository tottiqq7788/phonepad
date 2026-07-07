package cn.phonepad.touch

import cn.phonepad.protocol.Protocol
import kotlin.math.abs
import kotlin.math.hypot

data class GestureEvent(
    val kind: Protocol.GestureKind,
    val phase: Protocol.GesturePhase,
    val fingers: Int,
    val amount: Int = 0,
)

class GestureRecognizer(
    private val swipeMinDistance: Float = SWIPE_MIN_DISTANCE,
    private val dominantAxisRatio: Float = DOMINANT_AXIS_RATIO,
) {
    private var fingerCount = 0
    private var startX = 0f
    private var startY = 0f
    private var tracking = false

    fun begin(count: Int, x: Float, y: Float) {
        fingerCount = count
        startX = x
        startY = y
        tracking = count >= 3
    }

    fun update(x: Float, y: Float): GestureEvent? {
        if (!tracking) return null
        val totalDx = x - startX
        val totalDy = y - startY
        if (hypot(totalDx, totalDy) < swipeMinDistance) {
            return null
        }
        return classify(totalDx, totalDy)?.let { kind ->
            GestureEvent(
                kind = kind,
                phase = Protocol.GesturePhase.Update,
                fingers = fingerCount,
            )
        }
    }

    fun end(x: Float, y: Float): GestureEvent? {
        if (!tracking) return null
        tracking = false
        val totalDx = x - startX
        val totalDy = y - startY
        val distance = hypot(totalDx, totalDy)
        if (distance < swipeMinDistance) {
            return null
        }
        val kind = classify(totalDx, totalDy) ?: return null
        return GestureEvent(
            kind = kind,
            phase = Protocol.GesturePhase.End,
            fingers = fingerCount,
            amount = distance.toInt(),
        )
    }

    fun cancel() {
        tracking = false
        fingerCount = 0
    }

    fun cancelIfBelowThreshold(x: Float, y: Float): Boolean {
        if (!tracking) return false
        val distance = hypot(x - startX, y - startY)
        if (distance < swipeMinDistance) {
            cancel()
            return true
        }
        return false
    }

    internal fun classify(totalDx: Float, totalDy: Float): Protocol.GestureKind? {
        val absX = abs(totalDx)
        val absY = abs(totalDy)
        return when {
            absX >= absY * dominantAxisRatio -> {
                if (totalDx < 0) Protocol.GestureKind.SwipeLeft else Protocol.GestureKind.SwipeRight
            }
            absY >= absX * dominantAxisRatio -> {
                if (totalDy < 0) Protocol.GestureKind.SwipeUp else Protocol.GestureKind.SwipeDown
            }
            else -> null
        }
    }

    companion object {
        const val SWIPE_MIN_DISTANCE = 60f
        const val DOMINANT_AXIS_RATIO = 1.6f
    }
}
