package cn.phonepad.touch

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import cn.phonepad.logging.PhonePadLogger

/**
 * Native touch surface that captures multi-pointer events before Compose gesture routing.
 */
class TouchpadSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var engine: TouchpadEngine? = null
    var onSystemGestureConflict: (() -> Unit)? = null

    private var lastPointerCount = 0

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSystemGestureExclusion()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSystemGestureExclusion()
    }

    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (width <= 0 || height <= 0) return
        val edge = (resources.displayMetrics.density * 48).toInt().coerceAtMost(width / 4)
        val rects = listOf(
            Rect(0, 0, edge, height),
            Rect(width - edge, 0, width, height),
            Rect(edge, 0, width - edge, edge),
            Rect(edge, height - edge, width - edge, height),
        )
        systemGestureExclusionRects = rects
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = engine ?: return true
        val action = event.actionMasked
        val count = event.pointerCount
        val (centerX, centerY) = eventCenter(event)
        val eventTime = event.eventTime

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (action == MotionEvent.ACTION_DOWN || lastPointerCount != count) {
                    PhonePadLogger.d(
                        "touch",
                        "pointer_down",
                        "count=$count action=${actionName(action)}",
                    )
                    engine.onPointerDown(count, centerX, centerY, eventTime)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (count > 0) {
                    engine.onPointerMove(count, centerX, centerY)
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = when (action) {
                    MotionEvent.ACTION_UP -> 0
                    else -> count - 1
                }
                PhonePadLogger.d(
                    "touch",
                    "pointer_up",
                    "remaining=$remaining action=${actionName(action)}",
                )
                engine.onPointerUp(remaining, centerX, centerY, eventTime)
            }

            MotionEvent.ACTION_CANCEL -> {
                PhonePadLogger.w(
                    "touch",
                    "pointer_cancel",
                    "count=$count last=$lastPointerCount",
                )
                onSystemGestureConflict?.invoke()
                engine.onPointerCancel()
            }
        }

        lastPointerCount = when (action) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> 0
            MotionEvent.ACTION_POINTER_UP -> count - 1
            else -> count
        }
        return true
    }

    private fun eventCenter(event: MotionEvent): Pair<Float, Float> {
        val n = event.pointerCount
        if (n <= 0) return 0f to 0f
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until n) {
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        return sumX / n to sumY / n
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "down"
        MotionEvent.ACTION_POINTER_DOWN -> "pointer_down"
        MotionEvent.ACTION_MOVE -> "move"
        MotionEvent.ACTION_POINTER_UP -> "pointer_up"
        MotionEvent.ACTION_UP -> "up"
        MotionEvent.ACTION_CANCEL -> "cancel"
        else -> action.toString()
    }
}
