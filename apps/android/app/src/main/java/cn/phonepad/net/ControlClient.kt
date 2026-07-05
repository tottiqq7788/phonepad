package cn.phonepad.net

import cn.phonepad.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

data class ReceiverStatus(
    val running: Boolean,
    val packetsReceived: Long,
    val packetsDropped: Long,
    val movePackets: Long,
    val scrollPackets: Long,
    val clickPackets: Long,
    val lastRttMs: Double?,
    val deviceId: String,
    val deviceName: String,
)

data class ControlResponse(
    val ok: Boolean,
    val error: String? = null,
)

data class FocusState(
    val editable: Boolean,
    val appName: String?,
)

object TextInputKeyAction {
    const val BACKSPACE = "backspace"
    const val DELETE = "delete"
    const val CURSOR_LEFT = "cursor_left"
    const val CURSOR_RIGHT = "cursor_right"
}

class ControlClient {
    suspend fun fetchStatus(
        host: String,
        deviceId: String,
        secret: String,
        port: Int = Protocol.TCP_CONTROL_PORT,
    ): ReceiverStatus? = withContext(Dispatchers.IO) {
        runCatching {
            sendRequest(host, port, buildRequest("status", deviceId, secret)) { jsonText ->
                val json = JSONObject(jsonText)
                ReceiverStatus(
                    running = json.optBoolean("running"),
                    packetsReceived = json.optLong("packetsReceived"),
                    packetsDropped = json.optLong("packetsDropped"),
                    movePackets = json.optLong("movePackets"),
                    scrollPackets = json.optLong("scrollPackets"),
                    clickPackets = json.optLong("clickPackets"),
                    lastRttMs = json.optDouble("lastRttMs").takeIf { !it.isNaN() },
                    deviceId = json.optString("deviceId", deviceId),
                    deviceName = json.optString("deviceName", "PhonePad Receiver"),
                )
            }
        }.getOrNull()
    }

    suspend fun sendText(
        host: String,
        deviceId: String,
        secret: String,
        text: String,
        port: Int = Protocol.TCP_CONTROL_PORT,
    ): ControlResponse = withContext(Dispatchers.IO) {
        runCatching {
            sendRequest(host, port, buildRequest("text", deviceId, secret, content = text)) { jsonText ->
                parseControlResponse(jsonText)
            } ?: ControlResponse(ok = false, error = "未收到桌面端响应")
        }.getOrElse { err ->
            ControlResponse(ok = false, error = err.message ?: "发送失败")
        }
    }

    suspend fun sendKeyAction(
        host: String,
        deviceId: String,
        secret: String,
        action: String,
        repeat: Int = 1,
        port: Int = Protocol.TCP_CONTROL_PORT,
    ): ControlResponse = withContext(Dispatchers.IO) {
        runCatching {
            sendRequest(
                host,
                port,
                buildRequest("key", deviceId, secret, action = action, repeat = repeat),
            ) { jsonText ->
                parseControlResponse(jsonText)
            } ?: ControlResponse(ok = false, error = "未收到桌面端响应")
        }.getOrElse { err ->
            ControlResponse(ok = false, error = err.message ?: "按键发送失败")
        }
    }

    suspend fun subscribeFocusState(
        host: String,
        deviceId: String,
        secret: String,
        port: Int = Protocol.TCP_CONTROL_PORT,
        onState: suspend (FocusState) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val socket = Socket()
        coroutineContext.job.invokeOnCompletion {
            runCatching { socket.close() }
        }
        try {
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 2000
            socket.getOutputStream().write(
                buildRequest("focusSubscribe", deviceId, secret).toByteArray(),
            )
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val ackLine = reader.readLine() ?: throw IllegalStateException("未收到 focusSubscribe 响应")
            val ack = JSONObject(ackLine)
            if (!ack.optBoolean("ok")) {
                throw IllegalStateException(ack.optString("error", "focusSubscribe 被拒绝"))
            }

            while (coroutineContext.isActive) {
                val line = try {
                    reader.readLine()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (err: java.net.SocketException) {
                    if (!coroutineContext.isActive) {
                        return@withContext
                    }
                    throw err
                } ?: break
                if (line.isBlank()) continue
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (json.optString("type") != "focusState") continue
                onState(
                    FocusState(
                        editable = json.optBoolean("editable"),
                        appName = json.optString("appName").takeIf { it.isNotBlank() },
                    ),
                )
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseControlResponse(jsonText: String): ControlResponse {
        val json = JSONObject(jsonText)
        return ControlResponse(
            ok = json.optBoolean("ok"),
            error = json.optString("error").takeIf { it.isNotBlank() },
        )
    }

    private fun buildRequest(
        type: String,
        deviceId: String,
        secret: String,
        content: String? = null,
        action: String? = null,
        repeat: Int? = null,
    ): String {
        val request = JSONObject()
            .put("type", type)
            .put("deviceId", deviceId)
            .put("secret", secret)
        if (content != null) {
            request.put("content", content)
        }
        if (action != null) {
            request.put("action", action)
        }
        if (repeat != null && repeat > 1) {
            request.put("repeat", repeat)
        }
        return request.toString() + "\n"
    }

    private inline fun <T> sendRequest(
        host: String,
        port: Int,
        request: String,
        parse: (String) -> T,
    ): T? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 3000
            socket.getOutputStream().write(request.toByteArray())
            val jsonText = TcpStatusReader.readJson(socket.getInputStream()) ?: return null
            return parse(jsonText)
        }
    }
}
