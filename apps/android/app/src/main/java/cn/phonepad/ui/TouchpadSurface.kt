package cn.phonepad.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import cn.phonepad.touch.TouchpadEngine
import cn.phonepad.touch.TouchpadSurfaceView

@Composable
fun TouchpadSurface(
    engine: TouchpadEngine,
    modifier: Modifier = Modifier,
    onSystemGestureConflict: () -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TouchpadSurfaceView(context).apply {
                this.engine = engine
                this.onSystemGestureConflict = onSystemGestureConflict
            }
        },
        update = { view ->
            view.engine = engine
            view.onSystemGestureConflict = onSystemGestureConflict
        },
    )
}
