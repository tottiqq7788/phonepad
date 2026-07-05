package cn.phonepad.model

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
    val lastConnectedAt: Long = 0L,
    val onlineState: DeviceOnlineState = DeviceOnlineState.Unknown,
)
