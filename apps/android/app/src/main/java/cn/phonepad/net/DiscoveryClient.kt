package cn.phonepad.net

import cn.phonepad.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.random.Random

object DiscoveryClient {
    data class Result(
        val host: String,
        val tcpPort: Int,
        val udpPort: Int,
        val discoveryPort: Int,
        val deviceName: String,
    )

    suspend fun discover(
        deviceId: String,
        secret: String,
        discoveryPort: Int = Protocol.UDP_DISCOVERY_PORT,
        timeoutMs: Long = 2500,
    ): Result? = withContext(Dispatchers.IO) {
        val socket = DatagramSocket()
        socket.soTimeout = 200
        socket.broadcast = true
        try {
            val targets = broadcastTargets(discoveryPort)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val nonce = Random.nextInt(1, Int.MAX_VALUE)
                val auth = Protocol.authToken(secret, nonce)
                val request =
                    """{"type":"discover","version":2,"deviceId":"${escapeJson(deviceId)}","nonce":$nonce,"auth":$auth}"""
                val requestBytes = request.toByteArray(Charsets.UTF_8)
                for ((address, port) in targets) {
                    runCatching {
                        socket.send(
                            DatagramPacket(requestBytes, requestBytes.size, address, port),
                        )
                    }
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val buf = ByteArray(512)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                parseResponse(
                    payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
                    deviceId = deviceId,
                    secret = secret,
                    nonce = nonce,
                )?.let { return@withContext it }
            }
            null
        } finally {
            socket.close()
        }
    }

    internal fun parseResponse(
        payload: String,
        deviceId: String,
        secret: String,
        nonce: Int,
    ): Result? {
        if (jsonString(payload, "type") != "discoverResponse") return null
        if (jsonString(payload, "deviceId") != deviceId) return null
        val expectedAuth = Protocol.authToken(secret, nonce + 1)
        if (jsonLong(payload, "auth") != expectedAuth) return null
        val host = jsonString(payload, "ip")?.trim().orEmpty()
        if (host.isBlank()) return null
        return Result(
            host = host,
            tcpPort = jsonInt(payload, "tcpPort", Protocol.TCP_CONTROL_PORT),
            udpPort = jsonInt(payload, "udpPort", Protocol.UDP_INPUT_PORT),
            discoveryPort = jsonInt(payload, "discoveryPort", Protocol.UDP_DISCOVERY_PORT),
            deviceName = jsonString(payload, "name")?.ifBlank { "PhonePad Receiver" } ?: "PhonePad Receiver",
        )
    }

    private fun jsonString(payload: String, key: String): String? {
        val regex = """"$key"\s*:\s*"((?:\\.|[^"\\])*)"""".toRegex()
        return regex.find(payload)?.groupValues?.get(1)?.replace("\\\"", "\"")
    }

    private fun jsonLong(payload: String, key: String): Long? {
        val regex = """"$key"\s*:\s*(-?\d+)""".toRegex()
        return regex.find(payload)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun jsonInt(payload: String, key: String, default: Int): Int {
        return jsonLong(payload, key)?.toInt() ?: default
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun broadcastTargets(port: Int): List<Pair<InetAddress, Int>> {
        val targets = linkedSetOf<Pair<InetAddress, Int>>()
        runCatching {
            targets.add(InetAddress.getByName("255.255.255.255") to port)
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                iface.interfaceAddresses.forEach { addr ->
                    val broadcast = addr.broadcast ?: return@forEach
                    targets.add(broadcast to port)
                }
            }
        }
        return targets.toList()
    }
}
