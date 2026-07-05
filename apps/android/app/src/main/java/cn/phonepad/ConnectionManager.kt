package cn.phonepad

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.phonepad.haptics.HapticsManager
import cn.phonepad.net.ControlClient
import cn.phonepad.net.DiscoveredReceiver
import cn.phonepad.net.DiscoveryClient
import cn.phonepad.net.InputSender
import cn.phonepad.net.PairingUrlParser
import cn.phonepad.touch.ReceiverTarget
import cn.phonepad.touch.TouchpadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ConnectionUiState(
    val discovering: Boolean = false,
    val discoveryStage: String = "",
    val connected: Boolean = false,
    val host: String = "",
    val receiverName: String = "",
    val discovered: List<DiscoveredReceiver> = emptyList(),
    val lastRttMs: Double? = null,
    val packetsReceived: Long = 0,
    val error: String? = null,
)

class ConnectionManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("phonepad", Context.MODE_PRIVATE)
    private val discoveryClient = DiscoveryClient(context.applicationContext)
    private val controlClient = ControlClient()
    private val inputSender = InputSender()
    private val haptics = HapticsManager(context)
    val touchpadEngine = TouchpadEngine(inputSender, haptics)

    var uiState by mutableStateOf(ConnectionUiState())
        private set

    private var monitorJob: Job? = null

    init {
        prefs.getString("last_host", null)?.let { host ->
            uiState = uiState.copy(host = host)
        }
    }

    fun discover() {
        scope.launch {
            uiState = uiState.copy(discovering = true, error = null, discoveryStage = "开始搜索...")
            try {
                val lastHost = prefs.getString("last_host", null)
                val receivers = discoveryClient.discover(lastHost = lastHost) { progress ->
                    uiState = uiState.copy(
                        discoveryStage = progress.stage,
                        discovered = progress.found,
                    )
                }
                uiState = uiState.copy(
                    discovering = false,
                    discovered = receivers,
                    discoveryStage = if (receivers.isEmpty()) "未找到接收端，请扫码或手动输入 IP" else "找到 ${receivers.size} 个接收端",
                )
            } catch (e: Exception) {
                uiState = uiState.copy(
                    discovering = false,
                    error = "搜索失败：${e.message ?: "未知错误"}",
                    discoveryStage = "搜索失败",
                )
            }
        }
    }

    fun connect(host: String) {
        scope.launch {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) {
                uiState = uiState.copy(error = "请输入电脑 IP 或扫描配对二维码。")
                return@launch
            }

            val parsedHost = PairingUrlParser.parseHost(trimmed)
                ?: trimmed.takeIf { IPV4_PATTERN.matches(it) }

            if (parsedHost.isNullOrBlank()) {
                uiState = uiState.copy(
                    connected = false,
                    error = "无法识别连接地址，请扫描 PhonePad 二维码或输入电脑 IP。",
                )
                haptics.disconnected()
                return@launch
            }

            uiState = uiState.copy(error = null, host = parsedHost)
            val status = controlClient.fetchStatus(parsedHost)
            if (status == null || !status.running) {
                uiState = uiState.copy(
                    connected = false,
                    error = "无法连接到 $parsedHost，请确认桌面端已启动接收服务。",
                )
                haptics.disconnected()
                return@launch
            }

            prefs.edit().putString("last_host", parsedHost).apply()
            touchpadEngine.setTarget(ReceiverTarget(parsedHost))
            uiState = uiState.copy(
                connected = true,
                receiverName = "PhonePad Receiver",
                lastRttMs = status.lastRttMs,
                packetsReceived = status.packetsReceived,
                error = null,
            )
            haptics.connected()
            startMonitor(parsedHost)
        }
    }

    fun connect(receiver: DiscoveredReceiver) {
        connect(receiver.ip)
    }

    fun disconnect() {
        monitorJob?.cancel()
        touchpadEngine.setTarget(null)
        uiState = uiState.copy(connected = false, receiverName = "", lastRttMs = null)
        haptics.disconnected()
    }

    private fun startMonitor(host: String) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val status = controlClient.fetchStatus(host)
                if (status == null || !status.running) {
                    disconnect()
                    uiState = uiState.copy(error = "与桌面端连接已断开。")
                    break
                }
                uiState = uiState.copy(
                    lastRttMs = status.lastRttMs,
                    packetsReceived = status.packetsReceived,
                )
                delay(1000)
            }
        }
    }

    fun release() {
        monitorJob?.cancel()
        inputSender.close()
    }

    companion object {
        private val IPV4_PATTERN = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
