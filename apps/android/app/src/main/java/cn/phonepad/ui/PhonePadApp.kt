package cn.phonepad.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.phonepad.ConnectionManager
import cn.phonepad.ConnectionUiState
import cn.phonepad.net.DiscoveredReceiver
import cn.phonepad.net.PairingUrlParser
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun PhonePadApp(connectionManager: ConnectionManager) {
    val state = connectionManager.uiState
    if (state.connected) {
        TouchpadScreen(
            state = state,
            onDisconnect = connectionManager::disconnect,
            onPointerDown = { count, x, y, time ->
                connectionManager.touchpadEngine.onPointerDown(count, x, y, time)
            },
            onPointerMove = { count, x, y ->
                connectionManager.touchpadEngine.onPointerMove(count, x, y)
            },
            onPointerUp = { count, x, y, time ->
                connectionManager.touchpadEngine.onPointerUp(count, x, y, time)
            },
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

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            PairingUrlParser.parseHost(raw)?.let { host ->
                manualHost = host
                onConnect(host)
            }
        }
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
            .background(Color(0xFF0B1020))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "PhonePad",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "将手机变成低延迟触控板。优先通过局域网自动发现桌面接收端。",
                color = Color(0xFFAAB8CE),
            )
            if (state.discoveryStage.isNotBlank()) {
                Text(
                    text = state.discoveryStage,
                    color = Color(0xFF9CFFD1),
                )
            }
            state.error?.let {
                Text(text = it, color = Color(0xFFFF9E9E))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "提示：搜索失败时可扫码或手动输入电脑 IP。",
                color = Color(0xFF8798AF),
            )
        }

        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDiscover, enabled = !state.discovering) {
                    Text(if (state.discovering) "搜索中..." else "搜索接收端")
                }
                Button(onClick = ::startScan) {
                    Text("扫码连接")
                }
            }

            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手动输入电脑 IP 或 phonepad:// 地址") },
                singleLine = true,
            )
            Button(onClick = { onConnect(manualHost.trim()) }, modifier = Modifier.fillMaxWidth()) {
                Text("连接")
            }

            if (state.discovered.isNotEmpty()) {
                Text(text = "已发现设备", color = Color.White, fontWeight = FontWeight.SemiBold)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
private fun ReceiverCard(receiver: DiscoveredReceiver, onConnect: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = receiver.name, fontWeight = FontWeight.Bold)
                Text(text = "${receiver.ip}:${receiver.tcpPort}", color = Color.Gray)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onConnect(receiver.ip) }) {
                Text("连接")
            }
        }
    }
}

@Composable
private fun TouchpadScreen(
    state: ConnectionUiState,
    onDisconnect: () -> Unit,
    onPointerDown: (Int, Float, Float, Long) -> Unit,
    onPointerMove: (Int, Float, Float) -> Unit,
    onPointerUp: (Int, Float, Float, Long) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        val count = pressed.size
                        val time = event.changes.firstOrNull()?.uptimeMillis ?: System.currentTimeMillis()

                        if (event.changes.any { it.changedToDown() }) {
                            val center = pressedCenter(pressed)
                            onPointerDown(count, center.first, center.second, time)
                        } else if (event.changes.any { it.changedToUp() }) {
                            val center = event.changes.first().position
                            onPointerUp(count, center.x, center.y, time)
                        } else if (count > 0) {
                            val center = pressedCenter(pressed)
                            onPointerMove(count, center.first, center.second)
                        }

                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0x66000000))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.receiverName.ifBlank { "已连接" },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = buildString {
                        append(state.host)
                        state.lastRttMs?.let { append(" · ${"%.1f".format(it)} ms") }
                    },
                    color = Color(0xFF9CFFD1),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "单指移动/单击 · 双指滚动/右键",
                color = Color(0xFF8798AF),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    },
            )
            Button(onClick = onDisconnect) {
                Text("断开")
            }
        }
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
