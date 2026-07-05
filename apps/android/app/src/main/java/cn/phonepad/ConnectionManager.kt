package cn.phonepad

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.phonepad.haptics.HapticsManager
import cn.phonepad.model.DeviceOnlineState
import cn.phonepad.model.PairedDevice
import cn.phonepad.net.ControlClient
import cn.phonepad.net.InputSender
import cn.phonepad.net.PairingPayload
import cn.phonepad.net.PairingUrlParser
import cn.phonepad.storage.PairedDeviceStore
import cn.phonepad.touch.ReceiverTarget
import cn.phonepad.touch.TouchpadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ConnectionUiState(
    val pairedDevices: List<PairedDevice> = emptyList(),
    val connected: Boolean = false,
    val activeDeviceId: String = "",
    val activeDeviceName: String = "",
    val host: String = "",
    val checkingOnline: Boolean = false,
    val connecting: Boolean = false,
    val lastRttMs: Double? = null,
    val packetsReceived: Long = 0,
    val error: String? = null,
    val showDevicePicker: Boolean = false,
    val textSending: Boolean = false,
    val textInputError: String? = null,
)

class ConnectionManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val store = PairedDeviceStore(context.applicationContext)
    private val controlClient = ControlClient()
    private val inputSender = InputSender()
    private val haptics = HapticsManager(context)
    val touchpadEngine = TouchpadEngine(inputSender, haptics)

    var uiState by mutableStateOf(ConnectionUiState())
        private set

    private var monitorJob: Job? = null
    private var onlineJob: Job? = null
    private var onlineRefreshJob: Job? = null
    private var textSendJob: Job? = null

    companion object {
        private const val MAX_TEXT_CHARS = 4096
        private const val MAX_TEXT_BYTES = 6000
    }

    init {
        refreshDevices()
        startOnlinePolling()
    }

    fun refreshDevices() {
        uiState = uiState.copy(pairedDevices = store.load())
    }

    fun startOnlinePolling() {
        onlineJob?.cancel()
        onlineJob = scope.launch {
            while (isActive) {
                refreshOnlineStatesInternal()
                delay(3000)
            }
        }
    }

    fun refreshOnlineStates() {
        onlineRefreshJob?.cancel()
        onlineRefreshJob = scope.launch { refreshOnlineStatesInternal() }
    }

    private suspend fun refreshOnlineStatesInternal() {
        val devices = store.load()
        if (devices.isEmpty()) {
            uiState = uiState.copy(pairedDevices = emptyList(), checkingOnline = false)
            return
        }
        if (!uiState.connecting) {
            uiState = uiState.copy(checkingOnline = true)
        }
        val states = linkedMapOf<String, DeviceOnlineState>()
        devices.forEach { device ->
            val status = controlClient.fetchStatus(
                host = device.host,
                deviceId = device.id,
                secret = device.secret,
                port = device.tcpPort,
            )
            states[device.id] = if (status?.running == true) {
                DeviceOnlineState.Online
            } else {
                DeviceOnlineState.Offline
            }
        }
        val updated = store.updateOnlineStates(states)
        uiState = uiState.copy(pairedDevices = updated, checkingOnline = false)
    }

    fun pairFromScan(raw: String) {
        scope.launch {
            val payload = PairingUrlParser.parse(raw)
            if (payload == null) {
                uiState = uiState.copy(error = "无法识别二维码，请扫描 PhonePad 桌面端配对二维码。")
                return@launch
            }
            connectWithPayload(payload, fromScan = true)
        }
    }

    fun connectToDevice(deviceId: String) {
        scope.launch {
            val device = store.load().firstOrNull { it.id == deviceId }
            if (device == null) {
                uiState = uiState.copy(error = "设备不存在，请重新扫码。")
                return@launch
            }
            connectWithPayload(
                PairingPayload(
                    deviceId = device.id,
                    deviceName = device.name,
                    host = device.host,
                    tcpPort = device.tcpPort,
                    udpPort = device.udpPort,
                    secret = device.secret,
                ),
                fromScan = false,
            )
        }
    }

    private suspend fun connectWithPayload(payload: PairingPayload, fromScan: Boolean) {
        monitorJob?.cancel()
        uiState = uiState.copy(error = null, showDevicePicker = false, connecting = true)
        val status = controlClient.fetchStatus(
            host = payload.host,
            deviceId = payload.deviceId,
            secret = payload.secret,
            port = payload.tcpPort,
        )
        if (status == null || !status.running) {
            uiState = uiState.copy(
                connected = false,
                connecting = false,
                error = "无法连接到 ${payload.deviceName}，请确认桌面端已启动接收服务。",
            )
            haptics.disconnected()
            return
        }
        if (status.deviceId.isNotBlank() && status.deviceId != payload.deviceId) {
            uiState = uiState.copy(
                connected = false,
                connecting = false,
                error = "二维码设备身份不匹配，请重新扫码。",
            )
            haptics.disconnected()
            return
        }

        val device = PairedDevice(
            id = payload.deviceId,
            name = status.deviceName.ifBlank { payload.deviceName },
            host = payload.host,
            tcpPort = payload.tcpPort,
            udpPort = payload.udpPort,
            secret = payload.secret,
            lastConnectedAt = System.currentTimeMillis(),
            onlineState = DeviceOnlineState.Online,
        )
        val devices = store.upsert(device)
        inputSender.setSecret(payload.secret)
        touchpadEngine.setTarget(
            ReceiverTarget(
                host = payload.host,
                udpPort = payload.udpPort,
                deviceId = payload.deviceId,
                deviceName = device.name,
            ),
        )
        uiState = uiState.copy(
            pairedDevices = devices,
            connected = true,
            connecting = false,
            activeDeviceId = device.id,
            activeDeviceName = device.name,
            host = payload.host,
            lastRttMs = status.lastRttMs,
            packetsReceived = status.packetsReceived,
            error = null,
        )
        haptics.connected()
        startMonitor(device)
        if (fromScan) {
            refreshOnlineStates()
        }
    }

    fun disconnect() {
        monitorJob?.cancel()
        textSendJob?.cancel()
        touchpadEngine.setTarget(null)
        inputSender.setSecret("")
        uiState = uiState.copy(
            connected = false,
            connecting = false,
            activeDeviceId = "",
            activeDeviceName = "",
            host = "",
            lastRttMs = null,
            error = null,
            showDevicePicker = false,
            textSending = false,
            textInputError = null,
        )
        haptics.disconnected()
        refreshOnlineStates()
    }

    fun openDevicePicker() {
        uiState = uiState.copy(showDevicePicker = true)
        refreshOnlineStates()
    }

    fun closeDevicePicker() {
        uiState = uiState.copy(showDevicePicker = false)
    }

    fun clearTextInputError() {
        uiState = uiState.copy(textInputError = null)
    }

    fun sendTextToActiveDevice(text: String, onSuccess: () -> Unit = {}) {
        if (uiState.textSending) {
            return
        }
        textSendJob?.cancel()
        textSendJob = scope.launch {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                uiState = uiState.copy(textInputError = "请输入要发送的内容")
                return@launch
            }
            if (trimmed.length > MAX_TEXT_CHARS) {
                uiState = uiState.copy(textInputError = "文本超过 $MAX_TEXT_CHARS 字符限制")
                return@launch
            }
            if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
                uiState = uiState.copy(textInputError = "文本过长，请缩短后重试")
                return@launch
            }
            if (!uiState.connected || uiState.activeDeviceId.isBlank()) {
                uiState = uiState.copy(textInputError = "设备未连接")
                return@launch
            }
            val device = store.load().firstOrNull { it.id == uiState.activeDeviceId }
            if (device == null) {
                uiState = uiState.copy(textInputError = "设备不存在，请重新连接")
                return@launch
            }

            uiState = uiState.copy(textSending = true, textInputError = null)
            val response = controlClient.sendText(
                host = device.host,
                deviceId = device.id,
                secret = device.secret,
                port = device.tcpPort,
                text = trimmed,
            )
            if (!isActive || !uiState.connected) {
                uiState = uiState.copy(textSending = false)
                return@launch
            }
            if (response.ok) {
                haptics.textSent()
                uiState = uiState.copy(textSending = false, textInputError = null)
                onSuccess()
            } else {
                uiState = uiState.copy(
                    textSending = false,
                    textInputError = response.error ?: "发送失败，请确认桌面端仍在接收",
                )
            }
        }
    }

    private fun startMonitor(device: PairedDevice) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val status = controlClient.fetchStatus(
                    host = device.host,
                    deviceId = device.id,
                    secret = device.secret,
                    port = device.tcpPort,
                )
                if (status == null || !status.running) {
                    disconnect()
                    uiState = uiState.copy(error = "与 ${device.name} 的连接已断开。")
                    break
                }
                uiState = uiState.copy(
                    activeDeviceName = status.deviceName.ifBlank { device.name },
                    lastRttMs = status.lastRttMs,
                    packetsReceived = status.packetsReceived,
                )
                delay(1000)
            }
        }
    }

    fun release() {
        monitorJob?.cancel()
        onlineJob?.cancel()
        textSendJob?.cancel()
        inputSender.close()
    }
}
