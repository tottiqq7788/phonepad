package cn.phonepad.net

import cn.phonepad.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
            sendRequest(host, port, buildRequest("text", deviceId, secret, text)) { jsonText ->
                val json = JSONObject(jsonText)
                ControlResponse(
                    ok = json.optBoolean("ok"),
                    error = json.optString("error").takeIf { it.isNotBlank() },
                )
            } ?: ControlResponse(ok = false, error = "未收到桌面端响应")
        }.getOrElse { err ->
            ControlResponse(ok = false, error = err.message ?: "发送失败")
        }
    }

    private fun buildRequest(
        type: String,
        deviceId: String,
        secret: String,
        content: String? = null,
    ): String {
        val request = JSONObject()
            .put("type", type)
            .put("deviceId", deviceId)
            .put("secret", secret)
        if (content != null) {
            request.put("content", content)
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
