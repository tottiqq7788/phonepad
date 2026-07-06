package cn.phonepad.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cn.phonepad.ConnectionManager
import cn.phonepad.ConnectionUiState
import cn.phonepad.model.DeviceOnlineState
import cn.phonepad.model.PairedDevice
import cn.phonepad.net.TextInputKeyAction
import cn.phonepad.touch.TouchpadEngine
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgBase = Color(0xFF0A0C10)
private val BgPanel = Color(0xFF12151C)
private val BgElevated = Color(0xFF181C24)
private val BorderColor = Color(0xFF2A3040)
private val TextPrimary = Color(0xFFE8ECF2)
private val TextSecondary = Color(0xFF8B95A8)
private val TextMuted = Color(0xFF5C6578)
private val Accent = Color(0xFF5B9FD4)
private val Success = Color(0xFF4ADE80)
private val ErrorColor = Color(0xFFF87171)
private val TouchpadSurface = Color(0xFF0D0F14)
private val RailWidth = 72.dp

private fun buildScanOptions(): ScanOptions =
    ScanOptions()
        .setCaptureActivity(QrScanActivity::class.java)
        .setPrompt("扫描桌面端配对二维码")
        .setBeepEnabled(false)
        .setOrientationLocked(true)

@Composable
fun PhonePadApp(connectionManager: ConnectionManager) {
    val state = connectionManager.uiState
    val engine = connectionManager.touchpadEngine
    if (state.connected) {
        TouchpadScreen(
            state = state,
            engine = engine,
            onDisconnect = connectionManager::disconnect,
            onOpenDevicePicker = connectionManager::openDevicePicker,
            onCloseDevicePicker = connectionManager::closeDevicePicker,
            onSelectDevice = connectionManager::connectToDevice,
            onSendText = connectionManager::sendTextToActiveDevice,
            onKeyAction = connectionManager::sendKeyActionToActiveDevice,
            onKeyboardKey = connectionManager::sendKeyboardKeyToActiveDevice,
            onReleaseKeyboardModifiers = connectionManager::releaseHeldKeyboardModifiers,
            onClearTextInputError = connectionManager::clearTextInputError,
        )
    } else {
        DeviceHomeScreen(
            state = state,
            onScan = connectionManager::pairFromScan,
            onConnectDevice = connectionManager::connectToDevice,
        )
    }
}

@Composable
private fun DeviceHomeScreen(
    state: ConnectionUiState,
    onScan: (String) -> Unit,
    onConnectDevice: (String) -> Unit,
) {
    val context = LocalContext.current
    var scanError by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        scanError = null
        val raw = result.contents
        if (raw.isNullOrBlank()) return@rememberLauncherForActivityResult
        onScan(raw)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(buildScanOptions())
        } else {
            scanError = "需要相机权限才能扫码连接，请在系统设置中开启。"
        }
    }

    fun startScan() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> {
                scanLauncher.launch(buildScanOptions())
            }
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "PhonePad",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when {
                        state.connecting -> "正在连接..."
                        state.checkingOnline -> "正在检测设备状态..."
                        else -> "已配对设备"
                    },
                    color = if (state.connecting || state.checkingOnline) TextMuted else TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = ::startScan) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = "扫码连接",
                    tint = Accent,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        state.error?.let { ErrorBanner(it) }
        scanError?.let { ErrorBanner(it) }

        if (state.pairedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgPanel)
                    .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "还没有配对设备", color = TextSecondary, fontSize = 16.sp)
                    Text(text = "点击右上角相机图标扫码连接", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.pairedDevices, key = { it.id }) { device ->
                    PairedDeviceCard(
                        device = device,
                        connecting = state.connecting,
                        onClick = { onConnectDevice(device.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairedDeviceCard(device: PairedDevice, connecting: Boolean, onClick: () -> Unit) {
    val online = device.onlineState == DeviceOnlineState.Online
    val timeLabel = if (device.lastConnectedAt > 0) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(device.lastConnectedAt))
    } else {
        "未连接"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgPanel)
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = !connecting, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (online) Success else TextMuted),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${device.host} · 最近 $timeLabel",
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = when {
                connecting -> "连接中"
                online -> "进入"
                else -> "连接"
            },
            color = if (online || connecting) Accent else TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TouchpadScreen(
    state: ConnectionUiState,
    engine: TouchpadEngine,
    onDisconnect: () -> Unit,
    onOpenDevicePicker: () -> Unit,
    onCloseDevicePicker: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onSendText: (String, () -> Unit) -> Unit,
    onKeyAction: (String, Int) -> Unit,
    onKeyboardKey: (String, String?) -> Unit,
    onReleaseKeyboardModifiers: () -> Unit,
    onClearTextInputError: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }
    var showKeyboardInput by remember { mutableStateOf(false) }

    fun closeTextInput() {
        if (state.textSending) return
        onClearTextInputError()
        showTextInput = false
    }

    fun closeKeyboardInput() {
        onReleaseKeyboardModifiers()
        onClearTextInputError()
        showKeyboardInput = false
    }

    BackHandler(enabled = showTextInput && !state.textSending) {
        closeTextInput()
    }

    BackHandler(enabled = showKeyboardInput) {
        closeKeyboardInput()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase),
    ) {
        LeftRail(
            state = state,
            onToggleHelp = {
                if (showTextInput || showKeyboardInput) return@LeftRail
                showHelp = !showHelp
                if (showHelp) {
                    onCloseDevicePicker()
                }
            },
            onOpenDevicePicker = {
                if (showTextInput || showKeyboardInput) return@LeftRail
                showHelp = false
                onOpenDevicePicker()
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(if (showTextInput || showKeyboardInput) BgBase else TouchpadSurface),
        ) {
            if (showTextInput) {
                TextInputScreen(
                    sending = state.textSending,
                    error = state.textInputError,
                    onBack = ::closeTextInput,
                    onSend = onSendText,
                    onKeyAction = onKeyAction,
                )
            } else if (showKeyboardInput) {
                KeyboardInputScreen(
                    error = state.textInputError,
                    onBack = ::closeKeyboardInput,
                    onKeyboardKey = onKeyboardKey,
                    onReleaseModifiers = onReleaseKeyboardModifiers,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val pressed = event.changes.filter { it.pressed }
                                    val count = pressed.size
                                    val time = event.changes.firstOrNull()?.uptimeMillis ?: System.currentTimeMillis()

                                    val downs = event.changes.count { it.changedToDown() }
                                    val ups = event.changes.count { it.changedToUp() }
                                    val prevCount = count + ups - downs

                                    if (downs > 0 && count > 0 && (prevCount == 0 || count != prevCount)) {
                                        val center = pressedCenter(pressed)
                                        engine.onPointerDown(count, center.first, center.second, time)
                                    }
                                    if (ups > 0) {
                                        val (upX, upY) = if (count > 0) {
                                            val center = pressedCenter(pressed)
                                            center.first to center.second
                                        } else {
                                            val upChange = event.changes.first { it.changedToUp() }
                                            upChange.position.x to upChange.position.y
                                        }
                                        engine.onPointerUp(count, upX, upY, time)
                                    } else if (count > 0 && downs == 0) {
                                        val center = pressedCenter(pressed)
                                        engine.onPointerMove(count, center.first, center.second)
                                    }

                                    event.changes.forEach { it.consume() }
                                }
                            }
                        },
                )

                Text(
                    text = state.activeDeviceName,
                    color = TextMuted.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (showHelp) {
                    HelpOverlay(onDismiss = { showHelp = false })
                }

                if (state.showDevicePicker) {
                    DevicePickerOverlay(
                        devices = state.pairedDevices,
                        activeDeviceId = state.activeDeviceId,
                        onDismiss = onCloseDevicePicker,
                        onSelectDevice = onSelectDevice,
                    )
                }
            }
        }

        RightRail(
            onDisconnect = onDisconnect,
            onOpenTextInput = {
                showHelp = false
                onCloseDevicePicker()
                onClearTextInputError()
                showKeyboardInput = false
                showTextInput = true
            },
            onOpenKeyboardInput = {
                showHelp = false
                onCloseDevicePicker()
                onClearTextInputError()
                showTextInput = false
                showKeyboardInput = true
            },
        )
    }
}

@Composable
private fun TextInputScreen(
    sending: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSend: (String, () -> Unit) -> Unit,
    onKeyAction: (String, Int) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun dismiss() {
        if (sending) return
        keyboardController?.hide()
        onBack()
    }

    fun submitDraft() {
        if (draft.trim().isEmpty() || sending) return
        onSend(draft) {
            draft = ""
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = ::dismiss, enabled = !sending) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回触控板",
                    tint = TextSecondary,
                )
            }
            Text(
                text = "输入模式",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { onKeyAction(TextInputKeyAction.BACKSPACE, 1) },
                enabled = !sending,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "退格",
                    tint = if (!sending) Accent else TextMuted,
                )
            }
            IconButton(
                onClick = { onKeyAction(TextInputKeyAction.DELETE, 1) },
                enabled = !sending,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = if (!sending) Accent else TextMuted,
                )
            }
            Button(
                onClick = ::submitDraft,
                enabled = draft.trim().isNotEmpty() && !sending,
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.White,
                    disabledContainerColor = BgElevated,
                    disabledContentColor = TextMuted,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = if (sending) "发送中" else "发送", fontSize = 13.sp)
            }
        }

        Text(
            text = "请先把电脑光标放到目标输入框，可使用手机输入法的语音输入",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )

        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(12.dp))
                .background(BgPanel)
                .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
            cursorBrush = SolidColor(Accent),
            decorationBox = { innerTextField ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            text = "输入要发送到电脑的内容…",
                            color = TextMuted,
                            fontSize = 16.sp,
                        )
                    }
                    innerTextField()
                }
            },
        )

        CursorSwipeBar(
            enabled = !sending,
            onMoveLeft = { repeat -> onKeyAction(TextInputKeyAction.CURSOR_LEFT, repeat) },
            onMoveRight = { repeat -> onKeyAction(TextInputKeyAction.CURSOR_RIGHT, repeat) },
        )

        error?.let { ErrorBanner(it) }
    }
}

@Composable
private fun CursorSwipeBar(
    enabled: Boolean,
    onMoveLeft: (Int) -> Unit,
    onMoveRight: (Int) -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { 28.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgElevated)
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            .then(
                if (enabled) {
                    Modifier.pointerInput(thresholdPx) {
                        var accumulated = 0f
                        var lastDragAt = 0L
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                accumulated = 0f
                                lastDragAt = 0L
                            },
                            onDragCancel = {
                                accumulated = 0f
                                lastDragAt = 0L
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                val now = System.currentTimeMillis()
                                val deltaMs = if (lastDragAt == 0L) 16L else (now - lastDragAt).coerceAtLeast(1L)
                                lastDragAt = now
                                val speed = kotlin.math.abs(dragAmount / deltaMs.toFloat())
                                val multiplier = when {
                                    speed >= 2.5f -> 8
                                    speed >= 1.5f -> 4
                                    speed >= 0.8f -> 2
                                    else -> 1
                                }
                                accumulated += dragAmount
                                while (accumulated >= thresholdPx) {
                                    onMoveRight(multiplier)
                                    accumulated -= thresholdPx
                                }
                                while (accumulated <= -thresholdPx) {
                                    onMoveLeft(multiplier)
                                    accumulated += thresholdPx
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "← 左右滑动移动光标 →",
            color = if (enabled) TextSecondary else TextMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DevicePickerOverlay(
    devices: List<PairedDevice>,
    activeDeviceId: String,
    onDismiss: () -> Unit,
    onSelectDevice: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .clip(RoundedCornerShape(14.dp))
                .background(BgPanel)
                .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                .clickable(enabled = false) { }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "切换设备", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            devices.forEach { device ->
                val online = device.onlineState == DeviceOnlineState.Online
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (device.id == activeDeviceId) BgElevated else BgBase)
                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                        .clickable(enabled = device.id != activeDeviceId) {
                            onSelectDevice(device.id)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = device.name,
                        color = if (online) TextPrimary else TextMuted,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = when {
                            device.id == activeDeviceId -> "当前"
                            online -> "切换"
                            else -> "连接"
                        },
                        color = if (device.id == activeDeviceId) TextMuted else Accent,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LeftRail(
    state: ConnectionUiState,
    onToggleHelp: () -> Unit,
    onOpenDevicePicker: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(RailWidth)
            .fillMaxHeight()
            .background(BgPanel)
            .border(width = 1.dp, color = BorderColor),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Success),
        )

        Text(
            text = state.lastRttMs?.let { "${"%.0f".format(it)}" } ?: "—",
            color = Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )

        Spacer(modifier = Modifier.weight(1f))

        RailIconButton(
            icon = Icons.Filled.SwapHoriz,
            contentDescription = "切换设备",
            onClick = onOpenDevicePicker,
            accent = TextSecondary,
        )

        RailButton(label = "?", onClick = onToggleHelp, accent = TextSecondary)

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun RightRail(
    onDisconnect: () -> Unit,
    onOpenTextInput: () -> Unit,
    onOpenKeyboardInput: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(RailWidth)
            .fillMaxHeight()
            .background(BgPanel)
            .border(width = 1.dp, color = BorderColor),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        RailIconButton(
            icon = Icons.Filled.LinkOff,
            contentDescription = "断开连接",
            onClick = onDisconnect,
            accent = ErrorColor,
        )

        Spacer(modifier = Modifier.weight(1f))

        RailIconButton(
            icon = Icons.Filled.Keyboard,
            contentDescription = "键盘模式",
            onClick = onOpenKeyboardInput,
            accent = Accent,
        )

        Spacer(modifier = Modifier.height(8.dp))

        RailIconButton(
            icon = Icons.Filled.Edit,
            contentDescription = "编辑输入",
            onClick = onOpenTextInput,
            accent = Accent,
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun RailIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    accent: Color = Accent,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgElevated)
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RailButton(
    label: String,
    onClick: () -> Unit,
    accent: Color = Accent,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgElevated)
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) accent else TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ErrorColor.copy(alpha = 0.12f))
            .border(1.dp, ErrorColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(text = message, color = ErrorColor, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun HelpOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onDismiss)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(BgPanel)
                .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                .padding(20.dp)
                .clickable(enabled = false) { },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "手势说明", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            HelpLine("单指滑动", "移动鼠标")
            HelpLine("单指点击", "左键单击")
            HelpLine("双指滑动", "滚动页面")
            HelpLine("双指点击", "右键单击")
            Text(text = "点击任意处关闭", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HelpLine(gesture: String, action: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = gesture, color = TextSecondary, fontSize = 13.sp)
        Text(
            text = action,
            color = TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun pressedCenter(
    pressed: List<androidx.compose.ui.input.pointer.PointerInputChange>,
): Pair<Float, Float> {
    if (pressed.isEmpty()) return 0f to 0f
    val x = pressed.map { it.position.x }.average().toFloat()
    val y = pressed.map { it.position.y }.average().toFloat()
    return x to y
}
