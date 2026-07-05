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

class ControlClient {
    suspend fun fetchStatus(
        host: String,
        deviceId: String,
        secret: String,
        port: Int = Protocol.TCP_CONTROL_PORT,
    ): ReceiverStatus? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1200)
                socket.soTimeout = 1200
                val request = JSONObject()
                    .put("type", "status")
                    .put("deviceId", deviceId)
                    .put("secret", secret)
                    .toString() + "\n"
                socket.getOutputStream().write(request.toByteArray())
                val jsonText = TcpStatusReader.readJson(socket.getInputStream()) ?: return@runCatching null
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
}
