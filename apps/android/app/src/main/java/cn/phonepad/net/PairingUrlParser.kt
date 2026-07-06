package cn.phonepad.net

import cn.phonepad.protocol.Protocol
import java.net.URLDecoder

data class PairingPayload(
    val deviceId: String,
    val deviceName: String,
    val host: String?,
    val tcpPort: Int,
    val udpPort: Int,
    val secret: String,
    val discoveryPort: Int = Protocol.UDP_DISCOVERY_PORT,
) {
    fun needsDiscovery(): Boolean = host.isNullOrBlank()
}

object PairingUrlParser {
    fun parse(raw: String): PairingPayload? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val withoutScheme = when {
            trimmed.startsWith("phonepad://", ignoreCase = true) ->
                trimmed.substringAfter("://", trimmed)
            else -> return null
        }

        if (!withoutScheme.startsWith("pair", ignoreCase = true)) {
            return null
        }

        val query = withoutScheme.substringAfter('?', "")
        if (query.isBlank()) return null

        val params = linkedMapOf<String, String>()
        query.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val key = part.substringBefore('=').trim()
            val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
            params[key] = value
        }

        val host = params["host"]?.trim()?.ifBlank { null }
        val deviceId = params["id"]?.trim().orEmpty()
        val secret = params["secret"]?.trim().orEmpty()
        val deviceName = params["name"]?.trim().orEmpty().ifBlank { "PhonePad Receiver" }
        val tcpPort = params["tcp"]?.toIntOrNull() ?: Protocol.TCP_CONTROL_PORT
        val udpPort = params["udp"]?.toIntOrNull() ?: Protocol.UDP_INPUT_PORT
        val discoveryPort = params["discovery"]?.toIntOrNull() ?: Protocol.UDP_DISCOVERY_PORT

        if (deviceId.isBlank() || secret.isBlank()) {
            return null
        }

        return PairingPayload(
            deviceId = deviceId,
            deviceName = deviceName,
            host = host,
            tcpPort = tcpPort,
            udpPort = udpPort,
            secret = secret,
            discoveryPort = discoveryPort,
        )
    }
}
