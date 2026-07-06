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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

    fun onDown(action: String) {
        val next = (counts[action] ?: 0) + 1
        counts[action] = next
        if (next == 1) {
            onKeyboardKey(action, KeyboardKeyEvent.DOWN)
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
    }

    fun releaseAll() {
        counts.keys.toList().forEach { action ->
            onKeyboardKey(action, KeyboardKeyEvent.UP)
        }
        counts.clear()
    }
}

@Composable
fun KeyboardInputScreen(
    error: String?,
    onBack: () -> Unit,
    onKeyboardKey: (action: String, event: String?) -> Unit,
    onReleaseModifiers: () -> Unit = {},
) {
    val holdState = remember(onKeyboardKey) { ModifierHoldState(onKeyboardKey) }

    DisposableEffect(holdState) {
        onDispose {
            holdState.releaseAll()
            onReleaseModifiers()
        }
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
                    holdState.releaseAll()
                    onReleaseModifiers()
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
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        error?.let {
            Text(
                text = it,
                color = Color(0xFFF87171),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        KeyboardRow {
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { label ->
                KeyButton(label = label, action = label, flex = 1f, onKeyboardKey = onKeyboardKey)
            }
            KeyButton(label = "-", action = KeyboardKey.MINUS, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "=", action = KeyboardKey.EQUAL, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "⌫", action = KeyboardKey.BACKSPACE, flex = 1.4f, onKeyboardKey = onKeyboardKey)
        }

        KeyboardRow {
            KeyButton(label = "Tab", action = KeyboardKey.TAB, flex = 1.3f, onKeyboardKey = onKeyboardKey)
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").forEach { label ->
                KeyButton(label = label, action = label, flex = 1f, onKeyboardKey = onKeyboardKey)
            }
            KeyButton(label = "[", action = KeyboardKey.LEFT_BRACKET, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "]", action = KeyboardKey.RIGHT_BRACKET, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "\\", action = KeyboardKey.BACKSLASH, flex = 1f, onKeyboardKey = onKeyboardKey)
        }

        KeyboardRow {
            KeyButton(label = "Caps", action = KeyboardKey.CAPS_LOCK, flex = 1.5f, onKeyboardKey = onKeyboardKey)
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").forEach { label ->
                KeyButton(label = label, action = label, flex = 1f, onKeyboardKey = onKeyboardKey)
            }
            KeyButton(label = ";", action = KeyboardKey.SEMICOLON, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "'", action = KeyboardKey.QUOTE, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "Enter", action = KeyboardKey.ENTER, flex = 1.6f, onKeyboardKey = onKeyboardKey)
        }

        KeyboardRow {
            ModifierKeyButton(
                label = "Shift",
                action = KeyboardKey.SHIFT,
                flex = 1.8f,
                holdState = holdState,
            )
            listOf("z", "x", "c", "v", "b", "n", "m").forEach { label ->
                KeyButton(label = label, action = label, flex = 1f, onKeyboardKey = onKeyboardKey)
            }
            KeyButton(label = ",", action = KeyboardKey.COMMA, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = ".", action = KeyboardKey.PERIOD, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "/", action = KeyboardKey.SLASH, flex = 1f, onKeyboardKey = onKeyboardKey)
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
            KeyButton(label = "Space", action = KeyboardKey.SPACE, flex = 5f, onKeyboardKey = onKeyboardKey)
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

        KeyboardRow {
            Spacer(modifier = Modifier.weight(3f))
            KeyButton(label = "↑", action = KeyboardKey.UP, flex = 1f, onKeyboardKey = onKeyboardKey)
            Spacer(modifier = Modifier.weight(3f))
            KeyButton(label = "←", action = KeyboardKey.LEFT, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "↓", action = KeyboardKey.DOWN, flex = 1f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "→", action = KeyboardKey.RIGHT, flex = 1f, onKeyboardKey = onKeyboardKey)
            Spacer(modifier = Modifier.weight(1f))
            KeyButton(label = "Home", action = KeyboardKey.HOME, flex = 1.2f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "End", action = KeyboardKey.END, flex = 1.2f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "Del", action = KeyboardKey.DELETE, flex = 1.2f, onKeyboardKey = onKeyboardKey)
            KeyButton(label = "Esc", action = KeyboardKey.ESC, flex = 1.2f, onKeyboardKey = onKeyboardKey)
        }
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
private fun RowScope.KeyButton(
    label: String,
    action: String,
    flex: Float,
    onKeyboardKey: (String, String?) -> Unit,
) {
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
            text = label,
            color = KbText,
            fontSize = if (label.length <= 2) 12.sp else 10.sp,
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
