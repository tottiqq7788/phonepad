package cn.phonepad.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cn.phonepad.ConnectionManager
import cn.phonepad.ConnectionUiState
import cn.phonepad.net.DiscoveredReceiver
import cn.phonepad.net.PairingUrlParser
import cn.phonepad.touch.TouchpadEngine
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

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

@Composable
fun PhonePadApp(connectionManager: ConnectionManager) {
    val state = connectionManager.uiState
    val engine = connectionManager.touchpadEngine
    if (state.connected) {
        TouchpadScreen(
            state = state,
            engine = engine,
            onDisconnect = connectionManager::disconnect,
        )
    } else {
        ConnectScreen(
            state = state,
            onDiscover = connectionManager::discover,
            onConnect = connectionManager::connect,
        )
    }
}

@Composable
private fun ConnectScreen(
    state: ConnectionUiState,
    onDiscover: () -> Unit,
    onConnect: (String) -> Unit,
) {
    val context = LocalContext.current
    var manualHost by remember(state.host) { mutableStateOf(state.host) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        scanError = null
        val raw = result.contents
        if (raw.isNullOrBlank()) return@rememberLauncherForActivityResult
        val host = PairingUrlParser.parseHost(raw)
        if (host == null) {
            scanError = "无法识别二维码，请扫描 PhonePad 桌面端配对二维码。"
            return@rememberLauncherForActivityResult
        }
        manualHost = host
        onConnect(host)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(
                ScanOptions()
                    .setPrompt("扫描桌面端配对二维码")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true),
            )
        } else {
            scanError = "需要相机权限才能扫码连接，请在系统设置中开启。"
        }
    }

    fun startScan() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> {
                scanLauncher.launch(
                    ScanOptions()
                        .setPrompt("扫描桌面端配对二维码")
                        .setBeepEnabled(false)
                        .setOrientationLocked(true),
                )
            }
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "PhonePad",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
            Text(
                text = "将手机变成低延迟触控板",
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            StatusBlock(
                label = "连接状态",
                value = state.discoveryStage.ifBlank { "等待搜索或手动连接" },
                accent = if (state.discovering) Accent else TextSecondary,
            )

            if (state.host.isNotBlank()) {
                StatusBlock(label = "最近连接", value = state.host, accent = TextPrimary)
            }

            state.error?.let {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ErrorColor.copy(alpha = 0.12f))
                        .border(1.dp, ErrorColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Text(text = it, color = ErrorColor, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }

            scanError?.let {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ErrorColor.copy(alpha = 0.12f))
                        .border(1.dp, ErrorColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Text(text = it, color = ErrorColor, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "请确保手机与电脑在同一 Wi-Fi 网络",
                color = TextMuted,
                fontSize = 12.sp,
            )
        }

        Column(
            modifier = Modifier
                .weight(0.62f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(BgPanel)
                .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDiscover,
                    enabled = !state.discovering,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgBase),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (state.discovering) "搜索中..." else "搜索接收端")
                }
                OutlinedButton(
                    onClick = ::startScan,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                ) {
                    Text("扫码连接", color = TextPrimary)
                }
            }

            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("电脑 IP 或 phonepad:// 地址", color = TextMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = BorderColor,
                    cursorColor = Accent,
                ),
                shape = RoundedCornerShape(10.dp),
            )

            Button(
                onClick = { onConnect(manualHost.trim()) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgBase),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("连接")
            }

            if (state.discovered.isNotEmpty()) {
                Text(
                    text = "已发现 ${state.discovered.size} 个设备",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.discovered) { receiver ->
                        ReceiverCard(receiver = receiver, onConnect = onConnect)
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusBlock(label: String, value: String, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgPanel)
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, color = TextMuted, fontSize = 11.sp, letterSpacing = 0.5.sp)
        Text(text = value, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReceiverCard(receiver: DiscoveredReceiver, onConnect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgElevated)
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
            .clickable { onConnect(receiver.ip) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = receiver.name,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = "${receiver.ip}:${receiver.tcpPort}",
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        Text(text = "连接", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TouchpadScreen(
    state: ConnectionUiState,
    engine: TouchpadEngine,
    onDisconnect: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase),
    ) {
        LeftRail(
            state = state,
            onLeftClick = engine::clickLeft,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(TouchpadSurface)
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

                            if (downs > 0 && prevCount == 0) {
                                val center = pressedCenter(pressed)
                                engine.onPointerDown(count, center.first, center.second, time)
                            }
                            if (ups > 0) {
                                val upChange = event.changes.first { it.changedToUp() }
                                engine.onPointerUp(count, upChange.position.x, upChange.position.y, time)
                            } else if (count > 0 && downs == 0) {
                                val center = pressedCenter(pressed)
                                engine.onPointerMove(count, center.first, center.second)
                            }

                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            Text(
                text = "单指移动/单击 · 双指滚动/右键",
                color = TextMuted.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
            if (showHelp) {
                HelpOverlay(onDismiss = { showHelp = false })
            }
        }

        RightRail(
            onDisconnect = onDisconnect,
            onRightClick = engine::clickRight,
            onMiddleClick = engine::clickMiddle,
            onToggleHelp = { showHelp = !showHelp },
        )
    }
}

@Composable
private fun LeftRail(state: ConnectionUiState, onLeftClick: () -> Unit) {
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
        Text(text = "ms", color = TextMuted, fontSize = 9.sp)

        Spacer(modifier = Modifier.height(8.dp))

        RailButton(label = "左", onClick = onLeftClick)

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RightRail(
    onDisconnect: () -> Unit,
    onRightClick: () -> Unit,
    onMiddleClick: () -> Unit,
    onToggleHelp: () -> Unit,
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

        RailButton(label = "断", onClick = onDisconnect, accent = ErrorColor)

        RailButton(label = "右", onClick = onRightClick)

        RailButton(label = "中", onClick = onMiddleClick)

        Spacer(modifier = Modifier.weight(1f))

        RailButton(label = "?", onClick = onToggleHelp, accent = TextSecondary)

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun RailButton(
    label: String,
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
        Text(
            text = label,
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
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
            HelpLine("侧栏「左/右/中」", "直接发送对应按键")
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
