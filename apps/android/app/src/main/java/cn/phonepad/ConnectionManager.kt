package cn.phonepad

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.phonepad.haptics.HapticsManager
import cn.phonepad.logging.PhonePadLogger
import cn.phonepad.model.DeviceOnlineState
import cn.phonepad.model.PairedDevice
import cn.phonepad.model.onlineSwitchable
import cn.phonepad.net.ControlClient
import cn.phonepad.net.DiscoveryClient
import cn.phonepad.net.FileTransferClient
import cn.phonepad.net.FileTransferProgress
import cn.phonepad.net.KeyboardKey
import cn.phonepad.net.KeyboardKeyEvent
import cn.phonepad.net.SelectedAttachment
import cn.phonepad.net.InputSender
import cn.phonepad.net.PairingPayload
import cn.phonepad.net.PairingUrlParser
import cn.phonepad.protocol.Protocol
import cn.phonepad.storage.PairedDeviceStore
import cn.phonepad.touch.ReceiverTarget
import cn.phonepad.touch.TouchpadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

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
    val transferSending: Boolean = false,
    val transferProgress: FileTransferProgress? = null,
)

class ConnectionManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val store = PairedDeviceStore(appContext)
    private val controlClient = ControlClient()
    private val fileTransferClient = FileTransferClient()
    private val inputSender = InputSender()
    private val haptics = HapticsManager(context)
    val touchpadEngine = TouchpadEngine(inputSender, haptics)

    var uiState by mutableStateOf(ConnectionUiState())
        private set

    private var monitorJob: Job? = null
    private var onlineJob: Job? = null
    private var onlineRefreshJob: Job? = null
    private var transferJob: Job? = null
    private var connectJob: Job? = null
    @Volatile
    private var connectGeneration = 0L
    @Volatile
    private var transferCancelled = false
    private val keyboardSendMutex = Mutex()
    private val heldModifiers = mutableSetOf<String>()

    companion object {
        private const val MAX_TEXT_CHARS = 4096
        private const val MAX_TEXT_BYTES = 6000
        private val MODIFIER_KEYS = setOf(
            KeyboardKey.CTRL,
            KeyboardKey.SHIFT,
            KeyboardKey.ALT,
            KeyboardKey.META,
        )
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
        startConnect { generation ->
            val payload = PairingUrlParser.parse(raw)
            if (payload == null) {
                PhonePadLogger.w("connection", "pair_scan_failed", "reason=invalid_qr")
                if (!isStaleConnect(generation)) {
                    uiState = uiState.copy(error = "无法识别二维码，请扫描 PhonePad 桌面端配对二维码。")
                }
                return@startConnect
            }
            PhonePadLogger.i(
                "connection",
                "pair_scan",
                "device_id=${PhonePadLogger.shortId(payload.deviceId)} host=${payload.host ?: "-"} from_scan=true",
            )
            connectWithPayload(payload, fromScan = true, generation = generation)
        }
    }

    fun connectToDevice(deviceId: String) {
        if (uiState.connecting) {
            return
        }
        startConnect { generation ->
            val device = store.load().firstOrNull { it.id == deviceId }
            if (device == null) {
                if (!isStaleConnect(generation)) {
                    uiState = uiState.copy(error = "设备不存在，请重新扫码。")
                }
                return@startConnect
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
                generation = generation,
            )
        }
    }

    private fun startConnect(block: suspend (Long) -> Unit) {
        connectJob?.cancel()
        val generation = ++connectGeneration
        connectJob = scope.launch {
            try {
                block(generation)
            } finally {
                if (connectJob == coroutineContext[Job]) {
                    connectJob = null
                }
            }
        }
    }

    private fun isStaleConnect(generation: Long): Boolean =
        generation != connectGeneration

    private suspend fun connectWithPayload(
        payload: PairingPayload,
        fromScan: Boolean,
        generation: Long,
    ) {
        stopActiveTransfer()
        monitorJob?.cancel()
        releaseHeldKeyboardModifiers()
        touchpadEngine.setTarget(null)
        inputSender.setSecret("")
        uiState = uiState.copy(error = null, showDevicePicker = false, connecting = true)
        PhonePadLogger.i(
            "connection",
            "connect_attempt",
            "device_id=${PhonePadLogger.shortId(payload.deviceId)} from_scan=$fromScan needs_discovery=${payload.needsDiscovery()}",
        )

        var resolved = resolvePayload(payload)
        var host = resolved.host?.takeIf { it.isNotBlank() }
        if (host == null) {
            PhonePadLogger.w(
                "connection",
                "discover_timeout",
                "device_id=${PhonePadLogger.shortId(payload.deviceId)} reason=no_host",
            )
            if (isStaleConnect(generation) || !coroutineContext.isActive) return
            uiState = uiState.copy(
                connected = false,
                connecting = false,
                error = "未在当前局域网发现桌面端，请确认手机与电脑在同一 Wi-Fi，桌面端接收服务已启动，并重新扫描桌面端二维码。",
            )
            haptics.disconnected()
            return
        }

        var status = controlClient.fetchStatus(
            host = host,
            deviceId = resolved.deviceId,
            secret = resolved.secret,
            port = resolved.tcpPort,
        )
        if (status == null || !status.running) {
            val rediscovered = discoverDesktop(
                deviceId = payload.deviceId,
                secret = payload.secret,
                discoveryPort = payload.discoveryPort,
            )
            if (rediscovered != null) {
                resolved = payload.copy(
                    host = rediscovered.host,
                    tcpPort = rediscovered.tcpPort,
                    udpPort = rediscovered.udpPort,
                )
                host = rediscovered.host
                status = controlClient.fetchStatus(
                    host = host,
                    deviceId = resolved.deviceId,
                    secret = resolved.secret,
                    port = resolved.tcpPort,
                )
            }
        }
        if (status == null || !status.running) {
            PhonePadLogger.w(
                "connection",
                "tcp_connect_failed",
                "device_id=${PhonePadLogger.shortId(payload.deviceId)} host=$host reason=status_unavailable",
            )
            if (isStaleConnect(generation) || !coroutineContext.isActive) return
            uiState = uiState.copy(
                connected = false,
                connecting = false,
                error = "无法连接到 ${payload.deviceName}，请确认桌面端已启动接收服务。",
            )
            haptics.disconnected()
            return
        }
        if (status.deviceId.isNotBlank() && status.deviceId != payload.deviceId) {
            PhonePadLogger.w(
                "connection",
                "device_mismatch",
                "expected=${PhonePadLogger.shortId(payload.deviceId)} actual=${PhonePadLogger.shortId(status.deviceId)}",
            )
            if (isStaleConnect(generation) || !coroutineContext.isActive) return
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
            host = host,
            tcpPort = resolved.tcpPort,
            udpPort = resolved.udpPort,
            secret = payload.secret,
            lastConnectedAt = System.currentTimeMillis(),
            onlineState = DeviceOnlineState.Online,
        )
        val devices = store.upsert(device)
        if (isStaleConnect(generation) || !coroutineContext.isActive) return
        inputSender.setSecret(payload.secret)
        touchpadEngine.setTarget(
            ReceiverTarget(
                host = host,
                udpPort = resolved.udpPort,
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
            host = host,
            lastRttMs = status.lastRttMs,
            packetsReceived = status.packetsReceived,
            error = null,
        )
        PhonePadLogger.i(
            "connection",
            "connected",
            "device_id=${PhonePadLogger.shortId(device.id)} host=$host tcp=${resolved.tcpPort} udp=${resolved.udpPort}",
        )
        haptics.connected()
        startMonitor(device)
        if (fromScan) {
            refreshOnlineStates()
        }
    }

    private suspend fun resolvePayload(payload: PairingPayload): PairingPayload {
        if (!payload.needsDiscovery()) {
            return payload
        }
        val discovered = discoverDesktop(
            deviceId = payload.deviceId,
            secret = payload.secret,
            discoveryPort = payload.discoveryPort,
        ) ?: return payload
        return payload.copy(
            host = discovered.host,
            tcpPort = discovered.tcpPort,
            udpPort = discovered.udpPort,
            deviceName = discovered.deviceName.ifBlank { payload.deviceName },
        )
    }

    private suspend fun discoverDesktop(
        deviceId: String,
        secret: String,
        discoveryPort: Int,
    ): DiscoveryClient.Result? {
        val knownHosts = store.load()
            .map { it.host.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        PhonePadLogger.d(
            "discovery",
            "discover_start",
            "device_id=${PhonePadLogger.shortId(deviceId)} port=$discoveryPort targets=${knownHosts.size + 1}",
        )
        val result = DiscoveryClient.discover(
            deviceId = deviceId,
            secret = secret,
            discoveryPort = discoveryPort,
            extraHosts = knownHosts,
        )
        if (result == null) {
            PhonePadLogger.w(
                "discovery",
                "discover_timeout",
                "device_id=${PhonePadLogger.shortId(deviceId)} port=$discoveryPort",
            )
        } else {
            PhonePadLogger.i(
                "discovery",
                "discover_response",
                "device_id=${PhonePadLogger.shortId(deviceId)} host=${result.host} name=${result.deviceName}",
            )
        }
        return result
    }

    fun disconnect() {
        teardownActiveSession()
        PhonePadLogger.i(
            "connection",
            "disconnect",
            "device_id=${PhonePadLogger.shortId(uiState.activeDeviceId)}",
        )
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
            transferSending = false,
            transferProgress = null,
        )
        haptics.disconnected()
        refreshOnlineStates()
    }

    fun removePairedDevice(deviceId: String) {
        if (deviceId.isBlank()) {
            return
        }
        val wasActive = uiState.activeDeviceId == deviceId
        if (wasActive) {
            teardownActiveSession()
        }
        val updated = store.remove(deviceId)
        PhonePadLogger.i(
            "connection",
            "remove_paired_device",
            "device_id=${PhonePadLogger.shortId(deviceId)} was_active=$wasActive",
        )
        uiState = uiState.copy(
            pairedDevices = updated,
            connected = if (wasActive) false else uiState.connected,
            connecting = if (wasActive) false else uiState.connecting,
            activeDeviceId = if (wasActive) "" else uiState.activeDeviceId,
            activeDeviceName = if (wasActive) "" else uiState.activeDeviceName,
            host = if (wasActive) "" else uiState.host,
            lastRttMs = if (wasActive) null else uiState.lastRttMs,
            error = null,
            showDevicePicker = false,
            textSending = if (wasActive) false else uiState.textSending,
            textInputError = if (wasActive) null else uiState.textInputError,
            transferSending = if (wasActive) false else uiState.transferSending,
            transferProgress = if (wasActive) null else uiState.transferProgress,
        )
        if (wasActive) {
            haptics.disconnected()
        }
        refreshOnlineStates()
    }

    /** 当前可切换的在线设备：active 设备视为在线，其余仅保留 Online 状态。 */
    fun onlineSwitchableDevices(): List<PairedDevice> =
        uiState.pairedDevices.onlineSwitchable(uiState.activeDeviceId)

    fun openDevicePicker() {
        val online = onlineSwitchableDevices()
        when {
            online.size <= 1 -> {
                uiState = uiState.copy(showDevicePicker = false)
            }
            online.size == 2 -> {
                uiState = uiState.copy(showDevicePicker = false)
                val other = online.firstOrNull { it.id != uiState.activeDeviceId }
                if (other != null) {
                    PhonePadLogger.i(
                        "connection",
                        "switch_device",
                        "from=${PhonePadLogger.shortId(uiState.activeDeviceId)} to=${PhonePadLogger.shortId(other.id)} mode=direct",
                    )
                    connectToDevice(other.id)
                }
            }
            else -> {
                uiState = uiState.copy(showDevicePicker = true)
                refreshOnlineStates()
            }
        }
    }

    fun closeDevicePicker() {
        uiState = uiState.copy(showDevicePicker = false)
    }

    fun clearTextInputError() {
        uiState = uiState.copy(textInputError = null)
    }

    fun setTextInputError(message: String) {
        uiState = uiState.copy(textInputError = message)
    }

    private fun stopActiveTransfer() {
        transferCancelled = true
        transferJob?.cancel()
    }

    fun sendInputModeContent(
        text: String,
        attachments: List<SelectedAttachment>,
        onTextCleared: () -> Unit = {},
        onCompleted: () -> Unit = {},
    ) {
        if (uiState.textSending || uiState.transferSending) {
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) {
            uiState = uiState.copy(textInputError = "请输入内容或选择附件")
            return
        }

        transferJob?.cancel()
        transferCancelled = false
        transferJob = scope.launch {
            if (!uiState.connected || uiState.activeDeviceId.isBlank()) {
                uiState = uiState.copy(textInputError = "设备未连接")
                return@launch
            }
            val device = store.load().firstOrNull { it.id == uiState.activeDeviceId } ?: run {
                uiState = uiState.copy(textInputError = "设备不存在，请重新连接")
                return@launch
            }

            uiState = uiState.copy(textInputError = null)

            if (trimmed.isNotEmpty()) {
                validateTextContent(trimmed)?.let { message ->
                    uiState = uiState.copy(textInputError = message)
                    return@launch
                }
                uiState = uiState.copy(textSending = true)
                val textResponse = controlClient.sendText(
                    host = device.host,
                    deviceId = device.id,
                    secret = device.secret,
                    port = device.tcpPort,
                    text = trimmed,
                )
                if (!isActive || !uiState.connected || transferCancelled) {
                    uiState = uiState.copy(textSending = false, transferSending = false, transferProgress = null)
                    return@launch
                }
                if (!textResponse.ok) {
                    uiState = uiState.copy(
                        textSending = false,
                        textInputError = textResponse.error ?: "文本发送失败",
                    )
                    return@launch
                }
                haptics.textSent()
                uiState = uiState.copy(textSending = false)
                onTextCleared()
            }

            if (attachments.isNotEmpty()) {
                uiState = uiState.copy(
                    transferSending = true,
                    transferProgress = FileTransferProgress(
                        batchId = "",
                        currentIndex = 0,
                        totalFiles = attachments.size,
                        currentFileName = attachments.first().displayName,
                        sentBytes = 0,
                        totalBytes = attachments.sumOf { it.size },
                        fileSentBytes = 0,
                        fileTotalBytes = attachments.first().size,
                    ),
                )
                val response = fileTransferClient.uploadAttachments(
                    context = appContext,
                    host = device.host,
                    deviceId = device.id,
                    secret = device.secret,
                    tcpPort = device.tcpPort,
                    attachments = attachments,
                    onProgress = { progress ->
                        if (isActive && !transferCancelled) {
                            uiState = uiState.copy(transferProgress = progress)
                        }
                    },
                    isCancelled = { transferCancelled || !isActive },
                )
                if (!isActive || !uiState.connected) {
                    uiState = uiState.copy(
                        transferSending = false,
                        transferProgress = null,
                        textInputError = if (transferCancelled) null else "传输已中断",
                    )
                    return@launch
                }
                if (response.ok && !transferCancelled) {
                    haptics.textSent()
                    uiState = uiState.copy(
                        transferSending = false,
                        transferProgress = null,
                        textInputError = null,
                    )
                    onCompleted()
                } else {
                    uiState = uiState.copy(
                        transferSending = false,
                        transferProgress = null,
                        textInputError = response.error ?: "附件发送失败",
                    )
                }
            } else {
                onCompleted()
            }
        }
    }

    fun cancelTransfer() {
        stopActiveTransfer()
        uiState = uiState.copy(
            transferSending = false,
            transferProgress = null,
            textSending = false,
        )
    }

    fun sendTextToActiveDevice(text: String, onSuccess: () -> Unit = {}) {
        sendInputModeContent(
            text = text,
            attachments = emptyList(),
            onTextCleared = onSuccess,
            onCompleted = onSuccess,
        )
    }

    fun sendKeyActionToActiveDevice(action: String, repeat: Int = 1) {
        sendKeyboardKeyToActiveDevice(action, event = null, repeat = repeat)
    }

    fun sendKeyboardKeyToActiveDevice(action: String, event: String?, repeat: Int = 1) {
        scope.launch {
            keyboardSendMutex.withLock {
                sendKeyboardKeyLocked(action, event, repeat)
            }
        }
    }

    fun releaseHeldKeyboardModifiers() {
        val pending = synchronized(heldModifiers) { heldModifiers.toList().also { heldModifiers.clear() } }
        if (pending.isEmpty()) return
        scope.launch {
            keyboardSendMutex.withLock {
                pending.forEach { action ->
                    sendKeyboardKeyLocked(action, KeyboardKeyEvent.UP, repeat = 1, trackModifier = false)
                }
            }
        }
    }

    private suspend fun sendKeyboardKeyLocked(
        action: String,
        event: String?,
        repeat: Int,
        trackModifier: Boolean = true,
    ) {
        if (!uiState.connected || uiState.activeDeviceId.isBlank()) {
            uiState = uiState.copy(textInputError = "设备未连接")
            return
        }
        val device = store.load().firstOrNull { it.id == uiState.activeDeviceId } ?: run {
            uiState = uiState.copy(textInputError = "设备不存在，请重新连接")
            return
        }

        val response = controlClient.sendKeyboardKey(
            host = device.host,
            deviceId = device.id,
            secret = device.secret,
            port = device.tcpPort,
            action = action,
            event = event,
            repeat = repeat.coerceIn(1, 20),
        )
        if (!coroutineContext.isActive) {
            return
        }
        if (response.ok) {
            if (trackModifier && action in MODIFIER_KEYS) {
                synchronized(heldModifiers) {
                    when (event) {
                        KeyboardKeyEvent.DOWN -> heldModifiers.add(action)
                        KeyboardKeyEvent.UP -> heldModifiers.remove(action)
                    }
                }
            }
            haptics.keyTap()
            uiState = uiState.copy(textInputError = null)
        } else {
            uiState = uiState.copy(
                textInputError = response.error ?: "按键发送失败，请确认桌面端仍在接收",
            )
        }
    }

    private fun startMonitor(device: PairedDevice) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var currentDevice = device
            var consecutiveFailures = 0
            while (isActive) {
                var status = controlClient.fetchStatus(
                    host = currentDevice.host,
                    deviceId = currentDevice.id,
                    secret = currentDevice.secret,
                    port = currentDevice.tcpPort,
                )
                if (status == null || !status.running) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        val rediscovered = discoverDesktop(
                            deviceId = currentDevice.id,
                            secret = currentDevice.secret,
                            discoveryPort = Protocol.UDP_DISCOVERY_PORT,
                        )
                        if (rediscovered != null) {
                            val hostChanged = rediscovered.host != currentDevice.host
                            if (hostChanged) {
                                PhonePadLogger.i(
                                    "connection",
                                    "rediscover_host",
                                    "device_id=${PhonePadLogger.shortId(currentDevice.id)} host=${rediscovered.host}",
                                )
                            }
                            currentDevice = currentDevice.copy(
                                host = rediscovered.host,
                                tcpPort = rediscovered.tcpPort,
                                udpPort = rediscovered.udpPort,
                                name = rediscovered.deviceName.ifBlank { currentDevice.name },
                            )
                            val devices = store.upsert(currentDevice)
                            touchpadEngine.setTarget(
                                ReceiverTarget(
                                    host = currentDevice.host,
                                    udpPort = currentDevice.udpPort,
                                    deviceId = currentDevice.id,
                                    deviceName = currentDevice.name,
                                ),
                            )
                            uiState = uiState.copy(
                                pairedDevices = devices,
                                host = currentDevice.host,
                                activeDeviceName = currentDevice.name,
                            )
                            status = controlClient.fetchStatus(
                                host = currentDevice.host,
                                deviceId = currentDevice.id,
                                secret = currentDevice.secret,
                                port = currentDevice.tcpPort,
                            )
                        }
                    }
                    if (status == null || !status.running) {
                        if (consecutiveFailures >= 5) {
                            PhonePadLogger.w(
                                "connection",
                                "monitor_disconnect",
                                "device_id=${PhonePadLogger.shortId(device.id)} failures=$consecutiveFailures",
                            )
                            disconnect()
                            uiState = uiState.copy(error = "与 ${currentDevice.name} 的连接已断开。")
                            break
                        }
                    } else {
                        consecutiveFailures = 0
                    }
                } else {
                    consecutiveFailures = 0
                    uiState = uiState.copy(
                        activeDeviceName = status.deviceName.ifBlank { currentDevice.name },
                        lastRttMs = status.lastRttMs,
                        packetsReceived = status.packetsReceived,
                        error = null,
                    )
                }
                delay(1000)
            }
        }
    }

    fun release() {
        stopActiveTransfer()
        monitorJob?.cancel()
        onlineJob?.cancel()
        touchpadEngine.release()
        inputSender.close()
    }

    private fun teardownActiveSession() {
        connectJob?.cancel()
        ++connectGeneration
        stopActiveTransfer()
        releaseHeldKeyboardModifiers()
        monitorJob?.cancel()
        transferJob?.cancel()
        touchpadEngine.setTarget(null)
        inputSender.setSecret("")
    }

    private fun validateTextContent(trimmed: String): String? {
        if (trimmed.isEmpty()) {
            return "请输入要发送的内容"
        }
        if (trimmed.length > MAX_TEXT_CHARS) {
            return "文本超过 $MAX_TEXT_CHARS 字符限制"
        }
        if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            return "文本过长，请缩短后重试"
        }
        return null
    }
}
