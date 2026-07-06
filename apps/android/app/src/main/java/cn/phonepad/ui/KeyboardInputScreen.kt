package cn.phonepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.phonepad.net.KeyboardKey
import cn.phonepad.net.KeyboardKeyEvent
import kotlinx.coroutines.delay

private val KbBg = Color(0xFF0A0C10)
private val KbKey = Color(0xFF181C24)
private val KbKeyActive = Color(0xFF2A3A50)
private val KbBorder = Color(0xFF2A3040)
private val KbText = Color(0xFFE8ECF2)
private val KbMuted = Color(0xFF8B95A8)
private val KbAccent = Color(0xFF5B9FD4)

private class ModifierHoldState(
    private val onKeyboardKey: (String, String?) -> Unit,
) {
    private val counts = mutableMapOf<String, Int>()
    var onShiftChanged: ((Boolean) -> Unit)? = null

    fun isShiftHeld(): Boolean = (counts[KeyboardKey.SHIFT] ?: 0) > 0

    fun onDown(action: String) {
        val next = (counts[action] ?: 0) + 1
        counts[action] = next
        if (next == 1) {
            onKeyboardKey(action, KeyboardKeyEvent.DOWN)
        }
        if (action == KeyboardKey.SHIFT && next == 1) {
            onShiftChanged?.invoke(true)
        }
    }

    fun onUp(action: String) {
        val current = counts[action] ?: 0
        if (current <= 0) return
        val next = current - 1
        if (next <= 0) {
            counts.remove(action)
            onKeyboardKey(action, KeyboardKeyEvent.UP)
        } else {
            counts[action] = next
        }
        if (action == KeyboardKey.SHIFT && next <= 0) {
            onShiftChanged?.invoke(false)
        }
    }

    fun releaseAll() {
        val hadShift = isShiftHeld()
        counts.keys.toList().forEach { action ->
            onKeyboardKey(action, KeyboardKeyEvent.UP)
        }
        counts.clear()
        if (hadShift) {
            onShiftChanged?.invoke(false)
        }
    }
}

private fun shiftDisplayLabel(label: String): String {
    if (label.length != 1) return label
    return when (label) {
        in "a".."z" -> label.uppercase()
        "1" -> "!"
        "2" -> "@"
        "3" -> "#"
        "4" -> "$"
        "5" -> "%"
        "6" -> "^"
        "7" -> "&"
        "8" -> "*"
        "9" -> "("
        "0" -> ")"
        "-" -> "_"
        "=" -> "+"
        "[" -> "{"
        "]" -> "}"
        "\\" -> "|"
        ";" -> ":"
        "'" -> "\""
        "," -> "<"
        "." -> ">"
        "/" -> "?"
        "`" -> "~"
        else -> label
    }
}

@Composable
fun KeyboardInputScreen(
    error: String?,
    onBack: () -> Unit,
    onKeyboardKey: (action: String, event: String?) -> Unit,
) {
    var shiftHeld by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }
    val holdState = remember(onKeyboardKey) {
        ModifierHoldState(onKeyboardKey).apply {
            onShiftChanged = { shiftHeld = it }
        }
    }

    fun releaseOnce() {
        if (!released) {
            released = true
            holdState.releaseAll()
        }
    }

    DisposableEffect(holdState) {
        onDispose { releaseOnce() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KbBg)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    releaseOnce()
                    onBack()
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回触控板",
                    tint = KbMuted,
                )
            }
            Text(
                text = "键盘模式",
                color = KbText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (error != null) {
                Text(
                    text = error,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                    color = Color(0xFFF87171),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FunctionKeyButton(label = "Esc", action = KeyboardKey.ESC, onKeyboardKey = onKeyboardKey)
            Spacer(modifier = Modifier.weight(1f))
            FunctionKeyButton(
                label = "Del",
                action = KeyboardKey.DELETE,
                onKeyboardKey = onKeyboardKey,
                repeatOnHold = true,
            )
        }

        KeyboardRow {
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { label ->
                KeyButton(
                    label = label,
                    action = label,
                    flex = 1f,
                    shiftHeld = shiftHeld,
                    onKeyboardKey = onKeyboardKey,
                )
            }
            KeyButton(label = "-", action = KeyboardKey.MINUS, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "=", action = KeyboardKey.EQUAL, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            RepeatKeyButton(
                label = "⌫",
                action = KeyboardKey.BACKSPACE,
                flex = 1.4f,
                onKeyboardKey = onKeyboardKey,
            )
        }

        KeyboardRow {
            KeyButton(label = "Tab", action = KeyboardKey.TAB, flex = 1.3f, shiftHeld = false, onKeyboardKey = onKeyboardKey)
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").forEach { label ->
                KeyButton(label = label, action = label, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            }
            KeyButton(label = "[", action = KeyboardKey.LEFT_BRACKET, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "]", action = KeyboardKey.RIGHT_BRACKET, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "\\", action = KeyboardKey.BACKSLASH, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
        }

        KeyboardRow {
            KeyButton(label = "Caps", action = KeyboardKey.CAPS_LOCK, flex = 1.5f, shiftHeld = false, onKeyboardKey = onKeyboardKey)
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").forEach { label ->
                KeyButton(label = label, action = label, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            }
            KeyButton(label = ";", action = KeyboardKey.SEMICOLON, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "'", action = KeyboardKey.QUOTE, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "Enter", action = KeyboardKey.ENTER, flex = 1.6f, shiftHeld = false, onKeyboardKey = onKeyboardKey)
        }

        KeyboardRow {
            ModifierKeyButton(
                label = "Shift",
                action = KeyboardKey.SHIFT,
                flex = 1.8f,
                holdState = holdState,
            )
            listOf("z", "x", "c", "v", "b", "n", "m").forEach { label ->
                KeyButton(label = label, action = label, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            }
            KeyButton(label = ",", action = KeyboardKey.COMMA, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            KeyButton(label = ".", action = KeyboardKey.PERIOD, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "/", action = KeyboardKey.SLASH, flex = 1f, shiftHeld = shiftHeld, onKeyboardKey = onKeyboardKey)
            ModifierKeyButton(
                label = "Shift",
                action = KeyboardKey.SHIFT,
                flex = 1.8f,
                holdState = holdState,
            )
        }

        KeyboardRow {
            ModifierKeyButton(
                label = "Ctrl",
                action = KeyboardKey.CTRL,
                flex = 1.3f,
                holdState = holdState,
            )
            ModifierKeyButton(
                label = "Win",
                action = KeyboardKey.META,
                flex = 1.2f,
                holdState = holdState,
            )
            ModifierKeyButton(
                label = "Alt",
                action = KeyboardKey.ALT,
                flex = 1.2f,
                holdState = holdState,
            )
            KeyButton(label = "Space", action = KeyboardKey.SPACE, flex = 5f, shiftHeld = false, onKeyboardKey = onKeyboardKey)
            ModifierKeyButton(
                label = "Alt",
                action = KeyboardKey.ALT,
                flex = 1.2f,
                holdState = holdState,
            )
            ModifierKeyButton(
                label = "Ctrl",
                action = KeyboardKey.CTRL,
                flex = 1.3f,
                holdState = holdState,
            )
        }
    }
}

@Composable
private fun FunctionKeyButton(
    label: String,
    action: String,
    onKeyboardKey: (String, String?) -> Unit,
    repeatOnHold: Boolean = false,
) {
    var repeating by remember { mutableStateOf(false) }

    LaunchedEffect(repeating, repeatOnHold) {
        if (!repeating || !repeatOnHold) return@LaunchedEffect
        delay(350)
        while (repeating) {
            onKeyboardKey(action, KeyboardKeyEvent.CLICK)
            delay(45)
        }
    }

    Box(
        modifier = Modifier
            .width(72.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(KbKey)
            .border(1.dp, KbBorder, RoundedCornerShape(6.dp))
            .then(
                if (repeatOnHold) {
                    Modifier.pointerInput(action) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onKeyboardKey(action, KeyboardKeyEvent.CLICK)
                            repeating = true
                            waitForUpOrCancellation()
                            repeating = false
                        }
                    }
                } else {
                    Modifier.clickable { onKeyboardKey(action, KeyboardKeyEvent.CLICK) }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = KbText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun KeyboardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
}

@Composable
private fun RowScope.RepeatKeyButton(
    label: String,
    action: String,
    flex: Float,
    onKeyboardKey: (String, String?) -> Unit,
) {
    var repeating by remember { mutableStateOf(false) }

    LaunchedEffect(repeating) {
        if (!repeating) return@LaunchedEffect
        delay(350)
        while (repeating) {
            onKeyboardKey(action, KeyboardKeyEvent.CLICK)
            delay(45)
        }
    }

    Box(
        modifier = Modifier
            .weight(flex)
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(KbKey)
            .border(1.dp, KbBorder, RoundedCornerShape(6.dp))
            .pointerInput(action) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onKeyboardKey(action, KeyboardKeyEvent.CLICK)
                    repeating = true
                    waitForUpOrCancellation()
                    repeating = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = KbText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowScope.KeyButton(
    label: String,
    action: String,
    flex: Float,
    shiftHeld: Boolean,
    onKeyboardKey: (String, String?) -> Unit,
) {
    val display = if (shiftHeld) shiftDisplayLabel(label) else label
    Box(
        modifier = Modifier
            .weight(flex)
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(KbKey)
            .border(1.dp, KbBorder, RoundedCornerShape(6.dp))
            .clickable { onKeyboardKey(action, KeyboardKeyEvent.CLICK) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = display,
            color = KbText,
            fontSize = if (display.length <= 2) 12.sp else 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowScope.ModifierKeyButton(
    label: String,
    action: String,
    flex: Float,
    holdState: ModifierHoldState,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg = if (pressed) KbKeyActive else KbKey
    val border = if (pressed) KbAccent else KbBorder
    Box(
        modifier = Modifier
            .weight(flex)
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .pointerInput(action) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    holdState.onDown(action)
                    waitForUpOrCancellation()
                    pressed = false
                    holdState.onUp(action)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (pressed) KbAccent else KbMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
