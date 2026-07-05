package cn.phonepad.net

import android.content.Context
import android.net.wifi.WifiManager
import cn.phonepad.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class DiscoveredReceiver(
    val name: String,
    val ip: String,
    val udpPort: Int,
    val tcpPort: Int,
    val discoveryPort: Int,
    val running: Boolean,
)

data class DiscoveryProgress(
    val stage: String,
    val found: List<DiscoveredReceiver>,
)

class DiscoveryClient(private val context: Context) {
    suspend fun discover(
        timeoutMs: Long = 4500,
        lastHost: String? = null,
        onProgress: (DiscoveryProgress) -> Unit = {},
    ): List<DiscoveredReceiver> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, DiscoveredReceiver>()

        fun publish(stage: String) {
            onProgress(DiscoveryProgress(stage, results.values.toList()))
        }

        publish("广播搜索中...")
        discoverByBroadcast(results, timeoutMs / 2)
        publish("正在扫描同网段...")

        discoverBySubnetProbe(results)
        publish("校验最近连接...")

        lastHost?.let { host ->
            probeHost(host)?.let { receiver ->
                results[receiver.ip] = receiver
            }
        }

        publish("找到 ${results.size} 个接收端")
        results.values.toList()
    }

    private suspend fun discoverByBroadcast(
        results: LinkedHashMap<String, DiscoveredReceiver>,
        timeoutMs: Long,
    ) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifiManager?.createMulticastLock("phonepad-discovery")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val socket = DatagramSocket()
        socket.broadcast = true
        socket.soTimeout = 300

        try {
            repeat(3) {
                broadcastTargets().forEach { target ->
                    val request = DatagramPacket(
                        Protocol.DISCOVERY_REQUEST,
                        Protocol.DISCOVERY_REQUEST.size,
                        target,
                        Protocol.UDP_DISCOVERY_PORT,
                    )
                    socket.send(request)
                }
            }

            withTimeoutOrNull(timeoutMs) {
                val buffer = ByteArray(512)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        continue
                    }
                    val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parse(payload)?.let { receiver ->
                        results[receiver.ip] = receiver
                    }
                }
            }
        } finally {
            socket.close()
            if (lock?.isHeld == true) {
                lock.release()
            }
        }
    }

    private suspend fun discoverBySubnetProbe(results: LinkedHashMap<String, DiscoveredReceiver>) {
        val localIp = currentWifiIpv4() ?: return
        val octets = localIp.split('.')
        if (octets.size != 4) return

        val prefix = "${octets[0]}.${octets[1]}.${octets[2]}"
        val candidates = buildList {
            add(localIp)
            add("$prefix.1")
            add("$prefix.254")
            for (last in 2..20) add("$prefix.$last")
            for (last in 100..120) add("$prefix.$last")
        }.distinct()

        coroutineScope {
            candidates.map { host ->
                async {
                    probeHost(host)?.let { receiver ->
                        results[receiver.ip] = receiver
                    }
                }
            }.awaitAll()
        }
    }

    private fun probeHost(host: String): DiscoveredReceiver? {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, Protocol.TCP_CONTROL_PORT), 600)
                socket.getOutputStream().write("HELLO\n".toByteArray())
                val buffer = ByteArray(1024)
                val read = socket.getInputStream().read(buffer)
                if (read <= 0) return null
                val json = JSONObject(String(buffer, 0, read, Charsets.UTF_8))
                if (!json.optBoolean("running")) return null
                DiscoveredReceiver(
                    name = "PhonePad Receiver",
                    ip = host,
                    udpPort = json.optInt("udpPort", Protocol.UDP_INPUT_PORT),
                    tcpPort = json.optInt("tcpPort", Protocol.TCP_CONTROL_PORT),
                    discoveryPort = json.optInt("discoveryPort", Protocol.UDP_DISCOVERY_PORT),
                    running = true,
                )
            }
        }.getOrNull()
    }

    private fun currentWifiIpv4(): String? {
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.address }
            .firstOrNull { address ->
                !address.isLoopbackAddress &&
                    address.hostAddress?.contains(':') == false &&
                    isLikelyLan(address.hostAddress ?: "")
            }
            ?.hostAddress
    }

    private fun isLikelyLan(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            ip.startsWith("172.16.") ||
            ip.startsWith("172.17.") ||
            ip.startsWith("172.18.") ||
            ip.startsWith("172.19.") ||
            ip.startsWith("172.2") ||
            ip.startsWith("172.30.") ||
            ip.startsWith("172.31.")
    }

    private fun parse(payload: String): DiscoveredReceiver? {
        return try {
            val json = JSONObject(payload)
            DiscoveredReceiver(
                name = json.optString("name", "PhonePad Receiver"),
                ip = json.optString("ip"),
                udpPort = json.optInt("udpPort", Protocol.UDP_INPUT_PORT),
                tcpPort = json.optInt("tcpPort", Protocol.TCP_CONTROL_PORT),
                discoveryPort = json.optInt("discoveryPort", Protocol.UDP_DISCOVERY_PORT),
                running = json.optBoolean("running", true),
            ).takeIf { it.ip.isNotBlank() && isLikelyLan(it.ip) }
        } catch (_: Exception) {
            null
        }
    }

    private fun broadcastTargets(): Set<InetAddress> {
        val targets = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { targets += it }
        }
        return targets
    }
}
