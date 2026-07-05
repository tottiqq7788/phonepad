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
)

class ControlClient {
    suspend fun fetchStatus(host: String, port: Int = Protocol.TCP_CONTROL_PORT): ReceiverStatus? =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1200)
                    socket.getOutputStream().write("HELLO\n".toByteArray())
                    val buffer = ByteArray(1024)
                    val read = socket.getInputStream().read(buffer)
                    if (read <= 0) return@runCatching null
                    val json = JSONObject(String(buffer, 0, read, Charsets.UTF_8))
                    ReceiverStatus(
                        running = json.optBoolean("running"),
                        packetsReceived = json.optLong("packetsReceived"),
                        packetsDropped = json.optLong("packetsDropped"),
                        movePackets = json.optLong("movePackets"),
                        scrollPackets = json.optLong("scrollPackets"),
                        clickPackets = json.optLong("clickPackets"),
                        lastRttMs = json.optDouble("lastRttMs").takeIf { !it.isNaN() },
                    )
                }
            }.getOrNull()
        }
}
