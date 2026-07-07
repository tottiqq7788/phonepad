package cn.phonepad.model

import cn.phonepad.protocol.Protocol

enum class DeviceOnlineState {
    Unknown,
    Online,
    Offline,
}

data class PairedDevice(
    val id: String,
    val name: String,
    val host: String,
    val tcpPort: Int,
    val udpPort: Int,
    val secret: String,
    val discoveryPort: Int = Protocol.UDP_DISCOVERY_PORT,
    val lastConnectedAt: Long = 0L,
    val onlineState: DeviceOnlineState = DeviceOnlineState.Unknown,
)
